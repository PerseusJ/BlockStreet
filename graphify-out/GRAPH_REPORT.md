# Graph Report - .  (2026-06-13)

## Corpus Check
- Corpus is ~29,829 words - fits in a single context window. You may not need a graph.

## Summary
- 655 nodes · 1456 edges · 38 communities (31 shown, 7 thin omitted)
- Extraction: 77% EXTRACTED · 23% INFERRED · 0% AMBIGUOUS · INFERRED: 341 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- [[_COMMUNITY_Matching Engine & Order Book|Matching Engine & Order Book]]
- [[_COMMUNITY_Order Submission & Validation|Order Submission & Validation]]
- [[_COMMUNITY_Order Cancellation & Active Orders|Order Cancellation & Active Orders]]
- [[_COMMUNITY_Settlement & Fulfillment|Settlement & Fulfillment]]
- [[_COMMUNITY_BuyMenuOrders Commands|Buy/Menu/Orders Commands]]
- [[_COMMUNITY_Database Service & History|Database Service & History]]
- [[_COMMUNITY_Market Depth GUI|Market Depth GUI]]
- [[_COMMUNITY_Plugin Lifecycle & Player Events|Plugin Lifecycle & Player Events]]
- [[_COMMUNITY_Database Write Tasks|Database Write Tasks]]
- [[_COMMUNITY_Admin Commands & Config|Admin Commands & Config]]
- [[_COMMUNITY_Documentation & Resource Files|Documentation & Resource Files]]
- [[_COMMUNITY_Chat Input Capture|Chat Input Capture]]
- [[_COMMUNITY_Configuration Loading|Configuration Loading]]
- [[_COMMUNITY_Command Dispatcher|Command Dispatcher]]
- [[_COMMUNITY_Order Entry GUI|Order Entry GUI]]
- [[_COMMUNITY_GUI Navigation|GUI Navigation]]
- [[_COMMUNITY_GUI Session Management|GUI Session Management]]
- [[_COMMUNITY_Market Order Command|Market Order Command]]
- [[_COMMUNITY_Price Command|Price Command]]
- [[_COMMUNITY_Sell Command|Sell Command]]
- [[_COMMUNITY_GUI Inventory Listeners|GUI Inventory Listeners]]
- [[_COMMUNITY_Item Creation Utilities|Item Creation Utilities]]
- [[_COMMUNITY_GUI Manager Core|GUI Manager Core]]
- [[_COMMUNITY_SubCommand Interface|SubCommand Interface]]
- [[_COMMUNITY_Database Write Queue|Database Write Queue]]
- [[_COMMUNITY_Database Write Task Base|Database Write Task Base]]
- [[_COMMUNITY_Plugin Manager|Plugin Manager]]
- [[_COMMUNITY_Text Utilities|Text Utilities]]
- [[_COMMUNITY_Depth Snapshot Refresh|Depth Snapshot Refresh]]
- [[_COMMUNITY_OpenCode Configuration|OpenCode Configuration]]
- [[_COMMUNITY_OpenCode Package|OpenCode Package]]
- [[_COMMUNITY_VS Code Settings|VS Code Settings]]

## God Nodes (most connected - your core abstractions)
1. `Order` - 25 edges
2. `GuiManager` - 24 edges
3. `MatchingEngine` - 19 edges
4. `OrderBook` - 16 edges
5. `BlockStreet` - 14 edges
6. `AdminCommand` - 14 edges
7. `ConfigManager` - 14 edges
8. `ActiveOrdersGui` - 13 edges
9. `AssetConfig` - 12 edges
10. `OrderBook` - 12 edges

## Surprising Connections (you probably didn't know these)
- `Graphify Knowledge Graph System` --conceptually_related_to--> `BlockStreet Plugin Descriptor`  [INFERRED]
  AGENTS.md → src/main/resources/plugin.yml
