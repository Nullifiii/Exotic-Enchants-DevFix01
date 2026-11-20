package com.killstreakeffects.antifarm;

import com.killstreakeffects.KillstreakEffectsPlugin;
import com.killstreakeffects.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Anti-farming checks for kill rewards.
 *
 * The five original checks and their order are unchanged (same-IP, minimum victim alive time,
 * victim cooldown, same-victim window limit, pair window limit). What changed is the same-IP
 * check, which used to treat ANY two players with an identical connection address as cheaters.
 * Behind a tunnel or proxy (playit.gg, Aternos, ngrok, a hosting panel...) every player shows
 * the same address, so every kill was rejected. Now:
 *  - private / loopback / configured proxy addresses, and addresses shared by several online
 *    players at once, are "unreliable" and are NOT treated as the same household;
 *  - for a reliable shared address the action is configurable (LIMIT by default, BLOCK, ALLOW);
 *  - every rejection is logged with its reason (anti-farm.log-rejections).
 */
public final class AntiFarmManager {

    private record Pair(UUID first, UUID second) {
        static Pair unordered(UUID a, UUID b) {
            return a.compareTo(b) <= 0 ? new Pair(a, b) : new Pair(b, a);
        }
    }

    /** What to do when two players really do share a trustworthy address. */
    private enum SharedAction { BLOCK, LIMIT, ALLOW }

    private static final String MSG_SAME_IP = "You cannot earn a kill reward for killing someone on the same IP address.";
    private static final String MSG_LIMITED = "That player appears to share your connection address, so kill rewards "
            + "between you are limited. Try again later.";
    private static final long NOTE_INTERVAL_MS = 30 * 60 * 1000L;

    private final ConfigManager config;
    private final Map<UUID, Long> aliveSince = new HashMap<>();
    private final Map<Pair, Long> lastVictimKill = new HashMap<>();
    private final Map<Pair, ArrayDeque<Long>> victimKills = new HashMap<>();
    private final Map<Pair, ArrayDeque<Long>> pairKills = new HashMap<>();
    private final Map<String, Long> lastNote = new HashMap<>();

    private AddressRules cachedRules;
    private String cachedRulesKey;

    public AntiFarmManager(ConfigManager config) {
        this.config = config;
    }

    public void markAlive(Player player) {
        aliveSince.put(player.getUniqueId(), System.currentTimeMillis());
    }

    /**
     * @return null if the kill may be rewarded (and records it), otherwise the message to show the killer
     */
    public String checkAndRecord(Player killer, Player victim) {
        long now = System.currentTimeMillis();
        UUID killerId = killer.getUniqueId();
        UUID victimId = victim.getUniqueId();

        if (config.antiFarmEnabled("same-ip")) {
            String verdict = addressVerdict(killer, victim, now);
            if (verdict != null) return deny(killer, victim, "SAME_ADDRESS", verdict);
        }

        if (config.antiFarmEnabled("minimum-victim-alive-time")) {
            long aliveSinceMs = aliveSince.getOrDefault(victimId, now);
            if (now - aliveSinceMs < (long) config.minimumVictimAliveSeconds() * 1000L) {
                return deny(killer, victim, "VICTIM_NOT_ALIVE_LONG_ENOUGH",
                        "That player has not been alive long enough for a kill reward.");
            }
        }

        Pair directed = new Pair(killerId, victimId);
        long cooldownMs = (long) config.victimCooldownSeconds() * 1000L;
        Long lastKill = lastVictimKill.get(directed);
        if (config.antiFarmEnabled("victim-cooldown") && lastKill != null && now - lastKill < cooldownMs) {
            return deny(killer, victim, "VICTIM_COOLDOWN",
                    "You must wait before earning another reward for killing that player.");
        }

        ArrayDeque<Long> victimWindow = victimKills.computeIfAbsent(directed, k -> new ArrayDeque<>());
        prune(victimWindow, now - (long) config.sameVictimWindowSeconds() * 1000L);
        if (config.antiFarmEnabled("same-victim-window-limit") && victimWindow.size() >= config.maxSameVictimKills()) {
            return deny(killer, victim, "SAME_VICTIM_WINDOW_LIMIT",
                    "You have reached the kill reward limit for that player in this time window.");
        }

        Pair unordered = Pair.unordered(killerId, victimId);
        ArrayDeque<Long> pairWindow = pairKills.computeIfAbsent(unordered, k -> new ArrayDeque<>());
        prune(pairWindow, now - (long) config.pairWindowSeconds() * 1000L);
        if (config.antiFarmEnabled("pair-window-limit") && pairWindow.size() >= config.maxPairKills()) {
            return deny(killer, victim, "PAIR_WINDOW_LIMIT",
                    "You and that player have reached the pair kill reward limit for this time window.");
        }

        lastVictimKill.put(directed, now);
        victimWindow.addLast(now);
        pairWindow.addLast(now);
        return null;
    }

