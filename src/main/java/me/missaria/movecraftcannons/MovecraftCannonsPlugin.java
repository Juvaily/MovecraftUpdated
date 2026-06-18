package me.missaria.movecraftcannons;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class MovecraftCannonsPlugin extends JavaPlugin {

    private static MovecraftCannonsPlugin instance;
    private boolean debug;
    private ShipMenuListener shipMenu;
    private WindManager windManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        debug = getConfig().getBoolean("debug", false);

        Lang.load(this);

        getServer().getPluginManager().registerEvents(new CraftMoveListener(this), this);
        HealthBarListener healthBar = new HealthBarListener(this);
        getServer().getPluginManager().registerEvents(healthBar, this);
        windManager = new WindManager(this);
        getServer().getPluginManager().registerEvents(new WasdListener(this, windManager, healthBar), this);
        getServer().getPluginManager().registerEvents(new CommandBlockListener(), this);
        AimListener aimListener = new AimListener(this);
        getServer().getPluginManager().registerEvents(aimListener, this);
        shipMenu = new ShipMenuListener(this, windManager, aimListener, healthBar);
        getServer().getPluginManager().registerEvents(shipMenu, this);

        getLogger().info("MovecraftCannons enabled.");
        if (debug) getLogger().info("  Debug mode ON.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("shipmenu")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Lang.get("msg.only_players"));
                return true;
            }
            return shipMenu.onCommand(player);
        }
        if (command.getName().equalsIgnoreCase("windroll")) {
            if (!sender.hasPermission("movecraftcannons.admin.wind")) {
                sender.sendMessage("§cНет доступа.");
                return true;
            }
            windManager.randomize();
            sender.sendMessage("§aВетер изменён.");
            return true;
        }
        if (command.getName().equalsIgnoreCase("windmute")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Lang.get("msg.only_players"));
                return true;
            }
            boolean muted = windManager.toggleMute(player);
            player.sendMessage(Lang.get(muted ? "wind.mute.on" : "wind.mute.off", player));
            return true;
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
