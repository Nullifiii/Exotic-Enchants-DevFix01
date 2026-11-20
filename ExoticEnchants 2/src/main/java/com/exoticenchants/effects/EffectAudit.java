package com.exoticenchants.effects;

import com.exoticenchants.logic.EffectSupport;
import com.exoticenchants.logic.EffectSupport.Category;
import com.exoticenchants.logic.EffectSupport.Support;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Writes plugins/ExoticEnchants/effects-audit.txt: for EVERY enchantment on THIS server, on which item
 * categories it works natively, where the plugin implements it, and where it has no effect (and is
 * therefore never offered). Uses the server's own Enchantment#canEnchantItem, so it is always accurate.
 */
public final class EffectAudit {

    private static final Map<Category, String> SAMPLE = new EnumMap<>(Category.class);

    static {
        SAMPLE.put(Category.SWORD, "DIAMOND_SWORD");
        SAMPLE.put(Category.AXE, "DIAMOND_AXE");
        SAMPLE.put(Category.PICKAXE, "DIAMOND_PICKAXE");
        SAMPLE.put(Category.SHOVEL, "DIAMOND_SHOVEL");
        SAMPLE.put(Category.HOE, "DIAMOND_HOE");
        SAMPLE.put(Category.HELMET, "DIAMOND_HELMET");
        SAMPLE.put(Category.CHESTPLATE, "DIAMOND_CHESTPLATE");
        SAMPLE.put(Category.LEGGINGS, "DIAMOND_LEGGINGS");
        SAMPLE.put(Category.BOOTS, "DIAMOND_BOOTS");
        SAMPLE.put(Category.BOW, "BOW");
        SAMPLE.put(Category.CROSSBOW, "CROSSBOW");
        SAMPLE.put(Category.TRIDENT, "TRIDENT");
        SAMPLE.put(Category.MACE, "MACE");
        SAMPLE.put(Category.FISHING_ROD, "FISHING_ROD");
        SAMPLE.put(Category.SHIELD, "SHIELD");
        SAMPLE.put(Category.ELYTRA, "ELYTRA");
        SAMPLE.put(Category.SHEARS, "SHEARS");
    }

    private EffectAudit() {
    }

    /** @return {native, custom, none} combination counts (also written to the file) */
    public static int[] write(File file) {
        int[] totals = new int[3];
        List<String> lines = new ArrayList<>();
        for (Enchantment e : Registry.ENCHANTMENT) {
            String key = e.getKey().getKey();
            List<String> nat = new ArrayList<>();
            List<String> custom = new ArrayList<>();
            List<String> none = new ArrayList<>();
            for (Map.Entry<Category, String> en : SAMPLE.entrySet()) {
                Material m = Material.matchMaterial(en.getValue());
                if (m == null) continue;
                Support s = EffectSupport.support(key, m.name(), e.canEnchantItem(new ItemStack(m)));
                switch (s) {
                    case NATIVE -> { nat.add(en.getKey().name()); totals[0]++; }
                    case CUSTOM -> { custom.add(en.getKey().name()); totals[1]++; }
                    default -> { none.add(en.getKey().name()); totals[2]++; }
                }
            }
            lines.add(String.format("%-24s max level %d%n    works natively on : %s%n    plugin implements : %s%n    no effect (never offered): %s",
                    key, e.getMaxLevel(), nat.isEmpty() ? "-" : String.join(", ", nat),
                    custom.isEmpty() ? "-" : String.join(", ", custom), none.isEmpty() ? "-" : String.join(", ", none)));
        }
        try (PrintWriter out = new PrintWriter(file, "UTF-8")) {
            out.println("ExoticEnchants effect audit (generated from this server's enchantment registry)");
            out.println("native = vanilla already makes the effect work; plugin implements = custom code in ExoticEnchants;");
            out.println("no effect = the combination would silently do nothing, so it is never rolled, sold or applied");
            out.println("(unless exotic-effects.require-working-effect is false).");
            out.println();
            lines.forEach(out::println);
            out.println();
            out.println("Totals over " + (totals[0] + totals[1] + totals[2]) + " enchantment/item combinations: native=" + totals[0]
                    + " custom=" + totals[1] + " none=" + totals[2]);
        } catch (IOException ex) {
            return totals;
        }
        return totals;
    }
}
