package com.exoticenchants.logic;

import java.util.Locale;
import java.util.Set;

/**
 * Which enchantment + item-type combinations actually DO something.
 *
 * Vanilla only activates an enchantment's effect for the item types/slots it was designed for, so
 * "Frost Walker on a sword" or "Density on a bow" is applied to the item but silently does nothing.
 * This class is the single source of truth for what the plugin can make work:
 *  - NATIVE: vanilla already makes it work (the item type normally accepts the enchantment, or the
 *            enchantment works in any slot such as Mending/Unbreaking);
 *  - CUSTOM: the plugin implements the effect itself (see ExoticEffectsListener);
 *  - NONE:   nothing would happen, so the combination is never rolled, sold or applied
 *            (unless exotic-effects.require-working-effect is turned off).
 */
public final class EffectSupport {

    public enum Support { NATIVE, CUSTOM, NONE }

    public enum Category { SWORD, AXE, PICKAXE, SHOVEL, HOE, HELMET, CHESTPLATE, LEGGINGS, BOOTS, BOW, CROSSBOW, TRIDENT,
        MACE, FISHING_ROD, SHIELD, ELYTRA, SHEARS, OTHER }

    /** Enchantments that work from any inventory slot in vanilla. */
    private static final Set<String> ANY_SLOT = Set.of("mending", "unbreaking", "vanishing_curse");

    /** Armour enchantments the plugin emulates while the item is HELD in a hand. */
    public static final Set<String> HELD_PROTECTION = Set.of("protection", "fire_protection", "blast_protection",
            "projectile_protection", "feather_falling");

    /** Melee enchantments the plugin emulates on arrows shot from a bow or crossbow. */
    public static final Set<String> ARROW_ENCHANTS = Set.of("sharpness", "smite", "bane_of_arthropods", "fire_aspect",
            "density");

    private EffectSupport() {
    }

    public static Category categoryOf(String materialName) {
        String m = materialName.toUpperCase(Locale.ROOT);
        if (m.endsWith("_SWORD")) return Category.SWORD;
        if (m.endsWith("_PICKAXE")) return Category.PICKAXE;
        if (m.endsWith("_AXE")) return Category.AXE;
        if (m.endsWith("_SHOVEL")) return Category.SHOVEL;
        if (m.endsWith("_HOE")) return Category.HOE;
        if (m.endsWith("_HELMET")) return Category.HELMET;
        if (m.endsWith("_CHESTPLATE")) return Category.CHESTPLATE;
        if (m.endsWith("_LEGGINGS")) return Category.LEGGINGS;
        if (m.endsWith("_BOOTS")) return Category.BOOTS;
        return switch (m) {
            case "BOW" -> Category.BOW;
            case "CROSSBOW" -> Category.CROSSBOW;
            case "TRIDENT" -> Category.TRIDENT;
            case "MACE" -> Category.MACE;
            case "FISHING_ROD" -> Category.FISHING_ROD;
            case "SHIELD" -> Category.SHIELD;
            case "ELYTRA" -> Category.ELYTRA;
            case "SHEARS" -> Category.SHEARS;
            default -> Category.OTHER;
        };
    }

    public static boolean isArmor(Category c) {
        return c == Category.HELMET || c == Category.CHESTPLATE || c == Category.LEGGINGS || c == Category.BOOTS;
    }

    /**
     * @param vanillaCompatible result of Enchantment#canEnchantItem for this item (the item type normally
     *                          accepts this enchantment)
     */
    public static Support support(String enchantKey, String materialName, boolean vanillaCompatible) {
        String key = enchantKey.toLowerCase(Locale.ROOT);
        if (vanillaCompatible || ANY_SLOT.contains(key)) return Support.NATIVE;
        return customApplies(key, categoryOf(materialName)) ? Support.CUSTOM : Support.NONE;
    }

    /** Does the plugin implement this enchantment for this item category? */
    public static boolean customApplies(String enchantKey, Category c) {
        String key = enchantKey.toLowerCase(Locale.ROOT);
        if (key.equals("frost_walker")) return c != Category.BOOTS && c != Category.OTHER;
        if (key.equals("density")) return c == Category.BOW || c == Category.CROSSBOW;
        if (ARROW_ENCHANTS.contains(key)) return c == Category.BOW || c == Category.CROSSBOW;
        if (HELD_PROTECTION.contains(key)) return !isArmor(c) && c != Category.OTHER;
        return false;
    }

    public static boolean hasWorkingEffect(String enchantKey, String materialName, boolean vanillaCompatible) {
        return support(enchantKey, materialName, vanillaCompatible) != Support.NONE;
    }
}
