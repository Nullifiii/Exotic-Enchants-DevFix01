package com.exoticenchants.util;

import com.exoticenchants.core.Rarity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

import java.util.Locale;

/** Chat helpers. */
public final class Msg {

    private static final Component PREFIX = Component.text("[Exotics] ", NamedTextColor.LIGHT_PURPLE);

    private Msg() {
    }

    public static Component info(String text) {
        return PREFIX.append(Component.text(text, NamedTextColor.GRAY));
    }

    public static Component ok(String text) {
        return PREFIX.append(Component.text(text, NamedTextColor.GREEN));
    }

    public static Component error(String text) {
        return PREFIX.append(Component.text(text, NamedTextColor.RED));
    }

    public static Component prefixed(Component body) {
        return PREFIX.append(body);
    }

    public static TextColor rarityColor(Rarity r) {
        return switch (r) {
            case COMMON -> NamedTextColor.GRAY;
            case UNCOMMON -> NamedTextColor.GREEN;
            case RARE -> NamedTextColor.BLUE;
            case EPIC -> NamedTextColor.LIGHT_PURPLE;
            case LEGENDARY -> NamedTextColor.GOLD;
        };
    }

    /** "fire_aspect" -> "Fire Aspect". */
    public static String titleCase(String key) {
        StringBuilder sb = new StringBuilder();
        for (String part : key.split("_")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    public static String roman(int n) {
        String[] r = {"0", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n >= 0 && n < r.length ? r[n] : String.valueOf(n);
    }

    public static String duration(long millis) {
        long s = Math.max(0, millis / 1000);
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + sec + "s";
        return sec + "s";
    }
}
