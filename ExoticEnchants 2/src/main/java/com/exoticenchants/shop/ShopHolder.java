package com.exoticenchants.shop;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Marks our shop inventory so the click listener can recognise it. */
public final class ShopHolder implements InventoryHolder {

    private Inventory inventory;

    public void bind(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public static Inventory create(ShopHolder holder, int size, net.kyori.adventure.text.Component title) {
        Inventory inv = Bukkit.createInventory(holder, size, title);
        holder.bind(inv);
        return inv;
    }
}
