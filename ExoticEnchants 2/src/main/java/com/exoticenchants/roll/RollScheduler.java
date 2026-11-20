package com.exoticenchants.roll;

import com.exoticenchants.ExoticEnchantsPlugin;
import com.exoticenchants.config.Settings;
import com.exoticenchants.core.Candidate;
import com.exoticenchants.core.Outcome;
import com.exoticenchants.core.Participant;
import com.exoticenchants.core.Registration;
import com.exoticenchants.core.Registry;
import com.exoticenchants.core.RollEngine;
import com.exoticenchants.core.RollKind;
import com.exoticenchants.item.EnchantPool;
import com.exoticenchants.item.ItemTags;
import com.exoticenchants.service.AfkTracker;
import com.exoticenchants.service.ItemGuard;
import com.exoticenchants.storage.SqliteStorage;
import com.exoticenchants.util.AuditLog;
import com.exoticenchants.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The persisted hourly timer. Warns at 5 and 1 minutes, then runs one round on the main thread:
 * the engine reserves registry rows, this class applies the item changes and confirms (or rolls
 * back) each one. A restart never double-runs an hour: the next run time is stored in the DB.
 */
public final class RollScheduler {

    private static final String META_NEXT = "next_roll_at";

    private final ExoticEnchantsPlugin plugin;
    private final Settings settings;
    private final Registry registry;
    private final SqliteStorage storage;
    private final ItemTags tags;
    private final EnchantPool pool;
    private final AfkTracker afk;
    private final ItemGuard guard;
    private final AuditLog audit;

    private RollEngine engine;
    private BukkitTask task;
    private long nextRunAt;
    private boolean warned5;
    private boolean warned1;

    public RollScheduler(ExoticEnchantsPlugin plugin, Settings settings, Registry registry, SqliteStorage storage,
                         ItemTags tags, EnchantPool pool, AfkTracker afk, ItemGuard guard, AuditLog audit) {
        this.plugin = plugin;
        this.settings = settings;
        this.registry = registry;
        this.storage = storage;
        this.tags = tags;
        this.pool = pool;
        this.afk = afk;
        this.guard = guard;
        this.audit = audit;
        rebuildEngine();
    }

    public void rebuildEngine() {
        this.engine = new RollEngine(registry, settings.toRollConfig(), new java.util.Random(), java.time.Clock.systemUTC());
    }

    public RollEngine engine() {
        return engine;
    }

    public long millisUntilNext() {
        return Math.max(0, nextRunAt - System.currentTimeMillis());
    }

