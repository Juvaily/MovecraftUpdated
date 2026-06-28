package me.missaria.movecraftcannons;

import io.papermc.paper.event.player.SystemMessageEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Intercepts Movecraft's English system messages and replaces them with
 * per-player translations based on the player's Minecraft client locale.
 */
public class MovecraftI18n implements Listener {

    // [ru, uk] indexed by lang index: ru=0, uk=1
    private static final int RU = 0, UK = 1;

    // Exact-match table: english → [ru, uk]
    private static final List<String[]> EXACT = new ArrayList<>();
    // Prefix-match table (for messages with format args): [en_prefix, ru, uk]
    private static final List<String[]> PREFIX = new ArrayList<>();

    static {
        // Detection
        e("Succesfully piloted craft!",
          "Транспорт успешно пилотируется!",
          "Транспорт успішно пілотується!");
        e("You are already commanding a craft!",
          "Вы уже управляете транспортом!",
          "Ви вже керуєте транспортом!");
        e("The craft is already being controlled!",
          "Этот транспорт уже управляется!",
          "Цей транспорт вже керується!");
        e("Detection Failed! Forbidden block was found on the craft.",
          "Ошибка запуска! На транспорте запрещённый блок.",
          "Помилка запуску! На транспорті заборонений блок.");
        e("Detection Failed! Forbidden sign string was found on the craft.",
          "Ошибка запуска! На транспорте запрещённая строка знака.",
          "Помилка запуску! На транспорті заборонений рядок знаку.");
        e("Detection failed: Water contact required but not found",
          "Ошибка запуска: требуется контакт с водой.",
          "Помилка запуску: потрібен контакт з водою.");
        e("Not enough flyblock",
          "Недостаточно несущих блоков.",
          "Недостатньо несучих блоків.");
        e("Too much flyblock",
          "Слишком много несущих блоков.",
          "Забагато несучих блоків.");
        e("Not enough detectionblock",
          "Недостаточно блоков обнаружения.",
          "Недостатньо блоків виявлення.");
        e("Too much detectionblock",
          "Слишком много блоков обнаружения.",
          "Забагато блоків виявлення.");
        e("Not one of the registered pilots on this craft",
          "Вы не зарегистрированы как пилот этого транспорта.",
          "Ви не зареєстровані як пілот цього транспорту.");
        e("Parent Craft is busy",
          "Родительский транспорт занят.",
          "Батьківський транспорт зайнятий.");

        // Movement
        e("Craft Is Disabled!",
          "Транспорт отключён!",
          "Транспорт вимкнено!");
        e("The craft cannot move because its path is obstructed!",
          "Путь заблокирован!",
          "Шлях заблокований!");
        e("The craft cannot move because the incline is too steep!",
          "Слишком крутой уклон!",
          "Надто крутий нахил!");
        e("The craft is out of fuel!",
          "Нет топлива!",
          "Немає пального!");
        e("Craft has hit the height limit",
          "Достигнут верхний предел высоты.",
          "Досягнуто верхню межу висоти.");
        e("Craft has hit the minimum height limit",
          "Достигнут нижний предел высоты.",
          "Досягнуто нижню межу висоти.");
        e("Translation Failed: You cannot move the craft past the world border",
          "Нельзя пересечь границу мира.",
          "Не можна перетнути межу світу.");
        e("Rotation Failed: You cannot move the craft past the world border",
          "Поворот невозможен: граница мира.",
          "Поворот неможливий: межа світу.");
        e("You're turning too quickly!",
          "Слишком быстрый поворот!",
          "Надто швидкий поворот!");
        e("You are already rotating",
          "Поворот уже выполняется.",
          "Поворот вже виконується.");
        e("Craft is obstructed",
          "Транспорт заблокирован.",
          "Транспорт заблокований.");

        // Release / sinking
        e("Your craft has been released!",
          "Транспорт освобождён!",
          "Транспорт звільнено!");
        e("You have left your craft.",
          "Вы покинули транспорт.",
          "Ви покинули транспорт.");
        e("Your craft has taken too much damage and is sinking! ABANDON SHIP!",
          "Транспорт тонет! ПОКИНЬТЕ СУДНО!",
          "Транспорт тоне! ПОКИНЬТЕ СУДНО!");
        e("The craft is already sinking!",
          "Транспорт уже тонет!",
          "Транспорт вже тоне!");
        e("Scuttle was activated. Abandon Ship!",
          "Транспорт затоплен! Покиньте судно!",
          "Транспорт затоплено! Покиньте судно!");

        // Cruise
        e("Only Players may cruise.",
          "Только игрок может использовать круиз.",
          "Лише гравець може використовувати крейсерський режим.");
        e("This craft cannot cruise.",
          "Этот транспорт не поддерживает круиз.",
          "Цей транспорт не підтримує крейсерський режим.");

        // DC mode
        e("Entering Direct Control Mode",
          "Вход в режим прямого управления",
          "Вхід у режим прямого керування");
        e("Leaving Direct Control Mode",
          "Выход из режима прямого управления",
          "Вихід із режиму прямого керування");
        e("You must be piloting a craft",
          "Вы должны управлять транспортом.",
          "Ви маєте керувати транспортом.");

        // Misc
        e("Gear of craft changed.",
          "Передача переключена.",
          "Передачу змінено.");

        // --- Prefix matches (messages with format specifiers) ---
        p("Detection Failed! The craft was too small.",
          "Ошибка запуска! Транспорт слишком мал.",
          "Помилка запуску! Транспорт задто малий.");
        p("Detection Failed! The craft was too large.",
          "Ошибка запуска! Транспорт слишком велик.",
          "Помилка запуску! Транспорт задто великий.");
        p("Teleportation cooldown is active. You need to wait",
          "Перезарядка телепортации активна.",
          "Перезарядка телепортації активна.");
        p("This craft cannot move over",
          "Транспорт не может проехать над этим блоком.",
          "Транспорт не може проїхати над цим блоком.");
    }

    private static void e(String en, String ru, String uk) {
        EXACT.add(new String[]{ en, ru, uk });
    }

    private static void p(String prefix, String ru, String uk) {
        PREFIX.add(new String[]{ prefix, ru, uk });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSystemMessage(SystemMessageEvent event) {
        String plain = PlainTextComponentSerializer.plainText().serialize(event.message());
        String[] langs = match(plain);
        if (langs == null) return;

        Player player = event.getPlayer();
        String locale = Lang.langOf(player);
        String translated = "uk".equals(locale) ? langs[UK] : langs[RU];
        event.message(Component.text(translated));
    }

    private static String[] match(String plain) {
        for (String[] row : EXACT) {
            if (plain.equals(row[0])) return new String[]{ row[1], row[2] };
        }
        for (String[] row : PREFIX) {
            if (plain.startsWith(row[0])) return new String[]{ row[1], row[2] };
        }
        return null;
    }
}
