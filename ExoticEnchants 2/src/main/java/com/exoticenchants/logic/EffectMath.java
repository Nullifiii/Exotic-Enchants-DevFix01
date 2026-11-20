package com.exoticenchants.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Formulas for the custom effects. Pure functions so they can be unit tested. */
public final class EffectMath {

    public enum DamageKind { FIRE, EXPLOSION, PROJECTILE, FALL, OTHER }

    private static final Set<String> UNDEAD = Set.of("ZOMBIE", "SKELETON", "WITHER_SKELETON", "STRAY", "HUSK", "DROWNED",
            "ZOMBIE_VILLAGER", "ZOMBIFIED_PIGLIN", "PHANTOM", "WITHER", "ZOGLIN", "SKELETON_HORSE", "ZOMBIE_HORSE", "BOGGED");
    private static final Set<String> ARTHROPOD = Set.of("SPIDER", "CAVE_SPIDER", "SILVERFISH", "ENDERMITE", "BEE");

    private EffectMath() {
    }

    // ------------------------------------------------------------- frost walker

    /** Frost Walker radius: 2 + level (like vanilla), never above {@code max}. */
    public static int frostRadius(int level, int max) {
        return Math.max(1, Math.min(max, 2 + Math.max(0, level)));
    }

    /** Horizontal offsets {dx, dz} inside a circle of the given radius (vanilla shape). */
    public static List<int[]> frostOffsets(int radius) {
        List<int[]> out = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz <= radius * radius) out.add(new int[]{dx, dz});
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ density

    /**
     * Density on a bow/crossbow: arrows shot while falling deal extra damage, like a mace smash.
     * bonus = level * perBlockPerLevel * blocksFallen, nothing below minFall, capped at maxBonus.
     */
    public static double densityBonus(int level, double fallDistance, double perBlockPerLevel, double minFall, double maxBonus) {
        if (level <= 0 || fallDistance < minFall) return 0.0;
        return Math.min(maxBonus, level * perBlockPerLevel * fallDistance);
    }

    // ------------------------------------------------- melee enchants on arrows

    /** Sharpness: +0.5 per level, +0.5 flat (vanilla formula). */
    public static double sharpnessBonus(int level) {
        return level <= 0 ? 0 : 0.5 * level + 0.5;
    }

    /** Smite / Bane of Arthropods: +2.5 per level against the matching mob group, otherwise 0. */
    public static double smiteBonus(int level, String entityType) {
        return level > 0 && UNDEAD.contains(entityType) ? 2.5 * level : 0;
    }

    public static double baneBonus(int level, String entityType) {
        return level > 0 && ARTHROPOD.contains(entityType) ? 2.5 * level : 0;
    }

    /** Fire Aspect: 4 seconds of fire per level (80 ticks). */
    public static int fireTicks(int level) {
        return Math.max(0, level) * 80;
    }

    // --------------------------------------------------------------- protection

    /** Enchantment protection factor contributed by one held enchantment (vanilla values). */
    public static int epf(String enchantKey, int level, DamageKind kind) {
        if (level <= 0) return 0;
        return switch (enchantKey) {
            case "protection" -> level;
            case "fire_protection" -> kind == DamageKind.FIRE ? 2 * level : 0;
            case "blast_protection" -> kind == DamageKind.EXPLOSION ? 2 * level : 0;
            case "projectile_protection" -> kind == DamageKind.PROJECTILE ? 2 * level : 0;
            case "feather_falling" -> kind == DamageKind.FALL ? 3 * level : 0;
            default -> 0;
        };
    }

    /** Vanilla: each EPF point removes 4% damage, EPF capped at 20 (80%). {@code maxReduction} may lower the cap. */
    public static double applyProtection(double damage, int totalEpf, double maxReduction) {
        double reduction = Math.min(Math.min(20, Math.max(0, totalEpf)) * 0.04, Math.max(0, Math.min(1, maxReduction)));
        return damage * (1.0 - reduction);
    }
}
