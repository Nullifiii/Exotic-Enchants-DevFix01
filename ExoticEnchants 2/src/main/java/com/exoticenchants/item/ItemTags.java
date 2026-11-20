package com.exoticenchants.item;

import com.exoticenchants.core.Rarity;
import com.exoticenchants.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * All item metadata handling. An exotic item carries a "claim ticket" in its PersistentDataContainer
 * (item uuid + registration id + enchant + level). The DATABASE is the source of truth: a ticket is
 * only valid while the registry has an ACTIVE row for that item uuid.
 */
public final class ItemTags {

    private static final String LORE_PREFIX = "\u2726 Exotic:";

    private final NamespacedKey itemKey;
    private final NamespacedKey regKey;
    private final NamespacedKey enchKey;
    private final NamespacedKey lvlKey;
    private final NamespacedKey rarityKey;
    private final NamespacedKey bookId;
    private final NamespacedKey bookEnch;
    private final NamespacedKey bookLvl;
    private final NamespacedKey bookRarity;

    public ItemTags(Plugin plugin) {
        itemKey = new NamespacedKey(plugin, "exotic_item");
        regKey = new NamespacedKey(plugin, "exotic_reg");
        enchKey = new NamespacedKey(plugin, "exotic_ench");
        lvlKey = new NamespacedKey(plugin, "exotic_lvl");
        rarityKey = new NamespacedKey(plugin, "exotic_rarity");
        bookId = new NamespacedKey(plugin, "book_id");
        bookEnch = new NamespacedKey(plugin, "book_ench");
        bookLvl = new NamespacedKey(plugin, "book_lvl");
        bookRarity = new NamespacedKey(plugin, "book_rarity");
    }

    private static boolean empty(ItemStack item) {
        return item == null || item.getType().isAir();
    }

    // ------------------------------------------------------------- exotic items

    public boolean isExotic(ItemStack item) {
        if (empty(item) || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.STRING);
    }

    /** The item uuid on the ticket, or null. */
    public UUID itemUuid(ItemStack item) {
        if (empty(item) || !item.hasItemMeta()) return null;
        String s = item.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        if (s == null) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Everything on the claim ticket, read with a single ItemMeta copy (cheap enough for combat events). */
    public record ExoticInfo(UUID itemUuid, String enchant, int level) {
    }

    /** @return the ticket, or null if the item has none */
    public ExoticInfo info(ItemStack item) {
        if (empty(item) || !item.hasItemMeta()) return null;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        String uuid = pdc.get(itemKey, PersistentDataType.STRING);
        String ench = pdc.get(enchKey, PersistentDataType.STRING);
        Integer lvl = pdc.get(lvlKey, PersistentDataType.INTEGER);
        if (uuid == null || ench == null || lvl == null) return null;
        try {
            return new ExoticInfo(UUID.fromString(uuid), ench, lvl);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public Long regId(ItemStack item) {
        if (empty(item) || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(regKey, PersistentDataType.LONG);
    }

    /** Adds the exotic enchantment (ignoring vanilla limits) and the claim ticket. Normal enchants are untouched. */
    public void applyExotic(ItemStack item, Enchantment ench, int level, UUID itemUuid, long regId, Rarity rarity) {
        ItemMeta meta = item.getItemMeta();
        meta.addEnchant(ench, level, true);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(itemKey, PersistentDataType.STRING, itemUuid.toString());
        pdc.set(regKey, PersistentDataType.LONG, regId);
        pdc.set(enchKey, PersistentDataType.STRING, ench.getKey().getKey());
        pdc.set(lvlKey, PersistentDataType.INTEGER, level);
        pdc.set(rarityKey, PersistentDataType.STRING, rarity.name());

        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Component.text(LORE_PREFIX + " " + Msg.titleCase(ench.getKey().getKey()) + " " + Msg.roman(level)
                + " [" + Msg.titleCase(rarity.name()) + "]", Msg.rarityColor(rarity))
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
    }

    /** Removes ONLY the exotic enchantment, the ticket and the exotic lore line. */
    public void strip(ItemStack item) {
        if (empty(item) || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String ench = pdc.get(enchKey, PersistentDataType.STRING);
        if (ench != null) {
            Enchantment e = EnchantPool.lookup(ench);
            if (e != null) meta.removeEnchant(e);
        }
        pdc.remove(itemKey);
        pdc.remove(regKey);
        pdc.remove(enchKey);
        pdc.remove(lvlKey);
        pdc.remove(rarityKey);
        List<Component> lore = meta.lore();
        if (lore != null) {
            List<Component> kept = new ArrayList<>();
            for (Component c : lore) {
                if (!PlainTextComponentSerializer.plainText().serialize(c).startsWith(LORE_PREFIX)) kept.add(c);
            }
            meta.lore(kept.isEmpty() ? null : kept);
        }
        item.setItemMeta(meta);
    }

    // -------------------------------------------------------------------- books

    public ItemStack createBook(String enchant, int level, Rarity rarity) {
        ItemStack book = new ItemStack(Material.BOOK);
        ItemMeta meta = book.getItemMeta();
        meta.displayName(Component.text("Exotic Book: " + Msg.titleCase(enchant) + " " + Msg.roman(level),
                Msg.rarityColor(rarity)).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Rarity: " + Msg.titleCase(rarity.name()), Msg.rarityColor(rarity))
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Hold this in your off-hand and the item", net.kyori.adventure.text.format.NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("in your main hand, then run /exotic apply", net.kyori.adventure.text.format.NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(bookId, PersistentDataType.STRING, UUID.randomUUID().toString());
        pdc.set(bookEnch, PersistentDataType.STRING, enchant);
        pdc.set(bookLvl, PersistentDataType.INTEGER, level);
        pdc.set(bookRarity, PersistentDataType.STRING, rarity.name());
        book.setItemMeta(meta);
        return book;
    }

    public boolean isBook(ItemStack item) {
        if (empty(item) || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(bookId, PersistentDataType.STRING);
    }

    public String bookId(ItemStack item) {
        return item.getItemMeta().getPersistentDataContainer().get(bookId, PersistentDataType.STRING);
    }

    public String bookEnchant(ItemStack item) {
        return item.getItemMeta().getPersistentDataContainer().get(bookEnch, PersistentDataType.STRING);
    }

    public int bookLevel(ItemStack item) {
        Integer v = item.getItemMeta().getPersistentDataContainer().get(bookLvl, PersistentDataType.INTEGER);
        return v == null ? 1 : v;
    }

    public Rarity bookRarity(ItemStack item) {
        String s = item.getItemMeta().getPersistentDataContainer().get(bookRarity, PersistentDataType.STRING);
        try {
            return s == null ? Rarity.COMMON : Rarity.valueOf(s);
        } catch (IllegalArgumentException ex) {
            return Rarity.COMMON;
        }
    }
}
