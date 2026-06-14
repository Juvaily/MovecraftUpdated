package me.missaria.movecraftcannons;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.plugin.Plugin;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class CommandBlockListener implements Listener {

    private final Set<String> blocked;

    public CommandBlockListener() {
        Set<String> cmds = new HashSet<>();
        Plugin movecraft = Bukkit.getPluginManager().getPlugin("Movecraft");
        if (movecraft != null) {
            cmds.addAll(movecraft.getDescription().getCommands().keySet());
        }
        blocked = Collections.unmodifiableSet(cmds);
    }

    /** Hide Movecraft commands from the client's tab-completion list. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onCommandSend(PlayerCommandSendEvent event) {
        if (event.getPlayer().isOp()) return;
        event.getCommands().removeIf(blocked::contains);
    }

    /** Silently cancel Movecraft commands typed by non-ops. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (event.getPlayer().isOp()) return;
        String cmd = event.getMessage().split(" ")[0].substring(1).toLowerCase();
        if (blocked.contains(cmd)) event.setCancelled(true);
    }
}
