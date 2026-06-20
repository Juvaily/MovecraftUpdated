package me.missaria.movecraftcannons;

import at.pavlov.cannons.cannon.Cannon;
import net.countercraft.movecraft.MovecraftLocation;
import net.countercraft.movecraft.MovecraftRotation;
import net.countercraft.movecraft.craft.Craft;
import net.countercraft.movecraft.craft.CraftManager;
import net.countercraft.movecraft.craft.PlayerCraft;
import net.countercraft.movecraft.craft.type.CraftType;
import net.countercraft.movecraft.events.CraftReleaseEvent;
import net.countercraft.movecraft.util.hitboxes.HitBox;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TurretListener implements Listener {

    private static final long ROTATE_DEBOUNCE_MS = 400L;

    private static final Set<Material> RESERVED_ITEMS = Set.of(
            Material.WRITTEN_BOOK, Material.WRITABLE_BOOK,
            Material.CLOCK
    );

    private final Map<UUID, List<Craft>> turretCache = new ConcurrentHashMap<>();
    private final Map<UUID, Integer>     selectedIdx  = new ConcurrentHashMap<>();
    private final Map<UUID, Long>        lastRotate   = new ConcurrentHashMap<>();

    // ── Shift → cycle attached turrets ───────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) return;
        Player player = event.getPlayer();
        PlayerCraft craft = CraftManager.getInstance().getCraftByPlayer(player);
        if (craft == null || !craft.getPilotLocked()) return;
        event.setCancelled(true);

        List<Craft> turrets = findAttachedTurrets(craft);
        UUID uid = player.getUniqueId();
        turretCache.put(uid, turrets);

        if (turrets.isEmpty()) {
            selectedIdx.remove(uid);
            player.sendMessage(Lang.get("turret.none", player));
            return;
        }

        int idx = (selectedIdx.getOrDefault(uid, -1) + 1) % turrets.size();
        selectedIdx.put(uid, idx);
        player.sendMessage(Lang.msg("turret.selected", player, NamedTextColor.AQUA,
                idx + 1, turrets.size(), turretName(turrets.get(idx))));
    }

    // ── Left/right click → rotate selected turret ────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        boolean isLeft  = action == Action.LEFT_CLICK_AIR  || action == Action.LEFT_CLICK_BLOCK;
        boolean isRight = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        if (!isLeft && !isRight) return;

        Player player = event.getPlayer();
        PlayerCraft craft = CraftManager.getInstance().getCraftByPlayer(player);
        if (craft == null || !craft.getPilotLocked()) return;

        UUID uid = player.getUniqueId();
        if (!selectedIdx.containsKey(uid)) return;

        ItemStack held = event.getItem();
        if (held != null && RESERVED_ITEMS.contains(held.getType())) return;

        Craft turret = getSelectedTurret(uid);
        if (turret == null) { selectedIdx.remove(uid); return; }

        event.setCancelled(true);
        if (!debounce(uid)) return;

        rotateTurret(turret, isLeft ? MovecraftRotation.ANTICLOCKWISE : MovecraftRotation.CLOCKWISE);
    }

    // ── Clear on release / quit ───────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRelease(CraftReleaseEvent event) {
        if (!(event.getCraft() instanceof net.countercraft.movecraft.craft.PilotedCraft pc)) return;
        Player pilot = pc.getPilot();
        if (pilot != null) clear(pilot.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) { clear(event.getPlayer().getUniqueId()); }

    private void clear(UUID uid) {
        turretCache.remove(uid);
        selectedIdx.remove(uid);
        lastRotate.remove(uid);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public Craft getSelectedTurret(UUID uid) {
        Integer idx = selectedIdx.get(uid);
        if (idx == null) return null;
        List<Craft> list = turretCache.get(uid);
        if (list == null || idx >= list.size()) return null;
        Craft t = list.get(idx);
        if (!isRegistered(t)) { clear(uid); return null; }
        return t;
    }

    public String getHudLine(UUID uid) {
        Integer idx = selectedIdx.get(uid);
        List<Craft> list = turretCache.get(uid);
        if (idx == null || list == null || list.isEmpty() || idx >= list.size()) return null;
        return "§b🎯 " + turretName(list.get(idx)) + " §8[" + (idx + 1) + "/" + list.size() + "]";
    }

    public List<Cannon> getAttachedTurretCannons(PlayerCraft parent) {
        List<Cannon> result = new ArrayList<>();
        for (Craft t : findAttachedTurrets(parent))
            result.addAll(CannonUtils.findCannonsOnCraft(t));
        return result;
    }

    // ── Turret detection ──────────────────────────────────────────────────────

    public List<Craft> findAttachedTurrets(PlayerCraft parent) {
        List<Craft> result = new ArrayList<>();
        HitBox parentBox = parent.getHitBox();
        for (Craft craft : CraftManager.getInstance().getCraftsInWorld(parent.getWorld())) {
            if (craft == parent) continue;
            if (!isTurretType(craft)) continue;
            if (touches(parentBox, craft.getHitBox())) result.add(craft);
        }
        return result;
    }

    private boolean isTurretType(Craft craft) {
        try { return "Turret".equalsIgnoreCase(craft.getType().getStringProperty(CraftType.NAME)); }
        catch (Exception e) { return false; }
    }

    private boolean isRegistered(Craft turret) {
        try { return CraftManager.getInstance().getCraftsInWorld(turret.getWorld()).contains(turret); }
        catch (Exception e) { return false; }
    }

    private boolean touches(HitBox a, HitBox b) {
        return b.getMinX() <= a.getMaxX() + 1 && b.getMaxX() >= a.getMinX() - 1
            && b.getMinY() <= a.getMaxY() + 1 && b.getMaxY() >= a.getMinY() - 1
            && b.getMinZ() <= a.getMaxZ() + 1 && b.getMaxZ() >= a.getMinZ() - 1;
    }

    private void rotateTurret(Craft turret, MovecraftRotation rot) {
        HitBox hb = turret.getHitBox();
        try {
            turret.rotate(rot, new MovecraftLocation(
                    (hb.getMinX() + hb.getMaxX()) / 2,
                    (hb.getMinY() + hb.getMaxY()) / 2,
                    (hb.getMinZ() + hb.getMaxZ()) / 2));
        } catch (Exception ignored) {}
    }

    private boolean debounce(UUID uid) {
        long now = System.currentTimeMillis();
        Long last = lastRotate.get(uid);
        if (last != null && now - last < ROTATE_DEBOUNCE_MS) return false;
        lastRotate.put(uid, now);
        return true;
    }

    private String turretName(Craft turret) {
        try { String n = turret.getName(); if (n != null && !n.isBlank()) return n; }
        catch (Exception ignored) {}
        return Lang.get("turret.default_name");
    }
}
