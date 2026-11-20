package com.exoticenchants.shop;

import com.exoticenchants.config.Settings;
import com.exoticenchants.core.Rarity;
import com.exoticenchants.hook.EconomyHook;
import com.exoticenchants.item.EnchantPool;
import com.exoticenchants.item.ItemTags;
import com.exoticenchants.logic.Purchase;
import com.exoticenchants.storage.SqliteStorage;
import com.exoticenchants.storage.SqliteStorage.ShopEntry;
import com.exoticenchants.util.AuditLog;
import com.exoticenchants.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Global shared exotic-book shop. Stock is limited, rotates on a timer (unsold stock vanishes)
 * and is stored in the database. Buying a book does NOT register anything: registration happens
 * when the book is applied, so the shop can never bypass the global uniqueness registry.
 */
public final class ExoticShop {

    private static final String META_ROTATED = "shop_rotated_at";

    private final Settings settings;
    private final SqliteStorage storage;
    private final EnchantPool pool;
    private final ItemTags tags;
    private final EconomyHook economy;
    private final AuditLog audit;
    private final Random rnd = new Random();
    private List<ShopEntry> entries = new ArrayList<>();
    private final Map<UUID, Long> lastClick = new HashMap<>();
    private long rotatedAt;

    public ExoticShop(Settings settings, SqliteStorage storage, EnchantPool pool, ItemTags tags,
                      EconomyHook economy, AuditLog audit) {
        this.settings = settings;
        this.storage = storage;
        this.pool = pool;
        this.tags = tags;
        this.economy = economy;
        this.audit = audit;
        this.entries = storage.loadShop();
        String s = storage.getMeta(META_ROTATED);
        try {
            this.rotatedAt = s == null ? 0 : Long.parseLong(s);
        } catch (NumberFormatException ex) {
            this.rotatedAt = 0;
        }
    }

    public long millisUntilRotation() {
        return Math.max(0, rotatedAt + settings.shopRotationMillis - System.currentTimeMillis());
    }

    public synchronized void rotateIfNeeded() {
        long now = System.currentTimeMillis();
        if (!entries.isEmpty() && now - rotatedAt < settings.shopRotationMillis) return;
        rotate();
    }

    public synchronized void rotate() {
        List<Enchantment> all = pool.allEnchants();
        List<ShopEntry> fresh = new ArrayList<>();
        Set<String> used = new HashSet<>();
        int guard = 0;
        while (fresh.size() < settings.shopSlots && guard++ < 500 && !all.isEmpty()) {
            Enchantment e = all.get(rnd.nextInt(all.size()));
            int level = 1 + rnd.nextInt(e.getMaxLevel());
            String key = e.getKey().getKey();
            if (!used.add(key + ":" + level)) continue;
            Rarity r = pool.rarityTable().rarity(key, level);
            fresh.add(new ShopEntry(fresh.size(), key, level, r, settings.shopPrices.get(r), settings.shopStock));
        }
        Collections.sort(fresh, (a, b) -> Integer.compare(a.rarity().ordinal(), b.rarity().ordinal()));
        List<ShopEntry> renumbered = new ArrayList<>();
        for (int i = 0; i < fresh.size(); i++) {
            ShopEntry e = fresh.get(i);
            renumbered.add(new ShopEntry(i, e.enchant(), e.level(), e.rarity(), e.price(), e.remaining()));
        }
        entries = renumbered;
        rotatedAt = System.currentTimeMillis();
        storage.replaceShop(entries);
        storage.setMeta(META_ROTATED, Long.toString(rotatedAt));
        audit.log("SHOP rotated, " + entries.size() + " entries");
    }

    public void open(Player p) {
        if (!settings.shopEnabled) {
            p.sendMessage(Msg.error("The exotic shop is disabled."));
            return;
        }
        if (!economy.ensure(Bukkit.getServer())) {
            p.sendMessage(Msg.error("The exotic shop is unavailable: " + economy.problem()));
            return;
        }
        rotateIfNeeded();
        int size = ((entries.size() + 8) / 9) * 9;
        if (size < 9) size = 9;
        Inventory inv = ShopHolder.create(new ShopHolder(), size, Component.text("Exotic Book Shop"));
        for (ShopEntry e : entries) {
            if (e.slot() >= size) continue;
            inv.setItem(e.slot(), display(e));
        }
        p.openInventory(inv);
    }

