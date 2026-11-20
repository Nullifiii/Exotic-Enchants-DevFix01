package com.exoticenchants.effects;

import com.exoticenchants.config.Settings;
import com.exoticenchants.core.Registry;
import com.exoticenchants.item.EnchantPool;
import com.exoticenchants.item.ItemTags;
import com.exoticenchants.logic.EffectMath;
import com.exoticenchants.logic.EffectMath.DamageKind;
import com.exoticenchants.logic.EffectSupport;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Levelled;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityBlockFormEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The effects vanilla does NOT provide for exotic enchantments on unusual items. Only combinations
 * classified CUSTOM by {@link EffectSupport} are handled here, and only while the item is a VALID registered
 * exotic (a stolen/revoked item stops working immediately, before it is stripped).
 *
 *  - Frost Walker on any non-boots item: freezes water around the player while the item is held.
 *  - Sharpness / Smite / Bane of Arthropods / Fire Aspect / Density on bows and crossbows: apply to arrows.
 *  - Protection, Fire/Blast/Projectile Protection, Feather Falling on non-armor items: apply while held.
 * Vanilla behaviour (e.g. Frost Walker on boots, Density on a mace) is never touched.
 */
public final class ExoticEffectsListener implements Listener {

    private record Held(String enchant, int level) {
    }

    private final Settings settings;
    private final Registry registry;
    private final ItemTags tags;
    private final NamespacedKey arrowEnchant;
    private final NamespacedKey arrowLevel;
    private final NamespacedKey arrowFall;
    /** Blocks this plugin turned into frosted ice -> when to turn them back into water. */
    private final Map<Block, Long> frozen = new HashMap<>();

    public ExoticEffectsListener(Plugin plugin, Settings settings, Registry registry, ItemTags tags) {
        this.settings = settings;
        this.registry = registry;
        this.tags = tags;
        this.arrowEnchant = new NamespacedKey(plugin, "arrow_enchant");
        this.arrowLevel = new NamespacedKey(plugin, "arrow_level");
        this.arrowFall = new NamespacedKey(plugin, "arrow_fall");
    }

    // ------------------------------------------------------------------ helpers

    /** The CUSTOM-handled exotic on this stack, or null (also null if the registry no longer owns it). */
    private Held customExotic(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        ItemTags.ExoticInfo info = tags.info(item);
        if (info == null || !registry.isValidItem(info.itemUuid())) return null;
        Enchantment ench = EnchantPool.lookup(info.enchant());
        boolean vanilla = ench != null && ench.canEnchantItem(item);
        if (EffectSupport.support(info.enchant(), item.getType().name(), vanilla) != EffectSupport.Support.CUSTOM) return null;
        return new Held(info.enchant(), info.level());
    }

    private int heldLevel(Player p, String enchantKey) {
        int best = 0;
        for (ItemStack it : new ItemStack[]{p.getInventory().getItemInMainHand(), p.getInventory().getItemInOffHand()}) {
            Held h = customExotic(it);
            if (h != null && h.enchant().equals(enchantKey)) best = Math.max(best, h.level());
        }
        return best;
    }

