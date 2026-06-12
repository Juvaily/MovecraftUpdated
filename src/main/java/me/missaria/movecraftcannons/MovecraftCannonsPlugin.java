package me.missaria.movecraftcannons;

import org.bukkit.plugin.java.JavaPlugin;

public class MovecraftCannonsPlugin extends JavaPlugin {

    private static MovecraftCannonsPlugin instance;

    @Override
    public void onEnable() {
        instance = this;
        getServer().getPluginManager().registerEvents(new CraftMoveListener(this), this);
        getLogger().info("MovecraftCannons enabled — cannon state preserved during craft movement");
    }

    @Override
    public void onDisable() {
        getLogger().info("MovecraftCannons disabled");
    }

    public static MovecraftCannonsPlugin getInstance() {
        return instance;
    }
}