- `BlockStreet` --inherits--> `JavaPlugin`  [EXTRACTED]
  src/main/java/com/perseusj/blockstreet/BlockStreet.java → src/main/java/com/perseusj/blockstreet/config/ConfigManager.java
- `BlockStreet Default Configuration` --conceptually_related_to--> `BlockStreet Plugin Descriptor`  [INFERRED]
  src/main/resources/config.yml → src/main/resources/plugin.yml
- `Engine Configuration` --conceptually_related_to--> `Vault Dependency`  [INFERRED]
  src/main/resources/config.yml → src/main/resources/plugin.yml
- `/blockstreet Command` --conceptually_related_to--> `GUI Configuration`  [INFERRED]
  src/main/resources/plugin.yml → src/main/resources/config.yml

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Tradable Asset Definitions** — resources_config_diamond, resources_config_netherite_ingot [INFERRED 0.95]
- **BlockStreet Permission Hierarchy** — resources_plugin_blockstreet_use_permission, resources_plugin_blockstreet_admin_permission, resources_plugin_blockstreet_all_permission [EXTRACTED 1.00]

## Communities (38 total, 7 thin omitted)

### Community 0 - "Matching Engine & Order Book"
Cohesion: 0.05
Nodes (30): bestAsk(), bestBid(), emptyFor(), hasAsks(), hasBids(), OrderBook, PriceLevel, Consumer (+22 more)

### Community 1 - "Order Submission & Validation"
Cohesion: 0.07
Nodes (23): AssetConfig, OrderSubmissionService, OrderValidator, VaultEconomyService, OrderStatus, Material, Override, String (+15 more)

### Community 2 - "Order Cancellation & Active Orders"
Cohesion: 0.09
Nodes (18): ActiveOrdersGui, Object, BlockStreet, CommandSender, List, Override, String, Override (+10 more)

### Community 3 - "Settlement & Fulfillment"
Cohesion: 0.07
Nodes (19): Collection, SettlementDispatcher, MailboxManager, Order, OrderSide, OrderType, Override, String (+11 more)

### Community 4 - "Buy/Menu/Orders Commands"
Cohesion: 0.10
Nodes (19): BlockStreet, CommandSender, List, Override, String, BlockStreet, CommandSender, List (+11 more)

### Community 5 - "Database Service & History"
Cohesion: 0.12
Nodes (16): DatabaseService, File, MailboxEntry, ResultSet, BlockStreet, CommandSender, List, Override (+8 more)

### Community 6 - "Market Depth GUI"
Cohesion: 0.13
Nodes (12): DepthRenderer, MarketGui, ItemStack, MarketDepthSnapshot, Material, String, AssetConfig, Inventory (+4 more)

### Community 7 - "Plugin Lifecycle & Player Events"
Cohesion: 0.10
Nodes (16): BlockStreet, Listener, PlayerListener, PlayerJoinEvent, SettlementDispatcher, ConfigManager, DatabaseService, DbWriteQueue (+8 more)

### Community 8 - "Database Write Tasks"
Cohesion: 0.11
Nodes (15): InsertMailboxTask, InsertTradeTask, UpsertOrderTask, DbWriteTask, grossValue(), takerFeeAmount(), Connection, Override (+7 more)

### Community 9 - "Admin Commands & Config"
Cohesion: 0.23
Nodes (6): BlockStreet, CommandSender, List, Override, String, AdminCommand

### Community 10 - "Documentation & Resource Files"
Cohesion: 0.12
Nodes (21): Graphify Knowledge Graph System, Graphify Explain Tool, Graphify Path Tool, Graphify Query Tool, Graphify Update Command, AGENTS.md — Graphify Workflow Instructions, Database Configuration, Diamond Asset Definition (+13 more)

### Community 11 - "Chat Input Capture"
Cohesion: 0.17
Nodes (7): AsyncPlayerChatEvent, ChatInputCatcher, OrderSide, OrderType, Override, String, UUID

