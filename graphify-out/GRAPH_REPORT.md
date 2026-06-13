# Graph Report - BlockStreet  (2026-06-13)

## Corpus Check
- 61 files · ~31,024 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 821 nodes · 1945 edges · 34 communities (28 shown, 6 thin omitted)
- Extraction: 71% EXTRACTED · 29% INFERRED · 0% AMBIGUOUS · INFERRED: 565 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `c15a22cc`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- [[_COMMUNITY_Matching Engine & Order Book|Matching Engine & Order Book]]
- [[_COMMUNITY_Order Submission & Validation|Order Submission & Validation]]
- [[_COMMUNITY_Order Cancellation & Active Orders|Order Cancellation & Active Orders]]
- [[_COMMUNITY_Settlement & Fulfillment|Settlement & Fulfillment]]
- [[_COMMUNITY_Database Service & History|Database Service & History]]
- [[_COMMUNITY_Market Depth GUI|Market Depth GUI]]
- [[_COMMUNITY_Database Write Tasks|Database Write Tasks]]
- [[_COMMUNITY_Admin Commands & Config|Admin Commands & Config]]
- [[_COMMUNITY_Documentation & Resource Files|Documentation & Resource Files]]
- [[_COMMUNITY_Configuration Loading|Configuration Loading]]
- [[_COMMUNITY_Command Dispatcher|Command Dispatcher]]
- [[_COMMUNITY_GUI Navigation|GUI Navigation]]
- [[_COMMUNITY_GUI Session Management|GUI Session Management]]
- [[_COMMUNITY_Market Order Command|Market Order Command]]
- [[_COMMUNITY_Price Command|Price Command]]
- [[_COMMUNITY_Sell Command|Sell Command]]
- [[_COMMUNITY_SubCommand Interface|SubCommand Interface]]
- [[_COMMUNITY_Database Write Task Base|Database Write Task Base]]
- [[_COMMUNITY_Plugin Manager|Plugin Manager]]
- [[_COMMUNITY_Text Utilities|Text Utilities]]
- [[_COMMUNITY_OpenCode Configuration|OpenCode Configuration]]
- [[_COMMUNITY_OpenCode Package|OpenCode Package]]
- [[_COMMUNITY_VS Code Settings|VS Code Settings]]
- [[_COMMUNITY_GUI Session Enum|GUI Session Enum]]
- [[_COMMUNITY_Community 34|Community 34]]
- [[_COMMUNITY_Community 38|Community 38]]
- [[_COMMUNITY_Community 39|Community 39]]
- [[_COMMUNITY_Community 40|Community 40]]

## God Nodes (most connected - your core abstractions)
1. `GuiSession` - 35 edges
2. `Order` - 33 edges
3. `MatchingEngine` - 20 edges
4. `ConfigManager` - 19 edges
5. `GuiManager` - 19 edges
6. `BlockStreet` - 17 edges
7. `OrderBook` - 16 edges
8. `AdminCommand` - 15 edges
9. `OrderBook` - 13 edges
10. `Material` - 12 edges

## Surprising Connections (you probably didn't know these)
- `graphify` --conceptually_related_to--> `BlockStreet Plugin Descriptor`  [INFERRED]
  AGENTS.md → src/main/resources/plugin.yml
- `BlockStreet` --inherits--> `JavaPlugin`  [EXTRACTED]
  src/main/java/com/perseusj/blockstreet/BlockStreet.java → src/main/java/com/perseusj/blockstreet/config/ConfigManager.java
- `LedgerCommand` --implements--> `SubCommand`  [EXTRACTED]
  src/main/java/com/perseusj/blockstreet/commands/subcommands/LedgerCommand.java → src/main/java/com/perseusj/blockstreet/commands/BlockStreetCommand.java
- `MarketOrderCommand` --implements--> `SubCommand`  [EXTRACTED]
  src/main/java/com/perseusj/blockstreet/commands/subcommands/MarketOrderCommand.java → src/main/java/com/perseusj/blockstreet/commands/BlockStreetCommand.java
- `MenuCommand` --implements--> `SubCommand`  [EXTRACTED]
  src/main/java/com/perseusj/blockstreet/commands/subcommands/MenuCommand.java → src/main/java/com/perseusj/blockstreet/commands/BlockStreetCommand.java

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Tradable Asset Definitions** — resources_config_diamond, resources_config_netherite_ingot [INFERRED 0.95]
- **BlockStreet Permission Hierarchy** — resources_plugin_blockstreet_use_permission, resources_plugin_blockstreet_admin_permission, resources_plugin_blockstreet_all_permission [EXTRACTED 1.00]

## Communities (34 total, 6 thin omitted)

### Community 0 - "Matching Engine & Order Book"
Cohesion: 0.06
Nodes (24): OrderBook, PriceLevel, ConcurrentSkipListMap, Consumer, DepthLevel, Double, MatchingEngine, OrderBook (+16 more)

### Community 1 - "Order Submission & Validation"
Cohesion: 0.07
Nodes (25): AssetConfig, OrderSubmissionService, OrderValidator, VaultEconomyService, OrderStatus, Material, Override, String (+17 more)

### Community 2 - "Order Cancellation & Active Orders"
Cohesion: 0.05
Nodes (38): Automated, BlockStreet — Albion Online Market Rebuild, Chat Input State Machine, Config Changes, Decisions Locked In (from /grill-me session), Fee Engine Changes, GUI Architecture, Manual Verification (+30 more)

### Community 3 - "Settlement & Fulfillment"
Cohesion: 0.13
Nodes (15): BlockStreet, CommandSender, List, Override, String, MarketDepthSnapshot, ConfigManager, GuiSession (+7 more)

