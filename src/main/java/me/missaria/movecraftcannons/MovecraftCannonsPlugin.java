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
    private WasdListener wasdListener;

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
        wasdListener = new WasdListener(this, windManager, healthBar);
        getServer().getPluginManager().registerEvents(wasdListener, this);
        getServer().getPluginManager().registerEvents(new CommandBlockListener(), this);
        AimListener aimListener = new AimListener(this);
        getServer().getPluginManager().registerEvents(aimListener, this);
        shipMenu = new ShipMenuListener(this, windManager, aimListener, healthBar);
        getServer().getPluginManager().registerEvents(shipMenu, this);
        TurretListener turretListener = new TurretListener(this);
        getServer().getPluginManager().registerEvents(turretListener, this);
        wasdListener.setTurretListener(turretListener);
        shipMenu.setTurretListener(turretListener);

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
        if (command.getName().equalsIgnoreCase("hudhide")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Lang.get("msg.only_players"));
                return true;
            }
            boolean hidden = wasdListener.toggleHud(player);
            player.sendMessage(Lang.get(hidden ? "hud.hidden" : "hud.shown", player));
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
