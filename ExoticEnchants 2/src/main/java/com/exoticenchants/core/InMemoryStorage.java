package com.exoticenchants.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Simple storage used by tests; survives "restarts" if the same instance is reused. */
public final class InMemoryStorage implements RegistryStorage {
    private final Map<Long, Registration> rows = new ConcurrentHashMap<>();

    @Override
    public List<Registration> loadAll() {
        return new ArrayList<>(rows.values());
    }

    @Override
    public void save(Registration r) {
        rows.put(r.id(), r);
    }
}
