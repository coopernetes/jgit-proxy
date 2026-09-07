package com.rbc.fogwall.scheduler;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs the process's periodic background jobs — config polling, credential refresh — on one pool.
 *
 * <p>These jobs are process singletons that run on the order of minutes to days and tolerate being late. That is a
 * different class of work from the per-push sideband heartbeat, which is deliberately <em>not</em> scheduled here: a
 * heartbeat exists once per in-flight push rather than once per process, and a late dot can time a waiting git client
 * out. Sharing one pool between the two would let a bulk refresh occupy a slot for minutes while heartbeats queue.
 *
 * <p>The pool holds more than one thread on purpose. A scheduled executor runs a task on its own thread until that task
 * returns, and these jobs block on network I/O — a git fetch, a provider API sweep — so a single-threaded pool would
 * let one slow job hold up every other. Threads are virtual, so blocking costs a carrier only while it is parked.
 *
 * <p>Every job is wrapped so a thrown exception is logged rather than escaping: a task that throws out of
 * {@link ScheduledExecutorService#scheduleAtFixedRate} is silently cancelled and never runs again, which is a failure
 * mode that looks exactly like a job that is simply idle.
 */
@Slf4j
public class MaintenanceScheduler implements AutoCloseable {

    /** Enough that one blocked job cannot hold up the others, without sizing for work this pool does not do. */
    private static final int POOL_SIZE = 3;

    private final ScheduledExecutorService executor;

    public MaintenanceScheduler() {
        this(POOL_SIZE);
    }

    public MaintenanceScheduler(int poolSize) {
        ScheduledThreadPoolExecutor exec = new ScheduledThreadPoolExecutor(
                poolSize, Thread.ofVirtual().name("fogwall-maintenance-", 0).factory());
        exec.setRemoveOnCancelPolicy(true);
        this.executor = exec;
    }

    /**
     * Runs {@code job} every {@code interval}, starting after {@code initialDelay}.
     *
     * @param name identifies the job in logs; it is the only handle an operator has on a background failure
     * @return the scheduled task, so a caller that owns a job can cancel it independently of this scheduler
     */
    public ScheduledFuture<?> scheduleAtFixedRate(String name, Duration initialDelay, Duration interval, Runnable job) {
        log.info("Scheduling maintenance job '{}': every {}, first run in {}", name, interval, initialDelay);
        return executor.scheduleAtFixedRate(
                guard(name, job), initialDelay.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Runs {@code job} once, as soon as a thread is free. */
    public void submit(String name, Runnable job) {
        executor.submit(guard(name, job));
    }

    /**
     * Wraps a job so it cannot terminate its own schedule. Errors are logged against the job's name rather than
     * rethrown, because the alternative is a job that stops running with nothing to say why.
     */
    private static Runnable guard(String name, Runnable job) {
        return () -> {
            try {
                job.run();
            } catch (Exception e) {
                log.error("Maintenance job '{}' failed; it stays scheduled and will run again", name, e);
            }
        };
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
