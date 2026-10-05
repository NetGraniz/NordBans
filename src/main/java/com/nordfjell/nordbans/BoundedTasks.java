package com.nordfjell.nordbans;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** Permits cover queued/running tasks AND completed results waiting for main-thread delivery. */
final class BoundedTasks implements AutoCloseable {
    private final Semaphore permits;
    private final int capacity;
    private final ThreadPoolExecutor executor;
    private final ConcurrentLinkedQueue<Job<?>> completed = new ConcurrentLinkedQueue<>();
    private final long maximumWaitNanos;
    private volatile boolean closed;
    BoundedTasks(int maximumPending, long maximumWaitMillis) {
        if (maximumPending < 1 || maximumWaitMillis < 1) throw new IllegalArgumentException();
        permits = new Semaphore(maximumPending);
        capacity = maximumPending;
        maximumWaitNanos = TimeUnit.MILLISECONDS.toNanos(maximumWaitMillis);
        executor = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(maximumPending),
            runnable -> { Thread thread = new Thread(runnable,"NordBans-storage"); thread.setDaemon(true); return thread; },
            new ThreadPoolExecutor.AbortPolicy());
    }
    synchronized <T> boolean submit(Callable<T> work, BiConsumer<T,Exception> callback) {
        if (closed || !permits.tryAcquire()) return false;
        Job<T> job = new Job<>(work,callback);
        try { executor.execute(job); return true; }
        catch (RejectedExecutionException exception) { job.release(); return false; }
    }
    void drain(int maximum) {
        for (int i=0;i<maximum;i++) {
            Job<?> job = completed.poll(); if (job == null) return;
            try { if (!closed) job.deliver(); } finally { job.release(); }
        }
    }
    int pending() { return capacity - permits.availablePermits(); }
    @Override public synchronized void close() {
        closed=true;
        for (Runnable job : executor.shutdownNow()) ((Job<?>)job).release();
        Job<?> result; while ((result=completed.poll()) != null) result.release();
    }
    private final class Job<T> implements Runnable {
        final Callable<T> work; final BiConsumer<T,Exception> callback; final long queued=System.nanoTime();
        final AtomicBoolean released=new AtomicBoolean(); T value; Exception failure;
        Job(Callable<T> work, BiConsumer<T,Exception> callback) { this.work=work; this.callback=callback; }
        @Override public void run() {
            try {
                if (System.nanoTime()-queued > maximumWaitNanos) throw new TimeoutException("Storage queue wait expired");
                value=work.call();
            } catch (Exception exception) { failure=exception; }
            synchronized (BoundedTasks.this) {
                if (closed) release(); else completed.add(this);
            }
        }
        void deliver() { callback.accept(value,failure); }
        void release() { if (released.compareAndSet(false,true)) permits.release(); }
    }
}
