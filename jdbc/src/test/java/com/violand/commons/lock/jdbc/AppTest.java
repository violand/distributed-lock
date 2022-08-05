package com.violand.commons.lock.jdbc;

import com.violand.commons.lock.DistributedLock;
import com.violand.commons.lock.jdbc.JdbcDistributedLockProvider;
import junit.framework.TestCase;

import java.sql.DriverManager;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class AppTest extends TestCase {

    public void testLock() {
        JdbcDistributedLockProvider provider = new JdbcDistributedLockProvider(() -> DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/tmp", "root", "123456"));

        List<Thread> list = Arrays.asList(
                lockThread(provider, "lock"),
                lockThread(provider, "lock"),
                lockThread(provider, "lock"),
                lockThread(provider, "lock"),
                lockThread(provider, "lock"),
                lockThread(provider, "lock"),
                lockThread(provider, "lock"),
                lockThread(provider, "lock"),
                lockThread(provider, "lock")
        );

        list.forEach(thread -> thread.start());

        try {
            TimeUnit.SECONDS.sleep(Integer.MAX_VALUE);
        } catch (InterruptedException e) {

        }
    }

    private Thread lockThread(JdbcDistributedLockProvider provider, String key) {
        return new Thread(() -> {
            DistributedLock lock = provider.getLock(key);

            try {
                log("尝试获取锁");
                if (lock.tryLock(30, TimeUnit.SECONDS, 10, TimeUnit.SECONDS)) {
                    log("获取到锁");
                    try {
                        TimeUnit.SECONDS.sleep(5);
                    } catch (InterruptedException e) {

                    } finally {
                        log("释放了锁");
                        lock.unlock();
                    }
                } else {
                    log("获取锁超时");
                }
            } catch (InterruptedException e) {
                log("获取锁被中断");
            }
        });
    }

    private void log(String msg) {
        System.out.printf("%s thread id : %d -> %s\n", new Date(), Thread.currentThread().getId(), msg);
    }

}
