package me.missaria.movecraftcannons;

import net.countercraft.movecraft.MovecraftLocation;
import net.countercraft.movecraft.MovecraftRotation;
import net.countercraft.movecraft.craft.CraftManager;
import net.countercraft.movecraft.craft.PlayerCraft;
import net.countercraft.movecraft.events.CraftReleaseEvent;
import net.countercraft.movecraft.util.hitboxes.HitBox;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WasdListener implements Listener {

    private final MovecraftCannonsPlugin plugin;
    private final Map<UUID, Long> lastMove     = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRotate   = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastToggle   = new ConcurrentHashMap<>();
    private final Set<UUID>       directControl = ConcurrentHashMap.newKeySet();

    private static final double MIN_DELTA       = 0.15;
    private static final long   ROTATE_DEBOUNCE = 600L;
    private static final long   TOGGLE_DEBOUNCE = 500L;

    public WasdListener(MovecraftCannonsPlugin plugin) {
        this.plugin = plugin;
    }

    // Toggle: any left-click while piloting
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK
                && event.getAction() != Action.LEFT_CLICK_AIR) return;
        Player player = event.getPlayer();
        if (CraftManager.getInstance().getCraftByPlayer(player) == null) return;
        long now = System.currentTimeMillis();
        Long last = lastToggle.get(player.getUniqueId());
        if (last != null && now - last < TOGGLE_DEBOUNCE) return;
        lastToggle.put(player.getUniqueId(), now);
        UUID uid = player.getUniqueId();
        if (directControl.remove(uid)) {
            player.sendMessage(Component.text("Прямое управление: ВЫКЛ", NamedTextColor.YELLOW));
        } else {
            directControl.add(uid);
            player.sendMessage(Component.text("Прямое управление: ВКЛ (WASD/Q/F)", NamedTextColor.GREEN));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRelease(CraftReleaseEvent event) {
        if (!(event.getCraft() instanceof PlayerCraft pc)) return;
        UUID uid = pc.getPilot().getUniqueId();
        directControl.remove(uid);
        lastMove.remove(uid);
        lastRotate.remove(uid);
        lastToggle.remove(uid);
    }

    // WASD movement
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent) return;
        Player player = event.getPlayer();
        if (!directControl.contains(player.getUniqueId())) return;
        Location from = event.getFrom();
        Location to   = event.getTo();
        if (to == null) return;
        PlayerCraft craft = CraftManager.getInstance().getCraftByPlayer(player);
        if (craft == null) { directControl.remove(player.getUniqueId()); return; }
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        Location cancelTo = from.clone();
        cancelTo.setYaw(to.getYaw());
        cancelTo.setPitch(to.getPitch());
        event.setTo(cancelTo);
        if (Math.abs(dx) < MIN_DELTA && Math.abs(dz) < MIN_DELTA) return;
        long now = System.currentTimeMillis();
        Long last = lastMove.get(player.getUniqueId());
        long cooldown = plugin.getConfig().getLong("wasd.cooldown_ms", 200L);
        if (last != null && now - last < cooldown) return;
        lastMove.put(player.getUniqueId(), now);
        int tdx = 0, tdz = 0;
        if (Math.abs(dx) >= Math.abs(dz)) tdx = dx > 0 ? 1 : -1;
        else                               tdz = dz > 0 ? 1 : -1;
        craft.translate(tdx, 0, tdz);
        if (plugin.isDebug())
            plugin.getLogger().info("[wasd] " + player.getName() + " translate (" + tdx + ",0," + tdz + ")");
    }

    // Q = rotate left
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (!directControl.contains(player.getUniqueId())) return;
        PlayerCraft craft = CraftManager.getInstance().getCraftByPlayer(player);
        if (craft == null) return;
        event.setCancelled(true);
        if (!rotateDebounce(player.getUniqueId())) return;
        rotateCraft(craft, MovecraftRotation.ANTICLOCKWISE);
    }

    // F = rotate right
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (!directControl.contains(player.getUniqueId())) return;
        PlayerCraft craft = CraftManager.getInstance().getCraftByPlayer(player);
        if (craft == null) return;
        event.setCancelled(true);
        if (!rotateDebounce(player.getUniqueId())) return;
        rotateCraft(craft, MovecraftRotation.CLOCKWISE);
    }

    private boolean rotateDebounce(UUID uid) {
        long now = System.currentTimeMillis();
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
