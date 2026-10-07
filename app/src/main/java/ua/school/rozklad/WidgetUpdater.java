package ua.school.rozklad;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.widget.RemoteViews;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Calendar;

/** Оновлення віджетів: малює картинки, віддає їх на робочий стіл і ставить будильник на наступний дзвоник. */
final class WidgetUpdater {

    static final Class<?>[] PROVIDERS = {BellsWidget.class, LessonsWidget.class, BothWidget.class};
    static final String[] KINDS = {"bells", "lessons", "both"};

    private static Typeface typeface;

    private WidgetUpdater() {}

    static Class<?> providerFor(String kind) {
        for (int i = 0; i < KINDS.length; i++) if (KINDS[i].equals(kind)) return PROVIDERS[i];
        return BothWidget.class;
    }

    static int[] allIds(Context ctx) {
        AppWidgetManager m = AppWidgetManager.getInstance(ctx);
        int[] all = new int[0];
        for (Class<?> p : PROVIDERS) {
            int[] ids = m.getAppWidgetIds(new ComponentName(ctx, p));
            if (ids == null || ids.length == 0) continue;
            int[] joined = Arrays.copyOf(all, all.length + ids.length);
            System.arraycopy(ids, 0, joined, all.length, ids.length);
            all = joined;
        }
        Arrays.sort(all);
        return all;
    }

    /** Що показує віджет за замовчуванням — залежить від того, який із трьох віджетів додали. */
    static String kindOf(Context ctx, int id) {
        AppWidgetProviderInfo info = AppWidgetManager.getInstance(ctx).getAppWidgetInfo(id);
        String name = info == null || info.provider == null ? "" : info.provider.getClassName();
        if (name.endsWith("BellsWidget")) return "bells";
        if (name.endsWith("LessonsWidget")) return "lessons";
        return "both";
    }

    static void updateAll(Context ctx) {
        update(ctx, allIds(ctx));
        scheduleNext(ctx);
    }

    static void update(Context ctx, int[] ids) {
        for (int id : ids) {
            try {
                updateOne(ctx, id);
            } catch (Throwable t) {
                // один зіпсований віджет не повинен зупиняти решту
            }
        }
    }