    // ------------------------------------------------------------------ address check

    /** @return null if the kill passes the address check, otherwise the message to show */
    private String addressVerdict(Player killer, Player victim, long now) {
        InetSocketAddress a = killer.getAddress();
        InetSocketAddress b = victim.getAddress();
        if (a == null || b == null) return null;            // not connected yet / unknown: cannot compare
        InetAddress x = a.getAddress();
        InetAddress y = b.getAddress();
        if (x == null || y == null || !x.equals(y)) return null;   // unresolved or genuinely different

        IpSettings s = readSettings();
        String pairLabel = killer.getName() + " & " + victim.getName() + " (" + x.getHostAddress() + ")";

        if (s.rules.isUnreliable(x)) {
            note("unreliable:" + x.getHostAddress(), now,
                    "Address check skipped for " + pairLabel + ": this is a private/loopback/ignored address, so "
                            + "it cannot identify one household. (If ALL your players show this address, your server "
                            + "is behind a tunnel or proxy without real-IP forwarding.)");
            return null;
        }

        int sharing = 0;
        for (Player online : Bukkit.getOnlinePlayers()) {
            InetSocketAddress oa = online.getAddress();
            if (oa != null && x.equals(oa.getAddress())) sharing++;
        }
        if (sharing >= s.proxyMinPlayers) {
            note("proxy:" + x.getHostAddress(), now,
                    "WARNING: " + sharing + " online players share the address " + x.getHostAddress()
                            + ". This looks like a tunnel/proxy hiding real player IPs, so the address check is skipped "
                            + "for it. To silence this and always skip it, add \"" + x.getHostAddress()
                            + "\" to anti-farm.ip-check.ignored-addresses, or enable real-IP forwarding "
                            + "(Velocity modern forwarding, BungeeCord ip_forward, or your tunnel's proxy-protocol option).");
            return null;
        }

        switch (s.action) {
            case ALLOW -> {
                note("allow:" + x.getHostAddress(), now, "Shared address " + pairLabel + " allowed (on-shared-address: ALLOW).");
                return null;
            }
            case BLOCK -> {
                note("block:" + x.getHostAddress(), now, "Shared address " + pairLabel + " blocked (on-shared-address: BLOCK). "
                        + "If these are two different people behind a tunnel/proxy, add " + x.getHostAddress()
                        + " to anti-farm.ip-check.ignored-addresses.");
                return MSG_SAME_IP;
            }
            default -> {
                note("limit:" + x.getHostAddress(), now, "Shared address " + pairLabel + ": rewards limited to "
                        + s.sharedMaxPairKills + " per window with a " + s.sharedCooldownSeconds + "s cooldown. If these are "
                        + "different people behind a tunnel/proxy, add " + x.getHostAddress()
                        + " to anti-farm.ip-check.ignored-addresses for full rewards.");
                Long last = lastVictimKill.get(new Pair(killer.getUniqueId(), victim.getUniqueId()));
                if (last != null && now - last < s.sharedCooldownSeconds * 1000L) return MSG_LIMITED;
                ArrayDeque<Long> window = pairKills.computeIfAbsent(
                        Pair.unordered(killer.getUniqueId(), victim.getUniqueId()), k -> new ArrayDeque<>());
                prune(window, now - (long) config.pairWindowSeconds() * 1000L);
                if (window.size() >= s.sharedMaxPairKills) return MSG_LIMITED;
                return null;
            }
        }
    }

