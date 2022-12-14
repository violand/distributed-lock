package com.violand.commons.lock.jdbc;

import com.violand.commons.lock.DistributedLock;
import com.violand.commons.lock.DistributedLockProvider;

import javax.sql.DataSource;

public class JdbcDistributedLockProvider implements DistributedLockProvider {

    private ConnectionSupplier connectionSupplier;

    public JdbcDistributedLockProvider(DataSource dataSource) {
        this.connectionSupplier = () -> dataSource.getConnection();
    }

    public JdbcDistributedLockProvider(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
    }

    @Override
    public DistributedLock getLock(String lockKey) {
        return new JdbcDistributedLock(lockKey, connectionSupplier);
    }

}
