package com.exoticenchants.core;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Dependency-free tests for the registry and roll engine. Run: see README. */
public final class CoreSelfTest {

    /** Controllable clock. */
    static final class TestClock extends Clock {
        long millis = 1_000_000_000_000L;
        void advanceHours(int h) { millis += h * 3_600_000L; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
    }

    static int passed = 0;
    static int failed = 0;

    static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("PASS  " + name); }
        else { failed++; System.out.println("FAIL  " + name); }
    }

    static ExoticKey key(String e, int l, String m) { return new ExoticKey(e, l, m); }

    static List<Candidate> pool(int perTier) {
        List<Candidate> out = new ArrayList<>();
        Rarity[] r = Rarity.values();
        for (int i = 0; i < r.length * perTier; i++) out.add(new Candidate("ench" + i, 1 + i % 3, r[i % r.length]));
        return out;
    }

    public static void main(String[] args) throws Exception {
        simultaneousClaim();
        oneExoticPerItem();
        transferAndLazyInvalidation();
        rollbackRestoresOwner();
        crashRecoveryPending();
        crashRecoveryInterruptedTransfer();
        rollSplit();
        stealOfflineProtectionAndBias();
        sameKeyOnlyOneWinnerInRound();
        rarityDistribution();
        migrateMaterial();
        ownerChangeKeepsRegistration();
        failedDatabaseWrites();
        rollbackByCreatedId();
        staleHistoryNotResurrected();
        massSimulationInvariants();
        System.out.println("\n" + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    static void simultaneousClaim() throws Exception {
        Registry reg = new Registry(new InMemoryStorage(), Clock.systemUTC());
        ExoticKey k = key("power", 5, "DIAMOND_SWORD");
        ExecutorService ex = Executors.newFixedThreadPool(16);
        AtomicInteger wins = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            fs.add(ex.submit(() -> {
                go.await();
                if (reg.claim(k, UUID.randomUUID(), UUID.randomUUID(), Rarity.EPIC).isPresent()) wins.incrementAndGet();
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : fs) f.get();
        ex.shutdown();
        check("64 simultaneous claims of one key -> exactly 1 winner", wins.get() == 1 && reg.checkInvariants().isEmpty());
    }

    static void oneExoticPerItem() {
        Registry reg = new Registry(new InMemoryStorage(), Clock.systemUTC());
        UUID item = UUID.randomUUID();
        boolean a = reg.claim(key("power", 5, "DIAMOND_SWORD"), item, UUID.randomUUID(), Rarity.RARE).isPresent();
        boolean b = reg.claim(key("punch", 2, "DIAMOND_SWORD"), item, UUID.randomUUID(), Rarity.RARE).isPresent();
        check("a second exotic on the same item is refused", a && !b);
    }

    static void transferAndLazyInvalidation() {
        TestClock clock = new TestClock();
        Registry reg = new Registry(new InMemoryStorage(), clock);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), itemA = UUID.randomUUID(), itemB = UUID.randomUUID();
        ExoticKey k = key("power", 5, "DIAMOND_SWORD");
        Registration r = reg.claim(k, itemA, a, Rarity.EPIC).orElseThrow();
        reg.confirm(r.id());
        Registry.Transfer t = reg.transfer(k, b, itemB).orElseThrow();
        reg.confirm(t.created().id());
        check("transfer: old item no longer valid (will be stripped lazily)", !reg.isValidItem(itemA));
        check("transfer: new item valid and owned by B", reg.isValidItem(itemB) && reg.ownedBy(b).size() == 1 && reg.ownedBy(a).isEmpty());
        check("transfer: never two live copies", reg.checkInvariants().isEmpty());
    }

    static void rollbackRestoresOwner() {
        Registry reg = new Registry(new InMemoryStorage(), new TestClock());
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), itemA = UUID.randomUUID();
        ExoticKey k = key("power", 5, "DIAMOND_SWORD");
        reg.confirm(reg.claim(k, itemA, a, Rarity.EPIC).orElseThrow().id());
        Registry.Transfer t = reg.transfer(k, b, UUID.randomUUID()).orElseThrow();
        boolean rolled = reg.rollbackTransfer(t);
        check("failed item change -> rollback gives exotic back to previous owner",
                rolled && reg.isValidItem(itemA) && reg.ownedBy(a).size() == 1 && reg.checkInvariants().isEmpty());
    }

    static void crashRecoveryPending() {
        InMemoryStorage store = new InMemoryStorage();
        TestClock clock = new TestClock();
        Registry before = new Registry(store, clock);
        ExoticKey k = key("sharpness", 5, "BOW");
        before.claim(k, UUID.randomUUID(), UUID.randomUUID(), Rarity.LEGENDARY); // crash before confirm
        Registry after = new Registry(store, clock);                              // "restart"
        int[] res = after.reconcilePending(r -> false);                           // item never got it
        check("crash: PENDING with no item is aborted and key freed", res[1] == 1 && !after.isKeyLive(k));

        InMemoryStorage store2 = new InMemoryStorage();
        Registry b2 = new Registry(store2, clock);
        b2.claim(k, UUID.randomUUID(), UUID.randomUUID(), Rarity.LEGENDARY);
        Registry a2 = new Registry(store2, clock);
        int[] res2 = a2.reconcilePending(r -> true);                              // item did get it
        check("crash: PENDING whose item has the exotic is confirmed", res2[0] == 1 && a2.activeByKey(k).isPresent());
    }

    static void crashRecoveryInterruptedTransfer() {
        InMemoryStorage store = new InMemoryStorage();
        TestClock clock = new TestClock();
        Registry r1 = new Registry(store, clock);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), itemA = UUID.randomUUID(), itemB = UUID.randomUUID();
        ExoticKey k = key("power", 5, "DIAMOND_SWORD");
        r1.confirm(r1.claim(k, itemA, a, Rarity.EPIC).orElseThrow().id());
        r1.transfer(k, b, itemB);                                                 // crash mid-transfer
        Registry r2 = new Registry(store, clock);
        r2.reconcilePending(reg -> reg.itemUuid().equals(itemA));                 // only A's item has it
        check("crash mid-transfer: previous owner keeps the exotic, no duplicate",
                r2.isValidItem(itemA) && !r2.isValidItem(itemB) && r2.checkInvariants().isEmpty());
    }

    static void rollSplit() {
        Registry reg = new Registry(new InMemoryStorage(), new TestClock());
        RollEngine e = new RollEngine(reg, RollConfig.defaults(), new Random(42), new TestClock());
        int normal = 0;
        for (int i = 0; i < 10_000; i++) if (e.rollKind() == RollKind.NORMAL) normal++;
        double pct = normal / 100.0;
        check("65/35 split over 10,000 rolls (got " + pct + "% normal)", pct > 63 && pct < 67);
    }

    static Outcome one(List<Outcome> list, UUID p) {
        return list.stream().filter(o -> o.player().equals(p)).findFirst().orElseThrow();
    }

    static void stealOfflineProtectionAndBias() {
        TestClock clock = new TestClock();
        Registry reg = new Registry(new InMemoryStorage(), clock);
        UUID owner = UUID.randomUUID(), roller = UUID.randomUUID();
        ExoticKey k = key("ench0", 1, "DIAMOND_SWORD");
        reg.confirm(reg.claim(k, UUID.randomUUID(), owner, Rarity.COMMON).orElseThrow().id());
        List<Candidate> only = List.of(new Candidate("ench0", 1, Rarity.COMMON)); // nothing else to roll
        Participant p = new Participant(roller, "DIAMOND_SWORD", false, only);

        RollConfig base = RollConfig.defaults().withStealBias(1.0).withProtectionMillis(3L * 3_600_000);
        // 1) owner offline -> never stolen
        RollEngine e1 = new RollEngine(reg, base, new Random(1), clock);
        Outcome o1 = one(e1.runRound(RollKind.GREAT, List.of(p), Set.of(roller)), roller);
        check("offline owner is never targeted", o1.type() == Outcome.Type.SKIPPED && reg.ownedBy(owner).size() == 1);
        // 2) owner online but protected (just acquired)
        Outcome o2 = one(e1.runRound(RollKind.GREAT, List.of(p), Set.of(roller, owner)), roller);
        check("new owner is protected for the protection window", o2.type() == Outcome.Type.SKIPPED && reg.ownedBy(owner).size() == 1);
        // 3) protection expired -> stolen
        clock.advanceHours(4);
        Outcome o3 = one(e1.runRound(RollKind.GREAT, List.of(p), Set.of(roller, owner)), roller);
        check("after protection expires an online owner can be stolen from", o3.type() == Outcome.Type.STOLEN
                && o3.previousOwner().equals(owner));
        reg.confirm(o3.registration().id());
        check("steal leaves exactly one live copy", reg.ownedBy(roller).size() == 1 && reg.ownedBy(owner).isEmpty()
                && reg.checkInvariants().isEmpty());
        // 4) NORMAL roll never steals
        UUID third = UUID.randomUUID();
        clock.advanceHours(4);
        RollEngine e2 = new RollEngine(reg, base, new Random(2), clock);
        Outcome o4 = one(e2.runRound(RollKind.NORMAL, List.of(new Participant(third, "DIAMOND_SWORD", false, only)),
                Set.of(roller, third)), third);
        check("a NORMAL roll never takes someone else's exotic", o4.type() == Outcome.Type.SKIPPED);
        // 5) allow-offline switch works
        RollEngine e3 = new RollEngine(reg, base.withAllowOffline(true), new Random(3), clock);
        Outcome o5 = one(e3.runRound(RollKind.GREAT, List.of(new Participant(third, "DIAMOND_SWORD", false, only)),
                Set.of(third)), third);
        check("allow-steal-from-offline=true permits offline targets", o5.type() == Outcome.Type.STOLEN);
        // 6) item already holding an exotic is skipped
        UUID holder = UUID.randomUUID();
        Outcome o6 = one(e1.runRound(RollKind.GREAT, List.of(new Participant(holder, "DIAMOND_SWORD", true, only)),
                Set.of(holder)), holder);
        check("an item that already holds an exotic is skipped", o6.type() == Outcome.Type.SKIPPED);
    }

    static void sameKeyOnlyOneWinnerInRound() {
        TestClock clock = new TestClock();
        Registry reg = new Registry(new InMemoryStorage(), clock);
        List<Candidate> single = List.of(new Candidate("power", 5, Rarity.EPIC));
        List<Participant> ps = new ArrayList<>();
        Set<UUID> online = new HashSet<>();
        for (int i = 0; i < 6; i++) {
            UUID u = UUID.randomUUID();
            online.add(u);
            ps.add(new Participant(u, "BOW", false, single));
        }
        RollEngine e = new RollEngine(reg, RollConfig.defaults().withStealBias(0.0), new Random(7), clock);
        List<Outcome> res = e.runRound(RollKind.GREAT, ps, online);
        long claimed = res.stream().filter(o -> o.type() == Outcome.Type.CLAIMED).count();
        check("6 players roll the same single enchant in one round -> exactly 1 gets it",
                claimed == 1 && reg.checkInvariants().isEmpty());
    }

    static void rarityDistribution() {
        Registry reg = new Registry(new InMemoryStorage(), new TestClock());
        RollEngine e = new RollEngine(reg, RollConfig.defaults(), new Random(11), new TestClock());
        List<Candidate> pool = pool(20);
        int[] counts = new int[5];
        for (int i = 0; i < 10_000; i++) counts[e.pickFresh(pool, "BOW").orElseThrow().rarity().ordinal()]++;
        boolean ok = true;
        for (int c : counts) if (c < 1700 || c > 2300) ok = false;
        check("rarity tiers ~20% each " + Arrays.toString(counts), ok);
    }

    static void migrateMaterial() {
        Registry reg = new Registry(new InMemoryStorage(), new TestClock());
        UUID o = UUID.randomUUID();
        Registration r = reg.claim(key("power", 5, "DIAMOND_SWORD"), UUID.randomUUID(), o, Rarity.EPIC).orElseThrow();
        reg.confirm(r.id());
        Registration blocker = reg.claim(key("sharpness", 5, "NETHERITE_SWORD"), UUID.randomUUID(), UUID.randomUUID(), Rarity.RARE).orElseThrow();
        reg.confirm(blocker.id());
        boolean free = reg.migrateMaterial(r.id(), "NETHERITE_SWORD");
        Registration r2 = reg.claim(key("power", 5, "NETHERITE_SWORD"), UUID.randomUUID(), UUID.randomUUID(), Rarity.EPIC).orElse(null);
        check("netherite upgrade migrates the key when free (and the old key is released)", free && r2 == null
                && reg.activeByKey(key("power", 5, "NETHERITE_SWORD")).isPresent());
        Registry reg2 = new Registry(new InMemoryStorage(), new TestClock());
        Registration a = reg2.claim(key("power", 5, "DIAMOND_SWORD"), UUID.randomUUID(), o, Rarity.EPIC).orElseThrow();
        reg2.confirm(a.id());
        reg2.confirm(reg2.claim(key("power", 5, "NETHERITE_SWORD"), UUID.randomUUID(), o, Rarity.EPIC).orElseThrow().id());
        check("netherite upgrade is denied when the new key is already owned", !reg2.migrateMaterial(a.id(), "NETHERITE_SWORD"));
    }

    static void ownerChangeKeepsRegistration() {
        Registry reg = new Registry(new InMemoryStorage(), new TestClock());
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), item = UUID.randomUUID();
        Registration r = reg.claim(key("power", 5, "BOW"), item, a, Rarity.EPIC).orElseThrow();
        reg.confirm(r.id());
        reg.changeOwner(item, b);
        check("trade/auction: same item and registration, new owner",
                reg.isValidItem(item) && reg.ownedBy(b).size() == 1 && reg.ownedBy(a).isEmpty()
                        && reg.byItem(item).orElseThrow().id() == r.id());
    }

    /** Storage that throws on the Nth save, to simulate a disk/database failure. */
    static final class FlakyStorage implements RegistryStorage {
        final InMemoryStorage inner = new InMemoryStorage();
        int count = 0;
        int failAt = -1;
        @Override public List<Registration> loadAll() { return inner.loadAll(); }
        @Override public void save(Registration r) {
            if (++count == failAt) throw new IllegalStateException("simulated disk failure");
            inner.save(r);
        }
    }

    static void failedDatabaseWrites() {
        // claim: DB write fails -> exception, and the key stays free in memory
        FlakyStorage fs = new FlakyStorage();
        Registry reg = new Registry(fs, new TestClock());
        ExoticKey k = key("power", 5, "BOW");
        fs.failAt = 1;
        boolean threw = false;
        try { reg.claim(k, UUID.randomUUID(), UUID.randomUUID(), Rarity.EPIC); } catch (IllegalStateException ex) { threw = true; }
        check("DB failure during claim: exception, key stays free, nothing stored",
                threw && !reg.isKeyLive(k) && fs.inner.loadAll().isEmpty());

        // transfer: DB fails while writing the NEW row -> previous owner keeps the exotic
        FlakyStorage fs2 = new FlakyStorage();
        Registry reg2 = new Registry(fs2, new TestClock());
        UUID a = UUID.randomUUID(), itemA = UUID.randomUUID();
        reg2.confirm(reg2.claim(k, itemA, a, Rarity.EPIC).orElseThrow().id());   // saves 1 and 2
        fs2.count = 0;
        fs2.failAt = 2;                                                           // revoke ok, create fails
        boolean threw2 = false;
        try { reg2.transfer(k, UUID.randomUUID(), UUID.randomUUID()); } catch (IllegalStateException ex) { threw2 = true; }
        Registry reloaded = new Registry(fs2.inner, new TestClock());
        check("DB failure mid-transfer: previous owner keeps it, in memory AND on disk",
                threw2 && reg2.isValidItem(itemA) && reloaded.isValidItem(itemA) && reg2.checkInvariants().isEmpty()
                        && reloaded.checkInvariants().isEmpty());
    }

    static void rollbackByCreatedId() {
        Registry reg = new Registry(new InMemoryStorage(), new TestClock());
        UUID a = UUID.randomUUID(), itemA = UUID.randomUUID();
        ExoticKey k = key("power", 5, "BOW");
        reg.confirm(reg.claim(k, itemA, a, Rarity.EPIC).orElseThrow().id());
        Registry.Transfer t = reg.transfer(k, UUID.randomUUID(), UUID.randomUUID()).orElseThrow();
        boolean ok = reg.rollbackTransferByCreated(t.created().id());
        check("rollbackTransferByCreated restores the previous owner", ok && reg.isValidItem(itemA)
                && reg.checkInvariants().isEmpty());
    }

    /** A crashed plain claim must NOT bring back an old owner from an earlier, finished steal. */
    static void staleHistoryNotResurrected() {
        InMemoryStorage store = new InMemoryStorage();
        TestClock clock = new TestClock();
        Registry r1 = new Registry(store, clock);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        UUID itemA = UUID.randomUUID(), itemB = UUID.randomUUID();
        ExoticKey k = key("power", 5, "BOW");
        r1.confirm(r1.claim(k, itemA, a, Rarity.EPIC).orElseThrow().id());
        Registry.Transfer t = r1.transfer(k, b, itemB).orElseThrow();      // A -> B, completed
        r1.confirm(t.created().id());
        r1.release(t.created().id());                                       // B removes it (grindstone)
        r1.claim(k, UUID.randomUUID(), c, Rarity.EPIC);                     // C claims, crash before confirm
        Registry r2 = new Registry(store, clock);                           // restart
        r2.reconcilePending(row -> row.status() == Status.REVOKED);         // the plugin's real predicate
        check("crash recovery never resurrects an old owner from earlier history",
                !r2.isValidItem(itemA) && !r2.isKeyLive(k) && r2.checkInvariants().isEmpty());
    }

    static void massSimulationInvariants() {
        TestClock clock = new TestClock();
        Registry reg = new Registry(new InMemoryStorage(), clock);
        Random rnd = new Random(99);
        RollEngine e = new RollEngine(reg, RollConfig.defaults().withStealBias(0.7).withProtectionMillis(3_600_000)
                .withMaxSteals(5), rnd, clock);
        String[] mats = {"DIAMOND_SWORD", "BOW", "NETHERITE_CHESTPLATE"};
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < 14; i++) players.add(UUID.randomUUID());
        boolean healthy = true;
        int steals = 0, claims = 0;
        for (int round = 0; round < 400; round++) {
            clock.advanceHours(1);
            for (Registration act : reg.all()) {                 // churn: some exotics get removed (grindstone)
                if (act.status() == Status.ACTIVE && rnd.nextDouble() < 0.15) reg.release(act.id());
            }
            Set<UUID> online = new HashSet<>();
            List<Participant> ps = new ArrayList<>();
            for (UUID p : players) {
                if (rnd.nextDouble() < 0.7) {
                    online.add(p);
                    ps.add(new Participant(p, mats[rnd.nextInt(3)], !reg.ownedBy(p).isEmpty(), pool(4)));
                }
            }
            for (Outcome o : e.runRound(e.rollKind(), ps, online)) {
                if (o.type() == Outcome.Type.SKIPPED) continue;
                if (o.type() == Outcome.Type.STOLEN) steals++; else claims++;
                if (rnd.nextDouble() < 0.05) {                       // simulate a failed item change
                    if (o.type() == Outcome.Type.CLAIMED) reg.abort(o.registration().id());
                    else reg.rollbackTransfer(new Registry.Transfer(
                            reg.all().stream().filter(x -> x.key().equals(o.registration().key()) && x.status() == Status.REVOKED)
                                    .max(Comparator.comparingLong(Registration::id)).orElseThrow(), o.registration()));
                } else {
                    reg.confirm(o.registration().id());
                }
            }
            if (!reg.checkInvariants().isEmpty()) healthy = false;
        }
        check("400 simulated rounds (" + claims + " claims, " + steals + " steals, 5% failures): no duplicate key or item ever",
                healthy && steals >= 20);
    }
}
