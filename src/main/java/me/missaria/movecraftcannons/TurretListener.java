package me.missaria.movecraftcannons;

import at.pavlov.cannons.cannon.Cannon;
import net.countercraft.movecraft.MovecraftRotation;
import net.countercraft.movecraft.craft.CraftManager;
import net.countercraft.movecraft.craft.PlayerCraft;
import net.kyori.adventure.text.format.NamedTextColor;
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
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TurretListener implements Listener {

    private final MovecraftCannonsPlugin plugin;
    public TurretListener(MovecraftCannonsPlugin plugin) { this.plugin = plugin; }

    private static final long ROTATE_DEBOUNCE_MS = 500L;

    private static final Set<org.bukkit.Material> RESERVED_ITEMS = Set.of(
            org.bukkit.Material.WRITTEN_BOOK,
            org.bukkit.Material.WRITABLE_BOOK,
            org.bukkit.Material.CLOCK
    );

    // Stores sign blocks of found turrets, keyed by pilot UUID
    private final Map<UUID, List<Block>> turretCache = new ConcurrentHashMap<>();
    private final Map<UUID, Integer>     selectedIdx  = new ConcurrentHashMap<>();
    private final Map<UUID, Long>        lastRotate   = new ConcurrentHashMap<>();

    // ── Shift → cycle turret signs in ship hitbox ─────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) return;
        Player player = event.getPlayer();
        PlayerCraft craft = CraftManager.getInstance().getCraftByPlayer(player);
        if (craft == null || !craft.getPilotLocked()) return;
        event.setCancelled(true);

        List<Block> turrets = findTurretSigns(craft, player);
        UUID uid = player.getUniqueId();
        turretCache.put(uid, turrets);

        if (turrets.isEmpty()) {
            selectedIdx.remove(uid);
            player.sendMessage(Lang.get("turret.none", player));
            return;
        }

        int idx = (selectedIdx.getOrDefault(uid, -1) + 1) % turrets.size();
        selectedIdx.put(uid, idx);
        player.sendMessage(Lang.msg("turret.selected", player, NamedTextColor.AQUA,
                idx + 1, turrets.size(), turretLabel(turrets.get(idx))));
    }

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

        Block signBlock = getSelectedSign(uid);
        if (signBlock == null) { selectedIdx.remove(uid); return; }

        event.setCancelled(true);
        if (!debounce(uid)) return;

        MovecraftRotation rot = isLeft ? MovecraftRotation.ANTICLOCKWISE : MovecraftRotation.CLOCKWISE;
        simulateSignClick(signBlock, player, rot);
    }

    // ── Clear on quit ─────────────────────────────────────────────────────────

    @EventHandler
    public void onQuit(PlayerQuitEvent event) { clear(event.getPlayer().getUniqueId()); }

    private void clear(UUID uid) {
        turretCache.remove(uid);
        selectedIdx.remove(uid);
        lastRotate.remove(uid);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public String getHudLine(UUID uid) {
        Integer idx = selectedIdx.get(uid);
        List<Block> list = turretCache.get(uid);
        if (idx == null || list == null || list.isEmpty() || idx >= list.size()) return null;
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
        PlayerInteractEvent fake = new PlayerInteractEvent(
                player, action,
                player.getInventory().getItemInMainHand(),
                signBlock, BlockFace.SOUTH,
                EquipmentSlot.HAND);
        org.bukkit.Bukkit.getPluginManager().callEvent(fake);
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
