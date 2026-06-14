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
    private static String serverLang = DEFAULT;

    public static void load(Plugin plugin) {
        serverLang = plugin.getConfig().getString("language", DEFAULT);
        for (String code : List.of("ru", "uk")) {
            plugin.saveResource("lang/" + code + ".yml", true);
            File f = new File(plugin.getDataFolder(), "lang/" + code + ".yml");
            locales.put(code, YamlConfiguration.loadConfiguration(f));
        }
    }

    /** Translated string for the player's locale; falls back to server lang then ru. */
    public static String get(String key, Player player, Object... args) {
        String lang = langOf(player);
        String val = lookup(lang, key);
        if (val == null) val = lookup(DEFAULT, key);
        return format(val != null ? val : key, args);
    }

    /** Translated string using server-configured language (for TextDisplay etc.); falls back to ru. */
    public static String get(String key, Object... args) {
        String val = lookup(serverLang, key);
        if (val == null && !serverLang.equals(DEFAULT)) val = lookup(DEFAULT, key);
        return format(val != null ? val : key, args);
    }

    private static String lookup(String lang, String key) {
        YamlConfiguration cfg = locales.get(lang);
        return cfg != null ? cfg.getString(key) : null;
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
