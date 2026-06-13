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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WasdListener implements Listener {

    private final MovecraftCannonsPlugin plugin;
    private final Map<UUID, Long> lastMove   = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRotate = new ConcurrentHashMap<>();

    private static final double MIN_DELTA         = 0.15;
    private static final long   ROTATE_DEBOUNCE   = 600L;

    public WasdListener(MovecraftCannonsPlugin plugin) {
        this.plugin = plugin;
    }

    // ── WASD translation ──────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to   = event.getTo();
        if (to == null) return;

        Player      player = event.getPlayer();
        PlayerCraft craft  = CraftManager.getInstance().getCraftByPlayer(player);
        if (craft == null || !craft.getPilotLocked()) return;

        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();

        // Freeze ALL position changes — allow only head rotation
        boolean posChanged = Math.abs(dx) > 0.001 || Math.abs(dy) > 0.001 || Math.abs(dz) > 0.001;
        if (posChanged) {
            Location cancelTo = from.clone();
            cancelTo.setYaw(to.getYaw());
            cancelTo.setPitch(to.getPitch());
            event.setTo(cancelTo);
        }

        // Only translate on significant horizontal input
        if (Math.abs(dx) < MIN_DELTA && Math.abs(dz) < MIN_DELTA) return;

        long now      = System.currentTimeMillis();
        Long last     = lastMove.get(player.getUniqueId());
        long cooldown = plugin.getConfig().getLong("wasd.cooldown_ms", 200L);
        if (last != null && now - last < cooldown) return;
        lastMove.put(player.getUniqueId(), now);

        int tdx = 0, tdz = 0;
        if (Math.abs(dx) >= Math.abs(dz)) {
            tdx = dx > 0 ? 1 : -1;
        } else {
            tdz = dz > 0 ? 1 : -1;
        }

        craft.translate(tdx, 0, tdz);

        if (plugin.isDebug())
            plugin.getLogger().info("[wasd] " + player.getName() + " translate (" + tdx + ",0," + tdz + ")");
    }

    // ── Q → rotate left (anticlockwise) ───────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDropItem(PlayerDropItemEvent event) {
        Player      player = event.getPlayer();
        PlayerCraft craft  = CraftManager.getInstance().getCraftByPlayer(player);
        if (craft == null || !craft.getPilotLocked()) return;

        event.setCancelled(true);
        if (!rotateDebounce(player.getUniqueId())) return;
        rotateCraft(craft, MovecraftRotation.ANTICLOCKWISE);

        if (plugin.isDebug())
            plugin.getLogger().info("[wasd] " + player.getName() + " rotate LEFT");
    }

    // ── F → rotate right (clockwise) ──────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player      player = event.getPlayer();
        PlayerCraft craft  = CraftManager.getInstance().getCraftByPlayer(player);
        if (craft == null || !craft.getPilotLocked()) return;

        event.setCancelled(true);
        if (!rotateDebounce(player.getUniqueId())) return;
        rotateCraft(craft, MovecraftRotation.CLOCKWISE);

        if (plugin.isDebug())
            plugin.getLogger().info("[wasd] " + player.getName() + " rotate RIGHT");
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
        HitBox hb  = craft.getHitBox();
        MovecraftLocation mid = new MovecraftLocation(
                (hb.getMinX() + hb.getMaxX()) / 2,
                (hb.getMinY() + hb.getMaxY()) / 2,
                (hb.getMinZ() + hb.getMaxZ()) / 2
        );
        craft.rotate(rotation, mid);
    }
}
