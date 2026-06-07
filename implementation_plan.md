# BlockStreet — Technical Blueprint

> **Stack**: Paper/Spigot 1.21.1 · Java 21 · Maven · Vault · SQLite (primary) / MySQL (optional)  
> **Package root**: `com.perseusj.blockstreet`  
> **Existing scaffold**: `BlockStreet.java`, `PluginManager`, `PlayerListener`, `plugin.yml`

---

## Concurrency Risk Analysis (Pre-Blueprint)

Before any phase, the following risks were identified and drive every design decision below:

| Risk | Consequence | Mitigation |
|---|---|---|
| Order submitted from Main Thread touches order book | Contention / ConcurrentModificationException | All book mutations happen only on the `MatchingEngine` dedicated thread |
| Player disconnects between fund-lock and fill | Currency/item lost permanently or duplicated | Two-phase commit: lock → async match → main-thread release |
| GUI click fires concurrently with an order fill | Item delivered twice to player | Per-order `AtomicBoolean` "consumed" flag checked before any delivery |
| Database write fails mid-batch | Trade record lost; book and ledger diverge | WAL journal mode (SQLite); retryable queue with dead-letter log |
| Chat catcher left dangling (player logs out) | Listener leaks; duplicate registrations | Catcher registered with expiry; removed on `PlayerQuitEvent` |
| `ConcurrentSkipListMap` iterator + removal race | Stale entries, phantom orders | All structural changes serialized via the single `MatchingEngine` thread; iterators are snapshot-style |
| Market BUY order locked at wrong amount | Vault under/over-charge, currency duplication | Main Thread peeks `MarketDepthSnapshot` to compute exact sweep cost before locking |
| No economy drain (zero-sum trading) | Server hyperinflation over time | Taker fee % permanently removes currency from economy on every fill |
| Sub-tick prices fill `ConcurrentSkipListMap` with thousands of levels | O(log n) degrades; GUI overflow | All prices normalised to `price-tick` before entering the book |
| Player self-matches own resting order | Bleeds taker fees to self; wash-trade stats | Engine skips maker orders where `makerOrder.playerId == takerOrder.playerId` |

---

## Phase 1 — Core Engine Models & In-Memory Matching Logic

### 1.1 Package Layout (new packages to create)

```
com.perseusj.blockstreet
├── engine/
│   ├── model/
│   │   ├── Order.java
│   │   ├── OrderType.java
│   │   ├── OrderSide.java
│   │   ├── OrderStatus.java
│   │   └── TradeMatch.java
│   ├── book/
│   │   ├── OrderBook.java
│   │   └── PriceLevel.java
│   └── MatchingEngine.java
├── managers/          ← existing
├── listeners/         ← existing
├── gui/
├── db/
├── commands/
└── config/
```

### 1.2 Enumerations

#### `OrderType.java`
```java
public enum OrderType {
    LIMIT,   // rests in book until filled or cancelled
    MARKET   // sweeps the book at best available price
}
```

#### `OrderSide.java`
```java
public enum OrderSide {
    BUY,   // bid — willing to pay UP TO price
    SELL   // ask — willing to accept AT LEAST price
}
```

#### `OrderStatus.java`
```java
public enum OrderStatus {
    OPEN,         // resting in book
    PARTIALLY_FILLED,
    FILLED,       // fully executed
    CANCELLED,
    REJECTED      // failed pre-checks (insufficient funds/items)
}
```

### 1.3 `Order.java` POJO

```java
public final class Order {
    // Immutable identity
    private final UUID orderId;           // random UUID
    private final UUID playerId;
    private final String symbol;          // e.g. "DIAMOND", "NETHERITE_INGOT"
    private final OrderSide side;
    private final OrderType type;
    private final double limitPrice;      // 0 for MARKET orders
    private final long submittedAt;       // System.nanoTime() for FIFO tiebreak

    // Mutable state (all writes serialized on MatchingEngine thread)
    private volatile int quantityOriginal;
    private volatile int quantityRemaining;
    private volatile OrderStatus status;

    // Thread-safety primitive — prevents double-spend during async handoff
    // Set to true the moment the order's assets are reserved on the Main Thread
    private final AtomicBoolean assetsLocked = new AtomicBoolean(false);

    // Set to true ONCE when the order is "consumed" (filled/cancelled/rejected)
    // Prevents any secondary delivery or refund path from acting on a stale ref
    private final AtomicBoolean consumed = new AtomicBoolean(false);

    // ... constructor, getters, equals/hashCode on orderId only
}
```

### 1.4 `TradeMatch.java` — Value Object Produced by the Engine

```java
public record TradeMatch(
    UUID tradeId,           // random UUID for ledger
    String symbol,
    Order makerOrder,       // resting order (the passive side)
    Order takerOrder,       // incoming order (the active side)
    double executionPrice,  // always the MAKER's limit price (price-time priority)
    int quantityFilled,
    long executedAt         // System.currentTimeMillis() for persistence
) {}
```

### 1.5 `PriceLevel.java` — A Single Price Bucket in the Book

```java
public final class PriceLevel {
    private final double price;
    // FIFO queue; ConcurrentLinkedDeque allows O(1) head-peek and O(1) tail-add
    private final ConcurrentLinkedDeque<Order> orders = new ConcurrentLinkedDeque<>();

    public void addOrder(Order o) { orders.addLast(o); }
    public Order peekBest()       { return orders.peekFirst(); }
    public Order pollBest()       { return orders.pollFirst(); }
    public boolean isEmpty()      { return orders.isEmpty(); }
    public int totalVolume()      { return orders.stream().mapToInt(Order::getQuantityRemaining).sum(); }
}
```

### 1.6 `OrderBook.java` — The Central In-Memory Structure

