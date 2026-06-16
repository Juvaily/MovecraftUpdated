package me.missaria.movecraftcannons;

import at.pavlov.cannons.cannon.Cannon;
import at.pavlov.cannons.cannon.CannonManager;
import at.pavlov.cannons.cannon.data.CannonPosition;
import net.countercraft.movecraft.craft.Craft;
import net.countercraft.movecraft.events.CraftDetectEvent;
import net.countercraft.movecraft.events.CraftSinkEvent;
import net.countercraft.movecraft.events.CraftTranslateEvent;
import net.countercraft.movecraft.util.hitboxes.HitBox;
import net.countercraft.movecraft.MovecraftLocation;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CraftMoveListener implements Listener {

    private final MovecraftCannonsPlugin plugin;

    private final Map<UUID, List<MovecraftLocation>> spawnWaterPos = new ConcurrentHashMap<>();

    // Auto-managed seats for passengers (non-DC, non-GSit) on moving crafts
    private final Map<UUID, ArmorStand> managedSeats  = new ConcurrentHashMap<>();
    private final Map<UUID, Long>       lastSeatMove  = new ConcurrentHashMap<>();

    // Y offset: small ArmorStand mount height so the player appears at their original feet position
    private static final double SEAT_OFFSET_Y = 1.18;

    public CraftMoveListener(MovecraftCannonsPlugin plugin) {
        this.plugin = plugin;
        // Unseat players 600 ms after the craft stops moving
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickSeats, 10L, 10L);
    }

    // ── Managed seat lifecycle ────────────────────────────────────────────────

    private void tickSeats() {
        long now = System.currentTimeMillis();
        new ArrayList<>(managedSeats.keySet()).forEach(uid -> {
            if (now - lastSeatMove.getOrDefault(uid, 0L) > 600L) {
                unseatPlayer(uid);
            }
        });
    }

    private void unseatPlayer(UUID uid) {
        ArmorStand stand = managedSeats.remove(uid);
        lastSeatMove.remove(uid);
        if (stand != null && stand.isValid()) stand.remove();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        unseatPlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onVehicleExit(VehicleExitEvent event) {
        if (!(event.getExited() instanceof Player p)) return;
        UUID uid = p.getUniqueId();
        if (managedSeats.containsKey(uid)) {
            // Remove on next tick so the event fully resolves first
            Bukkit.getScheduler().runTaskLater(plugin, () -> unseatPlayer(uid), 1L);
        }
    }

    // ── Craft translate ───────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCraftTranslate(CraftTranslateEvent event) {
        HitBox oldBox = event.getOldHitBox();
        HitBox newBox = event.getNewHitBox();

        int dx = newBox.getMinX() - oldBox.getMinX();
        int dy = newBox.getMinY() - oldBox.getMinY();
        int dz = newBox.getMinZ() - oldBox.getMinZ();

        if (dx == 0 && dy == 0 && dz == 0) return;

        World world = event.getCraft().getWorld();
        UUID worldUID = world.getUID();
        Vector translation = new Vector(dx, dy, dz);

        // ── Cannons ───────────────────────────────────────────────────────────
        Collection<Cannon> allCannons = getCannons();
        if (allCannons != null && !allCannons.isEmpty()) {
            int updated = 0;
            for (Cannon cannon : allCannons) {
                try {
                    CannonPosition pos = cannon.getCannonPosition();
                    if (!worldUID.equals(pos.getWorld())) continue;
                    Vector offset = pos.getOffset();
                    MovecraftLocation mloc = new MovecraftLocation(
                            (int) Math.round(offset.getX()),
                            (int) Math.round(offset.getY()),
                            (int) Math.round(offset.getZ()));
                    if (!oldBox.contains(mloc)) continue;
                    pos.setOffset(offset.clone().add(translation));
                    cannon.setUpdated(true);
                    updated++;
                    if (plugin.isDebug())
                        plugin.getLogger().info("[debug] Cannon '" + cannon.getCannonDesign().getDesignID()
                                + "' moved by (" + dx + "," + dy + "," + dz + ")");
                } catch (Exception e) {
                    plugin.getLogger().warning("Error updating cannon position: " + e.getMessage());
                }
            }
            if (plugin.isDebug() && updated > 0)
                plugin.getLogger().info("[debug] Updated " + updated + " cannon(s) on craft translate.");
        }

        final int gdx = dx, gdy = dy, gdz = dz;
        final int mnX = oldBox.getMinX(), mxX = oldBox.getMaxX();
        final int mnY = oldBox.getMinY(), mxY = oldBox.getMaxY();
        final int mnZ = oldBox.getMinZ(), mxZ = oldBox.getMaxZ();

        // ── Auto-seat standing passengers ─────────────────────────────────────
        // Check X/Z only (same as GSit block) — Y differs when already riding.
        for (Player player : world.getPlayers()) {
            UUID uid = player.getUniqueId();
            if (WasdListener.DC_PILOTS.contains(uid)) continue;
            Location pl = player.getLocation();
            int px = (int) Math.floor(pl.getX());
            int pz = (int) Math.floor(pl.getZ());
            if (px < mnX || px > mxX || pz < mnZ || pz > mxZ) continue;

            // Always refresh timer so tickSeats doesn't evict while ship is moving
            if (managedSeats.containsKey(uid)) {
                lastSeatMove.put(uid, System.currentTimeMillis());
            }

            if (player.getVehicle() != null) continue; // already seated (managed or GSit)

            // Only seat players near/on the ship surface, not random players at same X/Z
            int py = (int) Math.floor(pl.getY());
            if (py < mnY || py > mxY + 2) continue;

            lastSeatMove.put(uid, System.currentTimeMillis());
            Location standLoc = pl.clone();
            standLoc.setY(pl.getY() - SEAT_OFFSET_Y);
            ArmorStand stand = world.spawn(standLoc, ArmorStand.class, as -> {
                as.setVisible(false);
                as.setGravity(false);
                as.setSmall(true);
                as.setInvulnerable(true);
                as.setCollidable(false);
            });
            stand.addPassenger(player);
            managedSeats.put(uid, stand);
        }

        // ── Move ArmorStand seats (GSit + our managed ones) ───────────────────
        // LOWEST fires before Movecraft processes — player is still at old position.
        // Capture ArmorStand pre-move location; 1 tick later teleport to oldLoc+delta.
        Map<Player, Object[]> seats = new HashMap<>();
        for (Player player : world.getPlayers()) {
            Entity vehicle = player.getVehicle();
            if (!(vehicle instanceof ArmorStand)) continue;
            Location pl = player.getLocation();
            int px = (int) Math.floor(pl.getX());
            int pz = (int) Math.floor(pl.getZ());
            if (px < mnX || px > mxX || pz < mnZ || pz > mxZ) continue;
            seats.put(player, new Object[]{vehicle, vehicle.getLocation().clone()});
        }

        if (!seats.isEmpty()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                for (Map.Entry<Player, Object[]> e : seats.entrySet()) {
                    Entity vehicle = (Entity) e.getValue()[0];
                    Location oldLoc = (Location) e.getValue()[1];
                    if (!vehicle.isValid()) continue;
                    vehicle.teleport(oldLoc.clone().add(gdx, gdy, gdz));
                    vehicle.addPassenger(e.getKey());
                }
            }, 1L);
        }
    }

    // ── Water fill ────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraftDetect(CraftDetectEvent event) {
        Craft craft = event.getCraft();
        List<MovecraftLocation> list = waterPositions(craft);
        if (list.isEmpty()) return;

        UUID uid = craft.getUUID();
        World world = craft.getWorld();
        spawnWaterPos.put(uid, list);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            refillWaterAt(spawnWaterPos.getOrDefault(uid, list), world);
            spawnWaterPos.remove(uid);
        }, 20L * 60 * 5);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCraftSink(CraftSinkEvent event) {
        Craft craft = event.getCraft();
        List<MovecraftLocation> list = waterPositions(craft);
        spawnWaterPos.remove(craft.getUUID());
        if (list.isEmpty()) return;

        World world = craft.getWorld();
        Bukkit.getScheduler().runTaskLater(plugin, () -> refillWaterAt(list, world), 20L * 60 * 5);
    }

    private List<MovecraftLocation> waterPositions(Craft craft) {
        int waterLevel = plugin.getConfig().getInt("waterLevel", 62);
        List<MovecraftLocation> list = new ArrayList<>();
        for (MovecraftLocation loc : craft.getHitBox()) {
            if (loc.getY() <= waterLevel) list.add(loc);
        }
        return list;
    }

    private void refillWaterAt(List<MovecraftLocation> positions, World world) {
        for (MovecraftLocation loc : positions) {
            Block block = world.getBlockAt(loc.getX(), loc.getY(), loc.getZ());
            if (!block.getType().isAir()) continue;
            block.setType(Material.WATER);
        }
    }

    @SuppressWarnings("unchecked")
    private Collection<Cannon> getCannons() {
        try {
            ConcurrentHashMap<UUID, Cannon> map = CannonManager.getInstance().getCannonList();
            return map.values();
        } catch (Exception e) {
            plugin.getLogger().warning("Could not access CannonManager.getCannonList(): " + e.getMessage());
            return null;
        }
    }
}
