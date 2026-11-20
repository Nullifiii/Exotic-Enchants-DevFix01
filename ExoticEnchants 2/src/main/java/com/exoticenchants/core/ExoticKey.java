package com.exoticenchants.core;

import java.util.Locale;

/** Uniqueness identity: enchantment + level + item material. Normal enchants are NOT part of it. */
public record ExoticKey(String enchant, int level, String material) {
    public ExoticKey {
        enchant = enchant.toLowerCase(Locale.ROOT);
        material = material.toUpperCase(Locale.ROOT);
        if (level < 1) throw new IllegalArgumentException("level must be >= 1");
    }
}
