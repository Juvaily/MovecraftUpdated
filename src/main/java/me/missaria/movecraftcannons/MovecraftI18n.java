package me.missaria.movecraftcannons;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player Movecraft message translation via Netty packet interception.
 * Intercepts ClientboundSystemChatPacket, checks against known Movecraft
 * English strings, replaces with RU or UK based on player's client locale.
 */
public class MovecraftI18n implements Listener {

    private static final String HANDLER = "movecraft_i18n";
    private static final int RU = 0, UK = 1;

    private static final List<String[]> EXACT  = new ArrayList<>();
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
          "Ошибка запуска! Запрещённый блок на транспорте.",
          "Помилка запуску! Заборонений блок на транспорті.");
        e("Detection Failed! Forbidden sign string was found on the craft.",
          "Ошибка запуска! Запрещённая строка знака на транспорте.",
          "Помилка запуску! Заборонений рядок знаку на транспорті.");
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

        // Movement failures
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
        e("Gearshift is disabled for this craft type",
          "Переключение передач недоступно для этого типа.",
          "Перемикання передач недоступне для цього типу.");
        e("Invalid Coordinates",
          "Неверные координаты.",
          "Невірні координати.");
        e("Insufficient Permissions",
          "Недостаточно прав.",
          "Недостатньо прав.");
        e("Craft must be part of another craft",
          "Транспорт должен быть частью другого транспорта.",
          "Транспорт має бути частиною іншого транспорту.");

        // Release
        e("You do not have a craft to release!",
          "У вас нет транспорта для освобождения!",
          "У вас немає транспорту для звільнення!");
        e("WARNING! There are blocks near your craft that may merge with the craft.",
          "ВНИМАНИЕ! Рядом с транспортом есть блоки, которые могут слиться с ним.",
          "УВАГА! Поруч з транспортом є блоки, які можуть злитися з ним.");

        // ManOverboard
        e("No valid craft to ManOverboard to.",
          "Нет подходящего транспорта.",
          "Немає підходящого транспорту.");
        e("Distance to craft is too far.",
          "Расстояние до транспорта слишком велико.",
          "Відстань до транспорту занадто велика.");
        e("You waited too long.",
          "Вы ждали слишком долго.",
          "Ви чекали надто довго.");
        e("Can't teleport to a disabled craft.",
          "Нельзя телепортироваться на отключённый транспорт.",
          "Неможливо телепортуватися на вимкнений транспорт.");

        // Remote Sign
        e("Remote Signs cannot be blank!",
          "Знаки дист. управления не могут быть пустыми!",
          "Знаки дист. керування не можуть бути порожніми!");
        e("Remote Sign must be a part of a piloted craft!",
          "Знак дист. управления должен быть частью пилотируемого транспорта!",
          "Знак дист. керування має бути частиною пілотованого транспорту!");
        e("Remote Signs not allowed on this craft!",
          "Знаки дист. управления не разрешены на этом транспорте!",
          "Знаки дист. керування не дозволені на цьому транспорті!");
        e("Could not find target sign!",
          "Не удалось найти целевой знак!",
          "Не вдалося знайти цільовий знак!");
        e("Remote Sign cannot remote another Remote Sign!",
          "Знак дист. управления не может управлять другим!",
          "Знак дист. керування не може керувати іншим!");

        // Prefix matches (messages with format args — translate prefix only)
        p("Detection Failed! The craft was too small.",
          "Ошибка запуска! Транспорт слишком мал.",
          "Помилка запуску! Транспорт задто малий.");
        p("Detection Failed! The craft was too large.",
          "Ошибка запуска! Транспорт слишком велик.",
          "Помилка запуску! Транспорт задто великий.");
        p("Teleportation cooldown is active.",
          "Перезарядка телепортации активна.",
          "Перезарядка телепортації активна.");
        p("This craft cannot move over",
          "Транспорт не может проехать над этим блоком.",
          "Транспорт не може проїхати над цим блоком.");
        p("The farthest extent now faces",
          "Нос теперь смотрит на",
          "Ніс тепер дивиться на");
        p("Warning: Forbidden remote sign(s) found",
          "Предупреждение: обнаружены запрещённые знаки:",
          "Попередження: знайдено заборонені знаки:");
    }

    private static void e(String en, String ru, String uk) { EXACT.add(new String[]{en, ru, uk}); }
    private static void p(String pf, String ru, String uk) { PREFIX.add(new String[]{pf, ru, uk}); }

    // ── Per-player locale cache ───────────────────────────────────────────────

    private final Map<UUID, String> locales = new ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        locales.put(player.getUniqueId(), Lang.langOf(player));
        inject(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        locales.remove(player.getUniqueId());
        try {
            Channel ch = getChannel(player);
            if (ch.pipeline().get(HANDLER) != null) ch.pipeline().remove(HANDLER);
        } catch (Exception ignored) {}
    }

    // ── Netty injection ───────────────────────────────────────────────────────

    private void inject(Player player) {
        try {
            Channel ch = getChannel(player);
            if (ch.pipeline().get(HANDLER) != null) return;
            UUID uid = player.getUniqueId();
            ch.pipeline().addBefore("packet_handler", HANDLER, new ChannelDuplexHandler() {
                @Override
                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                    super.write(ctx, tryReplace(msg, locales.getOrDefault(uid, "ru")), promise);
                }
            });
        } catch (Exception ignored) {}
    }

    // ── Packet replacement ────────────────────────────────────────────────────

    static Object tryReplace(Object pkt, String lang) {
        if (!pkt.getClass().getSimpleName().equals("ClientboundSystemChatPacket")) return pkt;
        try {
            Field cf = findField(pkt.getClass(), "content");
            if (cf == null) return pkt;
            Object nmsComp = cf.get(pkt);

            // Component.getString() returns full plain text
            String plain = (String) nmsComp.getClass().getMethod("getString").invoke(nmsComp);

            String[] tr = match(plain);
            if (tr == null) return pkt;
            String text = "uk".equals(lang) ? tr[UK] : tr[RU];

            // net.minecraft.network.chat.Component.literal(text)
            Class<?> compCls = Class.forName("net.minecraft.network.chat.Component");
            Object newNms = compCls.getMethod("literal", String.class).invoke(null, text);

            Field of = findField(pkt.getClass(), "overlay");
            if (of == null) return pkt;

            Constructor<?> ctor = pkt.getClass().getDeclaredConstructor(compCls, boolean.class);
            ctor.setAccessible(true);
            return ctor.newInstance(newNms, of.get(pkt));
        } catch (Exception e) {
            return pkt;
        }
    }

    static String[] match(String plain) {
        for (String[] r : EXACT)  if (plain.equals(r[0]))      return new String[]{r[1], r[2]};
        for (String[] r : PREFIX) if (plain.startsWith(r[0]))  return new String[]{r[1], r[2]};
        return null;
    }

    // ── Reflection helpers ────────────────────────────────────────────────────

    private static Channel getChannel(Player p) throws Exception {
        Object handle = p.getClass().getMethod("getHandle").invoke(p);
        Field f1 = findField(handle.getClass(), "connection");
        Object conn1 = f1.get(handle);
        Field f2 = findField(conn1.getClass(), "connection");
        Object conn2 = f2.get(conn1);
        Field f3 = findField(conn2.getClass(), "channel");
        return (Channel) f3.get(conn2);
    }

    private static Field findField(Class<?> c, String name) {
        for (; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }
}
