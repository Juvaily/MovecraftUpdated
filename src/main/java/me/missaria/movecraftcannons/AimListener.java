package me.missaria.movecraftcannons;

import at.pavlov.cannons.API.CannonsAPI;
import at.pavlov.cannons.Cannons;
import at.pavlov.cannons.Enum.InteractAction;
import at.pavlov.cannons.aim.GunAngles;
import at.pavlov.cannons.cannon.Cannon;
import at.pavlov.cannons.event.CannonUseEvent;
import net.countercraft.movecraft.craft.CraftManager;
import net.countercraft.movecraft.craft.PlayerCraft;
import net.countercraft.movecraft.events.CraftReleaseEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AimListener implements Listener {

    private static final double GRAVITY       = 0.05;
    private static final double DEFAULT_SPEED = 3.0;
    private static final int    MAX_TICKS     = 100;
    private static final int    SAMPLE_TICKS  = 2;

    private static final Map<BlockFace, Material> SIDE_COLOR = Map.of(
        BlockFace.NORTH, Material.LIME_STAINED_GLASS,
        BlockFace.SOUTH, Material.CYAN_STAINED_GLASS,
        BlockFace.EAST,  Material.YELLOW_STAINED_GLASS,
        BlockFace.WEST,  Material.BLUE_STAINED_GLASS
    );
    private static final Material FALLBACK_COLOR = Material.WHITE_STAINED_GLASS;
    private static final Material IMPACT_COLOR   = Material.RED_STAINED_GLASS;

    private final MovecraftCannonsPlugin plugin;

    private final Map<UUID, Map<BlockFace, Cannon>> aimGroups      = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask>             aimTasks       = new ConcurrentHashMap<>();
    private final Map<UUID, List<Location>>         sentTrajectory = new ConcurrentHashMap<>();

    public AimListener(MovecraftCannonsPlugin plugin) {
        this.plugin = plugin;
    }

    // ── Block default Cannons aiming ──────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void blockCannonsDefaultAim(CannonUseEvent event) {
        InteractAction a = event.getAction();
        if (a == InteractAction.adjustPlayer || a == InteractAction.adjustAutoaim
                || a == InteractAction.adjustSentry || a == InteractAction.adjustOther) {
            event.setCancelled(true);
        }
    }

    // ── Toggle AIM on right-click CLOCK ──────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onClockClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        var item = event.getItem();
        if (item == null || item.getType() != Material.CLOCK) return;

        Player player = event.getPlayer();
        PlayerCraft craft = CraftManager.getInstance().getCraftByPlayer(player);
        if (craft == null) return;

        event.setCancelled(true);
        if (aimGroups.containsKey(player.getUniqueId())) stopAiming(player);
        else startAiming(player, craft);
    }

    // ── Start ─────────────────────────────────────────────────────────────────

    private void startAiming(Player player, PlayerCraft craft) {
        // Use accurate hitbox-based cannon detection
        List<Cannon> all = CannonUtils.findCannonsOnCraft(craft);
        if (all.isEmpty()) {
            player.sendMessage(Lang.msg("msg.no_cannons", player, NamedTextColor.YELLOW));
            return;
        }
        CannonsAPI api = getCannonsAPI();
        if (api == null) {
            player.sendMessage(Lang.msg("msg.cannons_unavailable", player, NamedTextColor.RED));
            return;
        }

        // One representative cannon per facing direction for trajectory
        Map<BlockFace, Cannon> groups = new LinkedHashMap<>();
        for (Cannon cannon : all) {
            BlockFace face = safeDir(cannon);
            groups.putIfAbsent(face, cannon);
        }

        UUID uid = player.getUniqueId();
        aimGroups.put(uid, groups);

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            PlayerCraft current = CraftManager.getInstance().getCraftByPlayer(player);
            if (!player.isOnline() || current == null) { stopAiming(player); return; }

            Map<BlockFace, Cannon> tracked = aimGroups.get(uid);
            if (tracked == null) return;

            Location loc  = player.getLocation();
            float yaw     = loc.getYaw();
            float pitch   = loc.getPitch();

            // Aim ALL cannons
            for (Cannon cannon : all) {
                try {
                    GunAngles angles = GunAngles.getGunAngle(cannon, yaw, pitch);
                    api.setCannonAngle(cannon, angles.getHorizontal(), angles.getVertical());
                } catch (Exception ignored) {}
            }

            // Draw trajectory per side — skip sides whose facing angle is exceeded
            clearTrajectory(player);
            List<Location> allPoints = new ArrayList<>();
            for (Map.Entry<BlockFace, Cannon> entry : tracked.entrySet()) {
                BlockFace face = entry.getKey();
                if (face != null && !facingCompatible(face, yaw)) continue; // hide exceeded side
                Material color = SIDE_COLOR.getOrDefault(face, FALLBACK_COLOR);
                allPoints.addAll(drawTrajectory(player, entry.getValue(),
                        loc.getDirection().normalize(), color));
            }
            sentTrajectory.put(uid, allPoints);

        }, 0L, 2L);

        aimTasks.put(uid, task);
        player.sendMessage(Lang.msg("msg.aim_on", player, NamedTextColor.GREEN, all.size()));
    }

    /**
     * Returns true if the cannon facing direction is roughly compatible with the
     * player's horizontal look direction (dot product > 0, i.e. angle < 90°).
     */
    private boolean facingCompatible(BlockFace face, float yaw) {
        int fx = face.getModX();
        int fz = face.getModZ();
        if (fx == 0 && fz == 0) return true; // UP/DOWN or unknown — always show
        double yawRad = Math.toRadians(yaw);
        double px = -Math.sin(yawRad);
        double pz =  Math.cos(yawRad);
        return (fx * px + fz * pz) > 0;
    }

    // ── Trajectory ────────────────────────────────────────────────────────────

    private List<Location> drawTrajectory(Player player, Cannon cannon,
                                          org.bukkit.util.Vector direction,
                                          Material trailMat) {
        Location start = muzzleLocation(cannon);
        if (start == null) return List.of();
        World world = start.getWorld();
        if (world == null) return List.of();

        double speed = cannonSpeed(cannon);
        double vx = direction.getX() * speed;
        double vy = direction.getY() * speed;
        double vz = direction.getZ() * speed;
        double x = start.getX(), y = start.getY(), z = start.getZ();

        List<Location> points = new ArrayList<>();
        for (int t = 0; t < MAX_TICKS; t++) {
            vy -= GRAVITY;
            x += vx; y += vy; z += vz;
            int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
            Block block = world.getBlockAt(bx, by, bz);
            if (block.getType().isSolid()) {
                Location impact = new Location(world, bx, by, bz);
                player.sendBlockChange(impact, IMPACT_COLOR.createBlockData());
                points.add(impact);
                break;
            }
            if (t % SAMPLE_TICKS == 0) {
                Location pt = new Location(world, bx, by, bz);
                player.sendBlockChange(pt, trailMat.createBlockData());
                points.add(pt);
            }
        }
        return points;
    }

    private void clearTrajectory(Player player) {
        List<Location> prev = sentTrajectory.remove(player.getUniqueId());
        if (prev == null) return;
        for (Location loc : prev) player.sendBlockChange(loc, loc.getBlock().getBlockData());
    }

    // ── Stop ──────────────────────────────────────────────────────────────────

    public void stopAiming(Player player) {
        UUID uid = player.getUniqueId();
        BukkitTask task = aimTasks.remove(uid);
        if (task != null) task.cancel();
        boolean wasActive = aimGroups.remove(uid) != null;
        clearTrajectory(player);
        if (wasActive && player.isOnline())
            player.sendMessage(Lang.msg("msg.aim_off", player, NamedTextColor.YELLOW));
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    @EventHandler public void onQuit(PlayerQuitEvent event) { stopAiming(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCraftRelease(CraftReleaseEvent event) {
        if (!(event.getCraft() instanceof net.countercraft.movecraft.craft.PilotedCraft pc)) return;
        Player pilot = pc.getPilot();
        if (pilot != null) stopAiming(pilot);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BlockFace safeDir(Cannon cannon) {
        try { return cannon.getCannonPosition().getCannonDirection(); }
        catch (Exception e) { return null; }
    }

    private Location muzzleLocation(Cannon cannon) {
        try {
            Location loc = cannon.getCannonDesign().getMuzzle(cannon);
            if (loc != null) return loc;
        } catch (Exception ignored) {}
        try {
            var off = cannon.getCannonPosition().getOffset();
            World world = cannon.getWorldBukkit();
            return new Location(world, off.getX() + 0.5, off.getY() + 1.5, off.getZ() + 0.5);
        } catch (Exception ignored) {}
        return null;
    }

    private double cannonSpeed(Cannon cannon) {
        try { double v = cannon.getCannonballVelocity(); if (v > 0) return v; }
        catch (Exception ignored) {}
        return DEFAULT_SPEED;
    }

    private CannonsAPI getCannonsAPI() {
        Cannons c = (Cannons) Bukkit.getPluginManager().getPlugin("Cannons");
        return c != null ? c.getCannonsAPI() : null;
    }
}
