package com.exoticenchants.hook;

import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Talks to any Vault-compatible economy through reflection, so the plugin compiles and runs without Vault.
 *
 * Two separate things are required for money to work:
 *  1. the Vault plugin (a bridge only - it holds no money and has no commands for balances), and
 *  2. an economy PROVIDER plugin registered with Vault, e.g. EssentialsX (Economy), CMI, or a standalone
 *     Vault economy plugin.
 * {@link #status()} tells which of the two is missing so players and admins get an exact message.
 */
public final class EconomyHook {

    public enum Status { READY, NO_VAULT, NO_PROVIDER, ERROR }

    private final Logger logger;
    private Object economy;
    private Method has;
    private Method withdraw;
    private Method deposit;
    private Method format;
    private Method success;
    private Status status = Status.NO_VAULT;
    private String detail = "";
    private boolean warnedRuntime;

    public EconomyHook(Logger logger) {
        this.logger = logger;
    }

    /** (Re)detect Vault and the economy provider. Safe to call as often as needed. */
    public synchronized boolean setup(Server server) {
        economy = null;
        detail = "";
        try {
            if (server.getPluginManager().getPlugin("Vault") == null) {
                status = Status.NO_VAULT;
                return false;
            }
            Class<?> econClass = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> rsp = server.getServicesManager().getRegistration(econClass);
            if (rsp == null) {
                status = Status.NO_PROVIDER;
                return false;
            }
            Object provider = rsp.getProvider();
            has = econClass.getMethod("has", OfflinePlayer.class, double.class);
            withdraw = econClass.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            deposit = econClass.getMethod("depositPlayer", OfflinePlayer.class, double.class);
            format = econClass.getMethod("format", double.class);
            success = Class.forName("net.milkbowl.vault.economy.EconomyResponse").getMethod("transactionSuccess");
            economy = provider;
            status = Status.READY;
            detail = provider.getClass().getName();
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            economy = null;
            status = Status.ERROR;
            detail = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            return false;
        }
    }

    /** Re-detects only if not ready yet (an economy plugin may have registered after startup). */
    public boolean ensure(Server server) {
        return economy != null || setup(server);
    }

    public boolean available() {
        return economy != null;
    }

    public Status status() {
        return status;
    }

    public String providerName() {
        return detail;
    }

    /** Exact, human-readable explanation of what is missing (empty when everything works). */
    public String problem() {
        return switch (status) {
            case READY -> "";
            case NO_VAULT -> "Vault is not installed. The exotic shop and enchantment removal need Vault AND an economy "
                    + "plugin (for example EssentialsX). Ask an admin to install both.";
            case NO_PROVIDER -> "Vault is installed but no economy plugin is connected to it. Vault only bridges plugins - "
                    + "an economy plugin such as EssentialsX (economy enabled) or CMI must also be installed.";
            case ERROR -> "The economy could not be used (" + detail + "). Check the server console.";
        };
    }

    public boolean has(OfflinePlayer p, double amount) {
        if (economy == null) return false;
        try {
            return (boolean) has.invoke(economy, p, amount);
        } catch (ReflectiveOperationException ex) {
            runtimeFailure(ex);
            return false;
        }
    }

    public boolean withdraw(OfflinePlayer p, double amount) {
        return transact(withdraw, p, amount);
    }

    public boolean deposit(OfflinePlayer p, double amount) {
        return transact(deposit, p, amount);
    }

    public String format(double amount) {
        if (economy == null) return String.valueOf(amount);
        try {
            return String.valueOf(format.invoke(economy, amount));
        } catch (ReflectiveOperationException ex) {
            return String.valueOf(amount);
        }
    }

    private boolean transact(Method m, OfflinePlayer p, double amount) {
        if (economy == null) return false;
        try {
            Object response = m.invoke(economy, p, amount);
            return response != null && (boolean) success.invoke(response);
        } catch (ReflectiveOperationException ex) {
            runtimeFailure(ex);
            return false;
        }
    }

    private void runtimeFailure(ReflectiveOperationException ex) {
        Throwable cause = ex instanceof InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : ex;
        if (!warnedRuntime) {
            warnedRuntime = true;
            logger.warning("Economy call failed: " + cause);
        }
    }
}
