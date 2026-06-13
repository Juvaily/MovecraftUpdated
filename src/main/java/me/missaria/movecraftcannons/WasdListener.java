package me.missaria.movecraftcannons;

import net.countercraft.movecraft.MovecraftLocation;
import net.countercraft.movecraft.MovecraftRotation;
import net.countercraft.movecraft.craft.CraftManager;
import net.countercraft.movecraft.craft.PlayerCraft;
import net.countercraft.movecraft.util.hitboxes.HitBox;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WasdListener implements Listener {

    private final MovecraftCannonsPlugin plugin;
    private final Map<UUID, Long> lastRotate = new ConcurrentHashMap<>();

    private static final long ROTATE_DEBOUNCE = 600L;

    // Cached NMS fields — discovered once on first pilot login
    private volatile Field xxaField;
    private volatile Field zzaField;
    private volatile boolean nmsReady  = false; // true once init succeeded
    private volatile boolean nmsFailed = false; // true once init gave up

    public WasdListener(MovecraftCannonsPlugin plugin) {
        this.plugin = plugin;
        long ticks = Math.max(1L, plugin.getConfig().getLong("wasd.cooldown_ms", 200L) / 50L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickMovement, 0L, ticks);
    }

    // ── Periodic movement tick ─────────────────────────────────────────────────

    private void tickMovement() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerCraft craft = CraftManager.getInstance().getCraftByPlayer(player);
            if (craft == null || !craft.getPilotLocked()) continue;

            int[] dir = getInputDir(player);
            if (dir == null || (dir[0] == 0 && dir[1] == 0)) continue;

            craft.translate(dir[0], 0, dir[1]);

            if (plugin.isDebug())
                plugin.getLogger().info("[wasd] " + player.getName()
                    + " (" + dir[0] + ",0," + dir[1] + ") notProcessing=" + craft.isNotProcessing());
        }
    }

    // ── NMS input reading ──────────────────────────────────────────────────────

    private int[] getInputDir(Player player) {
        if (nmsFailed) return null;
        try {
            if (!nmsReady) initNmsFields(player);
            if (!nmsReady) return null;

            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            float xxa = (float) xxaField.get(handle);
            float zza = (float) zzaField.get(handle);
            return resolveFromAxes(player.getLocation().getYaw(), xxa, zza);
        } catch (Exception e) {
            if (plugin.isDebug())
                plugin.getLogger().warning("[wasd] NMS error: " + e.getMessage());
            return null;
        }
    }

    private synchronized void initNmsFields(Player player) {
        if (nmsReady || nmsFailed) return;
        try {
            Method getHandle = player.getClass().getMethod("getHandle");
            Object nmsPlayer = getHandle.invoke(player);
            Class<?> cls = nmsPlayer.getClass();
            while (cls != null) {
                try {
                    Field xf = cls.getDeclaredField("xxa");
                    Field zf = cls.getDeclaredField("zza");
                    if (xf.getType() == float.class && zf.getType() == float.class) {
                        xf.setAccessible(true);
                        zf.setAccessible(true);
                        xxaField = xf;
                        zzaField = zf;
                        nmsReady = true;
                        plugin.getLogger().info("[wasd] NMS input fields found in " + cls.getSimpleName());
                        return;
                    }
                } catch (NoSuchFieldException ignored) {}
                cls = cls.getSuperclass();
            }
            plugin.getLogger().warning("[wasd] NMS xxa/zza not found — WASD disabled");
            nmsFailed = true;
        } catch (Exception e) {
            plugin.getLogger().warning("[wasd] NMS init failed: " + e.getMessage());
            nmsFailed = true;
        }
    }

    /**
     * Converts NMS xxa/zza axes into a world-space (dx, dz) translation vector.
     * xxa = strafe input: positive = D (right), negative = A (left).
     * zza = forward input: positive = W (forward), negative = S (backward).
     * Yaw is snapped to nearest 90° cardinal for clean block-aligned movement.
     */
    private int[] resolveFromAxes(float yaw, float xxa, float zza) {
        if (Math.abs(xxa) < 0.1f && Math.abs(zza) < 0.1f) return new int[]{0, 0};

        double y = ((yaw % 360) + 360) % 360;
        int snapped = ((int) Math.round(y / 90.0) * 90) % 360;

        int fx = -(int) Math.round(Math.sin(Math.toRadians(snapped)));
        int fz =  (int) Math.round(Math.cos(Math.toRadians(snapped)));
        int rx = -(int) Math.round(Math.sin(Math.toRadians(snapped + 90)));
        int rz =  (int) Math.round(Math.cos(Math.toRadians(snapped + 90)));

        int dx = 0, dz = 0;
        if (zza >  0.1f) { dx += fx; dz += fz; }  // W
        if (zza < -0.1f) { dx -= fx; dz -= fz; }  // S
        if (xxa >  0.1f) { dx += rx; dz += rz; }  // D
        if (xxa < -0.1f) { dx -= rx; dz -= rz; }  // A

        return new int[]{Math.max(-1, Math.min(1, dx)), Math.max(-1, Math.min(1, dz))};
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
