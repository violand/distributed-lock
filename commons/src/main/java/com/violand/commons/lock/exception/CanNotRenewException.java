package com.violand.commons.lock.exception;

/**
 * 当可以预见接下来的续期操作绝对不会成功时抛出该异常，默认的看门狗会捕获该异常并停止续期
 */
public class CanNotRenewException extends Exception {

    public CanNotRenewException(String msg) {
        super(msg);
    }

}
