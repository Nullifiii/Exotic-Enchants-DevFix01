package com.exoticenchants.listener;

import com.exoticenchants.config.Settings;
import com.exoticenchants.core.Registry;
import com.exoticenchants.item.ItemTags;
import com.exoticenchants.service.ItemGuard;
import com.exoticenchants.shop.ExoticShop;
import com.exoticenchants.shop.ShopHolder;
import com.exoticenchants.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.GrindstoneInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Keeps physical items consistent with the registry and blocks vanilla paths that would break it. */
public final class ExoticListener implements Listener {

    private final Plugin plugin;
    private final Settings settings;
    private final Registry registry;
    private final ItemTags tags;
    private final ItemGuard guard;
    private final ExoticShop shop;

    public ExoticListener(Plugin plugin, Settings settings, Registry registry, ItemTags tags, ItemGuard guard,
                          ExoticShop shop) {
        this.plugin = plugin;
        this.settings = settings;
        this.registry = registry;
        this.tags = tags;
        this.guard = guard;
        this.shop = shop;
    }

    // ------------------------------------------------------------ validation

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) guard.scanPlayer(p);
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        ItemStack stack = e.getItem().getItemStack();
        if (guard.sanitize(stack, p)) e.getItem().setItemStack(stack);
    }

    @EventHandler
    public void onOpen(InventoryOpenEvent e) {
        Inventory top = e.getInventory();
        if (top.getHolder() instanceof ShopHolder) return;
        guard.sanitizeInventory(top);
    }

    /** Exotic items on the ground never burn, despawn or get destroyed (config protect-dropped-items). */
    @EventHandler
    public void onItemSpawn(ItemSpawnEvent e) {
        if (!settings.protectDropped) return;
        Item item = e.getEntity();
        if (tags.isExotic(item.getItemStack())) {
            item.setInvulnerable(true);
            item.setUnlimitedLifetime(true);
        }
    }

    // ------------------------------------------------------------- vanilla paths

    /** An exotic item may not be used as the sacrifice item in an anvil (would copy/merge the ticket). */
    @EventHandler
    public void onAnvil(PrepareAnvilEvent e) {
        AnvilInventory inv = e.getInventory();
        if (tags.isExotic(inv.getSecondItem())) e.setResult(null);
    }

    /** Vanilla grindstone would wipe the exotic without releasing the registry: use /exotic remove. */
    @EventHandler
    public void onGrindstone(PrepareGrindstoneEvent e) {
        GrindstoneInventory inv = e.getInventory();
        if (tags.isExotic(inv.getUpperItem()) || tags.isExotic(inv.getLowerItem())) {
            e.setResult(null);
            for (HumanEntity h : e.getViewers()) {
                h.sendMessage(Msg.info("Exotic items cannot go in a grindstone. Use /exotic remove."));
            }
        }
    }

    /** Netherite upgrade changes the item type, which is part of the uniqueness key: migrate or deny. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSmith(SmithItemEvent e) {
        ItemStack result = e.getCurrentItem();
        if (!tags.isExotic(result)) return;
        java.util.UUID itemUuid = tags.itemUuid(result);
        if (itemUuid == null || !registry.isValidItem(itemUuid)) return;   // stale ticket: the guard strips it
        Long regId = tags.regId(result);
        if (regId == null || !registry.migrateMaterial(regId, result.getType().name())) {
            e.setCancelled(true);
            e.getWhoClicked().sendMessage(Msg.error("Cannot upgrade: that exotic already exists on "
                    + Msg.titleCase(result.getType().name()) + "."));
        }
    }

    // ------------------------------------------------------------------- shop

    @EventHandler
    public void onShopClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof ShopHolder)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == top && e.getWhoClicked() instanceof Player p) {
            shop.buy(p, e.getSlot());
        }
    }

    @EventHandler
    public void onShopDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof ShopHolder) e.setCancelled(true);
    }
}
