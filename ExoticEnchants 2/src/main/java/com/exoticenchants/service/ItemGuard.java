package com.exoticenchants.service;

import com.exoticenchants.core.Registration;
import com.exoticenchants.core.Registry;
import com.exoticenchants.item.ItemTags;
import com.exoticenchants.util.AuditLog;
import com.exoticenchants.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps physical items consistent with the registry:
 *  - an exotic ticket that does not match an ACTIVE registration is stripped (lazy revocation);
 *  - the registered owner follows the player holding the item;
 *  - the same item uuid appearing twice is treated as a duplicate: both copies are stripped and
 *    the registration is flagged DISPUTED for admins.
 * Everything here runs on the main thread.
 */
public final class ItemGuard {

    private record Spot(Player player, int slot) {
    }

    private final Registry registry;
    private final ItemTags tags;
    private final AuditLog audit;

    public ItemGuard(Registry registry, ItemTags tags, AuditLog audit) {
        this.registry = registry;
        this.tags = tags;
        this.audit = audit;
    }

    /**
     * Validate one stack (and shulker boxes inside it).
     * @param holder the player carrying it, or null for containers/ground (owner is then left alone)
     * @return true if the stack was modified and must be written back
     */
    public boolean sanitize(ItemStack item, Player holder) {
        if (item == null || item.getType().isAir()) return false;
        boolean changed = false;

        if (tags.isExotic(item)) {
            UUID id = tags.itemUuid(item);
            if (id == null || !registry.isValidItem(id)) {
                tags.strip(item);
                audit.log("STRIPPED stale exotic from item (uuid " + id + ")"
                        + (holder != null ? " held by " + holder.getName() : ""));
                changed = true;
            } else if (holder != null) {
                registry.byItem(id).ifPresent(r -> {
                    if (!r.owner().equals(holder.getUniqueId())) registry.changeOwner(id, holder.getUniqueId());
                });
            }
        }

        if (item.hasItemMeta() && item.getItemMeta() instanceof BlockStateMeta bsm
                && bsm.getBlockState() instanceof ShulkerBox box) {
            boolean inner = false;
            Inventory inv = box.getInventory();
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (sanitize(s, holder)) {
                    inv.setItem(i, s);
                    inner = true;
                }
            }
            if (inner) {
                bsm.setBlockState(box);
                item.setItemMeta(bsm);
                changed = true;
            }
        }
        return changed;
    }

    /** Sanitize every slot of a container inventory (no owner changes). */
    public void sanitizeInventory(Inventory inv) {
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (sanitize(s, null)) inv.setItem(i, s);
        }
    }

    public void scanPlayer(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (sanitize(s, p)) inv.setItem(i, s);
        }
    }

    /** Periodic scan of everyone online, including duplicate-uuid detection across players. */
    public void scanAll() {
        Map<UUID, List<Spot>> seen = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            scanPlayer(p);
            PlayerInventory inv = p.getInventory();
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (!tags.isExotic(s)) continue;
                UUID id = tags.itemUuid(s);
                if (id != null) seen.computeIfAbsent(id, k -> new ArrayList<>()).add(new Spot(p, i));
            }
        }
        for (Map.Entry<UUID, List<Spot>> e : seen.entrySet()) {
            if (e.getValue().size() < 2) continue;
            handleDuplicate(e.getKey(), e.getValue());
        }
    }

    private void handleDuplicate(UUID itemUuid, List<Spot> spots) {
        StringBuilder where = new StringBuilder();
        for (Spot s : spots) {
            ItemStack item = s.player().getInventory().getItem(s.slot());
            if (item != null) {
                tags.strip(item);
                s.player().getInventory().setItem(s.slot(), item);
            }
            where.append(s.player().getName()).append(' ');
        }
        registry.byItem(itemUuid).ifPresent(r -> registry.dispute(r.id()));
        audit.log("DUPLICATE item uuid " + itemUuid + " found with: " + where + "- exotic stripped, registration DISPUTED");
        for (Player admin : Bukkit.getOnlinePlayers()) {
            if (admin.hasPermission("exoticenchants.admin")) {
                admin.sendMessage(Msg.error("Duplicate exotic item detected (" + where.toString().trim()
                        + "). Both copies were stripped and the registration is DISPUTED. See /exotic admin audit."));
            }
        }
    }

    /** True if some registration for this item uuid is ACTIVE. */
    public boolean isRegistered(UUID itemUuid) {
        return registry.isValidItem(itemUuid);
    }

    public Registration registration(UUID itemUuid) {
        return registry.byItem(itemUuid).orElse(null);
    }
}
