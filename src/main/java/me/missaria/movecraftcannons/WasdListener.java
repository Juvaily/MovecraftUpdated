package me.missaria.movecraftcannons;

import net.countercraft.movecraft.MovecraftLocation;
import net.countercraft.movecraft.MovecraftRotation;
import net.countercraft.movecraft.craft.CraftManager;
import net.countercraft.movecraft.craft.PlayerCraft;
import net.countercraft.movecraft.util.hitboxes.HitBox;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WasdListener implements Listener {

    private final MovecraftCannonsPlugin plugin;

    // Last detected direction (dx, dz) per player
    private final Map<UUID, Long> lastMove   = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRotate = new ConcurrentHashMap<>();

    private static final long   ROTATE_DEBOUNCE = 600L;
    private static final double MOVE_THRESHOLD  = 0.005;

    public WasdListener(MovecraftCannonsPlugin plugin) {
        this.plugin = plugin;
    }

    // ── WASD: translate directly in the event with per-player cooldown ─────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent) return;

        Location from = event.getFrom();
        Location to   = event.getTo();
        if (to == null) return;

        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        if (Math.abs(dx) < MOVE_THRESHOLD && Math.abs(dz) < MOVE_THRESHOLD) return;

        Player      player = event.getPlayer();
        PlayerCraft craft  = CraftManager.getInstance().getCraftByPlayer(player);
        if (craft == null || !craft.getPilotLocked()) return;

        UUID uid      = player.getUniqueId();
        long now      = System.currentTimeMillis();
        long cooldown = plugin.getConfig().getLong("wasd.cooldown_ms", 200L);
        Long last     = lastMove.get(uid);
        if (last != null && now - last < cooldown) return;
        lastMove.put(uid, now);

        int[] dir = resolveDir(player.getLocation().getYaw(), dx, dz);
        if (dir[0] == 0 && dir[1] == 0) return;

        craft.translate(dir[0], 0, dir[1]);

        if (plugin.isDebug())
            plugin.getLogger().info("[wasd] " + player.getName()
                + " (" + dir[0] + ",0," + dir[1] + ") notProcessing=" + craft.isNotProcessing());
    }

    /**
     * Converts a movement delta + player yaw to a world-space (dx, dz) step.
     * Yaw is snapped to the nearest 90° cardinal; the delta is projected onto
     * the resulting forward/right axes to determine which key was pressed.
     */
    private int[] resolveDir(float rawYaw, double dx, double dz) {
        double y       = ((rawYaw % 360) + 360) % 360;
        int    snapped = ((int) Math.round(y / 90.0) * 90) % 360;

        int fx = -(int) Math.round(Math.sin(Math.toRadians(snapped)));
        int fz =  (int) Math.round(Math.cos(Math.toRadians(snapped)));
        int rx = -(int) Math.round(Math.sin(Math.toRadians(snapped + 90)));
        int rz =  (int) Math.round(Math.cos(Math.toRadians(snapped + 90)));

        double fwd    = dx * fx + dz * fz;
        double strafe = dx * rx + dz * rz;

        int rdx = 0, rdz = 0;
        if      (fwd    >  MOVE_THRESHOLD) { rdx += fx; rdz += fz; }
        else if (fwd    < -MOVE_THRESHOLD) { rdx -= fx; rdz -= fz; }
        if      (strafe >  MOVE_THRESHOLD) { rdx += rx; rdz += rz; }
        else if (strafe < -MOVE_THRESHOLD) { rdx -= rx; rdz -= rz; }

        return new int[]{Math.max(-1, Math.min(1, rdx)), Math.max(-1, Math.min(1, rdz))};
    }

    // ── Q → rotate left — only in Direct Control ──────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDropItem(PlayerDropItemEvent event) {
        Player      player = event.getPlayer();
        PlayerCraft craft  = CraftManager.getInstance().getCraftByPlayer(player);
        if (craft == null || !craft.getPilotLocked()) return;
        event.setCancelled(true);
        if (!rotateDebounce(player.getUniqueId())) return;
        rotateCraft(craft, MovecraftRotation.ANTICLOCKWISE);
    }

    // ── F → rotate right — only in Direct Control ─────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player      player = event.getPlayer();
        PlayerCraft craft  = CraftManager.getInstance().getCraftByPlayer(player);
        if (craft == null || !craft.getPilotLocked()) return;
        event.setCancelled(true);
        if (!rotateDebounce(player.getUniqueId())) return;
        rotateCraft(craft, MovecraftRotation.CLOCKWISE);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean rotateDebounce(UUID uid) {
        long now  = System.currentTimeMillis();
        Long last = lastRotate.get(uid);
        if (last != null && now - last < ROTATE_DEBOUNCE) return false;
        lastRotate.put(uid, now);
        return true;
    }

    private void rotateCraft(PlayerCraft craft, MovecraftRotation rotation) {
        HitBox hb = craft.getHitBox();
        craft.rotate(rotation, new MovecraftLocation(
                (hb.getMinX() + hb.getMaxX()) / 2,
                (hb.getMinY() + hb.getMaxY()) / 2,
                (hb.getMinZ() + hb.getMaxZ()) / 2
        ));
    }
}
