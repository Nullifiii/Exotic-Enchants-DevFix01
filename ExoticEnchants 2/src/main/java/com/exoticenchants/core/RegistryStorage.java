package com.exoticenchants.core;

import java.util.List;

/** Persistence port. The plugin supplies a SQLite adapter; tests use {@link InMemoryStorage}. */
public interface RegistryStorage {
    List<Registration> loadAll();

    /** Insert or update one row, durably, before returning. */
    void save(Registration registration);
}
