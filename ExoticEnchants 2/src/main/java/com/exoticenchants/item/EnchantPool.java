package com.exoticenchants.item;

import com.exoticenchants.config.Settings;
import com.exoticenchants.core.Candidate;
import com.exoticenchants.logic.EffectSupport;
import com.exoticenchants.util.Msg;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** Builds the candidate pool for an item from the SERVER's enchantment registry (never hardcoded). */
public final class EnchantPool {

    private final Settings settings;
    private final RarityTable rarity;

    public EnchantPool(Settings settings, RarityTable rarity) {
        this.settings = settings;
        this.rarity = rarity;
    }

    public static Enchantment lookup(String key) {
        return Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key));
    }

    public List<Enchantment> allEnchants() {
        List<Enchantment> out = new ArrayList<>();
        for (Enchantment e : Registry.ENCHANTMENT) out.add(e);
        return out;
    }

    /** @return null if this enchantment is a valid, working exotic for the item, otherwise the reason it is not */
    public String rejection(ItemStack item, Enchantment e) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasEnchant(e)) return "the item already has that enchantment as a normal enchantment";
        boolean vanilla = e.canEnchantItem(item);
        boolean exotic;
        if (settings.poolAny) {
            exotic = true;
        } else {
            exotic = !vanilla;                                          // wrong item type
            if (!exotic && meta != null) {
                for (Enchantment existing : meta.getEnchants().keySet()) {
                    if (existing.conflictsWith(e)) exotic = true;       // conflicts with a present enchant
                }
            }
        }
        if (!exotic) return "that enchantment can be added normally to this item";
        if (settings.requireWorkingEffect
                && !EffectSupport.hasWorkingEffect(e.getKey().getKey(), item.getType().name(), vanilla)) {
            return Msg.titleCase(e.getKey().getKey()) + " would have no effect on a " + Msg.titleCase(item.getType().name())
                    + " (see /exotic admin effects)";
        }
        return null;
    }

    /**
     * Is this enchantment a working "exotic" for this item right now? Never true if the item already has
     * the enchantment as a normal one (normal enchants are never overwritten), and - unless
     * exotic-effects.require-working-effect is off - never true for combinations that would silently do nothing.
     */
    public boolean qualifies(ItemStack item, Enchantment e) {
        return rejection(item, e) == null;
    }

    public List<Candidate> build(ItemStack item) {
        List<Candidate> out = new ArrayList<>();
        for (Enchantment e : Registry.ENCHANTMENT) {
            if (!qualifies(item, e)) continue;
            String key = e.getKey().getKey();
            for (int level = 1; level <= e.getMaxLevel(); level++) {
                out.add(new Candidate(key, level, rarity.rarity(key, level)));
            }
        }
        return out;
    }

    public RarityTable rarityTable() {
        return rarity;
    }
}
