package com.violand.commons.lock.jdbc;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.violand.commons.lock.DistributedLock;
import com.violand.commons.lock.WatchDog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.violand.commons.lock.exception.CanNotRenewException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class JdbcDistributedLock implements DistributedLock {

    private final static Logger LOGGER = LoggerFactory.getLogger(JdbcDistributedLock.class);

    /**
     * 默认持有锁的时间为60s
     */
    private final static long DEFAULT_LEASE_TIME = 60;
    private final static TimeUnit DEFAULT_TIME_UNIT = TimeUnit.SECONDS;
    private final String lockKey;
    private final ConnectionSupplier connectionSupplier;
    private final ReentrantLock lock;
    private InnerLock innerLock;
    private WatchDog watchDog;

    public JdbcDistributedLock(String lockKey, ConnectionSupplier connectionSupplier) {
        this.lockKey = lockKey;
        this.connectionSupplier = connectionSupplier;
        this.lock = new ReentrantLock();
    }

    @Override
    public void lock() {
        this.lock(DEFAULT_LEASE_TIME, DEFAULT_TIME_UNIT);
    }

    @Override
    public void lock(long leaseTime, TimeUnit unit) {
        this.lock.lock();

        try {
            this.acquire(false, true, leaseTime, unit);
        } catch (Exception e) {
            lock.unlock();
            throw new RuntimeException(e);
        }
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
        lockInterruptibly(DEFAULT_LEASE_TIME, DEFAULT_TIME_UNIT);
    }

    @Override
    public void lockInterruptibly(long leaseTime, TimeUnit unit) throws InterruptedException {
        this.lock.lockInterruptibly();

        try {
            this.acquire(true, true, leaseTime, unit);
        } catch (Exception e) {
            lock.unlock();
            Throwables.throwIfInstanceOf(e, InterruptedException.class);
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean tryLock() {
        if (!this.lock.tryLock()) {
            return false;
        } else {
            try {
                if (this.acquire(false, false, DEFAULT_LEASE_TIME, DEFAULT_TIME_UNIT)) {
                    return true;
                }

                lock.unlock();
                return false;
            } catch (Exception e) {
                this.lock.unlock();
                LOGGER.error(e.getMessage(), e);
                return false;
            }
        }
    }

    @Override
    public boolean tryLock(long waitTime, TimeUnit unit) throws InterruptedException {
        return tryLock(waitTime, unit, DEFAULT_LEASE_TIME, DEFAULT_TIME_UNIT);
    }

    @Override
    public boolean tryLock(long waitTime, TimeUnit waitTimeUnit, long leaseTime, TimeUnit leaseTimeUnit) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        if (!this.lock.tryLock(waitTime, waitTimeUnit)) {
            return false;
        } else {
            long timeout = waitTimeUnit.toMillis(waitTime) - (System.currentTimeMillis() - startTime);

            try {
                if (this.acquire(true, true, timeout, TimeUnit.MILLISECONDS, leaseTime, leaseTimeUnit)) {
                    return true;
                }

                lock.unlock();
                return false;
            } catch (TimeoutException e) {
                this.lock.unlock();
                return false;
            } catch (Exception e) {
                this.lock.unlock();
                return false;
            }
        }
    }

    @Override
    public void unlock() {
        Preconditions.checkState(this.lock.isHeldByCurrentThread(), "非本地锁持有者");

        try {
            if (this.lock.getHoldCount() == 1) {
                try {
                    watchDog.stop();
                } catch (Exception e) {
                    // watchdog里是让线程自然结束，而不是取消，所以不会走到这个异常里
                    LOGGER.error("看门狗停止失败, cause : " + e.getMessage(), e);
                }
                this.innerLock.tryRelease();
            }
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public Condition newCondition() {
        throw new UnsupportedOperationException();
    }

    private boolean acquire(boolean interruptible, boolean waitForLock, long leaseTime, TimeUnit leaseTimeUnit) throws InterruptedException, TimeoutException {
        return this.acquire(interruptible, waitForLock, Long.MAX_VALUE, TimeUnit.MILLISECONDS, leaseTime, leaseTimeUnit);
    }

    private boolean acquire(boolean interruptible, boolean waitForLock, long waitTime, TimeUnit waitTimeUnit, long leaseTime, TimeUnit leaseTimeUnit) throws InterruptedException, TimeoutException {
        long startTime = System.currentTimeMillis();
        Preconditions.checkState(this.lock.isHeldByCurrentThread(), "非本地锁持有者");

        if (this.lock.getHoldCount() > 1) {
            return true;
        }

        waitTime = waitTimeUnit.toMillis(waitTime);

        // 获取锁，如果waitForLock=true，则每5s尝试一次，直到获取到锁、或是线程终中断为止
        do {
            InnerLock innerLock = new InnerLock(this.lockKey);
            if (innerLock.tryAcquire(leaseTimeUnit.toMillis(leaseTime))) {
                this.innerLock = innerLock;
                enableWatchDog(leaseTimeUnit.toMillis(leaseTime), innerLock);
                return true;
            }

            if (waitForLock) {
                if (System.currentTimeMillis() - startTime > waitTime) {
                    throw new TimeoutException();
                }

                try {
                    TimeUnit.SECONDS.sleep(5);
                } catch (InterruptedException e) {
                    if (interruptible) {
                        throw e;
                    } else {
                        return false;
                    }
                }
            }
        } while (waitForLock);

        return false;
    }

    private void enableWatchDog(long leaseTime, InnerLock innerLock) {
        watchDog = new WatchDog();
        watchDog.start(() -> innerLock.tryRenew(leaseTime / 3), leaseTime);
    }

    private class InnerLock {

        private String lockKey;

        private Integer version;

        private InnerLock(String lockKey) {
            this.lockKey = lockKey;
        }

        private boolean tryAcquire(long leaseTime) {
            boolean result = false;

            try (Connection connection = JdbcDistributedLock.this.connectionSupplier.get()) {
                result = tryAcquire0(connection, leaseTime);
            } catch (SQLException e) {
                LOGGER.error("获取锁失败, cause : " + e.getMessage(), e);
            }

            return result;
        }

        private boolean tryRelease() {
            String sql = "delete from distributed_lock where lock_key = ? and version = ?";

            try (Connection connection = JdbcDistributedLock.this.connectionSupplier.get();
                 PreparedStatement ps = connection.prepareStatement(sql)) {

                ps.setString(1, this.lockKey);
                ps.setInt(2, this.version);

                // 正常来说，只要锁住了就不存在删除不了的问题。如果影响条数为0的话，大概率是手动操作了数据库
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                LOGGER.error("释放mysql锁失败, cause : " + e.getMessage(), e);
                return false;
            }
        }

        private boolean tryRenew(long leaseTime) throws CanNotRenewException {
            long now = System.currentTimeMillis();

            try (Connection connection = JdbcDistributedLock.this.connectionSupplier.get()) {
                String sql = "update distributed_lock set version = version + 1, expired_time = ? where lock_key = ? and version = ?";

                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setLong(1, now + leaseTime);
                    ps.setString(2, this.lockKey);
                    ps.setInt(3, this.version);

                    if (ps.executeUpdate() == 0) {
                        throw new CanNotRenewException("找不到相应的数据库记录");
                    }

                    this.version++;
                    return true;
                }
            } catch (SQLException e) {
                LOGGER.error("续期mysql锁失败, cause : " + e.getMessage(), e);
                return false;
            }
        }

        private boolean tryAcquire0(Connection connection, long leaseTime) throws SQLException {
            long now = System.currentTimeMillis();

            int version;
            long expiredTime;
            boolean createFlag;

            String selectSql = "select version, expired_time from distributed_lock where lock_key = ?";

            try (PreparedStatement ps = connection.prepareStatement(selectSql)) {
                ps.setString(1, this.lockKey);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        createFlag = false;
                        version = rs.getInt(1);
                        expiredTime = rs.getLong(2);
                    } else {
                        createFlag = true;
                        version = 1;
                        expiredTime = now + leaseTime;
                    }
                }
            }

            // 数据库里不存在锁则尝试写入，否则判断下是否可以更新获取锁
            if (createFlag) {
                String sql = "insert into distributed_lock (lock_key, version, expired_time) values (?, ?, ?)";

                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, this.lockKey);
                    ps.setInt(2, version);
                    ps.setLong(3, expiredTime);

                    boolean result = ps.executeUpdate() > 0;

                    if (result) {
                        this.version = version;
                    }

                    return result;
                }
            } else {
                if (now < expiredTime) {
                    return false;
                }

                expiredTime = now + leaseTime;

                String sql = "update distributed_lock set version = version + 1, expired_time = ? where lock_key = ? and version = ?";

                try (PreparedStatement ps = connection.prepareStatement(sql)) {

                    ps.setLong(1, expiredTime);
                    ps.setString(2, this.lockKey);
                    ps.setInt(3, version);

                    boolean result = ps.executeUpdate() > 0;

                    if (result) {
                        this.version = version + 1;
                    }

                    return result;
                }
            }
        }
    }

}
