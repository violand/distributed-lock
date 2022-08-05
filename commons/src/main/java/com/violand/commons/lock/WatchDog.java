package com.violand.commons.lock;

import com.violand.commons.lock.exception.CanNotRenewException;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class WatchDog {

    private AtomicBoolean running = new AtomicBoolean(true);
    private ExecutorService pool;
    private Future<Boolean> future;

    public void start(RenewAction renewAction, long interval) {
        this.start(renewAction, interval, TimeUnit.MILLISECONDS);
    }

    public void start(RenewAction renewAction, long interval, TimeUnit unit) {
        pool = Executors.newFixedThreadPool(1);

        future = pool.submit(() -> {
            while (running.get()) {
                try {
                    unit.sleep(interval);
                    renewAction.process();
                } catch (InterruptedException | CanNotRenewException e) {
                    break;
                }
            }

            return true;
        });
    }

    public void stop() throws ExecutionException, InterruptedException {
        running.set(false);
        pool.shutdownNow();
        future.get();
    }

}
