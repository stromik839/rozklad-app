package ua.school.rozklad;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

/** Налаштування одного віджета. Тема й фон віджетів не залежать від теми застосунку. */
final class WidgetConfig {

    String kind = "both";       // bells, lessons, both
    String theme = "dark";      // system, light, dark — темна означає білий текст
    String bg = "none";         // none, color, photo — за замовчуванням без фону
    String color = "";          // колір фону: "" — як у теми (чорний або білий), або #rrggbb
    String photo = "app";       // app — шпалери застосунку, або позначка свого фото
    int clear = 30;             // прозорість фону, %
    int blur = 40;              // розмиття фото, %
    int dim = 20;               // затемнення фото, %
    int radius = 40;            // заокруглення кутів, %
    int scale = 100;            // розмір тексту, %

    static WidgetConfig defaults(String kind) {
        WidgetConfig c = new WidgetConfig();
        c.kind = kind;
        return c;
    }

    @SuppressWarnings("unchecked")
    static WidgetConfig fromJson(String json, String kind) {
        WidgetConfig c = defaults(kind);
        if (json == null || json.isEmpty()) return c;
        try {
            Map<String, Object> m = (Map<String, Object>) Json.parse(json);
            c.kind = pick(m, "kind", c.kind, "bells", "lessons", "both");
            c.theme = pick(m, "theme", c.theme, "system", "light", "dark");
            c.bg = pick(m, "bg", c.bg, "none", "color", "photo");
            Object col = m.get("color");
            if (col instanceof String && ((String) col).matches("#[0-9a-fA-F]{6}")) c.color = ((String) col).toLowerCase();
            Object ph = m.get("photo");
            if (ph instanceof String && ((String) ph).matches("[A-Za-z0-9_]{1,40}")) c.photo = (String) ph;
            c.clear = num(m, "clear", c.clear, 0, 100);
            c.blur = num(m, "blur", c.blur, 0, 100);
            c.dim = num(m, "dim", c.dim, 0, 90);
            c.radius = num(m, "radius", c.radius, 0, 100);
            c.scale = num(m, "scale", c.scale, 70, 130);
        } catch (RuntimeException e) {
            // зіпсовані дані — лишаємо типові значення
        }
        return c;
    }

    private static String pick(Map<String, Object> m, String key, String def, String... allowed) {
        Object v = m.get(key);
        if (v instanceof String) for (String a : allowed) if (a.equals(v)) return a;
        return def;
    }

    private static int num(Map<String, Object> m, String key, int def, int min, int max) {
        Object v = m.get(key);
        if (!(v instanceof Number)) return def;
        return Math.max(min, Math.min(max, (int) Math.round(((Number) v).doubleValue())));
    }

    String toJson() {
        return "{\"kind\":" + Json.quote(kind) + ",\"theme\":" + Json.quote(theme) + ",\"bg\":" + Json.quote(bg)
                + ",\"color\":" + Json.quote(color) + ",\"photo\":" + Json.quote(photo)
                + ",\"clear\":" + clear + ",\"blur\":" + blur + ",\"dim\":" + dim
                + ",\"radius\":" + radius + ",\"scale\":" + scale + "}";
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Store.PREFS, Context.MODE_PRIVATE);
    }

    static WidgetConfig load(Context ctx, int id, String kind) {
        return fromJson(prefs(ctx).getString("widget_" + id, null), kind);
    }

    /**
     * apply() одразу змінює налаштування в пам’яті (їх бачить і віджет, і прибирання фото),
     * а на диск записує у фоні — тож натискання в налаштуваннях не чекає на запис.
     */
    static void save(Context ctx, int id, String json, String kind) {
        prefs(ctx).edit().putString("widget_" + id, fromJson(json, kind).toJson()).apply();
    }

    static void delete(Context ctx, int[] ids) {
        SharedPreferences.Editor e = prefs(ctx).edit();
        for (int id : ids) e.remove("widget_" + id);
        e.apply();
    }
}
