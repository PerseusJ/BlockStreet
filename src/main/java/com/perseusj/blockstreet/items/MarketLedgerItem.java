package com.perseusj.blockstreet.items;

import com.perseusj.blockstreet.BlockStreet;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;

public class MarketLedgerItem {
    
    public static final NamespacedKey LEDGER_KEY = new NamespacedKey(BlockStreet.getInstance(), "market_ledger");

    public static ItemStack create() {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle("§6Market Ledger");
        meta.setAuthor("BlockStreet Exchange");
        meta.addPages(Component.text("§6§lBlockStreet Exchange\n\n")
            .append(Component.text("A player-driven market.\nSetup fee: 2.5%\nSales tax: 8% (4% Premium)\n\n"))
            .append(Component.text("[Open Marketplace]", Style.style(NamedTextColor.GREEN, TextDecoration.UNDERLINED))
                .clickEvent(ClickEvent.runCommand("/bs market open BROWSE")))
            .append(Component.text("\n"))
            .append(Component.text("[Check Mailbox]", Style.style(NamedTextColor.YELLOW, TextDecoration.UNDERLINED))
                .clickEvent(ClickEvent.runCommand("/bs market open MAILBOX"))));
        
        // Set PDC tag to identify as a Market Ledger
        meta.getPersistentDataContainer().set(LEDGER_KEY, PersistentDataType.BYTE, (byte) 1);
        book.setItemMeta(meta);
        return book;
    }

    public static boolean isMarketLedger(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(LEDGER_KEY, PersistentDataType.BYTE);
    }
}
