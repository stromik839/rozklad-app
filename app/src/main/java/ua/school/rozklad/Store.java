package ua.school.rozklad;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Спільне сховище застосунку і віджетів: налаштування і великі дані (фото) окремими файлами. */
final class Store {

    static final String PREFS = "rozklad";
    private static final String WIDGET_PHOTO = "big_wph_";

    private Store() {}

    static File bigFile(Context ctx, String key) {
        return new File(ctx.getFilesDir(), "big_" + key.replaceAll("[^A-Za-z0-9_]", "_") + ".txt");
    }

    static String readBig(Context ctx, String key) {
        return readFile(bigFile(ctx, key));
    }

    static String readFile(File f) {
        if (!f.isFile()) return "";
        byte[] buf = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            return new String(buf, 0, off, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    static boolean writeBig(Context ctx, String key, String value) {
        File f = bigFile(ctx, key);
        if (value == null || value.isEmpty()) {
            return !f.exists() || f.delete();
        }
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            tmp.delete();
            return false;
        }
        return tmp.renameTo(f);
    }

    /** Видаляє фото віджетів, яких уже не використовує жоден віджет (замінені або з видалених віджетів). */
    static synchronized void cleanupWidgetPhotos(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> used = new HashSet<>();
        for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
            if (e.getKey().startsWith("widget_") && e.getValue() instanceof String) {
                used.add(WidgetConfig.fromJson((String) e.getValue(), "both").photo);
            }
        }
        File[] files = ctx.getFilesDir().listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File f : files) {
            String n = f.getName();
            if (!n.startsWith(WIDGET_PHOTO) || !n.endsWith(".txt")) continue;
            String token = n.substring(WIDGET_PHOTO.length(), n.length() - 4);
            // щойно збережене фото ще може чекати на свій віджет — не чіпаємо його хвилину
            if (!used.contains(token) && now - f.lastModified() > 60_000) f.delete();
        }
    }
}
