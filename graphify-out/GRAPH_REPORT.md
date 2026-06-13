# Graph Report - BlockStreet  (2026-06-13)

## Corpus Check
- 62 files · ~34,200 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 828 nodes · 1900 edges · 34 communities (28 shown, 6 thin omitted)
- Extraction: 72% EXTRACTED · 28% INFERRED · 0% AMBIGUOUS · INFERRED: 534 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `91de2aa4`
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
8. `AdminCommand` - 14 edges
9. `OrderBook` - 13 edges
10. `AssetConfig` - 12 edges

## Surprising Connections (you probably didn't know these)
- `graphify` --conceptually_related_to--> `BlockStreet Plugin Descriptor`  [INFERRED]
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

## Communities (34 total, 6 thin omitted)

### Community 0 - "Matching Engine & Order Book"
Cohesion: 0.06
Nodes (25): OrderBook, PriceLevel, Consumer, DepthLevel, MatchingEngine, OrderBook, OrderStatus, Override (+17 more)

### Community 1 - "Order Submission & Validation"
Cohesion: 0.07
Nodes (23): AssetConfig, OrderSubmissionService, OrderValidator, VaultEconomyService, Material, Override, String, AssetConfig (+15 more)

### Community 2 - "Order Cancellation & Active Orders"
Cohesion: 0.05
Nodes (38): Automated, BlockStreet — Albion Online Market Rebuild, Chat Input State Machine, Config Changes, Decisions Locked In (from /grill-me session), Fee Engine Changes, GUI Architecture, Manual Verification (+30 more)

### Community 3 - "Settlement & Fulfillment"
Cohesion: 0.12
Nodes (15): BlockStreet, CommandSender, List, Override, String, MarketDepthSnapshot, ConfigManager, GuiSession (+7 more)

### Community 5 - "Database Service & History"
Cohesion: 0.11
Nodes (14): DatabaseService, File, ResultSet, BlockStreet, CommandSender, List, Override, String (+6 more)

### Community 6 - "Market Depth GUI"
Cohesion: 0.26
Nodes (9): BukkitRunnable, ExpirationScheduler, ConfigManager, DatabaseService, Logger, MailboxLedgerService, MatchingEngine, Override (+1 more)

### Community 8 - "Database Write Tasks"
Cohesion: 0.32
Nodes (5): InsertMailboxTask, Connection, Override, String, UUID

### Community 9 - "Admin Commands & Config"
Cohesion: 0.11
Nodes (17): BlockStreet, SettlementDispatcher, ConfigManager, DatabaseService, DbWriteQueue, Economy, MailboxLedgerService, MatchingEngine (+9 more)

### Community 10 - "Documentation & Resource Files"
Cohesion: 0.15
Nodes (16): graphify, Database Configuration, Diamond Asset Definition, Engine Configuration, GUI Configuration, Message Templates, Netherite Ingot Asset Definition, BlockStreet Default Configuration (+8 more)

### Community 12 - "Configuration Loading"
Cohesion: 0.14
Nodes (15): GuiTabHandler, BlockStreet, CommandSender, List, Override, String, Map, ConfigManager (+7 more)

### Community 13 - "Command Dispatcher"
Cohesion: 0.23
Nodes (10): Command, CommandExecutor, BlockStreetCommand, BlockStreet, CommandSender, List, Override, String (+2 more)

### Community 15 - "GUI Navigation"
Cohesion: 0.05
Nodes (28): InsertTradeTask, UpsertOrderTask, DbWriteTask, SettlementDispatcher, Order, grossValue(), takerFeeAmount(), Connection (+20 more)

### Community 16 - "GUI Session Management"
Cohesion: 0.06
Nodes (30): GuiManager, GuiListener, BlockStreet, CommandSender, List, Override, String, ConfigManager (+22 more)

### Community 17 - "Market Order Command"
Cohesion: 0.10
Nodes (19): BlockStreet, CommandSender, List, Override, String, BlockStreet, CommandSender, List (+11 more)

### Community 18 - "Price Command"
Cohesion: 0.11
Nodes (21): MailboxLedgerService, DatabaseService, DbWriteQueue, Economy, ItemStack, List, Logger, MailboxLedgerEntry (+13 more)

### Community 19 - "Sell Command"
Cohesion: 0.06
Nodes (27): AsyncPlayerChatEvent, PlayerCacheDao, ChatInputContext, ChatInputManager, MarketLedgerItem, Listener, PlayerListener, PlayerCacheDao (+19 more)

### Community 23 - "SubCommand Interface"
Cohesion: 0.36
Nodes (4): SubCommand, CommandSender, List, String

### Community 33 - "GUI Session Enum"
Cohesion: 0.05
Nodes (31): ChatInputManager, ConfigManager, ConfigurationSection, GuiSession, Integer, Object, AssetConfig, JavaPlugin (+23 more)

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
- **64 isolated node(s):** `$schema`, `plugin`, `@opencode-ai/plugin`, `java.compile.nullAnalysis.mode`, `java.configuration.updateBuildConfiguration` (+59 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **6 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Order` connect `GUI Navigation` to `Matching Engine & Order Book`, `GUI Session Enum`, `Database Service & History`, `Order Submission & Validation`?**
  _High betweenness centrality (0.038) - this node is a cross-community bridge._
- **Why does `OrderBook` connect `Matching Engine & Order Book` to `GUI Session Management`, `Admin Commands & Config`, `Settlement & Fulfillment`, `Configuration Loading`?**
  _High betweenness centrality (0.035) - this node is a cross-community bridge._
- **Why does `BlockStreet` connect `Admin Commands & Config` to `Matching Engine & Order Book`, `GUI Session Enum`, `Sell Command`?**
  _High betweenness centrality (0.030) - this node is a cross-community bridge._
- **What connects `$schema`, `plugin`, `@opencode-ai/plugin` to the rest of the system?**
  _64 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Matching Engine & Order Book` be split into smaller, more focused modules?**
  _Cohesion score 0.05839727195225917 - nodes in this community are weakly interconnected._
- **Should `Order Submission & Validation` be split into smaller, more focused modules?**
  _Cohesion score 0.0672316384180791 - nodes in this community are weakly interconnected._
- **Should `Order Cancellation & Active Orders` be split into smaller, more focused modules?**
  _Cohesion score 0.05128205128205128 - nodes in this community are weakly interconnected._