    // ----------------------------------------------------------------------- helpers

    private record IpSettings(AddressRules rules, SharedAction action, int proxyMinPlayers,
                              int sharedCooldownSeconds, int sharedMaxPairKills, boolean logRejections) {
    }

    private IpSettings readSettings() {
        boolean ignorePrivate = true;
        List<String> ignored = List.of();
        SharedAction action = SharedAction.LIMIT;
        int proxyMin = 3;
        int cooldown = 600;
        int maxPair = 2;
        boolean log = true;
        try {
            FileConfiguration c = JavaPlugin.getPlugin(KillstreakEffectsPlugin.class).getConfig();
            ignorePrivate = c.getBoolean("anti-farm.ip-check.ignore-private-addresses", true);
            ignored = c.getStringList("anti-farm.ip-check.ignored-addresses");
            String raw = c.getString("anti-farm.ip-check.on-shared-address", "LIMIT");
            try {
                action = SharedAction.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                note("badaction", System.currentTimeMillis(), "anti-farm.ip-check.on-shared-address must be BLOCK, LIMIT or "
                        + "ALLOW (got \"" + raw + "\"); using LIMIT.");
            }
            proxyMin = Math.max(3, c.getInt("anti-farm.ip-check.proxy-detection-min-players", 3));
            cooldown = Math.max(0, c.getInt("anti-farm.ip-check.shared-address-cooldown-seconds", 600));
            maxPair = Math.max(1, c.getInt("anti-farm.ip-check.shared-address-max-pair-kills", 2));
            log = c.getBoolean("anti-farm.log-rejections", true);
        } catch (RuntimeException ex) {
            note("cfgfail", System.currentTimeMillis(), "Could not read anti-farm.ip-check settings, using defaults: " + ex);
        }
        String key = ignorePrivate + "|" + ignored;
        if (cachedRules == null || !key.equals(cachedRulesKey)) {
            cachedRules = new AddressRules(ignorePrivate, ignored);
            cachedRulesKey = key;
            for (String bad : cachedRules.invalidEntries()) {
                logger().warning("[AntiFarm] Ignoring invalid entry in anti-farm.ip-check.ignored-addresses: \"" + bad
                        + "\" (use an IP like 203.0.113.7 or a CIDR like 203.0.113.0/24).");
            }
        }
        return new IpSettings(cachedRules, action, proxyMin, cooldown, maxPair, log);
    }

    private String deny(Player killer, Player victim, String code, String message) {
        if (readLogSetting()) {
            logger().info("[AntiFarm] No kill reward: killer=" + killer.getName() + " victim=" + victim.getName()
                    + " reason=" + code);
        }
        return message;
    }

    private boolean readLogSetting() {
        try {
            return JavaPlugin.getPlugin(KillstreakEffectsPlugin.class).getConfig().getBoolean("anti-farm.log-rejections", true);
        } catch (RuntimeException ex) {
            return true;
        }
    }

    /** Console diagnostics, at most once per key every 30 minutes so chat/console never gets spammed. */
    private void note(String key, long now, String text) {
        Long last = lastNote.get(key);
        if (last != null && now - last < NOTE_INTERVAL_MS) return;
        lastNote.put(key, now);
        logger().warning("[AntiFarm] " + text);
    }

    private Logger logger() {
        try {
            return JavaPlugin.getPlugin(KillstreakEffectsPlugin.class).getLogger();
        } catch (RuntimeException ex) {
            return Logger.getLogger("KillstreakEffects");
        }
    }

    private void prune(ArrayDeque<Long> window, long cutoff) {
        while (!window.isEmpty() && window.peekFirst() <= cutoff) {
            window.removeFirst();
        }
    }
}
