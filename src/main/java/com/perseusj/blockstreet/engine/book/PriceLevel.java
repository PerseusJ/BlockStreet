package com.perseusj.blockstreet.engine.book;

import com.perseusj.blockstreet.engine.model.Order;

import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * A single price bucket within an {@link OrderBook}.
 *
 * <p>Orders at the same price are held in a FIFO queue implemented as a
 * {@link ConcurrentLinkedDeque}. The head of the deque is always the <em>oldest</em>
 * (highest priority) order at this price level — this enforces price-time priority.
 *
 * <h2>Thread Safety</h2>
 * {@link ConcurrentLinkedDeque} operations ({@code addLast}, {@code peekFirst},
 * {@code pollFirst}) are individually lock-free and thread-safe. However, the compound
 * operation {@code peekAfter} iterates the deque and is therefore safe <em>only</em>
 * when called from the single MatchingEngine thread. Do not call {@code peekAfter}
 * from the Main Thread or any other thread.
 */
public final class PriceLevel {

    private final double price;
    private final ConcurrentLinkedDeque<Order> orders = new ConcurrentLinkedDeque<>();

    public PriceLevel(double price) {
        this.price = price;
    }

    // ──────────────────────────── Accessors ──────────────────────────────────────

    public double getPrice() { return price; }

    /**
     * Appends an order to the tail of the FIFO queue (lowest time priority at this level).
     * Thread-safe; callable from the engine thread only (to maintain ordering invariants).
     */
    public void addOrder(Order o) {
        orders.addLast(o);
    }

    /**
     * Peeks at the front of the queue (highest-priority order) without removing it.
     * Returns {@code null} if the level is empty.
     */
    public Order peekBest() {
        return orders.peekFirst();
    }

    /**
     * Removes and returns the front-of-queue order (the oldest / highest-priority order
     * at this price). Returns {@code null} if the level is empty.
     */
    public Order pollBest() {
        return orders.pollFirst();
    }

    /**
     * Returns {@code true} if this price level has no resting orders.
     */
    public boolean isEmpty() {
        return orders.isEmpty();
    }

    /**
     * Computes the total quantity remaining across all orders at this price level.
     * Used for market depth snapshot and escrow calculation.
     *
     * <p><b>Engine-thread only.</b> The iteration is consistent because all structural
     * modifications to this deque are performed on the single engine thread.
     */
    public int totalVolume() {
        return orders.stream().mapToInt(Order::getQuantityRemaining).sum();
    }

    // ──────────────────────────── Self-match traversal ───────────────────────────

    /**
     * Returns the next order in the FIFO queue <em>after</em> the given reference order,
     * skipping over it to support self-match prevention.
     *
     * <p>Iterates the deque from head until {@code ref} is found by reference equality,
     * then returns the following element. Returns {@code null} if {@code ref} is the last
     * element or is not found in the deque (stale reference).
     *
     * <p><b>Engine-thread only.</b>
     *
     * @param ref the order to skip past
     * @return the next order after {@code ref}, or {@code null}
     */
    public Order peekAfter(Order ref) {
        boolean foundRef = false;
        for (Order o : orders) {
            if (foundRef) {
                return o;
            }
            if (o == ref) { // reference equality — same object instance
                foundRef = true;
            }
        }
        return null; // ref was last or not found
    }

    /**
     * Removes a specific order from this level by reference equality.
     * Used during cancellation handling on the engine thread.
     *
     * @param order the order to remove
     * @return {@code true} if the order was found and removed
     */
    public boolean removeOrder(Order order) {
        return orders.removeIf(o -> o == order);
    }
}
