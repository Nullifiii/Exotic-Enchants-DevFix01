package com.exoticenchants.logic;

/**
 * The money/stock/delivery sequence of a shop purchase, with no Bukkit dependency so it can be tested.
 * Guarantees: a player is charged at most once; if anything fails after payment the stock change is
 * undone and the player is refunded; nothing is charged when a pre-check fails.
 */
public final class Purchase {

    public enum Result {
        OK, SOLD_OUT, NO_SPACE, INSUFFICIENT_FUNDS, PAYMENT_FAILED, DELIVERY_FAILED_REFUNDED, DELIVERY_FAILED_REFUND_FAILED
    }

    /** The player's money. Implemented on top of the Vault economy. */
    public interface Wallet {
        boolean has(double amount);

        boolean withdraw(double amount);

        boolean deposit(double amount);
    }

    private Purchase() {
    }

    /**
     * @param stockLeft   copies currently in stock
     * @param hasSpace    whether the buyer can receive the item
     * @param takeStock   reduces the stock (may throw)
     * @param restoreStock undoes {@code takeStock}
     * @param deliver     gives the item (may throw)
     */
    public static Result execute(Wallet wallet, double price, int stockLeft, boolean hasSpace,
                                 Runnable takeStock, Runnable restoreStock, Runnable deliver) {
        if (stockLeft <= 0) return Result.SOLD_OUT;
        if (!hasSpace) return Result.NO_SPACE;
        boolean free = price <= 0;
        if (!free) {
            if (!wallet.has(price)) return Result.INSUFFICIENT_FUNDS;
            if (!wallet.withdraw(price)) return Result.PAYMENT_FAILED;
        }
        boolean stockTaken = false;
        try {
            takeStock.run();
            stockTaken = true;
            deliver.run();
            return Result.OK;
        } catch (RuntimeException ex) {
            if (stockTaken) {
                try {
                    restoreStock.run();
                } catch (RuntimeException ignored) {
                    // stock inconsistency is logged by the caller; the refund below still matters most
                }
            }
            if (free) return Result.DELIVERY_FAILED_REFUNDED;
            return wallet.deposit(price) ? Result.DELIVERY_FAILED_REFUNDED : Result.DELIVERY_FAILED_REFUND_FAILED;
        }
    }
}
