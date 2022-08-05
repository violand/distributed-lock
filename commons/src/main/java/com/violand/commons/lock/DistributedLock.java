package com.violand.commons.lock;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

public interface DistributedLock extends Lock {

    void lockInterruptibly(long leaseTime, TimeUnit unit) throws InterruptedException;

    boolean tryLock(long waitTime, TimeUnit waitTimeUnit, long leaseTime, TimeUnit leaseTimeUnit) throws InterruptedException;

    void lock(long leaseTime, TimeUnit unit);

}
