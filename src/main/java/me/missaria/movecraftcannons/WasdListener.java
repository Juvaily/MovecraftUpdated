package me.missaria.movecraftcannons;

import net.countercraft.movecraft.craft.CraftManager;
import net.countercraft.movecraft.craft.PlayerCraft;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WasdListener implements Listener {

    private final MovecraftCannonsPlugin plugin;
    private final Map<UUID, Long> lastMove = new ConcurrentHashMap<>();

    private static final double MIN_DELTA = 0.15;

    public WasdListener(MovecraftCannonsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        if (Math.abs(dx) < MIN_DELTA && Math.abs(dz) < MIN_DELTA) return;

        Player player = event.getPlayer();
        PlayerCraft craft = CraftManager.getInstance().getCraftByPlayer(player);
        if (craft == null) return;

        // Keep player in place — craft will carry them on translate
        Location cancelTo = from.clone();
        cancelTo.setYaw(to.getYaw());
        cancelTo.setPitch(to.getPitch());
        event.setTo(cancelTo);

        // Rate limit
        long now = System.currentTimeMillis();
        Long last = lastMove.get(player.getUniqueId());
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
