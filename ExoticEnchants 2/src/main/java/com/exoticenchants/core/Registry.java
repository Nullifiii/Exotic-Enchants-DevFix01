package com.exoticenchants.core;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The global uniqueness registry. Every mutation is synchronized and written through to
 * storage, so at most ONE live registration can exist per {@link ExoticKey}, and at most one
 * per item UUID. Two-phase flow: claim/transfer creates PENDING, the caller applies the item
 * change, then confirm() (or abort()/rollbackTransfer()) finishes it. PENDING rows left over
 * from a crash are settled by {@link #reconcilePending}.
 */
public final class Registry {

    /** Result of a transfer: the old (now REVOKED) row and the new PENDING row. */
    public record Transfer(Registration revoked, Registration created) {
    }

    private final RegistryStorage storage;
    private final Clock clock;
    private final Map<Long, Registration> byId = new HashMap<>();
    private final Map<ExoticKey, Long> liveByKey = new HashMap<>();
    private final Map<UUID, Long> liveByItem = new HashMap<>();
    private final List<String> anomalies = new ArrayList<>();
    private long nextId = 1;

    public Registry(RegistryStorage storage, Clock clock) {
        this.storage = storage;
        this.clock = clock;
        for (Registration r : storage.loadAll()) {
            byId.put(r.id(), r);
            nextId = Math.max(nextId, r.id() + 1);
            if (r.status().isLive()) {
                if (liveByKey.containsKey(r.key()) || liveByItem.containsKey(r.itemUuid())) {
                    anomalies.add("Conflicting live registration #" + r.id() + " for " + r.key());
                } else {
                    index(r);
                }
            }
        }
    }

    // ------------------------------------------------------------------ writes

    /** Reserve a brand-new exotic. Empty if the key is taken or the item already holds one. */
    public synchronized Optional<Registration> claim(ExoticKey key, UUID itemUuid, UUID owner, Rarity rarity) {
        if (liveByKey.containsKey(key) || liveByItem.containsKey(itemUuid)) return Optional.empty();
        Instant now = clock.instant();
        Registration r = new Registration(nextId++, key, itemUuid, owner, rarity, Status.PENDING, now, now, 0L);
        store(r);
        index(r);
        return Optional.of(r);
    }

    /** PENDING -> ACTIVE. */
    public synchronized boolean confirm(long id) {
        Registration r = byId.get(id);
        if (r == null || r.status() != Status.PENDING) return false;
        store(r.withStatus(Status.ACTIVE).withOwner(r.owner(), clock.instant()));
        return true;
    }

    /** PENDING -> RELEASED (the item change failed). */
    public synchronized boolean abort(long id) {
        Registration r = byId.get(id);
        if (r == null || r.status() != Status.PENDING) return false;
        unindex(r);
        store(r.withStatus(Status.RELEASED));
        return true;
    }

    /**
     * Move an ACTIVE exotic to a new owner and a new item. The old row becomes REVOKED
     * (its item will be stripped lazily); a new PENDING row takes over the key.
     */
    public synchronized Optional<Transfer> transfer(ExoticKey key, UUID newOwner, UUID newItemUuid) {
        Long oldId = liveByKey.get(key);
        if (oldId == null) return Optional.empty();
        Registration old = byId.get(oldId);
        if (old.status() != Status.ACTIVE || liveByItem.containsKey(newItemUuid)) return Optional.empty();
        Registration revoked = old.withStatus(Status.REVOKED);
        store(revoked);
        unindex(old);
        Instant now = clock.instant();
        Registration created = new Registration(nextId++, key, newItemUuid, newOwner, old.rarity(),
                Status.PENDING, now, now, old.id());
        try {
            store(created);
        } catch (RuntimeException ex) {
            store(old);          // undo: previous owner keeps it
            index(old);
            throw ex;
        }
        index(created);
        return Optional.of(new Transfer(revoked, created));
    }

    /** Undo a transfer whose item change failed: the previous owner keeps the exotic. */
    public synchronized boolean rollbackTransfer(Transfer t) {
        Registration cur = byId.get(t.created().id());
        Registration old = byId.get(t.revoked().id());
        if (cur == null || cur.status() != Status.PENDING || old == null || old.status() != Status.REVOKED) {
            return false;
        }
        unindex(cur);
        store(cur.withStatus(Status.RELEASED));
        Registration restored = old.withStatus(Status.ACTIVE);
        store(restored);
        index(restored);
        return true;
    }

    /** Same as {@link #rollbackTransfer(Transfer)} when only the new row's id is known. */
    public synchronized boolean rollbackTransferByCreated(long createdId) {
        Registration cur = byId.get(createdId);
        if (cur == null || cur.status() != Status.PENDING || cur.replaces() == 0L) return false;
        Registration prev = byId.get(cur.replaces());
        return prev != null && rollbackTransfer(new Transfer(prev, cur));
    }

    /** Free the key (grindstone removal, admin unregister). */
    public synchronized boolean release(long id) {
        Registration r = byId.get(id);
        if (r == null || !r.status().isLive()) return false;
        unindex(r);
        store(r.withStatus(Status.RELEASED));
        return true;
    }

    /** Duplicate/uncertain item: keep the key blocked but flag for admins. */
    public synchronized boolean dispute(long id) {
        Registration r = byId.get(id);
        if (r == null || !r.status().isLive()) return false;
        store(r.withStatus(Status.DISPUTED));
        return true;
    }

    /** Trade / auction / pickup: the same item and registration, new holder. */
    public synchronized boolean changeOwner(UUID itemUuid, UUID newOwner) {
        Long id = liveByItem.get(itemUuid);
        if (id == null) return false;
        Registration r = byId.get(id);
        if (r.status() != Status.ACTIVE) return false;
        store(r.withOwner(newOwner, clock.instant()));
        return true;
    }

    /** Netherite upgrade etc.: re-key to a new material if that key is free. */
    public synchronized boolean migrateMaterial(long id, String newMaterial) {
        Registration r = byId.get(id);
        if (r == null || r.status() != Status.ACTIVE) return false;
        ExoticKey nk = new ExoticKey(r.key().enchant(), r.key().level(), newMaterial);
        if (nk.equals(r.key())) return true;
        if (liveByKey.containsKey(nk)) return false;
        unindex(r);
        Registration moved = r.withKey(nk);
        store(moved);
        index(moved);
        return true;
    }

    /**
     * Startup recovery: settle PENDING rows. If the item really carries the exotic the
     * transfer/claim completed (-> ACTIVE); otherwise it is aborted. Returns {confirmed, aborted}.
     */
    public synchronized int[] reconcilePending(Predicate<Registration> itemHasExotic) {
        int confirmed = 0;
        int aborted = 0;
        List<Registration> abortedRows = new ArrayList<>();
        for (Registration r : new ArrayList<>(byId.values())) {
            if (r.status() != Status.PENDING) continue;
            if (itemHasExotic.test(r)) {
                confirm(r.id());
                confirmed++;
            } else {
                abort(r.id());
                abortedRows.add(r);
                aborted++;
            }
        }
        // An interrupted TRANSFER must give the exotic back to the row it replaced. Only that exact row:
        // older revoked rows are history and must never be resurrected.
        for (Registration gone : abortedRows) {
            if (gone.replaces() == 0L) continue;                  // a plain claim has nothing to restore
            Registration prev = byId.get(gone.replaces());
            if (prev != null && prev.status() == Status.REVOKED && !liveByKey.containsKey(prev.key())
                    && itemHasExotic.test(prev)) {
                Registration restored = prev.withStatus(Status.ACTIVE);
                store(restored);
                index(restored);
            }
        }
        return new int[]{confirmed, aborted};
    }

    // ------------------------------------------------------------------- reads

    public synchronized boolean isKeyLive(ExoticKey key) {
        return liveByKey.containsKey(key);
    }

    /** True only if this item UUID matches an ACTIVE registration (else strip it lazily). */
    public synchronized boolean isValidItem(UUID itemUuid) {
        Long id = liveByItem.get(itemUuid);
        return id != null && byId.get(id).status() == Status.ACTIVE;
    }

    public synchronized Optional<Registration> activeByKey(ExoticKey key) {
        Long id = liveByKey.get(key);
        return id == null ? Optional.empty() : Optional.of(byId.get(id)).filter(r -> r.status() == Status.ACTIVE);
    }

    public synchronized Optional<Registration> byItem(UUID itemUuid) {
        Long id = liveByItem.get(itemUuid);
        return id == null ? Optional.empty() : Optional.of(byId.get(id));
    }

    public synchronized Optional<Registration> get(long id) {
        return Optional.ofNullable(byId.get(id));
    }

    public synchronized List<Registration> activeByMaterial(String material) {
        String m = material.toUpperCase();
        List<Registration> out = new ArrayList<>();
        for (Registration r : byId.values()) {
            if (r.status() == Status.ACTIVE && r.key().material().equals(m)) out.add(r);
        }
        return out;
    }

    public synchronized List<Registration> ownedBy(UUID owner) {
        List<Registration> out = new ArrayList<>();
        for (Registration r : byId.values()) {
            if (r.status() == Status.ACTIVE && r.owner().equals(owner)) out.add(r);
        }
        return out;
    }

    public synchronized List<Registration> all() {
        return new ArrayList<>(byId.values());
    }

    public synchronized List<String> anomalies() {
        return new ArrayList<>(anomalies);
    }

    /** Recomputes the invariants from scratch. Empty list = healthy. */
    public synchronized List<String> checkInvariants() {
        List<String> problems = new ArrayList<>();
        Map<ExoticKey, Integer> keys = new HashMap<>();
        Map<UUID, Integer> items = new HashMap<>();
        for (Registration r : byId.values()) {
            if (!r.status().isLive()) continue;
            if (keys.merge(r.key(), 1, Integer::sum) > 1) problems.add("duplicate live key " + r.key());
            if (items.merge(r.itemUuid(), 1, Integer::sum) > 1) problems.add("duplicate live item " + r.itemUuid());
        }
        return problems;
    }

    // ---------------------------------------------------------------- internals

    /** Durable write first, memory second: a failed DB write leaves memory untouched. */
    private void store(Registration r) {
        storage.save(r);
        byId.put(r.id(), r);
    }

    private void index(Registration r) {
        liveByKey.put(r.key(), r.id());
        liveByItem.put(r.itemUuid(), r.id());
    }

    private void unindex(Registration r) {
        liveByKey.remove(r.key());
        liveByItem.remove(r.itemUuid());
    }
}