```java
public final class OrderBook {
    private final String symbol;

    /**
     * BIDS (buy orders): highest price = best bid → DESCENDING key order.
     * Key = price (Double), Value = PriceLevel (FIFO queue of orders at that price).
     * ConcurrentSkipListMap gives O(log n) put/get/remove and lock-free iteration.
     */
    private final ConcurrentSkipListMap<Double, PriceLevel> bids =
        new ConcurrentSkipListMap<>(Comparator.reverseOrder());

    /**
     * ASKS (sell orders): lowest price = best ask → ASCENDING key order (natural).
     */
    private final ConcurrentSkipListMap<Double, PriceLevel> asks =
        new ConcurrentSkipListMap<>();

    // Running stats updated after every match (volatile for GUI reads from other threads)
    private volatile double lastTradePrice = 0.0;
    private volatile long lastTradeTimestamp = 0L;
    private volatile long totalVolume24h = 0L;

    /** Snapshot of top-N levels for GUI rendering — replaced atomically */
    private volatile MarketDepthSnapshot depthSnapshot = MarketDepthSnapshot.EMPTY;

    // ... getters, helpers
}
```

> **Why `ConcurrentSkipListMap`?**  
> - `O(log n)` for `put`, `get`, `remove`, `firstKey`  
> - Lock-free reads via `CAS` internally; safe to read from GUI thread  
> - Natural ordering eliminates need for external sort on iteration

### 1.7 `MatchingEngine.java` — Algorithmic Loop

The engine runs on a **single dedicated thread** (`MatchingEngineThread`) consuming from an unbounded `LinkedBlockingQueue<Order>` submission queue.

#### Submission Queue

```java
private final BlockingQueue<Order> submissionQueue = new LinkedBlockingQueue<>();

public void submitOrder(Order order) {
    submissionQueue.offer(order);  // non-blocking, O(1), thread-safe
}
```

#### Main Engine Loop (runs on dedicated thread)

```
LOOP:
    order ← submissionQueue.take()   // blocks until work arrives, no spin-wait

    IF order.isCancellation():
        handleCancellation(order)
        CONTINUE

    IF order.getSide() == BUY:
        matchAgainstAsks(order)
    ELSE:
        matchAgainstBids(order)

    IF order.getQuantityRemaining() > 0 AND order.getType() == LIMIT:
        restOrderInBook(order)

    rebuildDepthSnapshot()
    notifyGUISubscribers()
```

#### `matchAgainstAsks(Order takerBuyOrder)` — Detailed Algorithm

```
PRECONDITION: Executing on MatchingEngine thread only.

WHILE takerBuyOrder.quantityRemaining > 0:

    bestAskLevel ← asks.firstEntry()          // O(log n) — cheapest ask
    IF bestAskLevel == null: BREAK            // no sellers; order rests

    bestAsk ← bestAskLevel.getValue()
    makerOrder ← bestAsk.peekBest()           // O(1) — FIFO front of queue

    IF makerOrder == null:
        asks.remove(bestAskLevel.getKey())    // stale empty level, clean up
        CONTINUE

    // ── SELF-MATCH PREVENTION ──────────────────────────────────────────
    // If the taker and this maker are the same player, skip this maker
    // and inspect the next order in the queue. This prevents wash-trading
    // and avoids the player bleeding their own taker fee.
    IF takerBuyOrder.playerId == makerOrder.playerId:
        // Peek deeper into the same price level's deque
        makerOrder ← bestAsk.peekAfter(makerOrder)   // helper: next in FIFO after current
        IF makerOrder == null: BREAK                  // no non-self orders at this level; stop
        CONTINUE  // re-evaluate loop with the new makerOrder reference
    // ── END SELF-MATCH PREVENTION ──────────────────────────────────────

    // Price-Time Priority check:
    IF takerBuyOrder.type == LIMIT AND takerBuyOrder.limitPrice < makerOrder.limitPrice:
        BREAK  // buyer's price is below best ask; order rests

    // Determine fill quantity:
    fillQty ← min(takerBuyOrder.quantityRemaining, makerOrder.quantityRemaining)

    // Execution price = MAKER's price (price-time priority rule)
    execPrice ← makerOrder.limitPrice

    // Mutate both orders (single engine thread — no lock needed here)
    takerBuyOrder.quantityRemaining  -= fillQty
    makerOrder.quantityRemaining     -= fillQty

    // Build TradeMatch record
    // takerFee is calculated here for record-keeping; actual deduction happens
    // in SettlementDispatcher on the Main Thread (see Phase 2)
    trade ← new TradeMatch(UUID.random(), symbol, makerOrder, takerBuyOrder, execPrice, fillQty, now())

    // Dispatch settlement to Main Thread (see Phase 2)
    settlementDispatcher.dispatch(trade)

    // Enqueue to DB batch writer (see Phase 4)
    dbWriteQueue.offer(trade)

    IF makerOrder.quantityRemaining == 0:
        makerOrder.status ← FILLED
        bestAsk.pollBest()                    // remove from FIFO queue
        IF bestAsk.isEmpty():
            asks.remove(bestAskLevel.getKey()) // remove empty price level

IF takerBuyOrder.quantityRemaining == 0:
    takerBuyOrder.status ← FILLED
ELSE IF takerBuyOrder.type == MARKET:
    // Market order: fills what it can. The engine dispatched settlement for
    // all filled portions. The locked escrow amount minus actual cost is
    // refunded in SettlementDispatcher (see Phase 2 § Market Order Escrow).
    takerBuyOrder.status ← FILLED
```

`matchAgainstBids` is the mirror: iterate descending bids, taker is a SELL order, break if `takerSellOrder.limitPrice > bestBid.limitPrice`. The **same self-match check applies** — skip maker orders where `makerOrder.playerId == takerOrder.playerId`.

> **`PriceLevel.peekAfter(Order ref)` helper**: Implemented by iterating the `ConcurrentLinkedDeque` from head, skipping until `ref` is found, then returning the next element. Since this runs only on the single engine thread, iteration is safe. Returns `null` if `ref` is the last element or the deque is exhausted.

#### `restOrderInBook(Order order)`

```
IF order.side == BUY:
    level ← bids.computeIfAbsent(order.limitPrice, p -> new PriceLevel(p))
ELSE:
    level ← asks.computeIfAbsent(order.limitPrice, p -> new PriceLevel(p))
level.addOrder(order)
order.status ← OPEN   (or PARTIALLY_FILLED if fillQty > 0)
```

#### `handleCancellation(Order cancelOrder)`

```
// Look up in bids or asks by price key, then scan the PriceLevel's deque
// Mark order.consumed CAS(false → true) before removing
// If CAS fails → order already filled; do NOT refund
// If CAS succeeds → dispatch refund to Main Thread
```

