package com.exoticenchants.core;

/**
 * Tunable roll settings (all come from config.yml in the plugin).
 *
 * @param normalChance         chance an hourly roll is NORMAL (rest is GREAT), 0.65 default
 * @param stealBias            on a GREAT roll, chance of trying to take an owned exotic
 * @param allowStealFromOffline if false, offline owners are never targeted
 * @param protectionMillis     a new owner cannot be stolen from for this long
 * @param maxStealsPerRound    cap on transfers in one round
 * @param maxTries             attempts before a player is skipped
 * @param rarityWeights        weight per tier in enum order (COMMON..LEGENDARY)
 */
public record RollConfig(double normalChance, double stealBias, boolean allowStealFromOffline,
                         long protectionMillis, int maxStealsPerRound, int maxTries,
                         double[] rarityWeights) {

    public static RollConfig defaults() {
        return new RollConfig(0.65, 0.7, false, 3L * 60 * 60 * 1000, 3, 10,
                new double[]{20, 20, 20, 20, 20});
    }

    public RollConfig withStealBias(double v) {
        return new RollConfig(normalChance, v, allowStealFromOffline, protectionMillis, maxStealsPerRound, maxTries, rarityWeights);
    }

    public RollConfig withAllowOffline(boolean v) {
        return new RollConfig(normalChance, stealBias, v, protectionMillis, maxStealsPerRound, maxTries, rarityWeights);
    }

    public RollConfig withProtectionMillis(long v) {
        return new RollConfig(normalChance, stealBias, allowStealFromOffline, v, maxStealsPerRound, maxTries, rarityWeights);
    }

    public RollConfig withMaxSteals(int v) {
        return new RollConfig(normalChance, stealBias, allowStealFromOffline, protectionMillis, v, maxTries, rarityWeights);
    }
}
