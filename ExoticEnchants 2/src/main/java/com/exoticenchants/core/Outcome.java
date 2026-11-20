package com.exoticenchants.core;

import java.util.UUID;

/**
 * Result for one participant. For CLAIMED/STOLEN the registration is PENDING: the caller must
 * apply the item change on the main thread, then call registry.confirm(id) - or abort(id) /
 * rollbackTransfer() if it fails.
 */
public record Outcome(UUID player, Type type, Registration registration, UUID previousOwner, String note) {

    public enum Type { CLAIMED, STOLEN, SKIPPED }

    static Outcome claimed(UUID p, Registration r) {
        return new Outcome(p, Type.CLAIMED, r, null, "");
    }

    static Outcome stolen(UUID p, Registration r, UUID from) {
        return new Outcome(p, Type.STOLEN, r, from, "");
    }

    static Outcome skipped(UUID p, String why) {
        return new Outcome(p, Type.SKIPPED, null, null, why);
    }
}
