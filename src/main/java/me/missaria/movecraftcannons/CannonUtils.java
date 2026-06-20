package me.missaria.movecraftcannons;

import at.pavlov.cannons.cannon.Cannon;
import at.pavlov.cannons.cannon.CannonManager;
import net.countercraft.movecraft.MovecraftLocation;
import net.countercraft.movecraft.craft.Craft;
import net.countercraft.movecraft.util.hitboxes.HitBox;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CannonUtils {

    private CannonUtils() {}

    /**
     * Finds all cannons on the craft using two complementary approaches:
     * 1. CannonManager.getCannonsByLocations() — Cannons' own lookup by location list
     * 2. Iterate all cannons and match floor(offset) against the hitbox
     *    (handles fractional offsets that getCannonsByLocations may miss)
     * Results are deduplicated by block position.
     */
    public static List<Cannon> findCannonsOnCraft(Craft craft) {
        World world = craft.getWorld();
        HitBox hitBox = craft.getHitBox();
        UUID worldUID = world.getUID();
        List<Cannon> result = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();

        // Method 1: Cannons' own lookup by location list
        try {
            List<Location> locs = new ArrayList<>(hitBox.size());
            for (MovecraftLocation loc : hitBox)
                locs.add(new Location(world, loc.getX(), loc.getY(), loc.getZ()));
            HashSet<Cannon> found = CannonManager.getInstance().getCannonsByLocations(locs);
            if (found != null) {
                for (Cannon c : found) {
                    result.add(c);
                    try { seen.add(c.getUID()); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}

        // Method 2: iterate all cannons, match floor'd offset against hitbox
        try {
            for (Cannon cannon : CannonManager.getInstance().getCannonList().values()) {
                try {
                    UUID uid = cannon.getUID();
                    if (seen.contains(uid)) continue;
                    if (!worldUID.equals(cannon.getCannonPosition().getWorld())) continue;
                    Vector off = cannon.getCannonPosition().getOffset();
                    MovecraftLocation mloc = new MovecraftLocation(
                            (int) Math.floor(off.getX()),
                            (int) Math.floor(off.getY()),
                            (int) Math.floor(off.getZ()));
                    if (hitBox.contains(mloc)) {
                        result.add(cannon);
                        seen.add(uid);
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        // Deduplicate by block position: keeps one cannon per block coordinate.
        Map<String, Cannon> byPos = new LinkedHashMap<>();
        for (Cannon c : result) {
            try {
                Vector off = c.getCannonPosition().getOffset();
                String key = (int) Math.floor(off.getX()) + ","
                        + (int) Math.floor(off.getY()) + ","
                        + (int) Math.floor(off.getZ());
                if (!byPos.containsKey(key) || c.isReadyToFire()) byPos.put(key, c);
            } catch (Exception ignored) { byPos.put(c.getUID().toString(), c); }
        }
        return new ArrayList<>(byPos.values());
    }
}
