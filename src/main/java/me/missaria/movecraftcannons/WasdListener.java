package me.missaria.movecraftcannons;

import net.countercraft.movecraft.craft.CraftManager;
import net.countercraft.movecraft.craft.PlayerCraft;
import net.countercraft.movecraft.events.CraftReleaseEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WasdListener implements Listener {

    private final MovecraftCannonsPlugin plugin;
    private final Map<UUID, Long> lastMove      = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastToggle    = new ConcurrentHashMap<>();
    private final Set<UUID>       directControl = ConcurrentHashMap.newKeySet();

    private static final double MIN_DELTA      = 0.15;
    private static final long   TOGGLE_DEBOUNCE = 500L;

    public WasdListener(MovecraftCannonsPlugin plugin) {
        this.plugin = plugin;
    }

    // ── Toggle direct control ─────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK
                && event.getAction() != Action.LEFT_CLICK_AIR) return;

        // Must be left-clicking a sign (pilot/helm block) or swinging in air
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            var block = event.getClickedBlock();
            if (block == null || !Tag.SIGNS.isTagged(block.getType())) return;
        }

        Player player = event.getPlayer();
        PlayerCraft craft = CraftManager.getInstance().getCraftByPlayer(player);
        if (craft == null) return;

        // Debounce
        long now  = System.currentTimeMillis();
        Long last = lastToggle.get(player.getUniqueId());
        if (last != null && now - last < TOGGLE_DEBOUNCE) return;
        lastToggle.put(player.getUniqueId(), now);

        UUID uid = player.getUniqueId();
        if (directControl.contains(uid)) {
            directControl.remove(uid);
            player.sendMessage(Component.text("Прямое управление: ВЫКЛ", NamedTextColor.YELLOW));
        } else {
            directControl.add(uid);
            player.sendMessage(Component.text("Прямое управление: ВКЛ", NamedTextColor.GREEN));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCraftRelease(CraftReleaseEvent event) {
        if (!(event.getCraft() instanceof PlayerCraft pc)) return;
        UUID uid = pc.getPilot().getUniqueId();
        directControl.remove(uid);
        lastMove.remove(uid);
        lastToggle.remove(uid);
    }

    // ── WASD movement ─────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to   = event.getTo();
        if (to == null) return;

        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        if (Math.abs(dx) < MIN_DELTA && Math.abs(dz) < MIN_DELTA) return;

        Player player = event.getPlayer();
        if (!directControl.contains(player.getUniqueId())) return;

        PlayerCraft craft = CraftManager.getInstance().getCraftByPlayer(player);
        if (craft == null) return;

        // Keep player in place — craft carries them during translate
        Location cancelTo = from.clone();
        cancelTo.setYaw(to.getYaw());
        cancelTo.setPitch(to.getPitch());
        event.setTo(cancelTo);

        // Rate limit
        long now      = System.currentTimeMillis();
        Long last     = lastMove.get(player.getUniqueId());
        long cooldown = plugin.getConfig().getLong("wasd.cooldown_ms", 200L);
        if (last != null && now - last < cooldown) return;
        lastMove.put(player.getUniqueId(), now);

        // Move craft 1 block in dominant axis
        int tdx = 0, tdz = 0;
        if (Math.abs(dx) >= Math.abs(dz)) {
            tdx = dx > 0 ? 1 : -1;
        } else {
            tdz = dz > 0 ? 1 : -1;
        }

        craft.translate(tdx, 0, tdz);

        if (plugin.isDebug()) {
            plugin.getLogger().info("[wasd] " + player.getName() + " translate (" + tdx + ",0," + tdz + ")");
        }
    }
}
