package com.exoticenchants.config;

import com.exoticenchants.core.Rarity;
import com.exoticenchants.core.RollConfig;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Validated snapshot of config.yml. Fields are public for brevity; never mutate them elsewhere. */
public final class Settings {

    private final JavaPlugin plugin;

    public long intervalMillis;
    public double normalChance;
    public boolean announce;
    public double stealBias;
    public boolean allowStealOffline;
    public long protectionMillis;
    public int maxSteals;
    public long afkMillis;
    public boolean ignorePassive;
    public boolean poolAny;
    public List<String> eligible = new ArrayList<>();
    public boolean protectDropped;
    public int scanSeconds;
    public double[] rarityWeights = new double[5];
    // exotic-effects
    public boolean requireWorkingEffect;
    public boolean frostHeld;
    public int frostMaxRadius;
    public int frostMeltTicks;
    public boolean protectionHeld;
    public double protectionMaxReduction;
    public boolean arrowEnchants;
    public boolean densityEnabled;
    public double densityPerBlock;
    public double densityMinFall;
    public double densityMaxBonus;

    public boolean shopEnabled;
    public long shopRotationMillis;
    public int shopSlots;
    public int shopStock;
    public final Map<Rarity, Double> shopPrices = new EnumMap<>(Rarity.class);
    public final Map<Rarity, Double> removalCost = new EnumMap<>(Rarity.class);

    public Settings(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        FileConfiguration c = plugin.getConfig();

        intervalMillis = Math.max(1, c.getLong("rolls.interval-minutes", 60)) * 60_000L;
        normalChance = clamp(c.getDouble("rolls.normal-chance", 0.65), 0, 1, "rolls.normal-chance");
        announce = c.getBoolean("rolls.announce", true);

        stealBias = clamp(c.getDouble("great-reroll.steal-bias", 0.7), 0, 1, "great-reroll.steal-bias");
        allowStealOffline = c.getBoolean("great-reroll.allow-steal-from-offline", false);
        protectionMillis = (long) (Math.max(0, c.getDouble("great-reroll.protection-hours", 3)) * 3_600_000L);
        maxSteals = Math.max(0, c.getInt("great-reroll.max-steals-per-round", 3));

        afkMillis = Math.max(1, c.getLong("afk.minutes", 10)) * 60_000L;
        ignorePassive = c.getBoolean("afk.ignore-passive-movement", true);

        poolAny = "ANY".equalsIgnoreCase(c.getString("exotics.pool-mode", "NON_VANILLA_ONLY"));
        eligible = new ArrayList<>();
        for (String s : c.getStringList("exotics.eligible-items")) eligible.add(s.toUpperCase(Locale.ROOT));
        if (eligible.isEmpty()) plugin.getLogger().warning("exotics.eligible-items is empty: no item can hold an exotic.");
        protectDropped = c.getBoolean("exotics.protect-dropped-items", true);
        scanSeconds = Math.max(1, c.getInt("exotics.scan-interval-seconds", 3));

        Rarity[] tiers = Rarity.values();
        for (Rarity r : tiers) {
            rarityWeights[r.ordinal()] = Math.max(0, c.getDouble("rarity-weights." + r.name().toLowerCase(Locale.ROOT), 20));
        }

        requireWorkingEffect = c.getBoolean("exotic-effects.require-working-effect", true);
        frostHeld = c.getBoolean("exotic-effects.frost-walker-on-held-items.enabled", true);
        frostMaxRadius = Math.min(16, Math.max(1, c.getInt("exotic-effects.frost-walker-on-held-items.max-radius", 6)));
        frostMeltTicks = Math.max(20, c.getInt("exotic-effects.frost-walker-on-held-items.melt-seconds", 6) * 20);
        protectionHeld = c.getBoolean("exotic-effects.protection-on-held-items.enabled", true);
        protectionMaxReduction = clamp(c.getDouble("exotic-effects.protection-on-held-items.max-reduction", 0.8), 0, 0.8,
                "exotic-effects.protection-on-held-items.max-reduction");
        arrowEnchants = c.getBoolean("exotic-effects.bow-weapon-enchants.enabled", true);
        densityEnabled = c.getBoolean("exotic-effects.density-bow.enabled", true);
        densityPerBlock = Math.max(0, c.getDouble("exotic-effects.density-bow.damage-per-block-per-level", 0.25));
        densityMinFall = Math.max(0, c.getDouble("exotic-effects.density-bow.min-fall-distance", 1.5));
        densityMaxBonus = Math.max(0, c.getDouble("exotic-effects.density-bow.max-bonus-damage", 20.0));

        shopEnabled = c.getBoolean("shop.enabled", true);
        shopRotationMillis = Math.max(1, c.getLong("shop.rotation-hours", 4)) * 3_600_000L;
        shopSlots = Math.min(27, Math.max(1, c.getInt("shop.slots", 9)));
        shopStock = Math.max(1, c.getInt("shop.stock-per-entry", 2));
        for (Rarity r : tiers) {
            String k = r.name().toLowerCase(Locale.ROOT);
            shopPrices.put(r, Math.max(0, c.getDouble("shop.prices." + k, 1000)));
            removalCost.put(r, Math.max(0, c.getDouble("removal-cost." + k, 1000)));
        }
    }

    public RollConfig toRollConfig() {
        return new RollConfig(normalChance, stealBias, allowStealOffline, protectionMillis, maxSteals, 10,
                rarityWeights.clone());
    }

    public boolean isEligibleMaterial(String materialName) {
        for (String e : eligible) {
            if (materialName.equals(e)) return true;
            if (e.startsWith("_") && materialName.endsWith(e)) return true;
        }
        return false;
    }

    private double clamp(double v, double lo, double hi, String path) {
        if (v < lo || v > hi) {
            plugin.getLogger().warning(path + " must be between " + lo + " and " + hi + " (got " + v + "); clamped.");
            return Math.min(hi, Math.max(lo, v));
        }
        return v;
    }
}
