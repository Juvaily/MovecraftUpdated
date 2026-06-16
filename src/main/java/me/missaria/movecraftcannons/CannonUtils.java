package me.missaria.movecraftcannons;

import at.pavlov.cannons.cannon.Cannon;
import at.pavlov.cannons.cannon.CannonDesign;
import at.pavlov.cannons.cannon.CannonManager;
import at.pavlov.cannons.cannon.DesignStorage;
import net.countercraft.movecraft.MovecraftLocation;
import net.countercraft.movecraft.craft.PlayerCraft;
import net.countercraft.movecraft.util.hitboxes.HitBox;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class CannonUtils {

    private CannonUtils() {}

    // ── Find cannons on a craft ───────────────────────────────────────────────

    /**
     * Returns all Cannons whose stored world position falls inside the craft's hitbox.
     * Filters the full CannonManager list by exact block coordinate — more accurate
     * than getCannonsInBox which uses a loose bounding box.
     */
    public static List<Cannon> findCannonsOnCraft(PlayerCraft craft) {
        HitBox hitBox = craft.getHitBox();
        UUID worldUID = craft.getWorld().getUID();
        List<Cannon> result = new ArrayList<>();
        try {
            ConcurrentHashMap<UUID, Cannon> all = CannonManager.getInstance().getCannonList();
            for (Cannon cannon : all.values()) {
                try {
                    if (!worldUID.equals(cannon.getCannonPosition().getWorld())) continue;
                    Vector off = cannon.getCannonPosition().getOffset();
                    MovecraftLocation mloc = new MovecraftLocation(
                            (int) Math.round(off.getX()),
                            (int) Math.round(off.getY()),
                            (int) Math.round(off.getZ()));
                    if (hitBox.contains(mloc)) result.add(cannon);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Logger.getLogger("MovecraftCannons").warning("CannonUtils.findCannonsOnCraft: " + e.getMessage());
        }
        return result;
    }

    // ── Auto-create cannons via PlayerInteractEvent simulation ────────────────

    /**
     * Scans the craft's hitbox for blocks matching any cannon design's rotation-center
     * material. For each unregistered match, simulates a PlayerInteractEvent so that
     * Cannons' own PlayerListener detects and creates the cannon — no manual clicking.
     */
    public static void autoCreateCannons(PlayerCraft craft, Player pilot, Logger log) {
        if (pilot == null) return;
        World world = craft.getWorld();
        HitBox hitBox = craft.getHitBox();

        DesignStorage storage;
        try { storage = DesignStorage.getInstance(); }
        catch (Exception e) { return; }

        List<?> designs;
        try { designs = storage.getCannonDesignList(); }
        catch (Exception e) { return; }

        // Positions already registered so we skip them
        UUID worldUID = world.getUID();
        List<Location> registered = registeredPositions(worldUID, world);

        for (Object obj : designs) {
            if (!(obj instanceof CannonDesign design)) continue;

            // Try rotation center, then right-click trigger as fallback
            Material mat = blockMaterial(design, true);
            if (mat == null) mat = blockMaterial(design, false);
            if (mat == null) continue;

            for (MovecraftLocation loc : hitBox) {
                Block block = world.getBlockAt(loc.getX(), loc.getY(), loc.getZ());
                if (block.getType() != mat) continue;
                Location bLoc = block.getLocation();
                if (alreadyRegistered(registered, bLoc)) continue;

                // Simulate right-click: Cannons' PlayerListener handles creation
                try {
                    PlayerInteractEvent fake = new PlayerInteractEvent(
                            pilot,
                            Action.RIGHT_CLICK_BLOCK,
                            new ItemStack(Material.AIR),
                            block,
                            BlockFace.UP,
                            EquipmentSlot.HAND);
                    fake.setUseItemInHand(Event.Result.DENY);
                    Bukkit.getPluginManager().callEvent(fake);
                    registered.add(bLoc); // prevent duplicate events for same block
                } catch (Exception ignored) {}
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static List<Location> registeredPositions(UUID worldUID, World world) {
        List<Location> result = new ArrayList<>();
        try {
            for (Cannon c : CannonManager.getInstance().getCannonList().values()) {
                try {
                    if (!worldUID.equals(c.getCannonPosition().getWorld())) continue;
                    Vector o = c.getCannonPosition().getOffset();
                    result.add(new Location(world, o.getX(), o.getY(), o.getZ()));
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return result;
    }

    /** Get material from rotation center (useRotation=true) or right-click trigger. */
    private static Material blockMaterial(CannonDesign design, boolean useRotation) {
        try {
            Object raw = useRotation
                    ? design.getSchematicBlockTypeRotationCenter()
                    : design.getIngameBlockTypeRightClickTrigger();
            if (raw instanceof Material m) return m;
            if (raw instanceof org.bukkit.block.data.BlockData bd) return bd.getMaterial();
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean alreadyRegistered(List<Location> registered, Location loc) {
        int bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();
        for (Location r : registered) {
            if (r.getBlockX() == bx && r.getBlockY() == by && r.getBlockZ() == bz) return true;
        }
        return false;
    }
}
