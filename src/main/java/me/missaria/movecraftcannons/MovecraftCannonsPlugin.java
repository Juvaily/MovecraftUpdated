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

        getLogger().info("MovecraftCannons enabled.");
        getLogger().info("  Fix: cannon.move() missing in Cannons 3.4.3 → using setOffset() instead.");
        if (debug) getLogger().info("  Debug mode ON.");
    }

    @Override
    public void onDisable() {
        getLogger().info("MovecraftCannons disabled.");
    }

    public static MovecraftCannonsPlugin getInstance() { return instance; }
    public boolean isDebug() { return debug; }
}