#### `rebuildDepthSnapshot()`

Reads top 5 entries from `bids` and top 5 from `asks` (iterator is snapshot-consistent on `ConcurrentSkipListMap`), calculates spread = `bestAsk - bestBid`, stores in `OrderBook.depthSnapshot` as an immutable value object. GUI threads read this reference atomically.

---

### 1.8 Price Tick Normalization — Enforced Before Book Entry

All limit prices **must** be normalized to the asset's `price-tick` before any order enters the `OrderBook` or the matching loop. This prevents the `ConcurrentSkipListMap` from accumulating thousands of micro-fragmented price levels (e.g., `$10.001`, `$10.002`, `$10.003` instead of a single `$10.00` level).

#### Normalization Formula

$$P_{\text{normalized}} = \left\lfloor \frac{P_{\text{raw}}}{T} \right\rceil \times T$$

Where $T$ is the configured `price-tick` for the asset, and $\lfloor \cdot \rceil$ denotes **round-half-up** (banker's rounding is acceptable but round-half-up is more intuitive for players).

#### Implementation in `OrderValidator.java`

```java
/**
 * Normalizes a raw price to the nearest valid tick for the given asset.
 * Must be called BEFORE Phase 2A asset locking and BEFORE submissionQueue.offer().
 * Operates on the Main Thread — no concurrency concerns.
 *
 * @param rawPrice  the price entered by the player
 * @param tickSize  the asset's configured minimum price increment (e.g. 0.01)
 * @return          the normalized price, rounded to nearest tick
 */
public static double normalizeToTick(double rawPrice, double tickSize) {
    if (tickSize <= 0) throw new IllegalArgumentException("tickSize must be positive");
    // Use BigDecimal to avoid IEEE 754 floating-point drift compounding over many ticks
    BigDecimal raw  = BigDecimal.valueOf(rawPrice);
    BigDecimal tick = BigDecimal.valueOf(tickSize);
    BigDecimal normalized = raw.divide(tick, 0, RoundingMode.HALF_UP).multiply(tick);
    return normalized.doubleValue();
}
```

#### Enforcement Points

| Location | When Applied |
|---|---|
| `OrderValidator.validate(Order)` | Before Phase 2A asset locking (Main Thread) |
| `/bs buy` and `/bs sell` CLI handlers | After arg parsing, before order construction |
| `ChatInputCatcher` — step QTY complete | Before calling `buildOrder(...)` |
| `dbService.loadOpenOrders()` (reboot recovery) | Applied again on load in case tick changed in config |

> **Key invariant**: The `OrderBook`'s `ConcurrentSkipListMap` will **never** contain a key that is not an exact multiple of `tickSize`. This guarantees O(log n) performance stays bounded even under heavy order flow.

> **Market orders** are exempt — they carry no limit price and sweep at maker prices, which are already normalized.

---

## Phase 2 — Asynchronous Engine & Thread Boundary Management

### 2.1 Thread Map

```
┌─────────────────────────────────────────────────────────────────────┐
│  SERVER MAIN THREAD (Bukkit tick loop)                              │
│                                                                     │
│  • Vault: economy.withdrawPlayer / depositPlayer                    │
│  • Inventory: player.getInventory().addItem / removeItem            │
│  • GUI: InventoryClickEvent, InventoryCloseEvent handlers           │
│  • Player state reads (isOnline, getInventory)                      │
│  • Bukkit Scheduler runTask() callbacks land HERE                   │
└───────────────────────┬─────────────────────────────────────────────┘
                        │ submit order (offer to BlockingQueue)
                        ▼
┌─────────────────────────────────────────────────────────────────────┐
│  MATCHING ENGINE THREAD (single dedicated thread, daemon)           │
│                                                                     │
│  • Order matching algorithm (Phase 1 loop)                         │
│  • Price-time priority evaluation                                   │
│  • PriceLevel / OrderBook mutations                                 │
│  • Spread & market depth calculation                                │
│  • Produces TradeMatch records                                      │
│  • Calls settlementDispatcher.dispatch(trade)                       │
│  • Calls dbWriteQueue.offer(trade)                                  │
└───────────────────────┬─────────────────────────────────────────────┘
                        │ Bukkit.getScheduler().runTask(plugin, ...)
                        ▼
┌─────────────────────────────────────────────────────────────────────┐
│  SERVER MAIN THREAD (callback — settlement phase)                   │
│                                                                     │
│  • Verify player online                                             │
│  • Vault deposit/withdraw (final, irreversible)                     │
│  • Deliver items to inventory (or mailbox if full)                  │
│  • Release asset lock (assetsLocked stays true; consumed → true)    │
│  • Notify player via actionbar/chat                                 │
└─────────────────────────────────────────────────────────────────────┘
                        │ offer(TradeMatch)
                        ▼
┌─────────────────────────────────────────────────────────────────────┐
│  DB WRITER THREAD (single ScheduledExecutorService thread)          │
│                                                                     │
│  • Drains dbWriteQueue every 3 seconds                             │
│  • Batch-inserts into trades table                                  │
│  • Upserts resting_orders table on status change                    │
│  • Completely isolated from engine and main thread                  │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.2 `SettlementDispatcher` — Main-Thread Handshake

#### Taker Fee (Economy Sink)

Every fill charges the **taker** (the incoming, active order) a percentage fee that is **permanently destroyed** — not sent to any player. This is the primary economy sink preventing hyperinflation.

- The fee is applied **only** to the taker, not the maker (maker provides liquidity; taker takes it).
- The fee is deducted from the taker's **gross proceeds** if selling, or added to the taker's **cost** if buying. In practice, the taker's escrow already covers gross cost; the fee is simply not refunded.
- Fee rate read from `config.yml → engine.taker-fee-percentage` at startup and on `/bs admin reload`.

**Fee Accounting per fill:**

| Taker Side | Gross Value | Fee Deducted From | Net to Taker | Goes To |
|---|---|---|---|---|
| BUY (market/limit) | `execPrice × qty` | Taker's escrow (not refunded) | `qty` items delivered | Fee destroyed |
| SELL (market/limit) | `execPrice × qty` | Gross currency proceeds | `execPrice × qty × (1 - feeRate)` deposited | Fee destroyed |

```java
public final class SettlementDispatcher {
    private final Plugin plugin;
    private final VaultEconomyService economy;  // wrapper around Vault
    private final ConfigManager config;

    public void dispatch(TradeMatch trade) {
        // Called from MatchingEngine thread — hand off to Main Thread
        Bukkit.getScheduler().runTask(plugin, () -> {
            settleMaker(trade);
            settleTaker(trade);
        });
    }

    private void settleMaker(TradeMatch trade) {
        Order maker = trade.makerOrder();
        double grossValue = trade.executionPrice() * trade.quantityFilled();

        if (maker.getSide() == OrderSide.SELL) {
            // Maker SOLD → receive full gross value (makers pay NO fee — they provide liquidity)
            economy.depositPlayer(maker.getPlayerId(), grossValue);

        } else {
            // Maker BOUGHT → receive items
            deliverItems(maker.getPlayerId(), trade.symbol(), trade.quantityFilled());
            // Refund overpayment: maker locked limitPrice × qty; execution may be cheaper
            double overpay = (maker.getLimitPrice() - trade.executionPrice()) * trade.quantityFilled();
            if (overpay > 0.0) economy.depositPlayer(maker.getPlayerId(), overpay);
            // NOTE: Makers do NOT pay a taker fee — no deduction here.
        }
    }

    private void settleTaker(TradeMatch trade) {
        Order taker = trade.takerOrder();
        double grossValue  = trade.executionPrice() * trade.quantityFilled();
        double feeRate     = config.getTakerFeeRate();        // e.g. 0.01 for 1%
        double feeAmount   = grossValue * feeRate;            // destroyed; never deposited
        double netValue    = grossValue - feeAmount;          // what the taker effectively receives/pays net

        if (taker.getSide() == OrderSide.SELL) {
            // Taker SOLD → deposit net proceeds (fee is withheld and destroyed)
            economy.depositPlayer(taker.getPlayerId(), netValue);
            // Items were already removed from inventory during Phase 2A locking; no action needed.

        } else {
            // Taker BOUGHT → deliver items
            deliverItems(taker.getPlayerId(), trade.symbol(), trade.quantityFilled());

            // Escrow refund for market orders or limit orders filled below limit price:
            // escrowAmount was locked upfront in Phase 2A.
            // Actual cost = grossValue. Fee is withheld (not refunded). Remainder is refunded.
            // net escrow used = grossValue (fee is the "cost" of being the taker)
            double escrowUsed = grossValue;  // the fee is simply NOT refunded — it vanishes
            double escrowRefund = taker.getEscrowAmount()
                                  - (taker.getEscrowAmountConsumedSoFar() + escrowUsed);
            if (escrowRefund > 0.001) {   // float epsilon guard
                economy.depositPlayer(taker.getPlayerId(), escrowRefund);
                taker.incrementEscrowConsumed(escrowUsed);
            }
            // NOTE: feeAmount is intentionally NOT deposited anywhere — it is the economy sink.
        }

        // Log the fee for admin transparency
        plugin.getLogger().fine(String.format(
            "[Fee Sink] Trade %s | Fee: %.4f | Rate: %.2f%%",
            trade.tradeId(), feeAmount, feeRate * 100));
    }

    private void deliverItems(UUID playerId, String symbol, int qty) {
        Player player = Bukkit.getPlayer(playerId);
        ItemStack items = ItemFactory.create(symbol, qty);
        if (player != null && player.isOnline()) {
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(items);
            if (!overflow.isEmpty()) {
                // Inventory full — send to mailbox chest (see Phase 5 edge cases)
                MailboxManager.getInstance().storeItems(playerId, overflow.values());
                player.sendMessage(ChatColor.YELLOW + "[BlockStreet] Inventory full! Items stored in your mailbox.");
            }
        } else {
            // Player offline — store in mailbox, deliver on next login
            MailboxManager.getInstance().storeItems(playerId, List.of(items));
        }
    }
}
```

> **Why only the taker pays?** This mirrors real-world exchange mechanics (maker-taker model). Makers post resting orders that add liquidity; takers consume it. Incentivizing makers with zero fees keeps the order book deep and functional. A 1% taker fee on a busy server creates a meaningful, self-regulating money sink without discouraging market participation.

### 2.3 Transaction Lock Mechanism — Two-Phase Commit

The goal: **between order submission and first match**, funds/items must be atomically reserved so they cannot be spent elsewhere.

#### Phase A — Pre-Submission Lock (Main Thread)

When a player submits an order via GUI:

```
[MAIN THREAD]
1. Validate order parameters (price > 0, qty > 0, symbol tradeable).
   a. Normalize limitPrice via OrderValidator.normalizeToTick(rawPrice, tickSize)  ← §1.8

2. For SELL order:
   a. Count item in inventory: player.getInventory().containsAtLeast(item, qty)
   b. Remove items immediately: inventory.removeItem(item × qty)
   c. Set order.assetsLocked = true
   d. If removal fails → REJECT order, do not submit

3. For LIMIT BUY order:
   a. escrowAmount = normalizedLimitPrice × qty
   b. Check Vault balance: economy.getBalance(player) >= escrowAmount
   c. Withdraw funds immediately: economy.withdrawPlayer(player, escrowAmount)
   d. Store order.escrowAmount = escrowAmount
   e. Set order.assetsLocked = true
   f. If withdrawal fails → REJECT order, do not submit

4. For MARKET BUY order:  ← MARKET ORDER ESCROW FIX
   // A market order has no known price. We MUST estimate the sweep cost
   // using the current depth snapshot BEFORE locking, otherwise we either
   // over-lock (bad UX) or under-lock (Vault balance exploit).
   a. snapshot ← orderBook.getDepthSnapshot()   // volatile read — safe on Main Thread
   b. sweepCost ← 0.0; sweepQty ← 0
      FOR each askLevel in snapshot.asks (ascending price):
          fillable ← min(qty - sweepQty, askLevel.totalVolume)
          sweepCost += fillable × askLevel.price
          sweepQty  += fillable
          IF sweepQty >= qty: BREAK
   c. IF sweepQty < qty:
          // Book is too thin to fill the full order. Warn the player and
          // lock only the calculable portion; engine will fill what it can.
          notify player: "Book only has {sweepQty} available; locking cost for that qty."
   d. escrowAmount = sweepCost   // exact calculated cost for available liquidity
   e. Check Vault balance: economy.getBalance(player) >= escrowAmount
   f. Withdraw funds: economy.withdrawPlayer(player, escrowAmount)
   g. Store order.escrowAmount = escrowAmount
   h. Set order.assetsLocked = true
   i. If withdrawal fails → REJECT order
   // NOTE: If the book shifts between step (a) and engine execution,
   // the engine fills what the escrow covers and cancels the rest.
   // SettlementDispatcher refunds any unused escrow (see Phase C below).

5. Assign orderId, submittedAt = System.nanoTime()
6. submissionQueue.offer(order)  ← hand to engine thread
```

> **Snapshot staleness tolerance**: The `MarketDepthSnapshot` is rebuilt by the engine after every order processed. In normal operation it is at most one engine-tick stale. The worst case is a thin, fast-moving book — the escrow calculation may underestimate cost if prices move up between snapshot and execution. The engine handles this by filling only what the escrow covers and treating the remainder as unfilled (market orders do not rest, so the remainder is discarded and escrow is fully refunded for the unfilled portion).

#### Phase B — Cancellation Refund (Main Thread, via Engine handoff)

```
[ENGINE THREAD] handleCancellation:
  CAS order.consumed (false → true)
  IF success:
    Bukkit.scheduler.runTask → [MAIN THREAD]:
      IF order.side == SELL:
        deliverItems(playerId, symbol, quantityRemaining)
      IF order.side == BUY:
        refundAmt = order.limitPrice × order.quantityRemaining
        economy.depositPlayer(playerId, refundAmt)
  IF CAS fails (already consumed by a fill):
    No action — fill settlement handles delivery
```

#### Phase C — Fill Settlement (Main Thread, via SettlementDispatcher)

After each partial or full fill:
- Maker's consumed flag is only set `true` when `quantityRemaining == 0` (full fill). Partial fills leave the order alive in the book.
- Taker's consumed flag set `true` after all loop iterations complete.
- Any unused escrow (market orders or limit BUY filled below limit price) is refunded by `SettlementDispatcher` as part of settlement.

> **Key invariant**: `assetsLocked` is set `true` exactly once on the Main Thread before the order enters the queue, and `consumed` transitions `false → true` exactly once via CAS (either by cancellation OR by the final fill). These two flags together close every double-spend window.

### 2.4 `MatchingEngineThread` — Lifecycle

```java
public final class MatchingEngine {
    private final Thread engineThread;
    private volatile boolean running = false;

    public void start() {
        running = true;
        engineThread = new Thread(this::runLoop, "BlockStreet-MatchingEngine");
        engineThread.setDaemon(true);
        engineThread.start();
    }

    public void stop() {
        running = false;
        // Poison pill: insert a sentinel order to unblock take()
        submissionQueue.offer(Order.SHUTDOWN_SENTINEL);
    }

    private void runLoop() {
        while (running) {
            try {
                Order order = submissionQueue.take();
                if (order == Order.SHUTDOWN_SENTINEL) break;
                processOrder(order);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                plugin.getLogger().severe("MatchingEngine error: " + e.getMessage());
                // Log but keep running — engine must never die silently
            }
        }
    }
}
```

---

## Phase 3 — Live Inventory GUI & Order Book Visualization

### 3.1 Package Layout

```
com.perseusj.blockstreet.gui/
├── GuiManager.java          ← opens/closes GUIs, tracks open inventories
├── MarketGui.java           ← main 6-row trading screen
├── OrderEntryGui.java       ← order type/qty/price selection (3-row)
├── ActiveOrdersGui.java     ← player's open orders (paginated)
├── DepthRenderer.java       ← converts MarketDepthSnapshot → ItemStack[]
└── ChatInputCatcher.java    ← async-safe chat-based number entry
```

### 3.2 `MarketGui.java` — Slot Layout (54-slot chest)

```
Row 0  [0-8]:   ─── Header banner + asset icon + current price display ───
Row 1  [9-17]:  ASK levels (top 5, rendered right-to-left, worst→best)
Row 2 [18-26]:  ASK levels continued + SPREAD indicator (slot 22)
Row 3 [27-35]:  BID levels (top 5, rendered left-to-right, best→worst)
Row 4 [36-44]:  ─── Action buttons: [PLACE BUY] [CANCEL ORDER] [PLACE SELL] ───
Row 5 [45-53]:  ─── Stats bar: 24h vol, last price, your open orders ───
```

#### Slot Assignments (exact)

| Slot | Content |
|---|---|
| 4 | Asset display item (e.g. Diamond with lore: last price, spread) |
| 9–13 | Ask price levels 5→1 (worst to best ask) — RED stained glass |
| 14 | Spread display — YELLOW stained glass, lore = spread value |
| 15–19 | Bid price levels 1→5 (best to worst bid) — LIME stained glass |
| 36 | **[PLACE BUY ORDER]** — Emerald block |
| 40 | **[CANCEL ORDER]** — Barrier |
| 44 | **[PLACE SELL ORDER]** — Redstone block |
| 49 | **[MY ORDERS]** — Paper |
| 45 | Back / close |

### 3.3 `DepthRenderer.java` — Converting Depth to ItemStacks

```java
public ItemStack renderAskLevel(double price, int totalVolume, int rank) {
    // rank 1 = best ask (lowest price)
    ItemStack glass = new ItemStack(Material.RED_STAINED_GLASS_PANE);
    ItemMeta meta = glass.getItemMeta();
    meta.setDisplayName(ChatColor.RED + "ASK #" + rank + "  " + 
        PRICE_FORMAT.format(price));
    meta.setLore(List.of(
        ChatColor.GRAY + "Volume: " + totalVolume,
        ChatColor.GRAY + "Price:  " + CURRENCY_FORMAT.format(price)
    ));
    glass.setItemMeta(meta);
    return glass;
}
```

GUI refresh is driven by a `BukkitRunnable` repeating task running on the **Main Thread** every 10 ticks (0.5 s). It reads `OrderBook.depthSnapshot` (volatile reference, safe cross-thread read) and calls `MarketGui.refresh(snapshot)` which calls `Inventory.setItem()` for each changed slot.

> Do **not** call `Inventory.setItem` from the engine thread — it is not thread-safe.

### 3.4 `GuiManager.java` — Open GUI Registry

```java
// Tracks which Inventory object belongs to which player and GUI type
private final Map<UUID, GuiSession> openSessions = new ConcurrentHashMap<>();

public record GuiSession(UUID playerId, GuiType type, Inventory inventory, String symbol) {}
```

All `InventoryClickEvent` and `InventoryCloseEvent` handlers check `openSessions` to confirm the inventory is a BlockStreet GUI before processing.

### 3.5 `ChatInputCatcher.java` — Price/Quantity Entry Without Thread Blocking

When a player clicks **[PLACE BUY ORDER]**:

```
1. Close the MarketGui (fire InventoryCloseEvent, handled cleanly).
2. Register a ChatInputCatcher for this player UUID:
   - Store in a Map<UUID, ChatInputCatcher> pendingInputs
   - Annotate with: symbol, side (BUY/SELL), step (PRICE or QTY), expiry = now + 30s
3. Send player a chat prompt:
   "[BlockStreet] Enter your LIMIT PRICE (or type 'cancel'):"
4. On AsyncPlayerChatEvent (HIGHEST priority, cancelled=true to suppress):
   a. Check pendingInputs.containsKey(player.getUUID())
   b. Parse double from message
   c. If step == PRICE: store tempPrice, advance step to QTY, prompt for quantity
   d. If step == QTY: store tempQty
   e. Bukkit.scheduler.runTask(plugin, () -> {
         // NOW on Main Thread:
         Order order = buildOrder(player, symbol, side, LIMIT, tempPrice, tempQty)
         // Run Phase 2A pre-submission lock sequence
         submitOrder(order)
         openMarketGui(player, symbol)
      })
   f. Remove from pendingInputs
5. On PlayerQuitEvent: remove from pendingInputs (no orphan listeners)
6. Scheduled cleanup task every 5s: remove expired catchers, open GUI back
```

> `AsyncPlayerChatEvent` fires on an **async thread**. The handler MUST NOT call any Bukkit API directly — only parse the string, then hand off via `runTask`.

### 3.6 Real-Time GUI Update Architecture

```
MatchingEngine thread → updates OrderBook.depthSnapshot (volatile write)
                                    ↓
Main Thread (BukkitRunnable, every 10 ticks):
    For each open GuiSession of type MARKET:
        snapshot = book.getDepthSnapshot()    ← volatile read, safe
        depthRenderer.render(snapshot) → ItemStack[]
        inventory.setItem(slot, item)         ← must be Main Thread
        player.updateInventory()
```

---

## Phase 4 — Async Database Persistence & Transaction Ledger

### 4.1 Database Schema

#### `resting_orders` table

```sql
CREATE TABLE IF NOT EXISTS resting_orders (
    order_id        TEXT PRIMARY KEY,      -- UUID string
    player_id       TEXT NOT NULL,         -- UUID string
    symbol          TEXT NOT NULL,
    side            TEXT NOT NULL,         -- BUY / SELL
    order_type      TEXT NOT NULL,         -- LIMIT / MARKET
    limit_price     REAL NOT NULL,
    qty_original    INTEGER NOT NULL,
    qty_remaining   INTEGER NOT NULL,
    status          TEXT NOT NULL,         -- OPEN / PARTIALLY_FILLED / etc.
    submitted_at    INTEGER NOT NULL,      -- epoch millis
    updated_at      INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_resting_player ON resting_orders(player_id);
CREATE INDEX IF NOT EXISTS idx_resting_symbol_status ON resting_orders(symbol, status);
```

#### `trade_history` table

```sql
CREATE TABLE IF NOT EXISTS trade_history (
    trade_id        TEXT PRIMARY KEY,
    symbol          TEXT NOT NULL,
    maker_order_id  TEXT NOT NULL,
    taker_order_id  TEXT NOT NULL,
    maker_player_id TEXT NOT NULL,
    taker_player_id TEXT NOT NULL,
    execution_price REAL NOT NULL,
    qty_filled      INTEGER NOT NULL,
    taker_fee       REAL NOT NULL,         -- fee amount destroyed (economy sink audit trail)
    fee_rate_pct    REAL NOT NULL,         -- snapshot of the fee rate at time of trade
    executed_at     INTEGER NOT NULL       -- epoch millis
);

CREATE INDEX IF NOT EXISTS idx_trade_symbol ON trade_history(symbol, executed_at DESC);
CREATE INDEX IF NOT EXISTS idx_trade_player  ON trade_history(maker_player_id, taker_player_id);
```

#### `mailbox_items` table (for offline delivery)

```sql
CREATE TABLE IF NOT EXISTS mailbox_items (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    player_id       TEXT NOT NULL,
    symbol          TEXT NOT NULL,
    quantity        INTEGER NOT NULL,
    stored_at       INTEGER NOT NULL,
    delivered       INTEGER NOT NULL DEFAULT 0
);
```

### 4.2 SQLite Configuration

Enable WAL mode immediately after connection open:

```sql
PRAGMA journal_mode=WAL;
PRAGMA synchronous=NORMAL;
PRAGMA cache_size=-8000;   -- 8MB page cache
```

WAL (Write-Ahead Logging) allows concurrent readers without blocking the writer thread, critical for when the GUI reads price history while the writer flushes a batch.

### 4.3 `DatabaseService.java` — Connection Pool

```java
// Use HikariCP for connection pooling (add to pom.xml)
// SQLite: pool size = 1 (SQLite is single-writer)
// MySQL: pool size = 3-5

HikariConfig config = new HikariConfig();
config.setJdbcUrl("jdbc:sqlite:plugins/BlockStreet/blockstreet.db");
config.setMaximumPoolSize(1);          // SQLite single-writer
config.setConnectionTimeout(5000);
config.setMaxLifetime(600_000);
```

### 4.4 `DbWriteQueue` — Asynchronous Batch Writer

```java
public final class DbWriteQueue {
    // Both engine thread and main thread can offer to this queue safely
    private final ConcurrentLinkedQueue<DbWriteTask> pendingTasks = new ConcurrentLinkedQueue<>();

    // Dead-letter queue for failed writes (prevents data loss on transient errors)
    private final ConcurrentLinkedQueue<DbWriteTask> deadLetterQueue = new ConcurrentLinkedQueue<>();

    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BlockStreet-DbWriter");
            t.setDaemon(true);
            return t;
        });

    public void start() {
        // Flush every 3 seconds
        scheduler.scheduleAtFixedRate(this::flush, 3, 3, TimeUnit.SECONDS);
    }

    public void offer(DbWriteTask task) {
        pendingTasks.offer(task);
    }

    private void flush() {
        // Drain all pending into a local batch
        List<DbWriteTask> batch = new ArrayList<>();
        DbWriteTask t;
        while ((t = pendingTasks.poll()) != null) batch.add(t);

        if (batch.isEmpty()) return;

        try (Connection conn = DatabaseService.getConnection()) {
            conn.setAutoCommit(false);
            for (DbWriteTask task : batch) {
                task.execute(conn);
            }
            conn.commit();
        } catch (SQLException e) {
            plugin.getLogger().severe("DB flush failed: " + e.getMessage());
            // Re-queue failed batch into dead-letter
            batch.forEach(deadLetterQueue::offer);
        }
    }

    public void shutdown() {
        // On plugin disable: flush synchronously before connection close
        scheduler.shutdown();
        flush();  // final drain
    }
}
```

#### `DbWriteTask` — Sealed Interface for Type Safety

```java
public sealed interface DbWriteTask permits 
    InsertTradeTask, UpsertOrderTask, InsertMailboxTask {
    void execute(Connection conn) throws SQLException;
}
```

### 4.5 Server Reboot Recovery — Loading Resting Orders

On `onEnable()`, after DB init:

```java
// ASYNC — do not block main thread
Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
    List<Order> openOrders = dbService.loadOpenOrders(); // SELECT ... WHERE status IN ('OPEN','PARTIALLY_FILLED')
    for (Order order : openOrders) {
        order.setAssetsLocked(true);  // assets were locked pre-shutdown
        matchingEngine.submitOrder(order);
    }
    plugin.getLogger().info("[BlockStreet] Restored " + openOrders.size() + " resting orders from DB.");
});
```

On `onDisable()`:

```java
matchingEngine.stop();          // drain queue, then stop
dbWriteQueue.shutdown();        // flush all pending writes
// Upsert all currently resting orders to DB with their latest qty_remaining
for (OrderBook book : books.values()) {
    book.getAllOrders().forEach(o -> dbWriteQueue.offer(new UpsertOrderTask(o)));
}
dbWriteQueue.flush();          // synchronous final flush
```

---

## Phase 5 — Command Structure, Configuration & Edge-Case Safeguards

### 5.1 Command Map

#### Root command: `/blockstreet` (alias: `/bs`, `/bse`, `/market`)

| Sub-command | Args | Permission | Thread | Description |
|---|---|---|---|---|
| `/bs menu [symbol]` | Optional symbol | `blockstreet.use` | Main | Open the market GUI for the specified asset |
| `/bs buy <symbol> <qty> <price>` | symbol, qty, price | `blockstreet.use` | Main → Async | CLI shortcut to place a limit buy order |
| `/bs sell <symbol> <qty> <price>` | symbol, qty, price | `blockstreet.use` | Main → Async | CLI shortcut to place a limit sell order |
| `/bs market buy <symbol> <qty>` | symbol, qty | `blockstreet.use` | Main → Async | Place a market buy order |
| `/bs market sell <symbol> <qty>` | symbol, qty | `blockstreet.use` | Main → Async | Place a market sell order |
| `/bs cancel <orderId>` | orderId (UUID) | `blockstreet.use` | Main → Async | Cancel a resting limit order |
| `/bs orders` | — | `blockstreet.use` | Main | View own active orders GUI |
| `/bs history [page]` | page number | `blockstreet.use` | Main + Async | View personal trade history |
| `/bs price <symbol>` | symbol | `blockstreet.use` | Main (reads volatile) | Quick price check in chat |
| `/bs admin reload` | — | `blockstreet.admin` | Main | Hot-reload config.yml |
| `/bs admin additem <symbol>` | symbol | `blockstreet.admin` | Main | Register a new tradeable item |
| `/bs admin wipe <symbol>` | symbol | `blockstreet.admin` | Main | Wipe order book for symbol (admin reset) |
| `/bs admin setprice <symbol> <price>` | symbol, price | `blockstreet.admin` | Main → Async | Inject a reference price (does not execute) |

### 5.2 `config.yml` Structure

```yaml
database:
  type: SQLITE          # SQLITE or MYSQL
  host: localhost
  port: 3306
  name: blockstreet
  user: root
  password: ""
  sqlite-file: "blockstreet.db"

