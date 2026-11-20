package com.exoticenchants.item;

import com.exoticenchants.core.Rarity;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

/**
 * rarity.yml: a rarity for EVERY enchantment + level. Missing entries are generated (evenly
 * spread, NOT based on level) on startup and appended when new enchantments appear.
 * Server owners can edit the file freely.
 */
public final class RarityTable {

    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, Rarity> table = new TreeMap<>();

    public RarityTable(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "rarity.yml");
        load();
    }

    private void load() {
        table.clear();
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String ench : yaml.getKeys(false)) {
            ConfigurationSection sec = yaml.getConfigurationSection(ench);
            if (sec == null) continue;
            for (String lvl : sec.getKeys(false)) {
                try {
                    table.put(ench + ":" + lvl, Rarity.valueOf(sec.getString(lvl, "COMMON").toUpperCase()));
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("rarity.yml: bad rarity for " + ench + " " + lvl + ", using generated default.");
                }
            }
        }
    }

    /** Make sure every enchantment/level has an entry; write the file if anything was added. */
    public void ensure(Collection<Enchantment> all) {
        boolean changed = !file.exists();
        for (Enchantment e : all) {
            String key = e.getKey().getKey();
            for (int level = 1; level <= e.getMaxLevel(); level++) {
                String k = key + ":" + level;
                if (!table.containsKey(k)) {
                    table.put(k, generated(key, level));
                    changed = true;
                }
            }
        }
        if (changed) save();
    }

    public Rarity rarity(String enchant, int level) {
        Rarity r = table.get(enchant + ":" + level);
        return r != null ? r : generated(enchant, level);
    }

    private static Rarity generated(String enchant, int level) {
        int h = (enchant + ":" + level).hashCode();
        h ^= (h >>> 16);
        h *= 0x45d9f3b;
        h ^= (h >>> 16);
        return Rarity.values()[Math.floorMod(h, Rarity.values().length)];
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(java.util.List.of(
                "Rarity of every exotic enchantment + level. Values: COMMON, UNCOMMON, RARE, EPIC, LEGENDARY.",
                "Rarity is independent of level. Edit freely, then /exotic admin reload."));
        for (Map.Entry<String, Rarity> e : table.entrySet()) {
            String[] parts = e.getKey().split(":");
            yaml.set(parts[0] + "." + parts[1], e.getValue().name());
        }
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().warning("Could not create plugin data folder.");
            }
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save rarity.yml: " + ex.getMessage());
        }
    }

    public void reload() {
        load();
    }
}
