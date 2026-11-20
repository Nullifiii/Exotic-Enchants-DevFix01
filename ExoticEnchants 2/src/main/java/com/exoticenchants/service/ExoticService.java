package com.exoticenchants.service;

import com.exoticenchants.config.Settings;
import com.exoticenchants.core.Candidate;
import com.exoticenchants.core.ExoticKey;
import com.exoticenchants.core.Rarity;
import com.exoticenchants.core.Registration;
import com.exoticenchants.core.Registry;
import com.exoticenchants.core.RollEngine;
import com.exoticenchants.hook.EconomyHook;
import com.exoticenchants.item.EnchantPool;
import com.exoticenchants.item.ItemTags;
import com.exoticenchants.storage.SqliteStorage;
import com.exoticenchants.util.AuditLog;
import com.exoticenchants.util.Msg;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Player-facing operations that must be atomic: applying a book, removing an exotic, creating
 * books. Every method returns null on success or a human-readable error (nothing is lost on error).
 */
public final class ExoticService {

    private final Settings settings;
    private final Registry registry;
    private final SqliteStorage storage;
    private final ItemTags tags;
    private final EnchantPool pool;
    private final EconomyHook economy;
    private final AuditLog audit;

    public ExoticService(Settings settings, Registry registry, SqliteStorage storage, ItemTags tags,
                         EnchantPool pool, EconomyHook economy, AuditLog audit) {
        this.settings = settings;
        this.registry = registry;
        this.storage = storage;
        this.tags = tags;
        this.pool = pool;
        this.economy = economy;
        this.audit = audit;
    }

    /** Off-hand = Exotic Book, main hand = target item. */
    public String applyBook(Player p) {
        ItemStack book = p.getInventory().getItemInOffHand();
        ItemStack target = p.getInventory().getItemInMainHand();
        if (!tags.isBook(book)) return "Hold an Exotic Book in your OFF-hand and the item to enchant in your MAIN hand.";
        if (target.getType().isAir()) return "Hold the item you want to enchant in your main hand.";
        if (!settings.isEligibleMaterial(target.getType().name())) return "That item type cannot hold an exotic enchantment.";
        if (tags.isExotic(target)) return "That item already has an exotic enchantment (one per item).";

        String bookId = tags.bookId(book);
        if (storage.isBookUsed(bookId)) return "This book has already been used.";

        String enchName = tags.bookEnchant(book);
        int level = tags.bookLevel(book);
        Rarity rarity = tags.bookRarity(book);
        Enchantment ench = EnchantPool.lookup(enchName);
        if (ench == null) return "Unknown enchantment on this book.";
        if (level < 1 || level > ench.getMaxLevel()) return "This book has an invalid level.";
        String why = pool.rejection(target, ench);
        if (why != null) return "Cannot apply: " + why + ". The book was not used.";

        ExoticKey key = new ExoticKey(enchName, level, target.getType().name());
        UUID itemUuid = UUID.randomUUID();
        Optional<Registration> reg = registry.claim(key, itemUuid, p.getUniqueId(), rarity);
        if (reg.isEmpty()) {
            return Msg.titleCase(enchName) + " " + Msg.roman(level) + " already exists on a "
                    + Msg.titleCase(target.getType().name()) + ". Keep the book and try another item type, or wait.";
        }
        try {
            ItemStack updated = target.clone();
            tags.applyExotic(updated, ench, level, itemUuid, reg.get().id(), rarity);
            if (!storage.markBookUsed(bookId)) {
                registry.abort(reg.get().id());
                return "This book has already been used.";
            }
            p.getInventory().setItemInMainHand(updated);
            registry.confirm(reg.get().id());
            if (book.getAmount() <= 1) p.getInventory().setItemInOffHand(null);
            else {
                book.setAmount(book.getAmount() - 1);
                p.getInventory().setItemInOffHand(book);
            }
        } catch (RuntimeException ex) {
            registry.abort(reg.get().id());
            return "Something went wrong; nothing was changed. (" + ex.getClass().getSimpleName() + ")";
        }
        audit.log("APPLY book " + bookId + " " + key + " reg#" + reg.get().id() + " by " + p.getName());
        return null;
    }

    /** Step 1 (confirm=false) only reports the price; step 2 charges and removes. */
    public String removeExotic(Player p, boolean confirm) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!tags.isExotic(hand)) return "Hold the exotic item in your main hand.";
        UUID itemUuid = tags.itemUuid(hand);
        Optional<Registration> reg = itemUuid == null ? Optional.empty() : registry.byItem(itemUuid);
        if (reg.isEmpty() || !registry.isValidItem(itemUuid)) return "This item's exotic enchantment is not registered (it will be cleaned up automatically).";
        Registration r = reg.get();
        double cost = settings.removalCost.get(r.rarity());
        if (!economy.ensure(org.bukkit.Bukkit.getServer())) return "Removal is unavailable: " + economy.problem();
        if (!confirm) {
            return "Removing " + Msg.titleCase(r.key().enchant()) + " " + Msg.roman(r.key().level()) + " costs "
                    + economy.format(cost) + ". Run /exotic remove confirm to proceed.";
        }
        if (!economy.has(p, cost)) return "You need " + economy.format(cost) + " to remove this.";
        if (!economy.withdraw(p, cost)) return "Payment failed; nothing was changed.";
        try {
            ItemStack updated = hand.clone();
            tags.strip(updated);
            if (!registry.release(r.id())) throw new IllegalStateException("release failed");
            p.getInventory().setItemInMainHand(updated);
        } catch (RuntimeException ex) {
            economy.deposit(p, cost);                         // refund
            return "Removal failed and you were refunded.";
        }
        audit.log("REMOVE reg#" + r.id() + " " + r.key() + " by " + p.getName() + " cost " + cost);
        return null;
    }

    // --------------------------------------------------------------------- books

    /** Direct creation of a book for a known enchant/level (admin and shop). */
    public ItemStack createBook(Enchantment ench, int level) {
        String key = ench.getKey().getKey();
        return tags.createBook(key, level, pool.rarityTable().rarity(key, level));
    }

    /**
     * Crate API: a random book of the given rarity whose enchant/level is not already registered
     * on the given material. Empty when nothing is available (give the alternative reward).
     */
    public Optional<ItemStack> randomBook(Rarity rarity, Material material, RollEngine engine) {
        ItemStack sample = new ItemStack(material);
        List<Candidate> candidates = pool.build(sample).stream().filter(c -> c.rarity() == rarity).toList();
        Optional<Candidate> pick = engine.pickFresh(candidates, material.name());
        return pick.map(c -> tags.createBook(c.enchant(), c.level(), c.rarity()));
    }
}
