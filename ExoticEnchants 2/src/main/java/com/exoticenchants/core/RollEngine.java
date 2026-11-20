package com.exoticenchants.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Hourly roll logic with no Bukkit dependency. Registry changes are made here (atomically);
 * the plugin then applies the matching item changes and confirms or rolls back.
 */
public final class RollEngine {

    private final Registry registry;
    private final RollConfig cfg;
    private final Random rnd;
    private final Clock clock;

    public RollEngine(Registry registry, RollConfig cfg, Random rnd, Clock clock) {
        this.registry = registry;
        this.cfg = cfg;
        this.rnd = rnd;
        this.clock = clock;
    }

    /** The hourly 65/35 decision. */
    public RollKind rollKind() {
        return rnd.nextDouble() < cfg.normalChance() ? RollKind.NORMAL : RollKind.GREAT;
    }

    /** Process every participant in random order. Each gets at most one outcome. */
    public List<Outcome> runRound(RollKind kind, List<Participant> participants, Set<UUID> online) {
        List<Participant> order = new ArrayList<>(participants);
        Collections.shuffle(order, rnd);
        Set<ExoticKey> touched = new HashSet<>();
        List<Outcome> results = new ArrayList<>();
        int steals = 0;
        for (Participant p : order) {
            if (p.hasExotic()) {
                results.add(Outcome.skipped(p.player(), "item already has an exotic"));
                continue;
            }
            if (p.pool().isEmpty()) {
                results.add(Outcome.skipped(p.player(), "no candidates for this item"));
                continue;
            }
            Outcome o = null;
            if (kind == RollKind.GREAT && steals < cfg.maxStealsPerRound() && rnd.nextDouble() < cfg.stealBias()) {
                o = trySteal(p, online, touched);
                if (o != null) steals++;
            }
            if (o == null) o = tryClaim(p, touched);
            results.add(o);
        }
        return results;
    }

    private Outcome trySteal(Participant p, Set<UUID> online, Set<ExoticKey> touched) {
        Instant now = clock.instant();
        List<Registration> targets = new ArrayList<>();
        for (Registration r : registry.activeByMaterial(p.material())) {
            if (r.owner().equals(p.player())) continue;
            if (!cfg.allowStealFromOffline() && !online.contains(r.owner())) continue;
            if (Duration.between(r.ownedSince(), now).toMillis() < cfg.protectionMillis()) continue;
            if (touched.contains(r.key())) continue;
            targets.add(r);
        }
        Collections.shuffle(targets, rnd);
        int tries = 0;
        for (Registration t : targets) {
            if (tries++ >= cfg.maxTries()) break;
            Optional<Registry.Transfer> tr = registry.transfer(t.key(), p.player(), UUID.randomUUID());
            if (tr.isPresent()) {
                touched.add(t.key());
                return Outcome.stolen(p.player(), tr.get().created(), t.owner());
            }
        }
        return null;
    }

    private Outcome tryClaim(Participant p, Set<ExoticKey> touched) {
        for (int i = 0; i < cfg.maxTries(); i++) {
            Optional<Candidate> c = pickFresh(p.pool(), p.material());
            if (c.isEmpty()) break;
            ExoticKey key = new ExoticKey(c.get().enchant(), c.get().level(), p.material());
            Optional<Registration> r = registry.claim(key, UUID.randomUUID(), p.player(), c.get().rarity());
            if (r.isPresent()) {
                touched.add(key);
                return Outcome.claimed(p.player(), r.get());
            }
        }
        return Outcome.skipped(p.player(), "no unclaimed exotic available");
    }

    /** Pick a rarity tier by weight (only tiers that still have free candidates), then a candidate. */
    public Optional<Candidate> pickFresh(List<Candidate> pool, String material) {
        Map<Rarity, List<Candidate>> byTier = new EnumMap<>(Rarity.class);
        for (Candidate c : pool) {
            if (registry.isKeyLive(new ExoticKey(c.enchant(), c.level(), material))) continue;
            byTier.computeIfAbsent(c.rarity(), k -> new ArrayList<>()).add(c);
        }
        if (byTier.isEmpty()) return Optional.empty();
        double total = 0;
        for (Rarity r : byTier.keySet()) total += Math.max(0, cfg.rarityWeights()[r.ordinal()]);
        Rarity chosen = null;
        if (total <= 0) {
            chosen = new ArrayList<>(byTier.keySet()).get(rnd.nextInt(byTier.size()));
        } else {
            double roll = rnd.nextDouble() * total;
            for (Map.Entry<Rarity, List<Candidate>> e : byTier.entrySet()) {
                roll -= Math.max(0, cfg.rarityWeights()[e.getKey().ordinal()]);
                chosen = e.getKey();
                if (roll < 0) break;
            }
        }
        List<Candidate> tier = byTier.get(chosen);
        return Optional.of(tier.get(rnd.nextInt(tier.size())));
    }
}
