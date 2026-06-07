package com.perseusj.blockstreet.utils;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for creating Bukkit {@link ItemStack}s from BlockStreet asset symbols.
 *
 * <h2>Design</h2>
 * <ul>
 *   <li>Symbol → Material mappings are registered via {@link #register(String, Material)} at startup
 *       (called by {@link com.perseusj.blockstreet.config.ConfigManager} after loading asset configs).</li>
 *   <li>All created stacks are vanilla items with no NBT beyond a display name. Custom PDC-tagged items
 *       are a Phase 5 enhancement (edge case E4 in the plan).</li>
 *   <li>This class is stateless beyond the registry map and is safe to read from any thread.</li>
 * </ul>
 */
public final class ItemFactory {

    private ItemFactory() {}

    /** Thread-safe registry: symbol (e.g. "DIAMOND") → Bukkit Material. */
    private static final Map<String, Material> REGISTRY = new ConcurrentHashMap<>();

    // ──────────────────────────── Registry management ────────────────────────────

    /**
     * Registers (or re-registers) a symbol → Material mapping.
     * Called by ConfigManager on load/reload. Thread-safe.
     *
     * @param symbol   the canonical asset symbol (e.g. "DIAMOND")
     * @param material the Bukkit material to map to
     */
    public static void register(String symbol, Material material) {
        REGISTRY.put(symbol, material);
    }

    /**
     * Clears all registered mappings. Called before each config reload to
     * ensure removed assets are no longer accessible.
     */
    public static void clearRegistry() {
        REGISTRY.clear();
    }

    // ──────────────────────────── Item creation ──────────────────────────────────

    /**
     * Creates an {@link ItemStack} for the given symbol and quantity.
     *
     * @param symbol   the canonical asset symbol (must be registered)
     * @param quantity the stack size (≥ 1)
     * @return a new ItemStack, or a fallback BARRIER item with an error lore if
     *         the symbol is unknown (prevents null propagation from crashing delivery)
     * @throws IllegalArgumentException if quantity ≤ 0
     */
    public static ItemStack create(String symbol, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Item quantity must be positive, got: " + quantity);
        }

        Material mat = REGISTRY.get(symbol);
        if (mat == null) {
            // Defensive fallback — should never reach production if ConfigManager validates
            return createFallback(symbol, quantity);
        }

        ItemStack stack = new ItemStack(mat, quantity);
        return stack;
    }

    /**
     * Returns {@code true} if the given ItemStack matches the registered material for the symbol.
     * Used during Phase 2A SELL order validation to confirm the player holds the right item type.
     *
     * @param symbol the asset symbol to check against
     * @param stack  the item to test
     * @return {@code true} if the material matches
     */
    public static boolean matches(String symbol, ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) return false;
        Material expected = REGISTRY.get(symbol);
        return expected != null && stack.getType() == expected;
    }

    /**
     * Returns the {@link Material} registered for the given symbol, or {@code null}.
     */
    public static Material getMaterial(String symbol) {
        return REGISTRY.get(symbol);
    }

    // ──────────────────────────── Private helpers ─────────────────────────────────

    private static ItemStack createFallback(String symbol, int quantity) {
        ItemStack barrier = new ItemStack(Material.BARRIER, quantity);
        ItemMeta meta = barrier.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§c[ERROR] Unknown symbol: " + symbol);
            barrier.setItemMeta(meta);
        }
        return barrier;
    }
}
