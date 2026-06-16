package me.missaria.movecraftcannons;

import net.countercraft.movecraft.CruiseDirection;
import net.countercraft.movecraft.craft.CraftManager;
import net.countercraft.movecraft.craft.PlayerCraft;
import net.countercraft.movecraft.craft.type.CraftType;
import net.countercraft.movecraft.craft.type.RequiredBlockEntry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class WindManager {

    public enum Direction {
        NORTH, SOUTH, EAST, WEST;

        static Direction random() {
            Direction[] v = values();
            return v[ThreadLocalRandom.current().nextInt(v.length)];
        }

        int[] vec() {
            return switch (this) {
                case NORTH -> new int[]{0, -1};
                case SOUTH -> new int[]{0, 1};
                case EAST  -> new int[]{1, 0};
                case WEST  -> new int[]{-1, 0};
            };
        }

        String arrow() {
            return switch (this) {
                case NORTH -> "↑";
                case SOUTH -> "↓";
                case EAST  -> "→";
                case WEST  -> "←";
            };
        }
    }

    // Periods I–V: weak, strong, calm, strong, storm
    private static final int[] STRENGTHS = {1, 2, 0, 2, 3};

    private final MovecraftCannonsPlugin plugin;
    private int period = 0;
    private Direction direction = Direction.random();
    // Cache wool-check result per craft type name to avoid re-scanning every tick
    private final Map<String, Boolean> woolCache = new ConcurrentHashMap<>();

    public WindManager(MovecraftCannonsPlugin plugin) {
        this.plugin = plugin;
        long periodTicks = plugin.getConfig().getLong("wind.period_duration_minutes", 10) * 60L * 20L;
        Bukkit.getScheduler().runTaskTimer(plugin, this::advancePeriod, periodTicks, periodTicks);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    // ── Period management ─────────────────────────────────────────────────────

    private void advancePeriod() {
        period = (period + 1) % 5;
        if (getStrength() > 0) direction = Direction.random();
        String msg = Lang.get("wind.change", getPeriodNumber(), getStrengthDisplay());
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(msg));
    }

    // ── Per-second cruise effect ──────────────────────────────────────────────

    private void tick() {
        int s = getStrength();
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerCraft craft = CraftManager.getInstance().getCraftByPlayer(player);
            if (craft == null || !craft.getCruising()) continue;
            CruiseDirection cruiseDir = craft.getCruiseDirection();
            if (cruiseDir == CruiseDirection.UP || cruiseDir == CruiseDirection.DOWN) continue;
            if (!isWindAffected(craft)) continue;
            int effect = computeEffect(s, cruiseDir);
            if (effect == 0) continue;
            int[] cv = dirVec(cruiseDir);
            try { craft.translate(cv[0] * effect, 0, cv[1] * effect); }
            catch (Exception ignored) {}
        }
    }

    /**
     * Blocks/sec to add in cruise direction.
     * Calm → always -1. Weak → 0. Strong/storm → +base tailwind, -base headwind, 0 crosswind.
     */
    private int computeEffect(int strength, CruiseDirection cruiseDir) {
        if (strength == 0) return -1;
        if (strength == 1) return 0;
        int base = strength == 2 ? 2 : 4;
        int[] wv = direction.vec();
        int[] cv = dirVec(cruiseDir);
        return base * (wv[0] * cv[0] + wv[1] * cv[1]);
    }

    private static int[] dirVec(CruiseDirection dir) {
        return switch (dir) {
            case NORTH -> new int[]{0, -1};
            case SOUTH -> new int[]{0, 1};
            case EAST  -> new int[]{1, 0};
            case WEST  -> new int[]{-1, 0};
            default    -> new int[]{0, 0};
        };
    }

    // ── Wool detection ────────────────────────────────────────────────────────

    private boolean isWindAffected(PlayerCraft craft) {
        try {
            String typeName = craft.getType().getStringProperty(CraftType.NAME);
            if (typeName == null) return false;
            return woolCache.computeIfAbsent(typeName, k -> checkWool(craft));
        } catch (Exception ignored) { return false; }
    }

    @SuppressWarnings("unchecked")
    private boolean checkWool(PlayerCraft craft) {
        try {
            Set<RequiredBlockEntry> fly = (Set<RequiredBlockEntry>) craft.getType().getRequiredBlockProperty(CraftType.FLY_BLOCKS);
            if (fly != null) for (RequiredBlockEntry e : fly)
                for (Material m : e.getMaterials())
                    if (m.name().endsWith("_WOOL")) return true;
        } catch (Exception ignored) {}
        try {
            Set<RequiredBlockEntry> move = (Set<RequiredBlockEntry>) craft.getType().getRequiredBlockProperty(CraftType.MOVE_BLOCKS);
            if (move != null) for (RequiredBlockEntry e : move)
                for (Material m : e.getMaterials())
                    if (m.name().endsWith("_WOOL")) return true;
        } catch (Exception ignored) {}
        return false;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public int getStrength()     { return STRENGTHS[period]; }
    public Direction getDirection() { return direction; }
    public int getPeriodNumber() { return period + 1; }

    /** Colored string for server broadcasts — direction shown as word (server language). */
    public String getStrengthDisplay() {
        int s = getStrength();
        String name = colorPrefix(s) + Lang.get("wind.strength." + s);
        if (s >= 2) name += " §7" + Lang.get("wind.dir." + direction.name().toLowerCase());
        return name;
    }

    /** Colored string for player's scoreboard — direction shown as arrow + word. */
    public String getStrengthDisplay(Player player) {
        int s = getStrength();
        String name = colorPrefix(s) + Lang.get("wind.strength." + s, player);
        if (s >= 2) name += " " + direction.arrow() + Lang.get("wind.dir." + direction.name().toLowerCase(), player);
        return name;
    }

    private static String colorPrefix(int s) {
        return switch (s) {
            case 0  -> "§8";
            case 1  -> "§7";
            case 2  -> "§e";
            default -> "§c";
        };
    }
}
