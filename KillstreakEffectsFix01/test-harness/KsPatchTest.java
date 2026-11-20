import java.io.File;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;

/** Runs the ORIGINAL AntiFarmManager and the PATCHED one against the same scenarios. */
public final class KsPatchTest {

    static int passed = 0, failed = 0;
    static void check(String name, boolean ok) { if (ok) { passed++; System.out.println("PASS  " + name); } else { failed++; System.out.println("FAIL  " + name); } }

    static final class Env {
        ClassLoader cl; Class<?> playerCls, fileCfg, bukkit, jp; Object manager; Object plugin; Map<String,Object> values; List<Object> online; List<String> logs;
        @SuppressWarnings("unchecked")
        Env(boolean patched) throws Exception {
            List<URL> urls = new ArrayList<>();
            urls.add(new File("/tmp/kstest/stubsout").toURI().toURL());
            if (patched && System.getProperty("ks.jar") == null) urls.add(new File("/tmp/ksfix/out").toURI().toURL());
            urls.add(patched && System.getProperty("ks.jar") != null ? new File(System.getProperty("ks.jar")).toURI().toURL() : new File("/tmp/p1/ksB").toURI().toURL());
            urls.add(new File("/tmp/stubclasses").toURI().toURL());
            cl = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
            playerCls = cl.loadClass("org.bukkit.entity.Player");
            fileCfg = cl.loadClass("org.bukkit.configuration.file.FileConfiguration");
            bukkit = cl.loadClass("org.bukkit.Bukkit");
            jp = cl.loadClass("org.bukkit.plugin.java.JavaPlugin");
            values = (Map<String,Object>) fileCfg.getField("VALUES").get(null);
            online = (List<Object>) bukkit.getField("ONLINE").get(null);
            logs = (List<String>) jp.getField("LOGGED").get(null);
            Class<?> pluginCls = cl.loadClass("com.killstreakeffects.KillstreakEffectsPlugin");
            plugin = pluginCls.getConstructor().newInstance();
            jp.getField("INSTANCE").set(null, plugin);
            Object cm = cl.loadClass("com.killstreakeffects.config.ConfigManager").getConstructor(pluginCls).newInstance(plugin);
            manager = cl.loadClass("com.killstreakeffects.antifarm.AntiFarmManager")
                    .getConstructor(cm.getClass()).newInstance(cm);
        }
        Object player(String name, String ip) {
            UUID id = UUID.nameUUIDFromBytes(name.getBytes());
            InetSocketAddress addr;
            try { addr = ip == null ? null : new InetSocketAddress(InetAddress.getByName(ip), 25565); } catch (UnknownHostException e) { throw new RuntimeException(e); }
            return java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[]{playerCls}, (p, m, a) -> switch (m.getName()) {
                case "getUniqueId" -> id; case "getName" -> name; case "getAddress" -> addr;
                case "hashCode" -> id.hashCode(); case "equals" -> p == a[0]; case "toString" -> name;
                default -> throw new UnsupportedOperationException(m.getName());
            });
        }
        void markAlive(Object p) throws Exception { manager.getClass().getMethod("markAlive", playerCls).invoke(manager, p); }
        String check(Object killer, Object victim) throws Exception {
            return (String) manager.getClass().getMethod("checkAndRecord", playerCls, playerCls).invoke(manager, killer, victim);
        }
        void reset() { values.clear(); online.clear(); logs.clear(); }
    }

    public static void main(String[] args) throws Exception {
        differential();
        newBehaviour();
        System.out.println("\n" + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    /** The four non-IP checks must behave exactly like the original over many random scenarios. */
    static void differential() throws Exception {
        Random rnd = new Random(2026);
        int scenarios = 0, compared = 0, mismatches = 0, denials = 0;
        for (int s = 0; s < 150; s++) {
            Env oldE = new Env(false), newE = new Env(true);
            Map<String,Object> cfg = new HashMap<>();
            for (String k : new String[]{"same-ip", "minimum-victim-alive-time", "victim-cooldown", "same-victim-window-limit", "pair-window-limit"})
                cfg.put("anti-farm.enabled." + k, rnd.nextInt(4) != 0);
            cfg.put("anti-farm.minimum-victim-alive-seconds", new int[]{0, 0, 3600}[rnd.nextInt(3)]);
            cfg.put("anti-farm.victim-cooldown-seconds", new int[]{0, 0, 3600}[rnd.nextInt(3)]);
            cfg.put("anti-farm.same-victim-window-seconds", 1800);
            cfg.put("anti-farm.max-same-victim-kills", 1 + rnd.nextInt(3));
            cfg.put("anti-farm.pair-window-seconds", 1800);
            cfg.put("anti-farm.max-pair-kills", 1 + rnd.nextInt(4));
            oldE.values.putAll(cfg); newE.values.putAll(cfg);
            String[] names = {"A", "B", "C", "D"};
            List<Object> po = new ArrayList<>(), pn = new ArrayList<>();
            for (int i = 0; i < 4; i++) { po.add(oldE.player(names[i], "198.51.100." + (10 + i))); pn.add(newE.player(names[i], "198.51.100." + (10 + i))); }
            for (int i = 0; i < 4; i++) if (rnd.nextInt(5) != 0) { oldE.markAlive(po.get(i)); newE.markAlive(pn.get(i)); }
            scenarios++;
            for (int op = 0; op < 60; op++) {
                int k = rnd.nextInt(4), v = rnd.nextInt(4);
                if (k == v) continue;
                if (rnd.nextInt(8) == 0) { oldE.markAlive(po.get(v)); newE.markAlive(pn.get(v)); }
                String ro = oldE.check(po.get(k), po.get(v)), rn = newE.check(pn.get(k), pn.get(v));
                compared++;
                if (ro != null) denials++;
                if (!Objects.equals(ro, rn)) mismatches++;
            }
        }
        check("non-IP checks identical to original: " + compared + " decisions over " + scenarios + " random scenarios ("
                + denials + " denials exercised), mismatches=" + mismatches, mismatches == 0 && denials > 100);
    }

    static Env fresh() throws Exception {
        Env e = new Env(true);
        e.values.put("anti-farm.enabled.same-ip", true);
        e.values.put("anti-farm.minimum-victim-alive-seconds", 0);
        e.values.put("anti-farm.victim-cooldown-seconds", 0);
        e.values.put("anti-farm.same-victim-window-seconds", 1800);
        e.values.put("anti-farm.max-same-victim-kills", 50);
        e.values.put("anti-farm.pair-window-seconds", 1800);
        e.values.put("anti-farm.max-pair-kills", 50);
        return e;
    }

    static void newBehaviour() throws Exception {
        // 0. reproduce the reported bug on the ORIGINAL class
        Env orig = new Env(false);
        orig.values.put("anti-farm.minimum-victim-alive-seconds", 0);
        Object a = orig.player("Me", "203.0.113.50"), b = orig.player("Friend", "203.0.113.50");
        orig.markAlive(b);
        String msg = orig.check(a, b);
        check("BUG REPRODUCED on original: two players behind one proxy address are rejected (" + msg + ")",
                msg != null && msg.contains("same IP address"));

        // 1. different real IPs
        Env e = fresh();
        a = e.player("Me", "203.0.113.50"); b = e.player("Friend", "198.51.100.77");
        e.online.add(a); e.online.add(b); e.markAlive(b);
        check("different real IPs: kill rewarded", e.check(a, b) == null);

        // 2. shared PUBLIC address, default policy LIMIT: first kill ok, repeat blocked by shared-address cooldown
        e = fresh();
        a = e.player("Me", "203.0.113.50"); b = e.player("Friend", "203.0.113.50");
        e.online.add(a); e.online.add(b); e.markAlive(b);
        String first = e.check(a, b), second = e.check(a, b);
        check("shared public address, default LIMIT: first kill is rewarded (not blocked outright)", first == null);
        check("shared public address, default LIMIT: immediate repeat is limited (anti-farm kept)", second != null && second.contains("limited"));
        check("diagnostic logged with the address and the fix", e.logs.stream().anyMatch(l -> l.contains("203.0.113.50") && l.contains("ignored-addresses")));

        // 3. BLOCK keeps the original behaviour and message
        e = fresh(); e.values.put("anti-farm.ip-check.on-shared-address", "BLOCK");
        a = e.player("Me", "203.0.113.50"); b = e.player("Friend", "203.0.113.50"); e.online.add(a); e.online.add(b); e.markAlive(b);
        String blocked = e.check(a, b);
        check("on-shared-address: BLOCK reproduces the original rejection and message", blocked != null && blocked.contains("same IP address"));

        // 4. ALLOW
        e = fresh(); e.values.put("anti-farm.ip-check.on-shared-address", "ALLOW");
        a = e.player("Me", "203.0.113.50"); b = e.player("Friend", "203.0.113.50"); e.online.add(a); e.online.add(b); e.markAlive(b);
        check("on-shared-address: ALLOW rewards shared-address kills", e.check(a, b) == null && e.check(a, b) == null);

        // 5. loopback / private = unreliable even with BLOCK
        for (String ip : new String[]{"127.0.0.1", "192.168.1.20", "10.0.0.5", "172.16.4.4", "100.64.1.1", "::1"}) {
            e = fresh(); e.values.put("anti-farm.ip-check.on-shared-address", "BLOCK");
            a = e.player("Me", ip); b = e.player("Friend", ip); e.online.add(a); e.online.add(b); e.markAlive(b);
            check("tunnel/LAN address " + ip + " is not treated as 'same household' even with BLOCK", e.check(a, b) == null);
        }

        // 6. private addresses count again when ignore-private-addresses=false
        e = fresh(); e.values.put("anti-farm.ip-check.on-shared-address", "BLOCK"); e.values.put("anti-farm.ip-check.ignore-private-addresses", false);
        a = e.player("Me", "192.168.1.20"); b = e.player("Friend", "192.168.1.20"); e.online.add(a); e.online.add(b); e.markAlive(b);
        check("ignore-private-addresses=false: LAN duplicates are blocked again", e.check(a, b) != null);

        // 7. proxy auto-detection: 3 online players on one address
        e = fresh(); e.values.put("anti-farm.ip-check.on-shared-address", "BLOCK");
        a = e.player("Me", "203.0.113.50"); b = e.player("Friend", "203.0.113.50"); Object c = e.player("Third", "203.0.113.50");
        e.online.add(a); e.online.add(b); e.online.add(c); e.markAlive(b);
        check("3 online players on one address = proxy detected, kill not rejected as same-IP", e.check(a, b) == null);
        check("proxy detection warns the admin how to fix it", e.logs.stream().anyMatch(l -> l.contains("tunnel/proxy")));

        // 8. configured ignored address and CIDR; invalid entry must not crash
        e = fresh(); e.values.put("anti-farm.ip-check.on-shared-address", "BLOCK");
        e.values.put("anti-farm.ip-check.ignored-addresses", List.of("not-an-ip", "203.0.113.0/24"));
        a = e.player("Me", "203.0.113.50"); b = e.player("Friend", "203.0.113.50"); e.online.add(a); e.online.add(b); e.markAlive(b);
        check("ignored-addresses CIDR 203.0.113.0/24 covers the proxy; bad entry only logs a warning",
                e.check(a, b) == null && e.logs.stream().anyMatch(l -> l.contains("invalid entry")));
        e = fresh(); e.values.put("anti-farm.ip-check.on-shared-address", "BLOCK"); e.values.put("anti-farm.ip-check.ignored-addresses", List.of("203.0.114.0/24"));
        a = e.player("Me", "203.0.113.50"); b = e.player("Friend", "203.0.113.50"); e.online.add(a); e.online.add(b); e.markAlive(b);
        check("a CIDR that does not contain the address does not exempt it", e.check(a, b) != null);

        // 9. null address (not resolved): same as original, no crash
        e = fresh(); a = e.player("Me", null); b = e.player("Friend", null); e.markAlive(b);
        check("missing/unresolved addresses do not crash and are not treated as equal", e.check(a, b) == null);

        // 10. master switch
        e = fresh(); e.values.put("anti-farm.enabled.same-ip", false); e.values.put("anti-farm.ip-check.on-shared-address", "BLOCK");
        a = e.player("Me", "203.0.113.50"); b = e.player("Friend", "203.0.113.50"); e.online.add(a); e.online.add(b); e.markAlive(b);
        check("anti-farm.enabled.same-ip=false still disables the address check completely", e.check(a, b) == null);

        // 11. genuine farming still blocked for players on different IPs (same-victim limit)
        e = fresh(); e.values.put("anti-farm.max-same-victim-kills", 2);
        a = e.player("Me", "203.0.113.50"); b = e.player("Friend", "198.51.100.77"); e.online.add(a); e.online.add(b); e.markAlive(b);
        boolean r1 = e.check(a, b) == null, r2 = e.check(a, b) == null; String r3 = e.check(a, b);
        check("farming one victim on different IPs: 2 rewarded, 3rd blocked by the existing window limit", r1 && r2 && r3 != null && r3.contains("limit"));

        // 12. rejection logging can be switched off
        e = fresh(); e.values.put("anti-farm.max-same-victim-kills", 1); e.values.put("anti-farm.log-rejections", false);
        a = e.player("Me", "203.0.113.50"); b = e.player("Friend", "198.51.100.77"); e.online.add(a); e.online.add(b); e.markAlive(b);
        e.check(a, b); e.check(a, b);
        check("anti-farm.log-rejections=false silences rejection logging", e.logs.stream().noneMatch(l -> l.contains("No kill reward")));
        e = fresh(); e.values.put("anti-farm.max-same-victim-kills", 1);
        a = e.player("Me", "203.0.113.50"); b = e.player("Friend", "198.51.100.77"); e.online.add(a); e.online.add(b); e.markAlive(b);
        e.check(a, b); e.check(a, b);
        check("rejections are logged with killer, victim and a reason code", e.logs.stream().anyMatch(l -> l.contains("killer=Me") && l.contains("victim=Friend") && l.contains("SAME_VICTIM_WINDOW_LIMIT")));
    }
}
