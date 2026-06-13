package com.perseusj.blockstreet.db;

import java.util.UUID;

public record MailboxLedgerEntry(
    long id,
    UUID playerId,
    EntryType type,
    double amount,
    String itemNbt,
    int itemQty,
    String source,
    String orderId,
    long storedAt
) {
    public enum EntryType {
        ITEM,
        CURRENCY
    }
}
