package me.missaria.movecraftcannons;

import at.pavlov.cannons.cannon.Cannon;
import at.pavlov.cannons.hooks.movecraft.MovecraftUtils;
import net.countercraft.movecraft.craft.PlayerCraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class CannonUtils {

    private CannonUtils() {}

    /**
     * Returns all cannons on the craft using Cannons' own MovecraftUtils.getCannons().
     * This is the same method Cannons' TranslationListener uses internally, so it is
     * authoritative — no duplicates, no position-matching issues.
     */
    public static List<Cannon> findCannonsOnCraft(PlayerCraft craft) {
        try {
            Set<Cannon> cannons = MovecraftUtils.getCannons(craft);
            return cannons != null ? new ArrayList<>(cannons) : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
