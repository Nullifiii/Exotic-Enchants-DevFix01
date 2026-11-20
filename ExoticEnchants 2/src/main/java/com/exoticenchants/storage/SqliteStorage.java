package com.exoticenchants.storage;

import com.exoticenchants.core.ExoticKey;
import com.exoticenchants.core.Rarity;
import com.exoticenchants.core.Registration;
import com.exoticenchants.core.RegistryStorage;
import com.exoticenchants.core.Status;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SQLite persistence (registry rows, key/value metadata, used book ids, shop stock).
 * Writes are synchronous with synchronous=FULL so a crash can lose at most the write in
 * progress. Partial unique indexes make the database itself refuse a second live
 * registration for one key or one item, even if the Java code had a bug.
 */
public final class SqliteStorage implements RegistryStorage, AutoCloseable {

    /** One shop slot. */
    public record ShopEntry(int slot, String enchant, int level, Rarity rarity, double price, int remaining) {
    }

    private final Connection conn;

    public SqliteStorage(File file) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ex) {
            throw new SQLException("SQLite driver not found (is the server able to download libraries?)", ex);
        }
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new SQLException("Cannot create " + parent);
        }
        conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=FULL");
            st.execute("CREATE TABLE IF NOT EXISTS registrations ("
                    + "id INTEGER PRIMARY KEY, enchant TEXT NOT NULL, level INTEGER NOT NULL, "
                    + "material TEXT NOT NULL, item_uuid TEXT NOT NULL, owner_uuid TEXT NOT NULL, "
                    + "rarity TEXT NOT NULL, status TEXT NOT NULL, registered_at INTEGER NOT NULL, "
                    + "owned_since INTEGER NOT NULL, replaces INTEGER NOT NULL DEFAULT 0)");
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_live_key ON registrations(enchant, level, material) "
                    + "WHERE status IN ('PENDING','ACTIVE','DISPUTED')");
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_live_item ON registrations(item_uuid) "
                    + "WHERE status IN ('PENDING','ACTIVE','DISPUTED')");
            st.execute("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS used_books (id TEXT PRIMARY KEY)");
            st.execute("CREATE TABLE IF NOT EXISTS shop_stock (slot INTEGER PRIMARY KEY, enchant TEXT NOT NULL, "
                    + "level INTEGER NOT NULL, rarity TEXT NOT NULL, price REAL NOT NULL, remaining INTEGER NOT NULL)");
        }
    }

    // ------------------------------------------------------ RegistryStorage

    @Override
    public synchronized List<Registration> loadAll() {
        List<Registration> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, enchant, level, material, item_uuid, owner_uuid, rarity, "
                     + "status, registered_at, owned_since, replaces FROM registrations")) {
            while (rs.next()) {
                out.add(new Registration(rs.getLong(1), new ExoticKey(rs.getString(2), rs.getInt(3), rs.getString(4)),
                        UUID.fromString(rs.getString(5)), UUID.fromString(rs.getString(6)),
                        Rarity.valueOf(rs.getString(7)), Status.valueOf(rs.getString(8)),
                        Instant.ofEpochMilli(rs.getLong(9)), Instant.ofEpochMilli(rs.getLong(10)), rs.getLong(11)));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not load registry", ex);
        }
        return out;
    }

    @Override
    public synchronized void save(Registration r) {
        String sql = "INSERT INTO registrations (id, enchant, level, material, item_uuid, owner_uuid, rarity, status, "
                + "registered_at, owned_since, replaces) VALUES (?,?,?,?,?,?,?,?,?,?,?) "
                + "ON CONFLICT(id) DO UPDATE SET enchant=excluded.enchant, level=excluded.level, "
                + "material=excluded.material, item_uuid=excluded.item_uuid, owner_uuid=excluded.owner_uuid, "
                + "rarity=excluded.rarity, status=excluded.status, registered_at=excluded.registered_at, "
                + "owned_since=excluded.owned_since, replaces=excluded.replaces";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, r.id());
            ps.setString(2, r.key().enchant());
            ps.setInt(3, r.key().level());
            ps.setString(4, r.key().material());
            ps.setString(5, r.itemUuid().toString());
            ps.setString(6, r.owner().toString());
            ps.setString(7, r.rarity().name());
            ps.setString(8, r.status().name());
            ps.setLong(9, r.registeredAt().toEpochMilli());
            ps.setLong(10, r.ownedSince().toEpochMilli());
            ps.setLong(11, r.replaces());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Registry write failed for #" + r.id() + ": " + ex.getMessage(), ex);
        }
    }

    // ---------------------------------------------------------------- meta

    public synchronized String getMeta(String key) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT v FROM meta WHERE k=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public synchronized void setMeta(String key, String value) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO meta (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v=excluded.v")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // --------------------------------------------------------------- books

    public synchronized boolean isBookUsed(String id) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM used_books WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** @return false if it was already marked (a reuse attempt) */
    public synchronized boolean markBookUsed(String id) {
        try (PreparedStatement ps = conn.prepareStatement("INSERT OR IGNORE INTO used_books (id) VALUES (?)")) {
            ps.setString(1, id);
            return ps.executeUpdate() == 1;
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ---------------------------------------------------------------- shop

    public synchronized List<ShopEntry> loadShop() {
        List<ShopEntry> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT slot, enchant, level, rarity, price, remaining FROM shop_stock ORDER BY slot")) {
            while (rs.next()) {
                out.add(new ShopEntry(rs.getInt(1), rs.getString(2), rs.getInt(3), Rarity.valueOf(rs.getString(4)),
                        rs.getDouble(5), rs.getInt(6)));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
        return out;
    }

    public synchronized void replaceShop(List<ShopEntry> entries) {
        try {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("DELETE FROM shop_stock");
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO shop_stock (slot, enchant, level, rarity, price, remaining) VALUES (?,?,?,?,?,?)")) {
                for (ShopEntry e : entries) {
                    ps.setInt(1, e.slot());
                    ps.setString(2, e.enchant());
                    ps.setInt(3, e.level());
                    ps.setString(4, e.rarity().name());
                    ps.setDouble(5, e.price());
                    ps.setInt(6, e.remaining());
                    ps.executeUpdate();
                }
            }
            conn.commit();
        } catch (SQLException ex) {
            try {
                conn.rollback();
            } catch (SQLException ignored) {
                // nothing more we can do
            }
            throw new IllegalStateException(ex);
        } finally {
            try {
                conn.setAutoCommit(true);
            } catch (SQLException ignored) {
                // ignore
            }
        }
    }

    public synchronized void updateShopStock(int slot, int remaining) {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE shop_stock SET remaining=? WHERE slot=?")) {
            ps.setInt(1, remaining);
            ps.setInt(2, slot);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Override
    public synchronized void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
            // shutting down
        }
    }
}