engine:
  db-write-interval-seconds: 3
  gui-refresh-ticks: 10          # how often the market GUI re-renders
  order-expiry-hours: 72         # resting orders auto-cancelled after N hours
  max-open-orders-per-player: 10
  taker-fee-percentage: 1.0      # % of gross trade value destroyed as economy sink (maker pays 0%)
                                 # Range: 0.0 (disabled) to 100.0. Recommended: 0.5 – 2.0.

gui:
  market-depth-levels: 5         # top N bid/ask levels shown
  currency-symbol: "$"
  price-format: "#,##0.00"

assets:
  DIAMOND:
    display-name: "Diamond"
    material: DIAMOND
    min-price: 0.01
    max-price: 1000000.0
    min-qty: 1
    max-qty: 1000
    price-tick: 0.01             # minimum price increment — ALL prices normalised to this (§1.8)
    enabled: true

  NETHERITE_INGOT:
    display-name: "Netherite Ingot"
    material: NETHERITE_INGOT
    min-price: 1.0
    max-price: 10000000.0
    min-qty: 1
    max-qty: 64
    price-tick: 0.10
    enabled: true

messages:
  prefix: "&8[&6BlockStreet&8]&r "
  order-placed: "&aOrder placed! ID: {orderId}"
  order-filled: "&aYour order for {qty}x {symbol} filled at ${price}. Fee: ${fee}"
  order-cancelled: "&eOrder {orderId} cancelled. Refund processed."
  order-rejected: "&cOrder rejected: {reason}"
  market-order-partial: "&eMarket order partially filled ({filled}/{qty}). Insufficient liquidity."
