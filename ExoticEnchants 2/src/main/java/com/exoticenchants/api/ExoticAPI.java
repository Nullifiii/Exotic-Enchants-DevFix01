package com.exoticenchants.api;

import com.exoticenchants.ExoticEnchantsPlugin;
import com.exoticenchants.core.Rarity;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;
import java.util.UUID;

/**
 * Entry points for other plugins (crates, auction house, trading). All methods must be called
 * on the main server thread.
 */
public final class ExoticAPI {

    private ExoticAPI() {
    }

    private static ExoticEnchantsPlugin plugin() {
        ExoticEnchantsPlugin p = ExoticEnchantsPlugin.get();
        if (p == null) throw new IllegalStateException("ExoticEnchants is not enabled");
        return p;
    }

    /** Is this stack a valid, registered exotic item? */
    public static boolean isExotic(ItemStack item) {
        UUID id = plugin().tags().itemUuid(item);
        return id != null && plugin().registry().isValidItem(id);
    }

    /**
     * Crates: a random exotic book of this rarity whose enchantment is not already registered on the
     * given item type. Empty means nothing is available: give your configured alternative reward.
     */
    public static Optional<ItemStack> randomBook(Rarity rarity, Material material) {
        return plugin().service().randomBook(rarity, material, plugin().scheduler().engine());
    }

    /**
     * Auction house / trading: call after a SUCCESSFUL, atomic handover so the registered owner follows
     * the item. The item uuid and registration stay unchanged.
     * @return true if the item was a registered exotic and ownership was updated
     */
    public static boolean ownershipChanged(ItemStack item, Player newOwner) {
        UUID id = plugin().tags().itemUuid(item);
        return id != null && plugin().registry().changeOwner(id, newOwner.getUniqueId());
    }
}
