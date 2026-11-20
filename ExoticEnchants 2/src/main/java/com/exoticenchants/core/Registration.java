package com.exoticenchants.core;

import java.time.Instant;
import java.util.UUID;

/** Immutable registry row. {@code replaces} is the id of the row a transfer took over from (0 = none). */
public record Registration(long id, ExoticKey key, UUID itemUuid, UUID owner, Rarity rarity,
                           Status status, Instant registeredAt, Instant ownedSince, long replaces) {

    public Registration withStatus(Status s) {
        return new Registration(id, key, itemUuid, owner, rarity, s, registeredAt, ownedSince, replaces);
    }

    public Registration withOwner(UUID o, Instant since) {
        return new Registration(id, key, itemUuid, o, rarity, status, registeredAt, since, replaces);
    }

    public Registration withKey(ExoticKey k) {
        return new Registration(id, k, itemUuid, owner, rarity, status, registeredAt, ownedSince, replaces);
    }
}