    static void updateOne(Context ctx, int id) {
        AppWidgetManager m = AppWidgetManager.getInstance(ctx);
        WidgetConfig cfg = WidgetConfig.load(ctx, id, kindOf(ctx, id));
        boolean two = "system".equals(cfg.theme);
        Size s = size(ctx, m, id, maxPixels(ctx, two));
        Bitmap day, night;
        if (two) {
            // Обидва варіанти: робочий стіл сам покаже потрібний, коли телефон перемкне тему.
            day = render(ctx, cfg, false, s);
            night = render(ctx, cfg, true, s);
        } else {
            day = night = render(ctx, cfg, "dark".equals(cfg.theme), s);
        }
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget);
        rv.setImageViewBitmap(R.id.img_day, day);
        rv.setImageViewBitmap(R.id.img_night, night);
        rv.setContentDescription(R.id.widget_root, ctx.getString(R.string.app_name));
        rv.setOnClickPendingIntent(R.id.widget_root, openApp(ctx));
        m.updateAppWidget(id, rv);
    }

    /**
     * Робочий стіл приймає картинки віджета лише до 1,5 розміру екрана (у байтах).
     * Дві картинки (світла й темна) мусять разом уміститись у цю межу.
     */
    private static int maxPixels(Context ctx, boolean twoBitmaps) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        long screen = (long) dm.widthPixels * dm.heightPixels;
        long limit = (long) (screen * (twoBitmaps ? 0.6 : 1.2));
        return (int) Math.max(200_000, Math.min(1_000_000, limit));
    }

    static final class Size {
        int w, h, wDp, hDp;
        float dp;
    }

    /** Розмір віджета в пікселях (з обмеженням пам’яті для картинок на робочому столі). */
    static Size size(Context ctx, AppWidgetManager m, int id, int maxPixels) {
        Size s = new Size();
        Bundle o = id > 0 ? m.getAppWidgetOptions(id) : null;
        boolean land = ctx.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        int wDp = o == null ? 0 : o.getInt(land ? AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
                : AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        int hDp = o == null ? 0 : o.getInt(land ? AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
                : AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        s.wDp = wDp > 0 ? wDp : 250;
        s.hDp = hDp > 0 ? hDp : 250;
        float d = ctx.getResources().getDisplayMetrics().density;
        double px = (double) s.wDp * s.hDp * d * d;
        if (px > maxPixels) d = (float) (d * Math.sqrt(maxPixels / px));
        s.dp = d;
        s.w = Math.max(1, Math.round(s.wDp * d));
        s.h = Math.max(1, Math.round(s.hDp * d));
        return s;
    }

    static boolean isDark(Context ctx, WidgetConfig cfg) {
        if ("dark".equals(cfg.theme)) return true;
        if ("light".equals(cfg.theme)) return false;
        int night = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }

    static Bitmap render(Context ctx, WidgetConfig cfg, boolean dark, Size s) {
        Typeface tf = typeface(ctx);
        try {
            ScheduleData d = ScheduleData.get(ctx.getAssets());
            String cls = ctx.getSharedPreferences(Store.PREFS, Context.MODE_PRIVATE).getString("cls", null);
            if (cls != null && cls.isEmpty()) cls = null;
            Bitmap photo = "photo".equals(cfg.bg) ? loadPhoto(ctx, cfg, s) : null;
            return WidgetRenderer.render(d, cfg, cls, dark, tf, photo, s.w, s.h, s.dp, Calendar.getInstance());
        } catch (Exception e) {
            return WidgetRenderer.message("Не вдалося прочитати розклад", dark, tf, s.w, s.h, s.dp);
        }
    }

    private static synchronized Typeface typeface(Context ctx) {
        if (typeface == null) {
            try {
                typeface = Typeface.createFromAsset(ctx.getAssets(), "fonts/LiberationSerif-Bold.ttf");
            } catch (RuntimeException e) {
                typeface = Typeface.DEFAULT_BOLD;
            }
        }
        return typeface;
    }

    /** Фото для фону: своє фото віджета або шпалери застосунку, зменшене під розмір віджета. */
    private static Bitmap loadPhoto(Context ctx, WidgetConfig cfg, Size s) {
        String data = Store.readBig(ctx, "app".equals(cfg.photo) ? "wallpaper" : "wph_" + cfg.photo);
        if (data.isEmpty() && !"app".equals(cfg.photo)) data = Store.readBig(ctx, "wallpaper");
        int comma = data.indexOf(',');
        if (comma < 0) return null;
        byte[] bytes = Base64.decode(data.substring(comma + 1), Base64.DEFAULT);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int sample = 1;
        int tw = Math.max(1, s.w / 2), th = Math.max(1, s.h / 2);
        while (bounds.outWidth / (sample * 2) >= tw && bounds.outHeight / (sample * 2) >= th) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
    }

    private static PendingIntent openApp(Context ctx) {
        Intent i = new Intent(ctx, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | immutable());
    }

    private static int immutable() {
        return Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
    }

    /* ---------------- будильник на наступний дзвоник ---------------- */

    static void scheduleNext(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(ctx, WidgetTickReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | immutable());
        if (allIds(ctx).length == 0) {
            am.cancel(pi);
            return;
        }
        long at = nextBoundary(ctx, System.currentTimeMillis());
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC, at, pi);
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC, at, pi);
            } else {
                am.setExact(AlarmManager.RTC, at, pi);
            }
        } catch (RuntimeException e) {
            am.set(AlarmManager.RTC, at, pi);
        }
    }

    /** Найближчий початок чи кінець уроку, або початок наступної доби. */
    static long nextBoundary(Context ctx, long nowMs) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(nowMs);
        int now = c.get(Calendar.HOUR_OF_DAY) * 3600 + c.get(Calendar.MINUTE) * 60 + c.get(Calendar.SECOND);
        int best = Integer.MAX_VALUE;
        try {
            for (int[] b : ScheduleData.get(ctx.getAssets()).bells) {
                for (int edge : new int[] {b[0] * 60, b[1] * 60}) if (edge > now && edge < best) best = edge;
            }
        } catch (Exception e) {
            // без даних — оновимось опівночі
        }
        Calendar n = (Calendar) c.clone();
        n.set(Calendar.MILLISECOND, 0);
        if (best == Integer.MAX_VALUE) {
            n.add(Calendar.DAY_OF_MONTH, 1);
            n.set(Calendar.HOUR_OF_DAY, 0);
            n.set(Calendar.MINUTE, 0);
            n.set(Calendar.SECOND, 5);
        } else {
            n.set(Calendar.HOUR_OF_DAY, best / 3600);
            n.set(Calendar.MINUTE, (best % 3600) / 60);
            n.set(Calendar.SECOND, 1);
        }
        return n.getTimeInMillis();
    }

    /* ---------------- для налаштувань у застосунку ---------------- */

    static String listJson(Context ctx) {
        AppWidgetManager m = AppWidgetManager.getInstance(ctx);
        StringBuilder b = new StringBuilder("[");
        for (int id : allIds(ctx)) {
            WidgetConfig cfg = WidgetConfig.load(ctx, id, kindOf(ctx, id));
            Size s = size(ctx, m, id, 1_000_000);
            if (b.length() > 1) b.append(',');
            b.append("{\"id\":").append(id).append(",\"w\":").append(s.wDp).append(",\"h\":").append(s.hDp)
                    .append(",\"cfg\":").append(cfg.toJson()).append('}');
        }
        return b.append(']').toString();
    }

    /** Прев’ю віджета з ще не збереженими налаштуваннями — для екрана налаштувань. */
    static String previewDataUrl(Context ctx, int id, String json) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(ctx);
            WidgetConfig cfg = WidgetConfig.fromJson(json, kindOf(ctx, id));
            Size s = size(ctx, m, id, 350_000);
            Bitmap b = render(ctx, cfg, isDark(ctx, cfg), s);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
            return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable t) {
            return "";
        }
    }
}
