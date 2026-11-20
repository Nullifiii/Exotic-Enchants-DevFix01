package com.exoticenchants.logic;

import com.exoticenchants.logic.EffectMath.DamageKind;
import com.exoticenchants.logic.EffectSupport.Category;
import com.exoticenchants.logic.EffectSupport.Support;
import com.exoticenchants.logic.Purchase.Result;

import java.util.ArrayList;
import java.util.List;

/** Dependency-free tests for the shop purchase flow and the custom-effect rules/formulas. */
public final class LogicSelfTest {

    static int passed = 0;
    static int failed = 0;

    static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("PASS  " + name); }
        else { failed++; System.out.println("FAIL  " + name); }
    }

    /** A wallet that records every call so double charges are visible. */
    static final class FakeWallet implements Purchase.Wallet {
        double balance;
        int withdrawals;
        int deposits;
        boolean failWithdraw;
        boolean failDeposit;
        FakeWallet(double balance) { this.balance = balance; }
        @Override public boolean has(double a) { return balance >= a; }
        @Override public boolean withdraw(double a) { if (failWithdraw) return false; balance -= a; withdrawals++; return true; }
        @Override public boolean deposit(double a) { if (failDeposit) return false; balance += a; deposits++; return true; }
    }

    static final class Shelf {
        int stock;
        int delivered;
        Shelf(int stock) { this.stock = stock; }
    }

    static Result buy(FakeWallet w, Shelf s, double price, boolean space, boolean failDeliver, boolean failTake) {
        return Purchase.execute(w, price, s.stock, space,
                () -> { if (failTake) throw new IllegalStateException("db"); s.stock--; },
                () -> s.stock++,
                () -> { if (failDeliver) throw new IllegalStateException("inventory"); s.delivered++; });
    }

    public static void main(String[] args) {
        purchases();
        effectSupport();
        effectMath();
        System.out.println("\n" + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    static void purchases() {
        FakeWallet w = new FakeWallet(1000);
        Shelf s = new Shelf(2);
        check("purchase: money deducted exactly once, stock down, item delivered",
                buy(w, s, 300, true, false, false) == Result.OK && w.balance == 700 && w.withdrawals == 1 && s.stock == 1 && s.delivered == 1);

        w = new FakeWallet(100); s = new Shelf(2);
        check("purchase: insufficient balance -> nothing charged, nothing changed",
                buy(w, s, 300, true, false, false) == Result.INSUFFICIENT_FUNDS && w.balance == 100 && w.withdrawals == 0 && s.stock == 2 && s.delivered == 0);

        w = new FakeWallet(1000); s = new Shelf(0);
        check("purchase: sold out -> nothing charged",
                buy(w, s, 300, true, false, false) == Result.SOLD_OUT && w.balance == 1000 && w.withdrawals == 0);

        w = new FakeWallet(1000); s = new Shelf(2);
        check("purchase: full inventory -> nothing charged",
                buy(w, s, 300, false, false, false) == Result.NO_SPACE && w.balance == 1000 && w.withdrawals == 0 && s.stock == 2);

        w = new FakeWallet(1000); w.failWithdraw = true; s = new Shelf(2);
        check("purchase: economy refuses the withdrawal -> no item, no stock change",
                buy(w, s, 300, true, false, false) == Result.PAYMENT_FAILED && w.balance == 1000 && s.stock == 2 && s.delivered == 0);

        w = new FakeWallet(1000); s = new Shelf(2);
        check("purchase: delivery fails after payment -> refunded in full, stock restored",
                buy(w, s, 300, true, true, false) == Result.DELIVERY_FAILED_REFUNDED && w.balance == 1000 && w.withdrawals == 1 && w.deposits == 1 && s.stock == 2 && s.delivered == 0);

        w = new FakeWallet(1000); s = new Shelf(2);
        check("purchase: stock update fails after payment -> refunded, no item",
                buy(w, s, 300, true, false, true) == Result.DELIVERY_FAILED_REFUNDED && w.balance == 1000 && s.stock == 2 && s.delivered == 0);

        w = new FakeWallet(1000); w.failDeposit = true; s = new Shelf(2);
        check("purchase: refund impossible is reported, never silently ignored",
                buy(w, s, 300, true, true, false) == Result.DELIVERY_FAILED_REFUND_FAILED);

        FakeWallet w2 = new FakeWallet(1000);
        Shelf s2 = new Shelf(1);
        Result a = buy(w2, s2, 300, true, false, false);
        Result b = buy(w2, s2, 300, true, false, false);
        check("purchase: last copy cannot be bought twice (second attempt sees sold out, charged once)",
                a == Result.OK && b == Result.SOLD_OUT && w2.withdrawals == 1 && w2.balance == 700);

        w = new FakeWallet(0); s = new Shelf(1);
        check("purchase: free item needs no payment", buy(w, s, 0, true, false, false) == Result.OK && w.withdrawals == 0 && s.delivered == 1);
    }

    static void effectSupport() {
        check("category detection", EffectSupport.categoryOf("DIAMOND_SWORD") == Category.SWORD
                && EffectSupport.categoryOf("NETHERITE_PICKAXE") == Category.PICKAXE
                && EffectSupport.categoryOf("IRON_AXE") == Category.AXE
                && EffectSupport.categoryOf("BOW") == Category.BOW && EffectSupport.categoryOf("MACE") == Category.MACE);
        check("Frost Walker on a sword is CUSTOM (this was silently doing nothing before)",
                EffectSupport.support("frost_walker", "DIAMOND_SWORD", false) == Support.CUSTOM);
        check("Frost Walker on boots stays NATIVE (vanilla behaviour untouched)",
                EffectSupport.support("frost_walker", "DIAMOND_BOOTS", true) == Support.NATIVE);
        check("Density on a bow and a crossbow is CUSTOM",
                EffectSupport.support("density", "BOW", false) == Support.CUSTOM && EffectSupport.support("density", "CROSSBOW", false) == Support.CUSTOM);
        check("Density on a sword/axe is NONE (vanilla only supports the mace) and is never rolled",
                EffectSupport.support("density", "DIAMOND_SWORD", false) == Support.NONE);
        check("Density on a mace is NATIVE", EffectSupport.support("density", "MACE", true) == Support.NATIVE);
        check("Sharpness/Smite/Bane/Fire Aspect on bows are CUSTOM",
                EffectSupport.support("sharpness", "BOW", false) == Support.CUSTOM && EffectSupport.support("smite", "CROSSBOW", false) == Support.CUSTOM
                        && EffectSupport.support("bane_of_arthropods", "BOW", false) == Support.CUSTOM && EffectSupport.support("fire_aspect", "BOW", false) == Support.CUSTOM);
        check("Protection family on held non-armor items is CUSTOM",
                EffectSupport.support("protection", "DIAMOND_SWORD", false) == Support.CUSTOM
                        && EffectSupport.support("projectile_protection", "SHIELD", false) == Support.CUSTOM
                        && EffectSupport.support("feather_falling", "BOW", false) == Support.CUSTOM);
        check("Mending/Unbreaking work in any slot natively",
                EffectSupport.support("mending", "BOW", false) == Support.NATIVE && EffectSupport.support("unbreaking", "DIAMOND_SWORD", false) == Support.NATIVE);
        check("combos with no working effect are NONE (Power on a sword, Efficiency on a bow, Lure on armor)",
                EffectSupport.support("power", "DIAMOND_SWORD", false) == Support.NONE && EffectSupport.support("efficiency", "BOW", false) == Support.NONE
                        && EffectSupport.support("lure", "DIAMOND_CHESTPLATE", false) == Support.NONE);
        check("vanilla-compatible combos (e.g. Sharpness + Smite on a sword) are NATIVE",
                EffectSupport.support("smite", "DIAMOND_SWORD", true) == Support.NATIVE);
        check("protection custom never applies to armor pieces (they work natively)", !EffectSupport.customApplies("protection", Category.CHESTPLATE));
        // every key the custom listener handles must be reported as supported somewhere
        List<String> handled = new ArrayList<>(EffectSupport.ARROW_ENCHANTS);
        handled.addAll(EffectSupport.HELD_PROTECTION);
        handled.add("frost_walker");
        boolean allReachable = true;
        for (String k : handled) {
            boolean any = false;
            for (Category c : Category.values()) any |= EffectSupport.customApplies(k, c);
            allReachable &= any;
        }
        check("every custom-handled enchantment is reachable for at least one item category", allReachable);
    }

    static void effectMath() {
        check("frost radius = 2 + level, capped", EffectMath.frostRadius(1, 6) == 3 && EffectMath.frostRadius(2, 6) == 4 && EffectMath.frostRadius(10, 4) == 4);
        List<int[]> r2 = EffectMath.frostOffsets(2);
        boolean circle = r2.size() == 13 && r2.stream().allMatch(o -> o[0] * o[0] + o[1] * o[1] <= 4);
        check("frost offsets form a filled circle (13 blocks for radius 2)", circle);
        check("density: no bonus when barely falling", EffectMath.densityBonus(3, 1.0, 0.25, 1.5, 20) == 0.0);
        check("density: scales with level and fall distance", Math.abs(EffectMath.densityBonus(4, 10, 0.25, 1.5, 20) - 10.0) < 1e-9);
        check("density: capped (cannot one-shot from a tower)", EffectMath.densityBonus(5, 100, 0.25, 1.5, 20) == 20.0);
        check("sharpness arrow bonus matches vanilla formula (V = +3.0)", Math.abs(EffectMath.sharpnessBonus(5) - 3.0) < 1e-9);
        check("smite only hits undead, bane only arthropods",
                EffectMath.smiteBonus(5, "ZOMBIE") == 12.5 && EffectMath.smiteBonus(5, "SPIDER") == 0
                        && EffectMath.baneBonus(5, "SPIDER") == 12.5 && EffectMath.baneBonus(5, "ZOMBIE") == 0);
        check("fire aspect: 4 s per level", EffectMath.fireTicks(2) == 160);
        check("EPF: protection by level, fire/blast/projectile x2 for their damage only, feather falling x3 vs falls",
                EffectMath.epf("protection", 4, DamageKind.OTHER) == 4 && EffectMath.epf("fire_protection", 4, DamageKind.FIRE) == 8
                        && EffectMath.epf("fire_protection", 4, DamageKind.FALL) == 0 && EffectMath.epf("feather_falling", 4, DamageKind.FALL) == 12
                        && EffectMath.epf("projectile_protection", 2, DamageKind.PROJECTILE) == 4 && EffectMath.epf("blast_protection", 4, DamageKind.EXPLOSION) == 8);
        check("protection reduction: 4% per EPF, vanilla cap 20 EPF = 80%", Math.abs(EffectMath.applyProtection(10, 5, 0.8) - 8.0) < 1e-9
                && Math.abs(EffectMath.applyProtection(10, 99, 0.8) - 2.0) < 1e-9);
        check("protection reduction respects the configured maximum", Math.abs(EffectMath.applyProtection(10, 20, 0.5) - 5.0) < 1e-9);
    }
}
