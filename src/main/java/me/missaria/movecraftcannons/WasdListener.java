package me.missaria.movecraftcannons;

import net.countercraft.movecraft.MovecraftLocation;
import net.countercraft.movecraft.MovecraftRotation;
import net.countercraft.movecraft.craft.CraftManager;
import net.countercraft.movecraft.craft.PlayerCraft;
import net.countercraft.movecraft.events.CraftReleaseEvent;
import net.countercraft.movecraft.events.CraftTranslateEvent;
import net.countercraft.movecraft.util.hitboxes.HitBox;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WasdListener implements Listener {

    private final MovecraftCannonsPlugin plugin;

    private final Map<UUID, int[]>    latestDir       = new ConcurrentHashMap<>();
    private final Map<UUID, Long>     latestTime      = new ConcurrentHashMap<>();
    private final Map<UUID, Long>     lastRotate      = new ConcurrentHashMap<>();

    // Flight state saved on DC entry
    private final Map<UUID, Boolean>  savedAllowFlight = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean>  savedFlying      = new ConcurrentHashMap<>();
    private final Map<UUID, Float>    savedWalkSpeed   = new ConcurrentHashMap<>();
    private final Map<UUID, Float>    savedFlySpeed    = new ConcurrentHashMap<>();

    // DC extras: scoreboard
    private final Map<UUID, Scoreboard> dcScoreboards   = new ConcurrentHashMap<>();

    private static final long  ROTATE_DEBOUNCE = 600L;
    private static final float PILOT_SPEED     = 0.005f;
    private static final double MOVE_THRESHOLD = 0.001;

    public WasdListener(MovecraftCannonsPlugin plugin) {
        this.plugin = plugin;
        long cooldown = plugin.getConfig().getLong("wasd.cooldown_ms", 200L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, Math.max(1L, cooldown / 50L));
        Bukkit.getScheduler().runTaskTimer(plugin, this::syncFlightState, 0L, 10L);
    }

    // ── Flight + DC state management ──────────────────────────────────────────

    private void syncFlightState() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uid = player.getUniqueId();
            PlayerCraft craft = CraftManager.getInstance().getCraftByPlayer(player);
            boolean locked  = craft != null && craft.getPilotLocked();
            boolean tracked = savedAllowFlight.containsKey(uid);

            if (locked && !tracked) {
                // ── Enter DC mode ──
                // Save flight state
                float curWalk = player.getWalkSpeed();
                float curFly  = player.getFlySpeed();
                savedAllowFlight.put(uid, player.getAllowFlight());
                savedFlying.put(uid, player.isFlying());
                savedWalkSpeed.put(uid, curWalk > PILOT_SPEED * 2 ? curWalk : 0.2f);
                savedFlySpeed.put(uid,  curFly  > PILOT_SPEED * 2 ? curFly  : 0.1f);
                player.setAllowFlight(true);
                player.setFlying(true);
                player.setWalkSpeed(PILOT_SPEED);
                player.setFlySpeed(PILOT_SPEED);

                // Invulnerable + invisible to others
                player.setInvulnerable(true);
                player.addPotionEffect(new PotionEffect(
                        PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0,
                        true,   // ambient (fewer particles for self)
                        false,  // no particles for others
                        false)); // no icon

                // Scoreboard sidebar
                String shipName = craftName(craft);
                Scoreboard sb = buildDcScoreboard(player, shipName);
                dcScoreboards.put(uid, sb);
                player.setScoreboard(sb);

            } else if (!locked && tracked) {
                restoreFlight(player);
            }
        }
    }

    private void restoreFlight(Player player) {
        UUID uid = player.getUniqueId();

        // Restore flight
        Boolean origAllow  = savedAllowFlight.remove(uid);
        Boolean origFlying = savedFlying.remove(uid);
        Float   origWalk   = savedWalkSpeed.remove(uid);
        Float   origFly    = savedFlySpeed.remove(uid);

        if (origAllow != null) {
            player.setAllowFlight(origAllow);
            if (origFlying != null && !origFlying && !origAllow) player.setFlying(false);
        } else {
            player.setWalkSpeed(0.2f);
            player.setFlySpeed(0.1f);
        }
        if (origWalk != null) player.setWalkSpeed(origWalk);
        if (origFly  != null) player.setFlySpeed(origFly);

        latestDir.remove(uid);
        latestTime.remove(uid);

        // Restore DC extras
        player.setInvulnerable(false);
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        dcScoreboards.remove(uid);
        try { player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard()); }
        catch (Exception ignored) {}
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCraftRelease(CraftReleaseEvent event) {
        if (!(event.getCraft() instanceof net.countercraft.movecraft.craft.PilotedCraft pc)) return;
        Player pilot = pc.getPilot();
        if (pilot != null) restoreFlight(pilot);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        restoreFlight(event.getPlayer());
    }

    // ── Follow ship on translate ───────────────────────────────────────────────

    // When the craft moves, move the hovering pilot the same delta (next tick,
    // after blocks have physically translated).
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraftTranslateDC(CraftTranslateEvent event) {
        if (!(event.getCraft() instanceof net.countercraft.movecraft.craft.PilotedCraft pc)) return;
        Player pilot = pc.getPilot();
        if (pilot == null || !savedAllowFlight.containsKey(pilot.getUniqueId())) return;

        HitBox oldBox = event.getOldHitBox();
        HitBox newBox = event.getNewHitBox();
        int dx = newBox.getMinX() - oldBox.getMinX();
        int dz = newBox.getMinZ() - oldBox.getMinZ();
        if (dx == 0 && dz == 0) return;

        Location cur = pilot.getLocation().clone();
        Bukkit.getScheduler().runTask(plugin, () -> pilot.teleport(cur.add(dx, 0, dz)));
    }

    // ── WASD direction capture ─────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent) return;

        Location from = event.getFrom();
        Location to   = event.getTo();
        if (to == null) return;

        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        if (Math.abs(dx) < MOVE_THRESHOLD && Math.abs(dy) < MOVE_THRESHOLD && Math.abs(dz) < MOVE_THRESHOLD) return;

        Player      player = event.getPlayer();
        PlayerCraft craft  = CraftManager.getInstance().getCraftByPlayer(player);
        if (craft == null || !craft.getPilotLocked()) return;

        // Always anchor the pilot — cancel all movement (including jumping/falling)
        Location pushBack = from.clone();
        pushBack.setYaw(to.getYaw());
        pushBack.setPitch(to.getPitch());
        event.setTo(pushBack);

        // Ship movement from horizontal input only
        if (Math.abs(dx) >= MOVE_THRESHOLD || Math.abs(dz) >= MOVE_THRESHOLD) {
            int[] dir = resolveDir(player.getLocation().getYaw(), dx, dz);
            if (dir[0] != 0 || dir[1] != 0) {
                UUID uid = player.getUniqueId();
                latestDir.put(uid, dir);
                latestTime.put(uid, System.currentTimeMillis());
            }
        }
    }

    // ── Periodic movement tick ─────────────────────────────────────────────────

    private void tick() {
        long now      = System.currentTimeMillis();
        long cooldown = plugin.getConfig().getLong("wasd.cooldown_ms", 200L);
        long ttl      = 150L;

        latestDir.forEach((uid, dir) -> {
            Long t = latestTime.get(uid);
            if (t == null || now - t > ttl) {
                latestDir.remove(uid);
                latestTime.remove(uid);
                return;
            }
            if (dir[0] == 0 && dir[1] == 0) return;

            Player player = Bukkit.getPlayer(uid);
            if (player == null) { latestDir.remove(uid); latestTime.remove(uid); return; }

            PlayerCraft craft = CraftManager.getInstance().getCraftByPlayer(player);
            if (craft == null || !craft.getPilotLocked()) {
                latestDir.remove(uid); latestTime.remove(uid); return;
            }

            craft.translate(dir[0], 0, dir[1]);

            if (plugin.isDebug())
                plugin.getLogger().info("[wasd] " + player.getName()
                    + " (" + dir[0] + ",0," + dir[1] + ") age=" + (now - t) + "ms");
        });
    }

    private int[] resolveDir(float rawYaw, double dx, double dz) {
        double y       = ((rawYaw % 360) + 360) % 360;
        int    snapped = ((int) Math.round(y / 90.0) * 90) % 360;

        int fx = -(int) Math.round(Math.sin(Math.toRadians(snapped)));
        int fz =  (int) Math.round(Math.cos(Math.toRadians(snapped)));
        int rx = -(int) Math.round(Math.sin(Math.toRadians(snapped + 90)));
        int rz =  (int) Math.round(Math.cos(Math.toRadians(snapped + 90)));

        double fwd    = dx * fx + dz * fz;
        double strafe = dx * rx + dz * rz;

        int rdx = 0, rdz = 0;
        if      (fwd    >  MOVE_THRESHOLD) { rdx += fx; rdz += fz; }
        else if (fwd    < -MOVE_THRESHOLD) { rdx -= fx; rdz -= fz; }
        if      (strafe >  MOVE_THRESHOLD) { rdx += rx; rdz += rz; }
        else if (strafe < -MOVE_THRESHOLD) { rdx -= rx; rdz -= rz; }

        return new int[]{Math.max(-1, Math.min(1, rdx)), Math.max(-1, Math.min(1, rdz))};
    }

    // ── Q → rotate left, F → rotate right ────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDropItem(PlayerDropItemEvent event) {
        Player      player = event.getPlayer();
        PlayerCraft craft  = CraftManager.getInstance().getCraftByPlayer(player);
        if (craft == null || !craft.getPilotLocked()) return;
        event.setCancelled(true);
        if (!rotateDebounce(player.getUniqueId())) return;
        rotateCraft(craft, MovecraftRotation.ANTICLOCKWISE);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player      player = event.getPlayer();
        PlayerCraft craft  = CraftManager.getInstance().getCraftByPlayer(player);
        if (craft == null || !craft.getPilotLocked()) return;
        event.setCancelled(true);
        if (!rotateDebounce(player.getUniqueId())) return;
        rotateCraft(craft, MovecraftRotation.CLOCKWISE);
    }

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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String craftName(PlayerCraft craft) {
        try {
            String n = craft.getType().getStringProperty(
                    net.countercraft.movecraft.craft.type.CraftType.NAME);
            if (n != null && !n.isBlank()) return n;
        } catch (Exception ignored) {}
        return "Транспорт";
    }

    @SuppressWarnings("deprecation")
    private Scoreboard buildDcScoreboard(Player player, String shipName) {
        Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = sb.registerNewObjective("dc", "dummy",
                Component.text(Lang.get("dc.title", player))
                        .color(NamedTextColor.DARK_AQUA)
                        .decoration(TextDecoration.BOLD, true));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        String[] lines = {
            "§e" + shipName,
            Lang.get("dc.sep1", player),
            Lang.get("dc.wasd", player),
            Lang.get("dc.rotate", player),
            Lang.get("dc.menu", player),
            Lang.get("dc.sep2", player),
            Lang.get("dc.leave", player),
        };
        for (int i = 0; i < lines.length; i++) {
            obj.getScore(lines[i]).setScore(lines.length - i);
        }
        return sb;
    }
}