### Community 12 - "Configuration Loading"
Cohesion: 0.15
Nodes (6): ConfigManager, ConfigurationSection, AssetConfig, JavaPlugin, Map, String

### Community 13 - "Command Dispatcher"
Cohesion: 0.23
Nodes (10): Command, CommandExecutor, BlockStreetCommand, BlockStreet, CommandSender, List, Override, String (+2 more)

### Community 14 - "Order Entry GUI"
Cohesion: 0.26
Nodes (6): OrderEntryGui, AssetConfig, Inventory, ItemStack, OrderSide, String

### Community 15 - "GUI Navigation"
Cohesion: 0.33
Nodes (4): OrderSide, Override, Player, String

### Community 16 - "GUI Session Management"
Cohesion: 0.27
Nodes (4): ChatInputCatcher, GuiManager, GuiSession, UUID

### Community 17 - "Market Order Command"
Cohesion: 0.32
Nodes (6): BlockStreet, CommandSender, List, Override, String, MarketOrderCommand

### Community 18 - "Price Command"
Cohesion: 0.32
Nodes (6): BlockStreet, CommandSender, List, Override, String, PriceCommand

### Community 19 - "Sell Command"
Cohesion: 0.32
Nodes (6): BlockStreet, CommandSender, List, Override, String, SellCommand

### Community 20 - "GUI Inventory Listeners"
Cohesion: 0.29
Nodes (7): GuiListener, EventHandler, GuiManager, InventoryClickEvent, InventoryCloseEvent, PlayerQuitEvent, Plugin

### Community 21 - "Item Creation Utilities"
Cohesion: 0.32
Nodes (4): ItemStack, Material, String, ItemFactory

### Community 22 - "GUI Manager Core"
Cohesion: 0.24
Nodes (8): ConfigManager, Inventory, InventoryClickEvent, InventoryCloseEvent, MatchingEngine, OrderSubmissionService, OrderType, Plugin

### Community 23 - "SubCommand Interface"
Cohesion: 0.36
Nodes (4): SubCommand, CommandSender, List, String

### Community 24 - "Database Write Queue"
Cohesion: 0.24
Nodes (3): DbWriteQueue, DatabaseService, Logger

## Knowledge Gaps
- **39 isolated node(s):** `$schema`, `plugin`, `@opencode-ai/plugin`, `java.compile.nullAnalysis.mode`, `java.configuration.updateBuildConfiguration` (+34 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **7 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `OrderBook` connect `Matching Engine & Order Book` to `Admin Commands & Config`, `Order Cancellation & Active Orders`, `Price Command`, `GUI Manager Core`?**
  _High betweenness centrality (0.044) - this node is a cross-community bridge._
- **Why does `Order` connect `Settlement & Fulfillment` to `Matching Engine & Order Book`, `Order Submission & Validation`, `Order Cancellation & Active Orders`, `Database Service & History`?**
  _High betweenness centrality (0.039) - this node is a cross-community bridge._
- **Why does `GuiManager` connect `GUI Session Management` to `Chat Input Capture`, `Configuration Loading`, `GUI Navigation`, `GUI Manager Core`, `Depth Snapshot Refresh`?**
  _High betweenness centrality (0.037) - this node is a cross-community bridge._
- **What connects `$schema`, `plugin`, `@opencode-ai/plugin` to the rest of the system?**
  _39 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Matching Engine & Order Book` be split into smaller, more focused modules?**
  _Cohesion score 0.052982456140350874 - nodes in this community are weakly interconnected._
- **Should `Order Submission & Validation` be split into smaller, more focused modules?**
  _Cohesion score 0.07017543859649122 - nodes in this community are weakly interconnected._
- **Should `Order Cancellation & Active Orders` be split into smaller, more focused modules?**
  _Cohesion score 0.08635703918722787 - nodes in this community are weakly interconnected._