package me.missaria.movecraftcannons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Lang {

    private static final Map<String, YamlConfiguration> locales = new HashMap<>();
    private static final String DEFAULT = "ru";

    public static void load(Plugin plugin) {
        for (String code : List.of("ru", "uk")) {
            plugin.saveResource("lang/" + code + ".yml", false);
            File f = new File(plugin.getDataFolder(), "lang/" + code + ".yml");
            locales.put(code, YamlConfiguration.loadConfiguration(f));
        }
    }

    /** Translated string for the player's locale; falls back to ru; returns key if missing. */
    public static String get(String key, Player player, Object... args) {
        String lang = langOf(player);
        YamlConfiguration cfg = locales.get(lang);
        String val = cfg != null ? cfg.getString(key) : null;
        if (val == null) {
            YamlConfiguration def = locales.get(DEFAULT);
            val = def != null ? def.getString(key) : null;
        }
        if (val == null) return key;
        return format(val, args);
    }

    /** Translated string using default (ru) locale. */
    public static String get(String key, Object... args) {
        YamlConfiguration def = locales.get(DEFAULT);
        String val = def != null ? def.getString(key) : null;
        if (val == null) return key;
        return format(val, args);
    }

    /** Shortcut: colored Component for a player. */
    public static Component msg(String key, Player player, NamedTextColor color, Object... args) {
        return Component.text(get(key, player, args)).color(color);
    }

    /** Shortcut: colored Component using default locale. */
    public static Component msg(String key, NamedTextColor color, Object... args) {
        return Component.text(get(key, args)).color(color);
    }

    private static String langOf(Player player) {
        try { return player.getLocale().substring(0, 2).toLowerCase(); }
        catch (Exception e) { return DEFAULT; }
    }

    private static String format(String val, Object[] args) {
        for (int i = 0; i < args.length; i++) {
            val = val.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return val;
    }
}
