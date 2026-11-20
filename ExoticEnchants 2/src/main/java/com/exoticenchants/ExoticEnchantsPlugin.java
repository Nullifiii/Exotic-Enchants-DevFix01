package com.exoticenchants;

import com.exoticenchants.command.ExoticCommand;
import com.exoticenchants.config.Settings;
import com.exoticenchants.effects.EffectAudit;
import com.exoticenchants.effects.ExoticEffectsListener;
import com.exoticenchants.core.Registry;
import com.exoticenchants.core.Status;
import com.exoticenchants.hook.EconomyHook;
import com.exoticenchants.item.EnchantPool;
import com.exoticenchants.item.ItemTags;
import com.exoticenchants.item.RarityTable;
import com.exoticenchants.listener.EconomyListener;
import com.exoticenchants.listener.ExoticListener;
import com.exoticenchants.roll.RollScheduler;
import com.exoticenchants.service.AfkTracker;
import com.exoticenchants.service.ExoticService;
import com.exoticenchants.service.ItemGuard;
import com.exoticenchants.shop.ExoticShop;
import com.exoticenchants.storage.SqliteStorage;
import com.exoticenchants.util.AuditLog;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Objects;

/** Plugin entry point: wires everything together. */
public final class ExoticEnchantsPlugin extends JavaPlugin {

    private static ExoticEnchantsPlugin instance;

    private Settings settings;
    private AuditLog audit;
    private SqliteStorage storage;
    private Registry registry;
    private RarityTable rarity;
    private EnchantPool pool;
    private ItemTags tags;
    private EconomyHook economy;
    private AfkTracker afk;
    private ItemGuard guard;
    private ExoticService service;
    private ExoticShop shop;
    private RollScheduler scheduler;
    private ExoticEffectsListener effects;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        settings = new Settings(this);
        audit = new AuditLog(getDataFolder(), getLogger());

        try {
            storage = new SqliteStorage(new File(getDataFolder(), "exotics.db"));
        } catch (SQLException ex) {
            getLogger().severe("Cannot open the database, disabling: " + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        registry = new Registry(storage, Clock.systemUTC());
        for (String a : registry.anomalies()) getLogger().warning(a);
        // Crash recovery: an interrupted claim is aborted; an interrupted transfer returns to the previous owner.
        int[] rec = registry.reconcilePending(r -> r.status() == Status.REVOKED);
        if (rec[0] + rec[1] > 0) getLogger().warning("Recovered " + rec[0] + " confirmed / " + rec[1] + " aborted pending registrations.");

        rarity = new RarityTable(this);
        pool = new EnchantPool(settings, rarity);
        rarity.ensure(pool.allEnchants());
        tags = new ItemTags(this);

        economy = new EconomyHook(getLogger());
        // Economy providers may register after us: try now, again one tick after startup, and whenever one registers.
        economy.setup(getServer());
        Bukkit.getScheduler().runTask(this, () -> {
            economy.ensure(getServer());
            if (economy.available()) {
                getLogger().info("Economy connected: " + economy.providerName());
            } else {
                getLogger().warning("Shop and enchantment removal are DISABLED until an economy is found: " + economy.problem());
            }
        });

        afk = new AfkTracker(settings);
        guard = new ItemGuard(registry, tags, audit);
        service = new ExoticService(settings, registry, storage, tags, pool, economy, audit);
        shop = new ExoticShop(settings, storage, pool, tags, economy, audit);
        scheduler = new RollScheduler(this, settings, registry, storage, tags, pool, afk, guard, audit);

        getServer().getPluginManager().registerEvents(afk, this);
        getServer().getPluginManager().registerEvents(new ExoticListener(this, settings, registry, tags, guard, shop), this);
        getServer().getPluginManager().registerEvents(new EconomyListener(this, economy, getLogger()), this);
        effects = new ExoticEffectsListener(this, settings, registry, tags);
        getServer().getPluginManager().registerEvents(effects, this);
        Bukkit.getScheduler().runTaskTimer(this, () -> effects.sweep(false), 10L, 10L);
        writeEffectAudit();

        ExoticCommand command = new ExoticCommand(this);
        Objects.requireNonNull(getCommand("exotic"), "exotic command missing from plugin.yml").setExecutor(command);
        Objects.requireNonNull(getCommand("exotic")).setTabCompleter(command);

        for (Player p : Bukkit.getOnlinePlayers()) afk.touch(p);
        scheduler.start();
        long scanTicks = settings.scanSeconds * 20L;
        Bukkit.getScheduler().runTaskTimer(this, guard::scanAll, 60L, scanTicks);

        getLogger().info("ExoticEnchants enabled. Economy: " + (economy.available() ? "found" : "NOT found (shop/removal disabled)"));
    }

    @Override
    public void onDisable() {
        if (effects != null) effects.sweep(true);          // never leave frosted ice behind
        if (scheduler != null) scheduler.stop();
        if (storage != null) storage.close();
        instance = null;
    }

    /** /exotic admin reload */
    public void reloadAll() {
        settings.reload();
        rarity.reload();
        rarity.ensure(pool.allEnchants());
        scheduler.rebuildEngine();
        economy.setup(getServer());
        writeEffectAudit();
    }

    private void writeEffectAudit() {
        int[] t = EffectAudit.write(new File(getDataFolder(), "effects-audit.txt"));
        getLogger().info("Effect audit written to effects-audit.txt (native=" + t[0] + ", implemented by plugin=" + t[1]
                + ", no effect/never offered=" + t[2] + " enchantment/item combinations).");
    }

    public static ExoticEnchantsPlugin get() { return instance; }
    public Settings settings() { return settings; }
    public AuditLog audit() { return audit; }
    public Registry registry() { return registry; }
    public EnchantPool pool() { return pool; }
    public ItemTags tags() { return tags; }
    public ExoticService service() { return service; }
    public ExoticShop shop() { return shop; }
    public EconomyHook economy() { return economy; }
    public RollScheduler scheduler() { return scheduler; }
}
