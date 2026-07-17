package me.missaria.movecraftcannons;

import at.pavlov.cannons.cannon.Cannon;
import net.countercraft.movecraft.MovecraftRotation;
import net.countercraft.movecraft.craft.CraftManager;
import net.countercraft.movecraft.craft.PlayerCraft;
import net.countercraft.movecraft.events.CraftReleaseEvent;
import org.bukkit.ChatColor;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TurretListener implements Listener {

    private final MovecraftCannonsPlugin plugin;
    public TurretListener(MovecraftCannonsPlugin plugin) { this.plugin = plugin; }

    public static final int ALL_TURRETS = -1;

    private static final long ROTATE_DEBOUNCE_MS = 500L;

    private static final Set<org.bukkit.Material> RESERVED_ITEMS = Set.of(
            org.bukkit.Material.WRITTEN_BOOK,
            org.bukkit.Material.WRITABLE_BOOK,
            org.bukkit.Material.CLOCK
    );

    // Stores sign blocks of found turrets, keyed by pilot UUID
    private final Map<UUID, List<Block>> turretCache = new ConcurrentHashMap<>();
    private final Map<UUID, Integer>     selectedIdx = new ConcurrentHashMap<>();
    private final Map<UUID, Long>        lastRotate  = new ConcurrentHashMap<>();

    // ── Left/right click → simulate sign click (SubcraftRotateSign does the rest) ──

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        boolean isLeft  = action == Action.LEFT_CLICK_AIR  || action == Action.LEFT_CLICK_BLOCK;
        boolean isRight = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        if (!isLeft && !isRight) return;

        Player player = event.getPlayer();
        PlayerCraft craft = CraftManager.getInstance().getCraftByPlayer(player);
        if (craft == null || !craft.getPilotLocked()) return;

        UUID uid = player.getUniqueId();
        if (!selectedIdx.containsKey(uid)) return;

        ItemStack held = event.getItem();
        if (held != null && RESERVED_ITEMS.contains(held.getType())) return;

        List<Block> turrets = turretCache.get(uid);
        if (turrets == null || turrets.isEmpty()) { selectedIdx.remove(uid); return; }

        event.setCancelled(true);
        if (!debounce(uid)) return;

        MovecraftRotation rot = isLeft ? MovecraftRotation.ANTICLOCKWISE : MovecraftRotation.CLOCKWISE;
        int idx = selectedIdx.get(uid);

        if (idx == ALL_TURRETS) {
            for (Block signBlock : turrets) {
                if (isTurretSign(signBlock)) simulateSignClick(signBlock, player, rot);
            }
        } else {
            Block signBlock = getSelectedSign(uid);
            if (signBlock == null) { selectedIdx.remove(uid); return; }
            simulateSignClick(signBlock, player, rot);
        }
    }

    // ── Clear on quit / craft release ────────────────────────────────────────

    @EventHandler
    public void onQuit(PlayerQuitEvent event) { clear(event.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCraftRelease(CraftReleaseEvent event) {
        if (!(event.getCraft() instanceof net.countercraft.movecraft.craft.PilotedCraft pc)) return;
        org.bukkit.entity.Player pilot = pc.getPilot();
        if (pilot != null) clear(pilot.getUniqueId());
    }

    private void clear(UUID uid) {
        turretCache.remove(uid);
        selectedIdx.remove(uid);
        lastRotate.remove(uid);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Find turret signs for use by ship menu (no debug output). */
    public List<Block> findTurretSigns(PlayerCraft parent) {
        return findTurretSigns(parent, null);
    }

    public void rotateTurretFromMenu(Block signBlock, Player player, MovecraftRotation rotation) {
        player.sendMessage("§7[turret] rot=" + rotation + " block=" + signBlock.getType() + " @ " + signBlock.getX() + "," + signBlock.getY() + "," + signBlock.getZ());
        simulateSignClick(signBlock, player, rotation);
    }

    public String getTurretLabel(Block signBlock) {
        return turretLabel(signBlock);
    }

    /** Push turret list into cache (called from menu on open). */
    public void setCache(UUID uid, List<Block> turrets) {
        turretCache.put(uid, new ArrayList<>(turrets));
    }

    public void selectAll(UUID uid) {
        selectedIdx.put(uid, ALL_TURRETS);
    }

    public void selectOne(UUID uid, int idx) {
        selectedIdx.put(uid, idx);
    }

    public void deselect(UUID uid) {
        selectedIdx.remove(uid);
    }

    /** Returns null = nothing selected, ALL_TURRETS (-1) = all, ≥0 = specific index. */
    public Integer getSelectedIdx(UUID uid) {
        return selectedIdx.get(uid);
    }

    public String getHudLine(Player player) {
        UUID uid = player.getUniqueId();
        Integer idx = selectedIdx.get(uid);
        List<Block> list = turretCache.get(uid);
        if (idx == null || list == null || list.isEmpty()) return null;
        if (idx == ALL_TURRETS)
            return "§b🎯 " + Lang.get("turret.all", player) + " §8[" + list.size() + "]";
        if (idx >= list.size()) return null;
        return "§b🎯 " + turretLabel(list.get(idx)) + " §8[" + (idx + 1) + "/" + list.size() + "]";
    }

    /** Turret cannons are inside the parent craft's hitbox, so fire-all already covers them. */
    public List<Cannon> getAttachedTurretCannons(PlayerCraft parent) {
        return List.of();
    }

    // ── Sign scanning ─────────────────────────────────────────────────────────

    private static final int[][] FACES = {
        {1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}
    };

    private List<Block> findTurretSigns(PlayerCraft parent, Player debugPlayer) {
        List<Block> result = new ArrayList<>();
        org.bukkit.World world = parent.getWorld();
        java.util.Set<Long> checked = new java.util.HashSet<>();

        for (net.countercraft.movecraft.MovecraftLocation loc : parent.getHitBox()) {
            int x = loc.getX(), y = loc.getY(), z = loc.getZ();
            checkSign(world, x, y, z, checked, result);
            for (int[] d : FACES)
                checkSign(world, x + d[0], y + d[1], z + d[2], checked, result);
        }

        if (debugPlayer != null) {
            debugPlayer.sendMessage("§7[Debug] hitbox=" + parent.getHitBox().size()
                    + " checked=" + checked.size() + " turretSigns=" + result.size());
            // Report all signs found in the scan area
            java.util.Set<Long> signChecked = new java.util.HashSet<>();
            for (net.countercraft.movecraft.MovecraftLocation loc : parent.getHitBox()) {
                for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++) {
                    int x = loc.getX()+dx, y = loc.getY()+dy, z = loc.getZ()+dz;
                    long k = ((long)(x+30000000)<<42)|((long)(y+512)<<26)|(z+30000000);
                    if (!signChecked.add(k)) continue;
                    Block b = world.getBlockAt(x, y, z);
                    if (b.getState() instanceof Sign s) {
                        try {
                            String l0 = ChatColor.stripColor(s.getLine(0));
                            String l1 = ChatColor.stripColor(s.getLine(1));
                            debugPlayer.sendMessage("§7[Sign] '" + l0 + "' / '" + l1 + "' @ " + x+","+y+","+z);
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
        return result;
    }

    private void checkSign(org.bukkit.World world, int x, int y, int z,
                           java.util.Set<Long> checked, List<Block> result) {
        long key = ((long)(x + 30000000) << 42) | ((long)(y + 512) << 26) | (z + 30000000);
        if (!checked.add(key)) return;
        Block block = world.getBlockAt(x, y, z);
        if (isTurretSign(block)) result.add(block);
    }

    private boolean isTurretSign(Block block) {
        if (!(block.getState() instanceof Sign sign)) return false;
        // Check both sides of the sign (Bukkit 1.20+)
        for (var side : org.bukkit.block.sign.Side.values()) {
            try {
                if (matchesTurretLine(ChatColor.stripColor(sign.getSide(side).getLine(0)))) return true;
            } catch (Exception ignored) {}
        }
        // Fallback: getLine(0) for older API
        try {
            if (matchesTurretLine(ChatColor.stripColor(sign.getLine(0)))) return true;
        } catch (Exception ignored) {}
        return false;
    }

    private static boolean matchesTurretLine(String line) {
        if (line == null) return false;
        String stripped = line.replaceAll("[\\[\\]]", "").trim();
        return "Subcraft Rotate".equalsIgnoreCase(stripped);
    }

    private Block getSelectedSign(UUID uid) {
        Integer idx = selectedIdx.get(uid);
        List<Block> list = turretCache.get(uid);
        if (idx == null || list == null || idx >= list.size()) return null;
        Block b = list.get(idx);
        // Re-validate: still a turret sign?
        if (!isTurretSign(b)) { turretCache.remove(uid); selectedIdx.remove(uid); return null; }
        return b;
    }

    // ── Sign click simulation ─────────────────────────────────────────────────

    private void simulateSignClick(Block signBlock, Player player, MovecraftRotation rotation) {
        Action action = rotation == MovecraftRotation.CLOCKWISE
                ? Action.RIGHT_CLICK_BLOCK
                : Action.LEFT_CLICK_BLOCK;

        // SubcraftRotateSign.HEADER = "Subcraft Rotate" (no brackets).
        // Signs placed via Movecraft convention use "[Subcraft Rotate]" — ChatColor.stripColor()
        // does not strip brackets, so the header check fails. Temporarily remove brackets.
        Sign originalState = null;
        boolean patched = false;
        try {
            if (signBlock.getState() instanceof Sign orig) {
                String raw = orig.getLine(0);
                String noColor = ChatColor.stripColor(raw);
                if (noColor != null && noColor.startsWith("[") && noColor.endsWith("]")) {
                    originalState = orig;
                    Sign tmp = (Sign) signBlock.getState();
                    tmp.setLine(0, noColor.substring(1, noColor.length() - 1).trim());
                    tmp.update(true, false);
                    patched = true;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[turret] sign patch: " + e.getMessage());
            patched = false;
        }

        try {
            PlayerInteractEvent fake = new PlayerInteractEvent(
                    player, action, null, signBlock, BlockFace.SOUTH, EquipmentSlot.HAND);

            String l0 = "?", l1 = "?";
            try { Sign s = (Sign) signBlock.getState(); l0 = s.getLine(0); l1 = s.getLine(1); }
            catch (Exception ignored) {}
            player.sendMessage("§7[turret] sim " + action.name() + " L0='" + l0 + "' L1='" + l1 + "'");

            // Movecraft's InteractListener (LOWEST) cancels RIGHT_CLICK_BLOCK when the player is
            // piloting (for DC movement), causing SubcraftRotateSign (ignoreCancelled=true) to skip
            // the event. We bypass the event bus entirely and invoke SubcraftRotateSign directly.
            boolean invoked = false;
            for (RegisteredListener rl : PlayerInteractEvent.getHandlerList().getRegisteredListeners()) {
                if ("Movecraft".equals(rl.getPlugin().getName())
                        && "SubcraftRotateSign".equals(rl.getListener().getClass().getSimpleName())) {
                    try { rl.callEvent(fake); invoked = true; }
                    catch (Exception e) { plugin.getLogger().warning("[turret] SubcraftRotateSign: " + e.getMessage()); }
                    break;
                }
            }
            if (!invoked) player.sendMessage("§c[turret] SubcraftRotateSign listener not found");
            player.sendMessage("§7[turret] cancelled=" + fake.isCancelled());
        } finally {
            if (patched && originalState != null) {
                try { originalState.update(true, false); }
                catch (Exception ignored) {}
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean debounce(UUID uid) {
        long now = System.currentTimeMillis();
        Long last = lastRotate.get(uid);
        if (last != null && now - last < ROTATE_DEBOUNCE_MS) return false;
        lastRotate.put(uid, now);
        return true;
    }

    private String turretLabel(Block signBlock) {
        try {
            Sign sign = (Sign) signBlock.getState();
            // Try to get craft type name from line 1
            for (var side : org.bukkit.block.sign.Side.values()) {
                try {
                    String line1 = ChatColor.stripColor(sign.getSide(side).getLine(1));
                    if (line1 != null && !line1.isBlank()) return line1;
                } catch (Exception ignored) {}
            }
            String line1 = ChatColor.stripColor(sign.getLine(1));
            if (line1 != null && !line1.isBlank()) return line1;
        } catch (Exception ignored) {}
        return Lang.get("turret.default_name");
    }
}
