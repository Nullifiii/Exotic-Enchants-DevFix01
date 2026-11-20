package com.exoticenchants.core;

/** Lifecycle of a registration. PENDING/ACTIVE/DISPUTED are "live": they block the key. */
public enum Status {
    PENDING, ACTIVE, REVOKED, RELEASED, DISPUTED;

    public boolean isLive() {
        return this == PENDING || this == ACTIVE || this == DISPUTED;
    }
}
