package com.exoticenchants.service;

import com.exoticenchants.config.Settings;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * AFK = no REAL movement for the configured time. Turning the camera or jumping in place does
 * not count: only a horizontal move of at least one block away from the last anchor does.
 */
public final class AfkTracker implements Listener {

    private final Settings settings;
    private final Map<UUID, Long> lastMove = new HashMap<>();
    private final Map<UUID, Location> anchor = new HashMap<>();

    public AfkTracker(Settings settings) {
        this.settings = settings;
    }

    public boolean isAfk(Player player) {
        Long t = lastMove.get(player.getUniqueId());
        return t == null || System.currentTimeMillis() - t > settings.afkMillis;
    }

    public long idleMillis(Player player) {
        Long t = lastMove.get(player.getUniqueId());
        return t == null ? Long.MAX_VALUE : System.currentTimeMillis() - t;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        touch(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastMove.remove(e.getPlayer().getUniqueId());
        anchor.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location to = e.getTo();
        if (to == null) return;
        Player p = e.getPlayer();
        if (settings.ignorePassive && p.isInsideVehicle()) return;
        Location a = anchor.get(p.getUniqueId());
        if (a == null || a.getWorld() != to.getWorld()) {
            touch(p);
            return;
        }
        double dx = to.getX() - a.getX();
        double dz = to.getZ() - a.getZ();
        if (dx * dx + dz * dz >= 1.0) touch(p);
    }

    /** Called for players already online when the plugin (re)loads. */
    public void touch(Player p) {
        lastMove.put(p.getUniqueId(), System.currentTimeMillis());
        anchor.put(p.getUniqueId(), p.getLocation().clone());
    }
}
