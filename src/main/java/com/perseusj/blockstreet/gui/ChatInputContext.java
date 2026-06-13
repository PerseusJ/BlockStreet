package com.perseusj.blockstreet.gui;

import org.bukkit.entity.Player;

@FunctionalInterface
public interface ChatInputContext {
    void resolve(String input, Player player) throws NumberFormatException;
    
    default String prompt() {
        return "§6[BlockStreet] §fEnter input:";
    }
}
