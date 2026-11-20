package com.exoticenchants.listener;

import com.exoticenchants.hook.EconomyHook;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.plugin.Plugin;

import java.util.logging.Logger;

/** Connects to the economy as soon as a provider registers with Vault, even if it loads after us. */
public final class EconomyListener implements Listener {

    private final Plugin plugin;
    private final EconomyHook economy;
    private final Logger logger;

    public EconomyListener(Plugin plugin, EconomyHook economy, Logger logger) {
        this.plugin = plugin;
        this.economy = economy;
        this.logger = logger;
    }

    @EventHandler
    public void onServiceRegister(ServiceRegisterEvent e) {
        if (!e.getProvider().getService().getName().equals("net.milkbowl.vault.economy.Economy")) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!economy.available() && economy.setup(plugin.getServer())) {
                logger.info("Economy connected: " + economy.providerName() + " - the exotic shop is now available.");
            }
        });
    }
}
