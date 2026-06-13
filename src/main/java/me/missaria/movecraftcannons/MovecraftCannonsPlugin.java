package me.missaria.movecraftcannons;

import org.bukkit.plugin.java.JavaPlugin;

public class MovecraftCannonsPlugin extends JavaPlugin {

    private static MovecraftCannonsPlugin instance;
    private boolean debug;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        debug = getConfig().getBoolean("debug", false);

        getServer().getPluginManager().registerEvents(new CraftMoveListener(this), this);
        getServer().getPluginManager().registerEvents(new HealthBarListener(this), this);
        getServer().getPluginManager().registerEvents(new WasdListener(this), this);
        getServer().getPluginManager().registerEvents(new ShipMenuListener(this), this);

        getLogger().info("MovecraftCannons enabled.");
        getLogger().info("  Cannon translation fix: setOffset() instead of missing move().");
        getLogger().info("  Health bar: TextDisplay entity above craft, follows movement.");
        if (debug) getLogger().info("  Debug mode ON.");
    }

    @Override
    public void onDisable() {
        getLogger().info("MovecraftCannons disabled.");
    }

    public static MovecraftCannonsPlugin getInstance() { return instance; }
    public boolean isDebug() { return debug; }
}
