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
    private final Map<UUID, Long> lastMove   = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRotate = new ConcurrentHashMap<>();

    private static final double MIN_DELTA       = 0.15;
    private static final long   ROTATE_DEBOUNCE = 600L;

    public WasdListener(MovecraftCannonsPlugin plugin) {
        this.plugin = plugin;
    }

    // ── WASD (only in Direct Control — pilotLocked == true) ───────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // Skip craft-caused pilot teleports
        if (event instanceof PlayerTeleportEvent) return;

        Location from = event.getFrom();
        Location to   = event.getTo();
        if (to == null) return;

        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        if (Math.abs(dx) < MIN_DELTA && Math.abs(dz) < MIN_DELTA) return;

        Player      player = event.getPlayer();
        PlayerCraft craft  = CraftManager.getInstance().getCraftByPlayer(player);
        // Only active in Movecraft's Direct Control Mode (LMB feather → pilotLocked=true)
        if (craft == null || !craft.getPilotLocked()) return;

        // Don't freeze — pilotLocked mechanism already teleports pilot back to locked position.
        // We only need to submit the craft translation.

        long now      = System.currentTimeMillis();
        Long last     = lastMove.get(player.getUniqueId());
        long cooldown = plugin.getConfig().getLong("wasd.cooldown_ms", 200L);
        if (last != null && now - last < cooldown) return;
        lastMove.put(player.getUniqueId(), now);

        int tdx = 0, tdz = 0;
        if (Math.abs(dx) >= Math.abs(dz)) tdx = dx > 0 ? 1 : -1;
        else                               tdz = dz > 0 ? 1 : -1;

        craft.translate(tdx, 0, tdz);

        if (plugin.isDebug())
            plugin.getLogger().info("[wasd] " + player.getName()
                + " (" + tdx + ",0," + tdz + ") notProcessing=" + craft.isNotProcessing());
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
