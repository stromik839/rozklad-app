package ua.school.rozklad;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Де лежить розклад телефону. Його отримують від інших (посилання, код, QR, файл) або складають у застосунку;
 * читають і сторінка застосунку, і віджети.
 * До версії з обміном розклад був вбудований (assets/data.js): хто вже ним користувався,
 * той після оновлення отримує його як свій — нічого налаштовувати заново не треба.
 */
final class ScheduleStore {

    static final String KEY = "schedule";
    /** Позначка вбудованого розкладу школи: розклад з тією ж позначкою вважається його новішою версією. */
    static final String BUILTIN_ID = "school";
    private static final String MIGRATED = "schedule_migrated";

    private ScheduleStore() {}

    static File file(Context ctx) {
        return Store.bigFile(ctx, KEY);
    }

    static String read(Context ctx) {
        ensureMigrated(ctx);
        return Store.readBig(ctx, KEY);
    }

    /** Перевіряє і зберігає розклад, потім оновлює віджети. "" — збережено, інакше — що не так. */
    static String save(Context ctx, String json) {
        if (json == null || json.isEmpty()) return "Порожній розклад.";
        try {
            ScheduleData.fromJson(json);
        } catch (RuntimeException e) {
            return "Розклад не збережено: " + e.getMessage();
        }
        synchronized (ScheduleStore.class) {
            if (!Store.writeBig(ctx, KEY, json)) return "Не вдалося записати розклад у пам’ять телефону.";
            prefs(ctx).edit().putString(MIGRATED, "1").apply();
        }
        ScheduleData.invalidate();
        WidgetUpdater.requestAll(ctx);
        return "";
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Store.PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Перенесення вбудованого розкладу. Лише для тих, хто вже користувався застосунком (у налаштуваннях є клас);
     * новий користувач отримає розклад від інших або складе сам.
     */
    static synchronized void ensureMigrated(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (p.getString(MIGRATED, null) != null) return;
        if (file(ctx).isFile()) {
            p.edit().putString(MIGRATED, "1").apply();
            return;
        }
        String cls = p.getString("cls", null);
        if (cls == null || cls.isEmpty()) return;
        try {
            String json = migratedJson(ScheduleData.readAll(ctx.getAssets().open("data.js")));
            if (Store.writeBig(ctx, KEY, json)) p.edit().putString(MIGRATED, "1").apply();
        } catch (Exception e) {
            // не вийшло — спробуємо наступного разу
        }
    }

    /** Розклад у форматі обміну з тексту data.js (без автора застосунку — він не частина розкладу). */
    static String migratedJson(String dataJs) {
        Map<String, Object> src = ScheduleData.dataJsObject(dataJs);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("v", 1);
        out.put("id", BUILTIN_ID);
        out.put("rev", 0);
        out.put("title", "");
        for (Map.Entry<String, Object> e : src.entrySet()) {
            String k = e.getKey();
            if (k.equals("author") || out.containsKey(k)) continue;
            out.put(k, e.getValue());
        }
        new ScheduleData(out);   // перевірка: віджети мусять його прочитати
        return Json.write(out);
    }
}
