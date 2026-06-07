package com.perseusj.blockstreet.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Asynchronous, batched database write queue for BlockStreet.
 *
 * <h2>Design</h2>
 * <p>Producers (MatchingEngine thread, Main Thread via SettlementDispatcher) call
 * {@link #offer(DbWriteTask)} which is O(1) and non-blocking. A single-threaded
 * {@link ScheduledExecutorService} fires every {@value #FLUSH_INTERVAL_SECONDS} seconds
 * and drains the entire pending queue into one JDBC transaction. All work happens on the
 * dedicated "BlockStreet-DbWriter" thread — never on the game's Main Thread or engine thread.
 *
 * <h2>Durability guarantee</h2>
 * <p>The DB writer thread operates in a separate transaction per flush cycle. SQLite WAL
 * mode means readers (e.g. GUI history queries) are never blocked. If a flush fails, the
 * entire batch is moved to the {@code deadLetterQueue} and retried on the next flush cycle.
 * Repeated failures are logged at SEVERE level but do not crash the plugin.
 *
 * <h2>Shutdown</h2>
 * <p>Call {@link #shutdown()} from {@code onDisable()} <em>after</em> stopping the engine.
 * This cancels the scheduled task and runs a final synchronous flush to ensure no pending
 * writes are lost before the database connection is closed.
 *
 * <h2>Thread safety</h2>
 * <p>{@link ConcurrentLinkedQueue} is used for both queues: O(1) offer/poll, fully
 * lock-free, safe for concurrent producers and the single-consumer writer thread.
 */
public final class DbWriteQueue {

    private static final int FLUSH_INTERVAL_SECONDS = 3;
    private static final int MAX_DEAD_LETTER_RETRIES = 5;

    // ──────────────────────────── Fields ─────────────────────────────────────────

    private final DatabaseService dbService;
    private final Logger logger;

    /** Primary queue — producers offer here; flusher drains. */
    private final ConcurrentLinkedQueue<DbWriteTask> pendingTasks = new ConcurrentLinkedQueue<>();

    /**
     * Dead-letter queue — batches that failed to commit are moved here and retried
     * on the next flush cycle, ahead of new pending tasks (prepended ordering via re-offer).
     */
    private final ConcurrentLinkedQueue<DbWriteTask> deadLetterQueue = new ConcurrentLinkedQueue<>();

    /** Counter to prevent unbounded retry loops on a permanently broken connection. */
    private int consecutiveFailures = 0;

    private final ScheduledExecutorService scheduler;

    private volatile boolean shuttingDown = false;

    // ──────────────────────────── Constructor ────────────────────────────────────

    /**
     * @param dbService the database service providing pooled connections
     * @param logger    plugin logger for error reporting
     */
    public DbWriteQueue(DatabaseService dbService, Logger logger) {
        this.dbService = dbService;
        this.logger    = logger;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BlockStreet-DbWriter");
            t.setDaemon(true);
            return t;
        });
    }

    // ──────────────────────────── Lifecycle ──────────────────────────────────────

    /**
     * Starts the periodic flush task. Must be called once after the database service
     * has been initialized and the connection pool is ready.
     */
    public void start() {
        scheduler.scheduleAtFixedRate(
                this::flush,
                FLUSH_INTERVAL_SECONDS,
                FLUSH_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
        logger.info("[BlockStreet] DbWriteQueue started (flush interval: "
                + FLUSH_INTERVAL_SECONDS + "s).");
    }

    /**
     * Stops the periodic flush and performs a final synchronous flush.
     * Blocks until the final flush completes or the timeout is reached.
     * Call from {@code onDisable()} after stopping the matching engine.
     */
    public void shutdown() {
        if (shuttingDown) return;
        shuttingDown = true;

        scheduler.shutdown();
        try {
            scheduler.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Final synchronous drain — runs on the calling thread (Main Thread during onDisable)
        logger.info("[BlockStreet] DbWriteQueue: performing final flush before shutdown...");
        flush();
        logger.info("[BlockStreet] DbWriteQueue shutdown complete.");
    }

    // ──────────────────────────── Public API ─────────────────────────────────────

    /**
     * Enqueues a write task for asynchronous execution on the next flush cycle.
     * Non-blocking; safe to call from any thread.
     *
     * @param task the write task to enqueue
     */
    public void offer(DbWriteTask task) {
        pendingTasks.offer(task);
    }

    /**
     * Returns the current number of pending tasks awaiting the next flush.
     * For diagnostic/monitoring purposes only; not synchronized.
     */
    public int pendingCount() {
        return pendingTasks.size();
    }

    /**
     * Returns the current number of tasks in the dead-letter queue (failed writes
     * awaiting retry). Admin commands can expose this for monitoring.
     */
    public int deadLetterCount() {
        return deadLetterQueue.size();
    }

    // ──────────────────────────── Flush logic ────────────────────────────────────

    /**
     * Drains all pending and dead-letter tasks and writes them in a single transaction.
     * If the batch commit fails, all tasks are re-queued into the dead-letter queue.
     *
     * <p>Executed on the DbWriter thread during normal operation, and on the Main
     * Thread during {@link #shutdown()}.
     */
    void flush() {
        // Drain dead-letter tasks first (retry before new work)
        List<DbWriteTask> batch = new ArrayList<>();
        DbWriteTask t;
        while ((t = deadLetterQueue.poll()) != null) batch.add(t);
        while ((t = pendingTasks.poll()) != null)    batch.add(t);

        if (batch.isEmpty()) return;

        try (Connection conn = dbService.getConnection()) {
            conn.setAutoCommit(false);

            for (DbWriteTask task : batch) {
                task.execute(conn);
            }

            conn.commit();
            consecutiveFailures = 0;

            logger.fine("[DbWriteQueue] Flushed " + batch.size() + " task(s) to DB.");

        } catch (SQLException e) {
            consecutiveFailures++;
            logger.severe("[DbWriteQueue] Flush failed (attempt " + consecutiveFailures
                    + "): " + e.getMessage());

            if (consecutiveFailures >= MAX_DEAD_LETTER_RETRIES) {
                logger.severe("[DbWriteQueue] " + MAX_DEAD_LETTER_RETRIES
                        + " consecutive failures — dropping " + batch.size()
                        + " tasks to prevent unbounded growth. Check database health!");
                // Do NOT re-queue; log each dropped task for forensic recovery
                for (DbWriteTask task : batch) {
                    logger.severe("[DbWriteQueue] DROPPED task: " + task.getClass().getSimpleName());
                }
                consecutiveFailures = 0;
            } else {
                // Re-queue into dead-letter for retry on next flush cycle
                batch.forEach(deadLetterQueue::offer);
            }
        }
    }
}