### Community 5 - "Database Service & History"
Cohesion: 0.06
Nodes (31): DatabaseService, MailboxLedgerService, PlayerCacheDao, File, ResultSet, BlockStreet, CommandSender, List (+23 more)

### Community 6 - "Market Depth GUI"
Cohesion: 0.26
Nodes (9): BukkitRunnable, ExpirationScheduler, ConfigManager, DatabaseService, Logger, MailboxLedgerService, MatchingEngine, Override (+1 more)

### Community 8 - "Database Write Tasks"
Cohesion: 0.32
Nodes (5): InsertMailboxTask, Connection, Override, String, UUID

### Community 9 - "Admin Commands & Config"
Cohesion: 0.10
Nodes (17): BlockStreet, SettlementDispatcher, ConfigManager, DatabaseService, DbWriteQueue, Economy, MailboxLedgerService, MatchingEngine (+9 more)

### Community 10 - "Documentation & Resource Files"
Cohesion: 0.24
Nodes (9): graphify, blockstreet.admin Permission, blockstreet.* Permission, /blockstreet Command, com.perseusj.blockstreet.BlockStreet (Main Class), blockstreet.use Permission, PlaceholderAPI Soft-Dependency, Vault Dependency (+1 more)

### Community 12 - "Configuration Loading"
Cohesion: 0.12
Nodes (17): GuiTabHandler, Object, BlockStreet, CommandSender, List, Override, String, Map (+9 more)

### Community 13 - "Command Dispatcher"
Cohesion: 0.27
Nodes (9): Command, CommandExecutor, BlockStreetCommand, BlockStreet, CommandSender, List, Override, String (+1 more)

### Community 15 - "GUI Navigation"
Cohesion: 0.05
Nodes (28): InsertTradeTask, UpsertOrderTask, DbWriteTask, SettlementDispatcher, MarketLedgerItem, Order, grossValue(), takerFeeAmount() (+20 more)

### Community 16 - "GUI Session Management"
Cohesion: 0.07
Nodes (23): GuiManager, Material, BlockStreet, CommandSender, List, Override, String, ConfigManager (+15 more)

### Community 17 - "Market Order Command"
Cohesion: 0.10
Nodes (19): BlockStreet, CommandSender, List, Override, String, BlockStreet, CommandSender, List (+11 more)

### Community 18 - "Price Command"
Cohesion: 0.22
Nodes (10): List, MailboxLedgerEntry, GuiSession, InventoryClickEvent, ItemStack, MailboxLedgerEntry, MailboxLedgerService, Override (+2 more)

### Community 19 - "Sell Command"
Cohesion: 0.07
Nodes (27): AsyncPlayerChatEvent, ChatInputContext, ChatInputManager, Listener, GuiListener, PlayerListener, PlayerCacheDao, PlayerInteractEvent (+19 more)

### Community 23 - "SubCommand Interface"
Cohesion: 0.36
Nodes (4): SubCommand, CommandSender, List, String

### Community 33 - "GUI Session Enum"
Cohesion: 0.05
Nodes (30): ChatInputManager, ConfigManager, ConfigurationSection, GuiSession, Integer, AssetConfig, JavaPlugin, List (+22 more)

### Community 34 - "Community 34"
Cohesion: 0.32
Nodes (6): BlockStreet, CommandSender, List, Override, String, MarketOrderCommand

### Community 38 - "Community 38"
Cohesion: 0.31
Nodes (6): BlockStreet, CommandSender, List, Override, String, MenuCommand

### Community 39 - "Community 39"
Cohesion: 0.09
Nodes (16): bestAsk(), bestBid(), emptyFor(), hasAsks(), hasBids(), Collection, DbWriteQueue, MailboxManager (+8 more)

### Community 40 - "Community 40"
Cohesion: 0.53
Nodes (3): GuiSession, InventoryClickEvent, GuiTabHandler

## Knowledge Gaps
- **63 isolated node(s):** `List`, `Integer`, `DatabaseService`, `DbWriteQueue`, `DatabaseService` (+58 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **6 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Order` connect `GUI Navigation` to `Matching Engine & Order Book`, `GUI Session Enum`, `Order Submission & Validation`, `Database Service & History`, `Configuration Loading`?**
  _High betweenness centrality (0.038) - this node is a cross-community bridge._
- **Why does `Material` connect `GUI Session Management` to `GUI Session Enum`, `Order Submission & Validation`, `Settlement & Fulfillment`, `Database Service & History`, `Admin Commands & Config`, `Configuration Loading`, `GUI Navigation`, `Price Command`?**
  _High betweenness centrality (0.031) - this node is a cross-community bridge._
- **Why does `OrderBook` connect `Matching Engine & Order Book` to `GUI Session Management`, `Admin Commands & Config`, `Settlement & Fulfillment`, `Configuration Loading`?**
  _High betweenness centrality (0.029) - this node is a cross-community bridge._
- **What connects `List`, `Integer`, `DatabaseService` to the rest of the system?**
  _63 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Matching Engine & Order Book` be split into smaller, more focused modules?**
  _Cohesion score 0.05970149253731343 - nodes in this community are weakly interconnected._
- **Should `Order Submission & Validation` be split into smaller, more focused modules?**
  _Cohesion score 0.07049180327868852 - nodes in this community are weakly interconnected._
- **Should `Order Cancellation & Active Orders` be split into smaller, more focused modules?**
  _Cohesion score 0.05128205128205128 - nodes in this community are weakly interconnected._