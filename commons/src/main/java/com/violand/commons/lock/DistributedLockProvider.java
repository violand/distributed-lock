package com.violand.commons.lock;

public interface DistributedLockProvider {

    DistributedLock getLock(String key);

}
