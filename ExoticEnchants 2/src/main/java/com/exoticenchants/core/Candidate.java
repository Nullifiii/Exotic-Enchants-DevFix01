package com.exoticenchants.core;

/** One possible reward for a given item type: enchantment + level + its configured rarity. */
public record Candidate(String enchant, int level, Rarity rarity) {
}
