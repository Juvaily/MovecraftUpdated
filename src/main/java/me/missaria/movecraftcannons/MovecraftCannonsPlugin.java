package me.missaria.movecraftcannons;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class MovecraftCannonsPlugin extends JavaPlugin {

    private static MovecraftCannonsPlugin instance;
    private boolean debug;
    private ShipMenuListener shipMenu;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        debug = getConfig().getBoolean("debug", false);

        getServer().getPluginManager().registerEvents(new CraftMoveListener(this), this);
        getServer().getPluginManager().registerEvents(new HealthBarListener(this), this);
        getServer().getPluginManager().registerEvents(new WasdListener(this), this);
        shipMenu = new ShipMenuListener(this);
        getServer().getPluginManager().registerEvents(shipMenu, this);

        getLogger().info("MovecraftCannons enabled.");
        getLogger().info("  Cannon translation fix: setOffset() instead of missing move().");
        getLogger().info("  Health bar: TextDisplay entity above craft, follows movement.");
        if (debug) getLogger().info("  Debug mode ON.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("shipmenu")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Только для игроков.");
                return true;
            }
            return shipMenu.onCommand(player);
        }
        return false;
    }

    @Override
    public void onDisable() {
        getLogger().info("MovecraftCannons disabled.");
    }

    public static MovecraftCannonsPlugin getInstance() { return instance; }
    public boolean isDebug() { return debug; }
}
