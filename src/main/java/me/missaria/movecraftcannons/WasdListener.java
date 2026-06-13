package me.missaria.movecraftcannons;

import net.countercraft.movecraft.MovecraftLocation;
import net.countercraft.movecraft.MovecraftRotation;
import net.countercraft.movecraft.craft.CraftManager;
import net.countercraft.movecraft.craft.PlayerCraft;
import net.countercraft.movecraft.events.CraftReleaseEvent;
import net.countercraft.movecraft.util.hitboxes.HitBox;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WasdListener implements Listener {

    private final MovecraftCannonsPlugin plugin;

    private final Map<UUID, int[]> latestDir  = new ConcurrentHashMap<>();
    private final Map<UUID, Long>  latestTime = new ConcurrentHashMap<>();
    private final Map<UUID, Long>  lastRotate = new ConcurrentHashMap<>();

    // Saved player state while in Direct Control
    private final Map<UUID, Boolean> savedAllowFlight = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> savedFlying      = new ConcurrentHashMap<>();
    private final Map<UUID, Float>   savedWalkSpeed   = new ConcurrentHashMap<>();
    private final Map<UUID, Float>   savedFlySpeed    = new ConcurrentHashMap<>();

    private static final long  ROTATE_DEBOUNCE = 600L;
    // Minimum speed: slow enough to be invisible, non-zero so client always sends packets
    private static final float PILOT_SPEED     = 0.005f;
    // Low threshold to catch near-zero deltas from minimum speed
    private static final double MOVE_THRESHOLD = 0.001;

    public WasdListener(MovecraftCannonsPlugin plugin) {
        this.plugin = plugin;
        long cooldown = plugin.getConfig().getLong("wasd.cooldown_ms", 200L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, Math.max(1L, cooldown / 50L));
        // Sync flight state with pilotLocked every 10 ticks
        Bukkit.getScheduler().runTaskTimer(plugin, this::syncFlightState, 0L, 10L);
    }

    // ── Flight state management ────────────────────────────────────────────────

    /**
     * While pilotLocked:
     * - Flying mode (no gravity, client can drift freely through air pockets)
     * - WalkSpeed / FlySpeed set to PILOT_SPEED (~0): movement is nearly invisible
     *   but non-zero, so the client sends a movement packet every tick while a key
     *   is held — even near walls where at least a tiny gap exists.
     */
    private void syncFlightState() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uid = player.getUniqueId();
            PlayerCraft craft = CraftManager.getInstance().getCraftByPlayer(player);
            boolean locked  = craft != null && craft.getPilotLocked();
            boolean tracked = savedAllowFlight.containsKey(uid);

            if (locked && !tracked) {
                savedAllowFlight.put(uid, player.getAllowFlight());
                savedFlying.put(uid, player.isFlying());
                // Guard: if speed is already at PILOT_SPEED (failed restore from prev session), save default
                float curWalk = player.getWalkSpeed();
                float curFly  = player.getFlySpeed();
                savedWalkSpeed.put(uid, curWalk > PILOT_SPEED * 2 ? curWalk : 0.2f);
                savedFlySpeed.put(uid,  curFly  > PILOT_SPEED * 2 ? curFly  : 0.1f);
                player.setAllowFlight(true);
                player.setFlying(true);
                player.setWalkSpeed(PILOT_SPEED);
                player.setFlySpeed(PILOT_SPEED);
            } else if (!locked && tracked) {
                restoreFlight(player);
            }
        }
    }

    private void restoreFlight(Player player) {
        UUID uid = player.getUniqueId();
        Boolean origAllow  = savedAllowFlight.remove(uid);
        Boolean origFlying = savedFlying.remove(uid);
        Float   origWalk   = savedWalkSpeed.remove(uid);
        Float   origFly    = savedFlySpeed.remove(uid);
        if (origAllow != null) {
            player.setAllowFlight(origAllow);
            if (origFlying != null && !origFlying && !origAllow) player.setFlying(false);
        } else {
            // Failsafe: no saved state, still reset speeds to defaults
            player.setWalkSpeed(0.2f);
            player.setFlySpeed(0.1f);
            latestDir.remove(uid);
            latestTime.remove(uid);
            return;
        }
        player.setWalkSpeed(origWalk != null ? origWalk : 0.2f);
        player.setFlySpeed(origFly   != null ? origFly  : 0.1f);
        latestDir.remove(uid);
        latestTime.remove(uid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCraftRelease(CraftReleaseEvent event) {
        if (!(event.getCraft() instanceof net.countercraft.movecraft.craft.PilotedCraft pc)) return;
        Player pilot = pc.getPilot();
        if (pilot != null) restoreFlight(pilot);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        restoreFlight(event.getPlayer());
    }

    // ── WASD direction capture ─────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
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

        int[] dir = resolveDir(player.getLocation().getYaw(), dx, dz);
        if (dir[0] == 0 && dir[1] == 0) return;

        UUID uid = player.getUniqueId();
        latestDir.put(uid, dir);
        latestTime.put(uid, System.currentTimeMillis());

        // Push player back to original position (keep look direction)
        Location pushBack = from.clone();
        pushBack.setYaw(to.getYaw());
        pushBack.setPitch(to.getPitch());
        event.setTo(pushBack);
    }

    // ── Periodic movement tick ─────────────────────────────────────────────────

    private void tick() {
        long now      = System.currentTimeMillis();
        long cooldown = plugin.getConfig().getLong("wasd.cooldown_ms", 200L);
        // Near-zero speed means events fire every tick (~50ms) while key is held.
        // TTL of 150ms = 3 ticks: ship stops almost immediately when key is released.
        long ttl = 150L;

        latestDir.forEach((uid, dir) -> {
            Long t = latestTime.get(uid);
            if (t == null || now - t > ttl) {
                latestDir.remove(uid);
                latestTime.remove(uid);
                return;
            }
            if (dir[0] == 0 && dir[1] == 0) return;

            Player player = Bukkit.getPlayer(uid);
            if (player == null) { latestDir.remove(uid); latestTime.remove(uid); return; }

            PlayerCraft craft = CraftManager.getInstance().getCraftByPlayer(player);
            if (craft == null || !craft.getPilotLocked()) {
                latestDir.remove(uid); latestTime.remove(uid); return;
            }

            craft.translate(dir[0], 0, dir[1]);

            if (plugin.isDebug())
                plugin.getLogger().info("[wasd] " + player.getName()
                    + " (" + dir[0] + ",0," + dir[1] + ") age=" + (now - t) + "ms");
        });
    }

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

    // ── Q → rotate left, F → rotate right ────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDropItem(PlayerDropItemEvent event) {
        Player      player = event.getPlayer();
        PlayerCraft craft  = CraftManager.getInstance().getCraftByPlayer(player);
        if (craft == null || !craft.getPilotLocked()) return;
        event.setCancelled(true);
        if (!rotateDebounce(player.getUniqueId())) return;
        rotateCraft(craft, MovecraftRotation.ANTICLOCKWISE);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player      player = event.getPlayer();
        PlayerCraft craft  = CraftManager.getInstance().getCraftByPlayer(player);
        if (craft == null || !craft.getPilotLocked()) return;
        event.setCancelled(true);
        if (!rotateDebounce(player.getUniqueId())) return;
        rotateCraft(craft, MovecraftRotation.CLOCKWISE);
    }

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