    private ItemStack display(ShopEntry e) {
        ItemStack it = tags.createBook(e.enchant(), e.level(), e.rarity());
        ItemMeta meta = it.getItemMeta();
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Rarity: " + Msg.titleCase(e.rarity().name()), Msg.rarityColor(e.rarity()))
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Price: " + economy.format(e.price()), NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text(e.remaining() > 0 ? "In stock: " + e.remaining() : "SOLD OUT",
                e.remaining() > 0 ? NamedTextColor.GREEN : NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Click to buy", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Resets in " + Msg.duration(millisUntilRotation()), NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    /**
     * Purchase through {@link Purchase#execute}: stock, funds and inventory space are checked before any
     * money moves; the player is charged once; any failure afterwards restores the stock and refunds.
     */
    public synchronized void buy(Player p, int slot) {
        long now = System.currentTimeMillis();
        Long last = lastClick.get(p.getUniqueId());
        if (last != null && now - last < 400) return;           // ignore double clicks
        lastClick.put(p.getUniqueId(), now);

        if (!economy.ensure(Bukkit.getServer())) {
            p.sendMessage(Msg.error("The exotic shop is unavailable: " + economy.problem()));
            return;
        }
        rotateIfNeeded();
        ShopEntry entry = null;
        for (ShopEntry e : entries) if (e.slot() == slot) entry = e;
        if (entry == null) return;
        final ShopEntry bought = entry;
        final ShopEntry[] after = new ShopEntry[1];

        Purchase.Wallet wallet = new Purchase.Wallet() {
            @Override public boolean has(double amount) { return economy.has(p, amount); }
            @Override public boolean withdraw(double amount) { return economy.withdraw(p, amount); }
            @Override public boolean deposit(double amount) { return economy.deposit(p, amount); }
        };

        Purchase.Result result = Purchase.execute(wallet, bought.price(), bought.remaining(),
                p.getInventory().firstEmpty() != -1,
                () -> {
                    storage.updateShopStock(bought.slot(), bought.remaining() - 1);
                    after[0] = new ShopEntry(bought.slot(), bought.enchant(), bought.level(), bought.rarity(),
                            bought.price(), bought.remaining() - 1);
                    entries.set(entries.indexOf(bought), after[0]);
                },
                () -> {
                    storage.updateShopStock(bought.slot(), bought.remaining());
                    if (after[0] != null) entries.set(entries.indexOf(after[0]), bought);
                },
                () -> {
                    if (!p.getInventory().addItem(tags.createBook(bought.enchant(), bought.level(), bought.rarity())).isEmpty()) {
                        throw new IllegalStateException("inventory full");
                    }
                });

        String what = Msg.titleCase(bought.enchant()) + " " + Msg.roman(bought.level());
        switch (result) {
            case OK -> {
                audit.log("SHOP buy " + bought.enchant() + " " + bought.level() + " by " + p.getName() + " for " + bought.price());
                p.sendMessage(Msg.ok("Bought Exotic Book: " + what + ". Apply it with /exotic apply."));
                Inventory top = p.getOpenInventory().getTopInventory();
                if (top.getHolder() instanceof ShopHolder && after[0] != null) top.setItem(bought.slot(), display(after[0]));
            }
            case SOLD_OUT -> p.sendMessage(Msg.error("Sold out."));
            case NO_SPACE -> p.sendMessage(Msg.error("Your inventory is full. You were not charged."));
            case INSUFFICIENT_FUNDS -> p.sendMessage(Msg.error("You need " + economy.format(bought.price())
                    + " to buy this. You were not charged."));
            case PAYMENT_FAILED -> p.sendMessage(Msg.error("The economy refused the payment. You were not charged."));
            case DELIVERY_FAILED_REFUNDED -> {
                audit.log("SHOP delivery failed, REFUNDED " + bought.price() + " to " + p.getName());
                p.sendMessage(Msg.error("Something went wrong delivering the book. You were refunded."));
            }
            case DELIVERY_FAILED_REFUND_FAILED -> {
                audit.log("SHOP SEVERE: delivery failed AND refund failed for " + p.getName() + " price " + bought.price());
                Bukkit.getLogger().severe("[ExoticEnchants] Refund of " + bought.price() + " to " + p.getName()
                        + " FAILED after a failed shop delivery. Refund them manually.");
                p.sendMessage(Msg.error("Something went wrong and the automatic refund failed. Please tell an admin right now."));
            }
        }
    }

    public List<ShopEntry> entries() {
        return new ArrayList<>(entries);
    }
}
