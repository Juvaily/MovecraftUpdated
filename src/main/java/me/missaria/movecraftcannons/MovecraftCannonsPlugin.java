package me.missaria.movecraftcannons;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

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
        getServer().getPluginManager().registerEvents(new MovecraftI18n(), this);
        HealthBarListener healthBar = new HealthBarListener(this);
        getServer().getPluginManager().registerEvents(healthBar, this);
        windManager = new WindManager(this, healthBar);
        wasdListener = new WasdListener(this, windManager, healthBar);
        getServer().getPluginManager().registerEvents(wasdListener, this);
        getServer().getPluginManager().registerEvents(new CommandBlockListener(), this);
        CannonActivationListener cannonActivation = new CannonActivationListener();
        getServer().getPluginManager().registerEvents(cannonActivation, this);
        AimListener aimListener = new AimListener(this, cannonActivation);
        getServer().getPluginManager().registerEvents(aimListener, this);
        shipMenu = new ShipMenuListener(this, windManager, aimListener, healthBar, cannonActivation);
        getServer().getPluginManager().registerEvents(shipMenu, this);
        TurretListener turretListener = new TurretListener(this);
        getServer().getPluginManager().registerEvents(turretListener, this);
        wasdListener.setTurretListener(turretListener);
        shipMenu.setTurretListener(turretListener);
        craftAdminMenu = new CraftAdminMenu(this);
        getServer().getPluginManager().registerEvents(craftAdminMenu, this);

        installMovecraftLang();

        getLogger().info("MovecraftCannons enabled.");
        if (debug) getLogger().info("  Debug mode ON.");
    }

    private CraftAdminMenu craftAdminMenu;

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("craftadmin")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Lang.get("msg.only_players"));
                return true;
            }
            return craftAdminMenu.onCommand(player, args);
        }
        if (command.getName().equalsIgnoreCase("shipmenu")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Lang.get("msg.only_players"));
                return true;
            }
            return shipMenu.onCommand(player);
        }
        if (command.getName().equalsIgnoreCase("windroll")) {
            if (!sender.hasPermission("movecraftcannons.admin.wind")) {
                sender.sendMessage(Lang.get("msg.no_permission"));
                return true;
            }
            windManager.randomize();
            sender.sendMessage(Lang.get("msg.wind_randomized"));
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

    private void installMovecraftLang() {
        Plugin mc = getServer().getPluginManager().getPlugin("Movecraft");
        if (mc == null) return;
        File dir = new File(mc.getDataFolder(), "localisation");
        dir.mkdirs();
        for (String name : new String[]{"movecraftlang_ru.properties", "movecraftlang_uk.properties"}) {
            File dest = new File(dir, name);
            try (InputStream in = getResource(name)) {
                if (in != null) Files.copy(in, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                getLogger().warning("Failed to install " + name + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("MovecraftCannons disabled.");
    }

    public static MovecraftCannonsPlugin getInstance() { return instance; }
    public boolean isDebug() { return debug; }
}
