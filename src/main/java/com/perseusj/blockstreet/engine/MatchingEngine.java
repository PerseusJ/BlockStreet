package com.perseusj.blockstreet.engine;

import com.perseusj.blockstreet.db.DbWriteQueue;
import com.perseusj.blockstreet.db.InsertTradeTask;
import com.perseusj.blockstreet.db.UpsertOrderTask;
import com.perseusj.blockstreet.engine.book.OrderBook;
import com.perseusj.blockstreet.engine.book.PriceLevel;
import com.perseusj.blockstreet.engine.model.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * The BlockStreet matching engine — a single-threaded event loop that serializes
 * all order book mutations and match decisions for correctness and performance.
 *
 * <h2>Design Goals</h2>
 * <ul>
 *   <li><b>Correctness</b>: All book mutations happen on exactly one thread. No locks are
 *       needed inside the hot matching path.</li>
 *   <li><b>Non-blocking submission</b>: {@link #submitOrder(Order)} uses a
 *       {@link LinkedBlockingQueue#offer} — O(1), fully thread-safe, never blocks callers.</li>
 *   <li><b>Graceful shutdown</b>: A poison-pill sentinel ({@link Order#SHUTDOWN_SENTINEL})
 *       unblocks the {@code take()} call without relying on thread interruption.</li>
 *   <li><b>Resilience</b>: The engine loop wraps each order in a {@code try/catch(Exception)}.
 *       A single bad order cannot kill the engine thread.</li>
 * </ul>
 *
 * <h2>Matching Algorithm Summary</h2>
 * <ol>
 *   <li>Dequeue order from submission queue.</li>
 *   <li>If it is a cancellation sentinel, call {@link #handleCancellation}.</li>
 *   <li>If BUY: call {@link #matchAgainstAsks}. If SELL: call {@link #matchAgainstBids}.</li>
 *   <li>If quantity remains and type is LIMIT, rest the order in the book.</li>
 *   <li>Rebuild the depth snapshot and notify all registered trade listeners.</li>
 * </ol>
 *
 * <h2>Self-Match Prevention</h2>
 * The engine skips maker orders whose {@code playerId} matches the taker's {@code playerId}.
 * The skipping logic uses {@link PriceLevel#peekAfter} to look deeper into the same price
 * level without removing any orders.
 *
 * <h2>Callback Integration</h2>
 * Instead of depending directly on Bukkit scheduler (to keep Phase 1 unit-testable),
 * the engine accepts a {@link Consumer}{@code <TradeMatch>} callback injected at
 * construction time. In production, this callback is {@code SettlementDispatcher::dispatch}
 * which hands the trade off to the Main Thread via {@code Bukkit.getScheduler().runTask()}.
 * In unit tests, the callback is a simple list collector.
 */
public final class MatchingEngine {

    // ──────────────────────────── Fields ─────────────────────────────────────────

    private final Logger logger;

    /** Maps symbol → OrderBook. Populated lazily when a symbol's first order arrives. */
    private final Map<String, OrderBook> books = new ConcurrentHashMap<>();

    /**
     * The submission queue — all inter-thread order handoffs go through here.
     * Unbounded queue: back-pressure is handled at the command/GUI layer
     * (per-player submission guard and {@code max-open-orders} config limit).
     */
    private final BlockingQueue<Order> submissionQueue = new LinkedBlockingQueue<>();

    /** Callback invoked on every fill. Executed on the engine thread. */
    private final Consumer<TradeMatch> tradeMatchCallback;

    /**
     * Optional DB write queue — if set, every fill produces an {@link InsertTradeTask}
     * and every order state change produces an {@link UpsertOrderTask}.
     * {@code null} during unit tests that do not exercise persistence.
     */
    private volatile DbWriteQueue dbWriteQueue = null;

    /** Engine thread lifecycle flags. */
    private volatile boolean running = false;
    private Thread engineThread;

    // ──────────────────────────── Constructor ────────────────────────────────────

    /**
     * @param logger             plugin logger for error reporting
     * @param tradeMatchCallback invoked once per fill with the resulting {@link TradeMatch};
     *                           must be thread-safe (will be called from engine thread)
     */
    public MatchingEngine(Logger logger, Consumer<TradeMatch> tradeMatchCallback) {
        this.logger              = logger;
        this.tradeMatchCallback  = tradeMatchCallback;
    }

    // ──────────────────────────── Lifecycle ──────────────────────────────────────

    /**
     * Starts the matching engine on a new daemon thread named "BlockStreet-MatchingEngine".
     * Must be called exactly once during plugin enable.
     *
     * @throws IllegalStateException if the engine is already running
     */
    public void start() {
        if (running) throw new IllegalStateException("MatchingEngine is already running.");
        running = true;
        engineThread = new Thread(this::runLoop, "BlockStreet-MatchingEngine");
        engineThread.setDaemon(true);
        engineThread.start();
        logger.info("[BlockStreet] MatchingEngine started.");
    }

    /**
     * Signals the engine to stop and waits up to 5 seconds for the thread to finish.
     * Inserts a poison-pill sentinel to unblock the {@code take()} call.
     * Called during plugin {@code onDisable()}.
     */
    public void stop() {
        if (!running) return;
        running = false;
        submissionQueue.offer(Order.SHUTDOWN_SENTINEL);
        if (engineThread != null) {
            try {
                engineThread.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        logger.info("[BlockStreet] MatchingEngine stopped.");
    }

    /**
     * Submits an order to the matching engine. Non-blocking; returns immediately.
     * Caller is responsible for ensuring assets are locked ({@link Order#isAssetsLocked()})
     * before calling this method.
     *
     * @param order the order to submit (must not be the SHUTDOWN_SENTINEL)
     */
    public void submitOrder(Order order) {
        submissionQueue.offer(order);
    }

    /**
     * Injects the async database write queue. Must be called before the engine processes
     * any orders if persistence is desired. Safe to call from any thread (volatile write).
     *
     * @param queue the initialized and started {@link DbWriteQueue}, or {@code null} to disable
     */
    public void setDbWriteQueue(DbWriteQueue queue) {
        this.dbWriteQueue = queue;
    }

    // ──────────────────────────── Engine Loop ────────────────────────────────────

    private void runLoop() {
        while (running) {
            try {
                Order order = submissionQueue.take(); // blocks until work arrives

                if (order == Order.SHUTDOWN_SENTINEL) {
                    logger.fine("[MatchingEngine] Received shutdown sentinel, stopping.");
                    break;
                }

                processOrder(order);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Log and continue — the engine must never die silently
                logger.severe("[MatchingEngine] Unhandled exception: " + e.getMessage());
                for (StackTraceElement el : e.getStackTrace()) {
                    logger.severe("  at " + el);
                }
            }
        }
    }

    /**
     * Processes a single dequeued order through the full matching pipeline.
     * Engine thread only.
     */
    private void processOrder(Order order) {
        // Obtain (or lazily create) the book for this symbol
        OrderBook book = books.computeIfAbsent(order.getSymbol(), OrderBook::new);

        // Check for cancellation or expiration
        if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.EXPIRED) {
            handleCancellation(order, book);
            book.rebuildDepthSnapshot();
            return;
        }

        // Route to the correct matching side
        switch (order.getSide()) {
            case BUY  -> matchAgainstAsks(order, book);
            case SELL -> matchAgainstBids(order, book);
        }

        // Rest unfilled LIMIT orders in the book
        if (order.getQuantityRemaining() > 0 && order.getType() == OrderType.LIMIT) {
            book.restOrder(order);
            // Status: OPEN if untouched, PARTIALLY_FILLED if partially matched
            if (order.getStatus() == OrderStatus.OPEN) {
                order.setStatus(OrderStatus.OPEN); // already OPEN by default
            }
            // Persist the resting order to DB
            offerUpsertOrder(order);
        }

        // Finalize taker status
        if (order.getQuantityRemaining() == 0) {
            if (order.getStatus() != OrderStatus.FILLED) {
                order.setStatus(OrderStatus.FILLED);
            }
            // Persist terminal state — fully filled takers are recorded
            offerUpsertOrder(order);
        } else if (order.getType() == OrderType.MARKET) {
            // Market order: fills what it can; remainder is discarded, not rested
            order.setStatus(OrderStatus.FILLED); // filled as much as possible
            offerUpsertOrder(order);
        }

        // Rebuild depth snapshot for GUI consumption
        book.rebuildDepthSnapshot();
    }

    // ──────────────────────────── Matching Algorithms ────────────────────────────

    /**
     * Matches a BUY order (taker) against the ask side of the book.
     * Enforces price-time priority and self-match prevention.
     *
     * <p><b>Engine thread only.</b>
     *
     * @param takerBuy the incoming buy order to match
     * @param book     the order book for the relevant symbol
     */
    private void matchAgainstAsks(Order takerBuy, OrderBook book) {
        while (takerBuy.getQuantityRemaining() > 0) {

            Map.Entry<Double, PriceLevel> bestAskEntry = book.getAsks().firstEntry();
            if (bestAskEntry == null) break; // no sellers

            PriceLevel bestAskLevel = bestAskEntry.getValue();
            Order makerOrder = bestAskLevel.peekBest();

            if (makerOrder == null) {
                // Stale empty level — remove and retry
                book.getAsks().remove(bestAskEntry.getKey());
                continue;
            }

            // ── Self-match prevention ──────────────────────────────────────────
            if (makerOrder.getPlayerId().equals(takerBuy.getPlayerId())) {
                makerOrder = skipSelfMatchOrders(bestAskLevel, takerBuy.getPlayerId(), makerOrder);
                if (makerOrder == null) break; // no non-self orders at this level
            }
            // ─────────────────────────────────────────────────────────────────

            // Price crossability check (LIMIT buy only — MARKET always crosses)
            if (takerBuy.getType() == OrderType.LIMIT
                    && takerBuy.getLimitPrice() < makerOrder.getLimitPrice()) {
                break; // buyer's limit is below the best ask; order rests
            }

            // Execute the fill
            executeFill(takerBuy, makerOrder, makerOrder.getLimitPrice(), book, bestAskLevel, book.getAsks());
        }
    }

    /**
     * Matches a SELL order (taker) against the bid side of the book.
     * Mirror of {@link #matchAgainstAsks}.
     *
     * <p><b>Engine thread only.</b>
     *
     * @param takerSell the incoming sell order to match
     * @param book      the order book for the relevant symbol
     */
    private void matchAgainstBids(Order takerSell, OrderBook book) {
        while (takerSell.getQuantityRemaining() > 0) {

            Map.Entry<Double, PriceLevel> bestBidEntry = book.getBids().firstEntry();
            if (bestBidEntry == null) break; // no buyers

            PriceLevel bestBidLevel = bestBidEntry.getValue();
            Order makerOrder = bestBidLevel.peekBest();

            if (makerOrder == null) {
                book.getBids().remove(bestBidEntry.getKey());
                continue;
            }

            // ── Self-match prevention ──────────────────────────────────────────
            if (makerOrder.getPlayerId().equals(takerSell.getPlayerId())) {
                makerOrder = skipSelfMatchOrders(bestBidLevel, takerSell.getPlayerId(), makerOrder);
                if (makerOrder == null) break;
            }
            // ─────────────────────────────────────────────────────────────────

            // Price crossability check (LIMIT sell only)
            if (takerSell.getType() == OrderType.LIMIT
                    && takerSell.getLimitPrice() > makerOrder.getLimitPrice()) {
                break; // seller's limit is above the best bid; order rests
            }

            // Execute the fill
            executeFill(takerSell, makerOrder, makerOrder.getLimitPrice(), book, bestBidLevel, book.getBids());
        }
    }

    /**
     * Executes a fill between a taker and maker order. Mutates both orders'
     * {@code quantityRemaining} and dispatches a {@link TradeMatch} via callback.
     *
     * <p><b>Engine thread only.</b>
     *
     * @param taker          the incoming active order consuming liquidity
     * @param maker          the resting passive order providing liquidity
     * @param executionPrice always the maker's limit price
     * @param book           the order book (for stat updates)
     * @param level          the price level the maker resides in
     * @param side           the map (bids or asks) the level resides in
     */
    private void executeFill(Order taker, Order maker, double executionPrice,
                             OrderBook book, PriceLevel level,
                             ConcurrentSkipListMap<Double, PriceLevel> side) {
        int fillQty = Math.min(taker.getQuantityRemaining(), maker.getQuantityRemaining());

        // Mutate quantities (engine thread — no lock needed)
        taker.decrementQuantityRemaining(fillQty);
        maker.decrementQuantityRemaining(fillQty);

        // Update status
        taker.setStatus(taker.getQuantityRemaining() == 0
                ? OrderStatus.FILLED
                : OrderStatus.PARTIALLY_FILLED);
        maker.setStatus(maker.getQuantityRemaining() == 0
                ? OrderStatus.FILLED
                : OrderStatus.PARTIALLY_FILLED);

        // Record trade stats
        book.recordTrade(executionPrice, fillQty);

        // Build and dispatch TradeMatch record
        TradeMatch trade = new TradeMatch(
                UUID.randomUUID(),
                book.getSymbol(),
                maker,
                taker,
                executionPrice,
                fillQty,
                0.0, // taker fee logic removed; handled in SettlementDispatcher
                System.currentTimeMillis()
        );
        tradeMatchCallback.accept(trade);

        // Persist fill to trade_history (async, non-blocking)
        offerInsertTrade(trade);

        // Remove fully filled maker from its price level
        if (maker.getQuantityRemaining() == 0) {
            level.pollBest(); // O(1) FIFO removal
            if (level.isEmpty()) {
                side.remove(executionPrice); // remove empty price level
            }
            // Persist terminal maker state
            offerUpsertOrder(maker);
        } else {
            // Partial fill — persist updated qty_remaining for the resting maker
            offerUpsertOrder(maker);
        }
    }

    // ──────────────────────────── Self-match helper ───────────────────────────────

    /**
     * Attempts to find the next non-self order in the given price level after the
     * provided reference. Uses {@link PriceLevel#peekAfter} to look deeper without
     * disturbing the queue.
     *
     * @param level    the price level to search within
     * @param playerId the player to avoid (the taker)
     * @param ref      the self-matched order we just skipped
     * @return the next non-self order, or {@code null} if none exists
     */
    private Order skipSelfMatchOrders(PriceLevel level, UUID playerId, Order ref) {
        Order candidate = level.peekAfter(ref);
        while (candidate != null) {
            if (!candidate.getPlayerId().equals(playerId)) {
                return candidate;
            }
            candidate = level.peekAfter(candidate);
        }
        return null; // entire level is this player's own orders
    }

    // ──────────────────────────── Cancellation ───────────────────────────────────

    /**
     * Handles a cancellation request for a resting order.
     *
     * <p>Uses the {@code consumed} CAS flag to ensure exactly one path (cancel vs fill)
     * acts on the order. If the CAS succeeds, the order is removed from the book and
     * the trade callback is invoked with a special cancellation trade to trigger refund
     * logic in {@code SettlementDispatcher}.
     *
     * <p><b>Engine thread only.</b>
     *
     * @param cancelOrder the order with status {@link OrderStatus#CANCELLED}
     * @param book        the order book to search for and remove the order from
     */
    private void handleCancellation(Order cancelOrder, OrderBook book) {
        // Attempt atomic consumption — prevent race with an in-flight fill
        if (!cancelOrder.tryConsume()) {
            // Another path (a fill) already consumed this order — do nothing
            logger.fine("[MatchingEngine] Cancellation raced with fill for order "
                    + cancelOrder.getOrderId() + " — fill wins.");
            return;
        }

        boolean removed = book.removeOrder(cancelOrder);
        if (removed) {
            logger.fine("[MatchingEngine] Order " + cancelOrder.getOrderId() + " (" + cancelOrder.getStatus() + ") removed from book.");
        } else {
            logger.warning("[MatchingEngine] Cancellation/Expiration: order " + cancelOrder.getOrderId()
                    + " not found in book (may have been filled just before).");
        }

        // Dispatch a cancellation event so SettlementDispatcher can issue the refund
        // on the Main Thread. We signal this with a zero-quantity TradeMatch with null taker.
        // The actual refund logic in SettlementDispatcher checks for this marker.
        TradeMatch cancellationEvent = new TradeMatch(
                UUID.randomUUID(),
                cancelOrder.getSymbol(),
                cancelOrder,    // the cancelled order is the "maker" (resting side)
                cancelOrder,    // taker == maker signals cancellation (SettlementDispatcher checks this)
                0.0,            // no execution price
                0,              // no quantity filled
                0.0,            // no fee
                System.currentTimeMillis()
        );
        tradeMatchCallback.accept(cancellationEvent);

        // Persist cancelled status to DB
        offerUpsertOrder(cancelOrder);
    }

    // ──────────────────────────── Book access (read-only, any thread) ─────────────

    /**
     * Returns the {@link OrderBook} for the given symbol, or {@code null} if no orders
     * have been submitted for that symbol yet.
     *
     * <p>Safe to call from any thread — {@link ConcurrentHashMap} guarantees
     * memory visibility for the map reference itself.
     */
    public OrderBook getBook(String symbol) {
        return books.get(symbol);
    }

    /**
     * Returns an unmodifiable view of all order books.
     * Used by {@code onDisable()} to iterate all symbols for persistence.
     */
    public Map<String, OrderBook> getAllBooks() {
        return Collections.unmodifiableMap(books);
    }

    /**
     * Returns the best (highest) resting bid price for the given symbol, or {@code 0.0}
     * if there are no buy orders in the book.
     *
     * <p>Safe to call from any thread — reads the concurrent skip-list snapshot.
     */
    public double getBestBid(String symbol) {
        OrderBook book = books.get(symbol);
        if (book == null) return 0.0;
        Map.Entry<Double, PriceLevel> entry = book.getBids().firstEntry();
        return entry != null ? entry.getKey() : 0.0;
    }

    /**
     * Returns the best (lowest) resting ask price for the given symbol, or {@code 0.0}
     * if there are no sell orders in the book.
     *
     * <p>Safe to call from any thread — reads the concurrent skip-list snapshot.
     */
    public double getBestAsk(String symbol) {
        OrderBook book = books.get(symbol);
        if (book == null) return 0.0;
        Map.Entry<Double, PriceLevel> entry = book.getAsks().firstEntry();
        return entry != null ? entry.getKey() : 0.0;
    }

    /** Returns {@code true} if the engine thread is currently running. */
    public boolean isRunning() { return running; }

    // ──────────────────────────── DB write helpers ───────────────────────────────

    /**
     * Offers a trade insert to the DB write queue if one is configured.
     * No-op if {@link #dbWriteQueue} is {@code null} (unit test mode).
     */
    private void offerInsertTrade(TradeMatch trade) {
        DbWriteQueue q = dbWriteQueue;
        if (q != null) {
            q.offer(new InsertTradeTask(trade));
        }
    }

    /**
     * Offers an order upsert to the DB write queue if one is configured.
     * No-op if {@link #dbWriteQueue} is {@code null} (unit test mode).
     */
    private void offerUpsertOrder(Order order) {
        DbWriteQueue q = dbWriteQueue;
        if (q != null) {
            q.offer(new UpsertOrderTask(order));
        }
    }
}