    public void start() {
        long now = System.currentTimeMillis();
        String stored = storage.getMeta(META_NEXT);
        long next = now + settings.intervalMillis;
        if (stored != null) {
            try {
                long s = Long.parseLong(stored);
                // Still in the future (and not absurdly far): keep it. If it passed while the server was
                // down we skip that missed hour instead of running twice.
                if (s > now && s - now <= settings.intervalMillis) next = s;
            } catch (NumberFormatException ignored) {
                // use default
            }
        }
        setNext(next);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) task.cancel();
    }

    private void setNext(long when) {
        nextRunAt = when;
        warned5 = nextRunAt - System.currentTimeMillis() <= 5 * 60_000L;
        warned1 = nextRunAt - System.currentTimeMillis() <= 60_000L;
        storage.setMeta(META_NEXT, Long.toString(nextRunAt));
    }

    private void tick() {
        long left = nextRunAt - System.currentTimeMillis();
        if (left <= 0) {
            setNext(System.currentTimeMillis() + settings.intervalMillis);
            runRound();
            return;
        }
        if (!warned5 && left <= 5 * 60_000L) {
            warned5 = true;
            Bukkit.broadcast(Msg.info("Hourly exotic roll in 5 minutes! Hold the item you want enchanted and keep moving (not AFK)."));
        }
        if (!warned1 && left <= 60_000L) {
            warned1 = true;
            Bukkit.broadcast(Msg.info("Hourly exotic roll in 1 minute!"));
        }
    }

    /** Runs a full round right now (also used by /exotic admin rollnow). */
    public void runRound() {
        RollKind kind = engine.rollKind();
        List<Participant> participants = new ArrayList<>();
        Set<UUID> online = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            online.add(p.getUniqueId());
            if (afk.isAfk(p)) continue;
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand.getType().isAir() || !settings.isEligibleMaterial(hand.getType().name())) continue;
            List<Candidate> candidates = pool.build(hand);
            participants.add(new Participant(p.getUniqueId(), hand.getType().name(), tags.isExotic(hand), candidates));
        }
        if (settings.announce) {
            Bukkit.broadcast(Msg.prefixed(Component.text(kind == RollKind.GREAT
                    ? "GREAT REROLL! Owned exotics can change hands this hour."
                    : "Hourly exotic roll!", kind == RollKind.GREAT ? NamedTextColor.GOLD : NamedTextColor.AQUA)));
        }
        audit.log("ROUND " + kind + " participants=" + participants.size());

        for (Outcome o : engine.runRound(kind, participants, online)) {
            if (o.type() == Outcome.Type.SKIPPED) continue;
            apply(o);
        }
    }

    private void apply(Outcome o) {
        Registration reg = o.registration();
        Player p = Bukkit.getPlayer(o.player());
        boolean stolen = o.type() == Outcome.Type.STOLEN;
        try {
            if (p == null) throw new IllegalStateException("player left");
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand.getType().isAir() || !hand.getType().name().equals(reg.key().material()) || tags.isExotic(hand)) {
                throw new IllegalStateException("main-hand item changed");
            }
            Enchantment ench = EnchantPool.lookup(reg.key().enchant());
            if (ench == null) throw new IllegalStateException("unknown enchantment");
            ItemStack updated = hand.clone();
            tags.applyExotic(updated, ench, reg.key().level(), reg.itemUuid(), reg.id(), reg.rarity());
            p.getInventory().setItemInMainHand(updated);
            registry.confirm(reg.id());
        } catch (RuntimeException ex) {
            if (stolen) registry.rollbackTransferByCreated(reg.id());
            else registry.abort(reg.id());
            audit.log("ROLL FAILED reg#" + reg.id() + " " + reg.key() + ": " + ex.getMessage());
            return;
        }

        String what = Msg.titleCase(reg.key().enchant()) + " " + Msg.roman(reg.key().level()) + " on "
                + Msg.titleCase(reg.key().material()) + " (" + Msg.titleCase(reg.rarity().name()) + ")";
        audit.log((stolen ? "STEAL " : "CLAIM ") + "reg#" + reg.id() + " " + reg.key() + " -> " + p.getName()
                + (stolen ? " from " + o.previousOwner() : ""));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        p.showTitle(Title.title(Component.text("Exotic!", Msg.rarityColor(reg.rarity())), Component.text(what)));

        if (stolen) {
            String oldName = nameOf(o.previousOwner());
            if (settings.announce) {
                Bukkit.broadcast(Msg.prefixed(Component.text(p.getName() + " took " + what + " from " + oldName + "!",
                        Msg.rarityColor(reg.rarity()))));
            }
            Player victim = Bukkit.getPlayer(o.previousOwner());
            if (victim != null) {
                guard.scanPlayer(victim);       // strip the now-revoked item immediately
                victim.showTitle(Title.title(Component.text("Exotic lost!", NamedTextColor.RED),
                        Component.text(Msg.titleCase(reg.key().enchant()) + " " + Msg.roman(reg.key().level())
                                + " was taken by " + p.getName())));
            }
        } else if (settings.announce) {
            Bukkit.broadcast(Msg.prefixed(Component.text(p.getName() + " received " + what, Msg.rarityColor(reg.rarity()))));
        }
    }

    private static String nameOf(UUID id) {
        if (id == null) return "someone";
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? id.toString().substring(0, 8) : n;
    }
}