```

### 5.3 `ConfigManager.java` — Asset Validation on Load

```java
// Called on load and on /bs admin reload
public void load() {
    plugin.reloadConfig();
    FileConfiguration cfg = plugin.getConfig();
    tradableAssets.clear();
    ConfigurationSection assets = cfg.getConfigurationSection("assets");
    for (String key : assets.getKeys(false)) {
        String materialName = assets.getString(key + ".material");
        Material mat = Material.matchMaterial(materialName);
        if (mat == null) {
            plugin.getLogger().warning("Unknown material for asset: " + key + " → " + materialName);
            continue;
        }
        tradableAssets.put(key, new AssetConfig(key, mat, ...));
    }
}
```

### 5.4 Edge Case Safeguard Checklist

#### Inventory & Item Delivery

| # | Edge Case | Mitigation |
|---|---|---|
| E1 | Player inventory full when order fills | `addItem()` returns overflow map → store in `MailboxManager`; notify player; deliver on next login via `PlayerJoinEvent` |
| E2 | Player offline when resting order fills | `SettlementDispatcher.deliverItems()` detects `!player.isOnline()` → store in `mailbox_items` DB table |
| E3 | Player offline at cancellation time | Refund currency via Vault (works for offline players via Vault's OfflinePlayer API); items go to mailbox |
| E4 | Item has NBT / custom data (not vanilla stack) | `ItemFactory` uses exact `ItemStack` matching; custom items matched by PDC tag, not just Material |
| E5 | Player clicks GUI slot during fill settlement | `InventoryClickEvent` is cancelled for ALL BlockStreet GUIs by default; re-enabled only on non-action slots |

#### Order & Engine Safety

| # | Edge Case | Mitigation |
|---|---|---|
| E6 | Player submits duplicate orders in rapid succession (GUI spam) | Per-player `AtomicBoolean submitting` flag set true during Phase 2A, cleared after `submissionQueue.offer()`; reject duplicates |
| E7 | Order quantity exceeds config `max-qty` | Validated in `OrderValidator` before Phase 2A lock; send `REJECTED` status immediately |
| E8 | Price outside `min-price` / `max-price` bounds | Same validator; send rejection message |
| E9 | Price not a valid tick multiple | `OrderValidator.normalizeToTick()` silently rounds to nearest tick (§1.8); player is notified of adjusted price before lock |
| E10 | Market order with empty or thin book | Escrow calculated against snapshot (Phase 2A §4); engine fills what is available; `SettlementDispatcher` refunds unused escrow minus fee for filled portion; player notified via `market-order-partial` message |
| E11 | Engine thread dies due to uncaught exception | `try/catch(Exception)` in `runLoop()`; logs severe error; engine keeps running; alert via Discord webhook (optional) |
| E12 | Two cancellation requests for same order | `CAS order.consumed (false→true)` is atomic; second CAS fails silently |

#### Database & Persistence

| # | Edge Case | Mitigation |
|---|---|---|
| E13 | DB connection timeout during flush | `try/catch(SQLException)` moves batch to `deadLetterQueue`; retry on next flush cycle |
| E14 | Plugin crash mid-flush (partial batch) | SQLite WAL + single transaction per batch; crash before `conn.commit()` rolls back entire batch |
| E15 | Server restart with full `submissionQueue` | `onDisable()` drains queue with engine stop; all resting orders upserted to DB before connection close |
| E16 | DB file corrupted | On startup, attempt schema validation; log error and disable plugin gracefully rather than loading corrupt state into memory |
| E17 | MySQL connection lost mid-operation | HikariCP auto-reconnect; `testOnBorrow=true`; retry logic in `DbWriteQueue.flush()` |

#### Player & Exploit Prevention

| # | Edge Case | Mitigation |
|---|---|---|
| E18 | Player attempts to use items that are also locked in a pending SELL order | Items removed from inventory in Phase 2A; they cannot be used or dropped |
| E19 | Player kicks/disconnects during Phase 2A (after fund lock, before `offer()`) | `PlayerQuitEvent` listener checks `pendingInputs` AND calls `OrderValidator.rollbackLock(order)` to refund the locked currency/items |
| E20 | Player banned while holding locked assets | Admin command `/bs admin release <player>` to manually trigger refund + cancel flow |
| E21 | Server stops after Vault withdraw but before `submissionQueue.offer()` | Order was never persisted; player lost funds. Mitigation: persist order to DB *before* Vault withdrawal in Phase 2A, with status `PENDING_LOCK`. On recovery, detect `PENDING_LOCK` orders and re-verify/rollback |
| E22 | Price manipulation (wash trading with alt accounts) | Future feature: per-IP or per-session order rate limiter; configurable cooldown between same-symbol orders |

---

## Dependency Additions Required (pom.xml)

The following dependencies must be added to the existing `pom.xml`:

| Artifact | Purpose |
|---|---|
| `net.milkbowl.vault:VaultAPI:1.7` | Economy integration (already in plugin.yml) |
| `com.zaxxer:HikariCP:5.1.0` | JDBC connection pooling |
| `org.xerial:sqlite-jdbc:3.45.3.0` | SQLite driver |
| `mysql:mysql-connector-java:8.0.33` | MySQL driver (optional, shade if used) |
| Paper API (replace Spigot) | Access to Paper-specific async features and component API |

Replace `spigot-api` with `paper-api` in the pom for access to Paper's adventure text components and async chunk APIs.

---

## Implementation Order Recommendation

```
Phase 1  →  Engine models + matching algorithm unit tests (JUnit 5, no Bukkit needed)
Phase 4  →  DB schema + DbWriteQueue (testable standalone)
Phase 2  →  Wire engine thread + settlement dispatcher to plugin (requires Bukkit mock)
Phase 3  →  GUI layer (dependent on Phase 2 data)
Phase 5  →  Commands + config + edge case handlers (dependent on all above)
```

> **Suggested first code generation target**: `Order.java`, `OrderBook.java`, `MatchingEngine.java` with a pure-Java unit test suite — zero Bukkit dependencies, fully verifiable before any server interaction is written.
