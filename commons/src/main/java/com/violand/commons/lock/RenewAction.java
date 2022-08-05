package com.violand.commons.lock;

import com.violand.commons.lock.exception.CanNotRenewException;

@FunctionalInterface
public interface RenewAction {

    void process() throws CanNotRenewException;

}