    // -------------------------------------------------------------- frost walker

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!settings.frostHeld) return;
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) return;
        Player p = e.getPlayer();
        int level = heldLevel(p, "frost_walker");
        if (level <= 0) return;

        int radius = EffectMath.frostRadius(level, settings.frostMaxRadius);
        Block center = to.getBlock().getRelative(0, -1, 0);
        long expiry = System.currentTimeMillis() + settings.frostMeltTicks * 50L;
        for (int[] o : EffectMath.frostOffsets(radius)) {
            Block b = center.getRelative(o[0], 0, o[1]);
            if (b.getType() != Material.WATER) continue;
            if (!(b.getBlockData() instanceof Levelled water) || water.getLevel() != 0) continue;   // source blocks only
            if (!b.getRelative(0, 1, 0).getType().isAir()) continue;
            BlockState newState = b.getState();
            newState.setType(Material.FROSTED_ICE);
            EntityBlockFormEvent form = new EntityBlockFormEvent(p, b, newState);
            Bukkit.getPluginManager().callEvent(form);        // lets region/claim plugins veto it
            if (form.isCancelled()) continue;
            b.setType(Material.FROSTED_ICE);
            frozen.put(b, expiry);
        }
    }

    /** Called every 10 ticks: turns expired ice back into water. {@code all} reverts everything (shutdown). */
    public void sweep(boolean all) {
        if (frozen.isEmpty()) return;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Block, Long>> it = frozen.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Block, Long> en = it.next();
            if (!all && en.getValue() > now) continue;
            Block b = en.getKey();
            if (b.getType() == Material.FROSTED_ICE) b.setType(Material.WATER);   // vanilla may already have melted it
            it.remove();
        }
    }

    // ------------------------------------------------------- arrow enchantments

    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Held h = customExotic(e.getBow());
        if (h == null || !EffectSupport.ARROW_ENCHANTS.contains(h.enchant())) return;
        if (h.enchant().equals("density") ? !settings.densityEnabled : !settings.arrowEnchants) return;
        PersistentDataContainer pdc = e.getProjectile().getPersistentDataContainer();
        pdc.set(arrowEnchant, PersistentDataType.STRING, h.enchant());
        pdc.set(arrowLevel, PersistentDataType.INTEGER, h.level());
        pdc.set(arrowFall, PersistentDataType.DOUBLE, (double) p.getFallDistance());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArrowHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Projectile proj)) return;
        PersistentDataContainer pdc = proj.getPersistentDataContainer();
        String ench = pdc.get(arrowEnchant, PersistentDataType.STRING);
        Integer level = pdc.get(arrowLevel, PersistentDataType.INTEGER);
        if (ench == null || level == null) return;
        Entity victim = e.getEntity();
        if (!(victim instanceof LivingEntity living)) return;
        String type = victim.getType().name();
        double extra = 0;
        switch (ench) {
            case "density" -> {
                Double fall = pdc.get(arrowFall, PersistentDataType.DOUBLE);
                extra = EffectMath.densityBonus(level, fall == null ? 0 : fall, settings.densityPerBlock,
                        settings.densityMinFall, settings.densityMaxBonus);
            }
            case "sharpness" -> extra = EffectMath.sharpnessBonus(level);
            case "smite" -> extra = EffectMath.smiteBonus(level, type);
            case "bane_of_arthropods" -> extra = EffectMath.baneBonus(level, type);
            case "fire_aspect" -> living.setFireTicks(Math.max(living.getFireTicks(), EffectMath.fireTicks(level)));
            default -> {
                // not an arrow enchantment
            }
        }
        if (extra > 0) e.setDamage(e.getDamage() + extra);
    }

    // ------------------------------------------------- protection on held items

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamaged(EntityDamageEvent e) {
        if (!settings.protectionHeld) return;
        if (!(e.getEntity() instanceof Player p)) return;
        DamageKind kind = switch (e.getCause()) {
            case FIRE, FIRE_TICK, LAVA, HOT_FLOOR -> DamageKind.FIRE;
            case BLOCK_EXPLOSION, ENTITY_EXPLOSION -> DamageKind.EXPLOSION;
            case PROJECTILE -> DamageKind.PROJECTILE;
            case FALL -> DamageKind.FALL;
            default -> DamageKind.OTHER;
        };
        int epf = 0;
        for (ItemStack it : new ItemStack[]{p.getInventory().getItemInMainHand(), p.getInventory().getItemInOffHand()}) {
            Held h = customExotic(it);
            if (h != null && EffectSupport.HELD_PROTECTION.contains(h.enchant())) epf += EffectMath.epf(h.enchant(), h.level(), kind);
        }
        if (epf > 0) e.setDamage(EffectMath.applyProtection(e.getDamage(), epf, settings.protectionMaxReduction));
    }
}
