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
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
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

    // Players frozen on a moving ship: flight mode keeps gravity off between teleports
    private final Map<UUID, Boolean> passengerAllowFlight = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> passengerFlying      = new ConcurrentHashMap<>();
    private final Map<UUID, Long>    lastShipMove         = new ConcurrentHashMap<>();

    public CraftMoveListener(MovecraftCannonsPlugin plugin) {
        this.plugin = plugin;
        // Restore flight state 600 ms after the craft stops moving
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickPassengers, 10L, 10L);
    }

    // ── Passenger freeze lifecycle ────────────────────────────────────────────

    private void tickPassengers() {
        long now = System.currentTimeMillis();
        new ArrayList<>(passengerAllowFlight.keySet()).forEach(uid -> {
            if (now - lastShipMove.getOrDefault(uid, 0L) > 600L) {
                restorePassenger(uid);
            }
        });
    }

    private void restorePassenger(UUID uid) {
        Boolean origAllow  = passengerAllowFlight.remove(uid);
        Boolean origFlying = passengerFlying.remove(uid);
        lastShipMove.remove(uid);
        Player p = Bukkit.getPlayer(uid);
        if (p == null || origAllow == null) return;
        p.setAllowFlight(origAllow);
        if (!origFlying && !origAllow) p.setFlying(false);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        restorePassenger(event.getPlayer().getUniqueId());
    }

    // Cancel voluntary movement while frozen (flight mode lets the player move freely
    // if we don't block it). Head rotation (yaw/pitch only) is still allowed.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPassengerMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent) return;
        UUID uid = event.getPlayer().getUniqueId();
        if (!passengerAllowFlight.containsKey(uid)) return;
        Location from = event.getFrom();
        Location to   = event.getTo();
        if (to == null) return;
        if (Math.abs(to.getX() - from.getX()) < 0.001
                && Math.abs(to.getY() - from.getY()) < 0.001
                && Math.abs(to.getZ() - from.getZ()) < 0.001) return;
        Location pushBack = from.clone();
        pushBack.setYaw(to.getYaw());
        pushBack.setPitch(to.getPitch());
        event.setTo(pushBack);
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

        // ── Freeze passengers with flight + teleport ──────────────────────────
        // Flight mode eliminates gravity for the 1-tick gap between block move and
        // teleport. PlayerMoveEvent (above) cancels voluntary movement between ticks.
        // Approach is identical to how WasdListener freezes the DC pilot, but here
        // we also teleport the player by (dx, dy, dz) so they move WITH the craft.
        for (Player player : world.getPlayers()) {
            UUID uid = player.getUniqueId();
            if (WasdListener.DC_PILOTS.contains(uid)) continue;
            if (player.getVehicle() != null) continue; // GSit users — handled below
            Location pl = player.getLocation();
            int px = (int) Math.floor(pl.getX());
            int py = (int) Math.floor(pl.getY());
            int pz = (int) Math.floor(pl.getZ());
            if (px < mnX || px > mxX || py < mnY || py > mxY + 2 || pz < mnZ || pz > mxZ) continue;

            lastShipMove.put(uid, System.currentTimeMillis());

            if (!passengerAllowFlight.containsKey(uid)) {
                passengerAllowFlight.put(uid, player.getAllowFlight());
                passengerFlying.put(uid, player.isFlying());
                player.setAllowFlight(true);
                player.setFlying(true);
            }

            final Location preMovePos = pl.clone();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;
                player.teleport(preMovePos.clone().add(gdx, gdy, gdz),
                        PlayerTeleportEvent.TeleportCause.PLUGIN);
            }, 1L);
        }

        // ── GSit: move ArmorStand seats with the craft ────────────────────────
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
