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
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
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

    // Positions at Y≤waterLevel recorded on craft detect, used for delayed water restore.
    private final Map<UUID, List<MovecraftLocation>> spawnWaterPos = new ConcurrentHashMap<>();

    public CraftMoveListener(MovecraftCannonsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * ROOT CAUSE: Cannons 3.4.3 TranslationListener calls cannon.move(Vector),
     * which does NOT exist → NoSuchMethodError → cannon offset never updated →
     * Cannons can't find the cannon at the new block location → creates a fresh
     * cannon with zeroed reload/ammo/temperature state.
     *
     * FIX: iterate all cannons via getCannonList() and match by stored offset position
     * against the old hitbox. CraftTranslateEvent fires BEFORE blocks physically move,
     * so the old hitbox positions are still valid for lookup.
     */
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

        Collection<Cannon> allCannons = getCannons();
        if (allCannons == null || allCannons.isEmpty()) return;

        int updated = 0;
        for (Cannon cannon : allCannons) {
            try {
                CannonPosition pos = cannon.getCannonPosition();
                if (!worldUID.equals(pos.getWorld())) continue;

                Vector offset = pos.getOffset();
                MovecraftLocation mloc = new MovecraftLocation(
                        (int) Math.round(offset.getX()),
                        (int) Math.round(offset.getY()),
                        (int) Math.round(offset.getZ())
                );
                if (!oldBox.contains(mloc)) continue;

                pos.setOffset(offset.clone().add(translation));
                cannon.setUpdated(true);
                updated++;

                if (plugin.isDebug()) {
                    plugin.getLogger().info("[debug] Cannon '" + cannon.getCannonDesign().getDesignID()
                            + "' moved by (" + dx + "," + dy + "," + dz + ")");
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error updating cannon position: " + e.getMessage());
            }
        }

        if (plugin.isDebug() && updated > 0) {
            plugin.getLogger().info("[debug] Updated " + updated + " cannon(s) on craft translate.");
        }

        // ── GSit: move ArmorStand seats with the craft ────────────────────────
        // LOWEST fires before Movecraft processes anything — player is still seated.
        // Check craft membership via the PLAYER's position (ArmorStand sits 1 block
        // below the surface and may be outside the hitbox Y range).
        // Capture the ArmorStand's exact pre-move location; in the next tick
        // teleport it to oldLoc+delta regardless of whether Movecraft moved it.
        final int gdx = dx, gdy = dy, gdz = dz;
        final int mnX = oldBox.getMinX(), mxX = oldBox.getMaxX();
        final int mnY = oldBox.getMinY(), mxY = oldBox.getMaxY();
        final int mnZ = oldBox.getMinZ(), mxZ = oldBox.getMaxZ();

        // player → [vehicle, oldVehicleLocation]
        Map<Player, Object[]> seats = new HashMap<>();
        for (Player player : world.getPlayers()) {
            Entity vehicle = player.getVehicle();
            if (!(vehicle instanceof ArmorStand)) continue;
            // Check only X/Z — player sitting on a stair/floor may have Y below hitbox minY
            org.bukkit.Location pl = player.getLocation();
            int px = (int) Math.floor(pl.getX());
            int pz = (int) Math.floor(pl.getZ());
            if (px < mnX || px > mxX || pz < mnZ || pz > mxZ) continue;
            seats.put(player, new Object[]{vehicle, vehicle.getLocation().clone()});
        }

        if (!seats.isEmpty()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                for (Map.Entry<Player, Object[]> e : seats.entrySet()) {
                    Entity vehicle  = (Entity) e.getValue()[0];
                    org.bukkit.Location oldLoc = (org.bukkit.Location) e.getValue()[1];
                    if (!vehicle.isValid()) continue;
                    // Always place seat at oldLoc+delta — correct regardless of
                    // whether Movecraft already moved it or not.
                    vehicle.teleport(oldLoc.clone().add(gdx, gdy, gdz));
                    vehicle.addPassenger(e.getKey());
                }
            }, 1L);
        }

        // ── Freeze standing passengers: teleport with the craft ───────────────
        // DC pilots already fly in place; seated players handled above.
        Map<Player, org.bukkit.Location> standing = new HashMap<>();
        for (Player player : world.getPlayers()) {
            if (player.getVehicle() != null) continue;
            if (WasdListener.DC_PILOTS.contains(player.getUniqueId())) continue;
            org.bukkit.Location pl = player.getLocation();
            int px = (int) Math.floor(pl.getX());
            int py = (int) Math.floor(pl.getY());
            int pz = (int) Math.floor(pl.getZ());
            if (px < mnX || px > mxX || py < mnY || py > mxY + 2 || pz < mnZ || pz > mxZ) continue;
            standing.put(player, pl.clone());
        }
        if (!standing.isEmpty()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                for (Map.Entry<Player, org.bukkit.Location> e : standing.entrySet()) {
                    Player p = e.getKey();
                    if (!p.isOnline()) continue;
                    p.teleport(e.getValue().clone().add(gdx, gdy, gdz),
                            PlayerTeleportEvent.TeleportCause.PLUGIN);
                }
            }, 1L);
        }
    }

    // ── Water fill ────────────────────────────────────────────────────────────

    // On detect: save the sub-waterline positions of the ship's spawn footprint.
    // After 5 minutes the ship has likely moved, so fill those positions with water.
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
        }, 20L * 60 * 5); // 5 minutes
    }

    // On sink: fill the crash position after 5 minutes; cancel the spawn fill.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCraftSink(CraftSinkEvent event) {
        Craft craft = event.getCraft();
        List<MovecraftLocation> list = waterPositions(craft);
        spawnWaterPos.remove(craft.getUUID()); // spawn fill no longer relevant
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

    /** Get all cannons from CannonManager's internal map (indexed by UUID). */
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
