package com.killstreakeffects.antifarm;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Pure logic (no Bukkit) for deciding whether a connection address says anything useful about
 * a player. Addresses that belong to a tunnel/proxy, a LAN or the machine itself are "unreliable":
 * two different players can legitimately share them, so they must not count as "same IP".
 */
public final class AddressRules {

    /** One configured address or CIDR block, e.g. "203.0.113.7" or "203.0.113.0/24". */
    private record Cidr(byte[] network, int prefixBits) {
        boolean contains(byte[] address) {
            if (address.length != network.length) return false;
            int full = prefixBits / 8;
            for (int i = 0; i < full; i++) {
                if (address[i] != network[i]) return false;
            }
            int rest = prefixBits % 8;
            if (rest == 0) return true;
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (address[full] & mask) == (network[full] & mask);
        }
    }

    private static final Pattern LITERAL = Pattern.compile("[0-9a-fA-F:.]+");

    private final boolean ignorePrivate;
    private final List<Cidr> ignored = new ArrayList<>();
    private final List<String> invalidEntries = new ArrayList<>();

    public AddressRules(boolean ignorePrivate, List<String> ignoredAddresses) {
        this.ignorePrivate = ignorePrivate;
        if (ignoredAddresses != null) {
            for (String raw : ignoredAddresses) parseInto(raw);
        }
    }

    private void parseInto(String raw) {
        if (raw == null) return;
        String entry = raw.trim();
        if (entry.isEmpty()) return;
        try {
            String ip = entry;
            Integer bits = null;
            int slash = entry.indexOf('/');
            if (slash >= 0) {
                ip = entry.substring(0, slash).trim();
                bits = Integer.parseInt(entry.substring(slash + 1).trim());
            }
            if (!LITERAL.matcher(ip).matches()) throw new IllegalArgumentException("not an IP literal");
            byte[] net = InetAddress.getByName(ip).getAddress();   // literal only: never does a DNS lookup
            int max = net.length * 8;
            if (bits == null) bits = max;
            if (bits < 0 || bits > max) throw new IllegalArgumentException("bad prefix length");
            ignored.add(new Cidr(net, bits));
        } catch (UnknownHostException | RuntimeException ex) {
            invalidEntries.add(entry);
        }
    }

    /** Entries from the config that could not be understood (so the plugin can warn about them). */
    public List<String> invalidEntries() {
        return invalidEntries;
    }

    /** Loopback, LAN, link-local, carrier-grade NAT and IPv6 unique-local addresses. */
    public static boolean isNonPublic(InetAddress a) {
        if (a == null) return true;
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            return first == 100 && second >= 64 && second <= 127;          // 100.64.0.0/10
        }
        return b.length == 16 && (b[0] & 0xFE) == 0xFC;                    // fc00::/7
    }

    /** True if this address cannot be trusted to identify one household/machine. */
    public boolean isUnreliable(InetAddress a) {
        if (a == null) return true;
        if (ignorePrivate && isNonPublic(a)) return true;
        byte[] bytes = a.getAddress();
        for (Cidr c : ignored) {
            if (c.contains(bytes)) return true;
        }
        return false;
    }

    /** "203.0.113.45" -> "203.0.x.x" (used when a masked address is wanted). */
    public static String mask(InetAddress a) {
        if (a == null) return "unknown";
        String s = a.getHostAddress();
        int dot = s.indexOf('.');
        if (dot > 0) {
            String[] p = s.split("\\.");
            if (p.length == 4) return p[0] + "." + p[1] + ".x.x";
        }
        int colon = s.indexOf(':');
        return colon > 0 ? s.substring(0, Math.min(s.length(), colon + 5)) + ":x" : s;
    }
}
