分布式锁接口定义与实现

默认已基于JDBC实现了，表结构如下：

```ddl
CREATE TABLE distributed_lock  (
  lock_key varchar(64) NOT NULL,
  version int NOT NULL,
  expired_time bigint NULL,
  PRIMARY KEY (lock_key, version)
);
```

使用方法基本和juc包下的lock一致，补充了部分方法以设置leaseTime(锁持有时间)

JDBC实现说明：\
leaseTime不填的情况下，默认持有30s。内部有看门狗保证锁在未释放前一直续期。

```示例，以mysql为例
JdbcDistributedLockProvider provider = new JdbcDistributedLockProvider(() -> DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/tmp", "root", "123456"));
DistributedLock lock = provider.getLock("test");
if (lock.tryLock()) {
    try {
        System.out.println("do something");
    } finally {
        lock.unlock();
    }
}
```