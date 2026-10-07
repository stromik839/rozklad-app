package ua.school.rozklad;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.widget.RemoteViews;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Оновлення віджетів: малює картинки, віддає їх на робочий стіл і ставить будильник на наступний дзвоник.
 * Уся робота йде у фонових потоках, тож застосунок і робочий стіл не гальмують.
 */
final class WidgetUpdater {

    static final Class<?>[] PROVIDERS = {BellsWidget.class, LessonsWidget.class, BothWidget.class};
    static final String[] KINDS = {"bells", "lessons", "both"};

    /** Віджети малюються по черзі в одному потоці з низьким пріоритетом — не заважають екрану. */
    private static final ExecutorService WORKER =
            Executors.newSingleThreadExecutor(r -> thread(r, "rozklad-widgets", Process.THREAD_PRIORITY_BACKGROUND));
    /** Прев’ю в налаштуваннях — окремий потік, щоб не чекати, поки оновляться всі віджети. */
    private static final ExecutorService PREVIEW =
            Executors.newSingleThreadExecutor(r -> thread(r, "rozklad-preview", Process.THREAD_PRIORITY_DEFAULT));

    private static Thread thread(Runnable r, String name, int priority) {
        Thread t = new Thread(() -> {
            Process.setThreadPriority(priority);
            r.run();
        }, name);
        t.setDaemon(true);
        return t;
    }

    /** Що зараз намальовано на кожному віджеті: якщо нове те саме — не перемальовуємо. */
    private static final Map<Integer, String> DRAWN = new ConcurrentHashMap<>();
    private static final AtomicBoolean ALL_QUEUED = new AtomicBoolean();
    private static final Set<Integer> QUEUED = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());

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

    /** Чи це справді наш віджет на робочому столі (а не випадковий чи чужий номер). */
    static boolean owns(Context ctx, int id) {
        return Arrays.binarySearch(allIds(ctx), id) >= 0;
    }

    /** Що показує віджет за замовчуванням — залежить від того, який із трьох віджетів додали. */
    static String kindOf(Context ctx, int id) {
        AppWidgetProviderInfo info = AppWidgetManager.getInstance(ctx).getAppWidgetInfo(id);
        String name = info == null || info.provider == null ? "" : info.provider.getClassName();
        if (name.endsWith("BellsWidget")) return "bells";
        if (name.endsWith("LessonsWidget")) return "lessons";
        return "both";
    }

    /* ---------------- запуск у фоні ---------------- */

    /** Виконати у фоновому потоці віджетів; done (з goAsync) завершується, коли робота скінчиться. */
    static void runInBackground(Runnable task, BroadcastReceiver.PendingResult done) {
        WORKER.execute(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                // помилка в одному завданні не повинна зупиняти наступні
            } finally {
                if (done != null) done.finish();
            }
        });
    }

    /** Оновити всі віджети у фоні; кілька запитів поспіль зливаються в одне оновлення. */
    static void requestAll(Context ctx) {
        final Context app = ctx.getApplicationContext();
        if (!ALL_QUEUED.compareAndSet(false, true)) return;
        WORKER.execute(() -> {
            ALL_QUEUED.set(false);   // зміни, що прийдуть під час малювання, запланують ще одне
            try {
                updateAll(app);
            } catch (Throwable t) {
                // наступний запит спробує ще раз
            }
        });
    }

    /** Перемалювати один віджет після зміни його налаштувань і прибрати фото, яких більше ніхто не використовує. */
    static void requestOne(Context ctx, final int id) {
        final Context app = ctx.getApplicationContext();
        if (!QUEUED.add(id)) return;
        WORKER.execute(() -> {
            QUEUED.remove(id);
            try {
                update(app, new int[] {id});
                Store.cleanupWidgetPhotos(app);
            } catch (Throwable t) {
                // наступний запит спробує ще раз
            }
        });
    }

    /** Віджети видалили — забуваємо, що на них було намальовано. */
    static void forget(int[] ids) {
        for (int id : ids) DRAWN.remove(id);
    }

    /* ---------------- оновлення ---------------- */

    static void updateAll(Context ctx) {
        update(ctx, allIds(ctx));
        scheduleNext(ctx);
    }

    static void update(Context ctx, int[] ids) {
        int[] mine = allIds(ctx);
        for (int id : ids) {
            if (Arrays.binarySearch(mine, id) < 0) continue;   // не наш або вже видалений віджет
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
        String cls = currentClass(ctx);
        Calendar now = Calendar.getInstance();
        String sig = signature(ctx, cfg, cls, s, now);
        if (sig.equals(DRAWN.get(id))) return;   // на віджеті вже саме це

        Bitmap photo = "photo".equals(cfg.bg) ? loadPhoto(ctx, cfg, s) : null;
        Bitmap day, night;
        if (two) {
            // Обидва варіанти: робочий стіл сам покаже потрібний, коли телефон перемкне тему.
            day = render(ctx, cfg, cls, false, s, photo, now);
            night = render(ctx, cfg, cls, true, s, photo, now);
        } else {
            day = night = render(ctx, cfg, cls, "dark".equals(cfg.theme), s, photo, now);
        }
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget);
        rv.setImageViewBitmap(R.id.img_day, day);
        rv.setImageViewBitmap(R.id.img_night, night);
        rv.setContentDescription(R.id.widget_root, ctx.getString(R.string.app_name));
        rv.setOnClickPendingIntent(R.id.widget_root, openApp(ctx));
        m.updateAppWidget(id, rv);
        DRAWN.put(id, sig);
    }

    /** Усе, від чого залежить картинка віджета: налаштування, розмір, клас, зміст таблиці, фото. */
    private static String signature(Context ctx, WidgetConfig cfg, String cls, Size s, Calendar now) {
        StringBuilder b = new StringBuilder(cfg.toJson())
                .append('|').append(s.w).append('x').append(s.h).append('@').append(s.dp)
                .append('|').append(cls);
        try {
            b.append('|').append(WidgetRenderer.signature(ScheduleData.get(ctx.getAssets()), cfg, cls, now));
        } catch (Exception e) {
            b.append("|error");
        }
        if ("photo".equals(cfg.bg)) {
            File f = photoFile(ctx, cfg);
            b.append('|').append(f == null ? "-" : f.getName() + ":" + f.lastModified() + ":" + f.length());
        }
        return b.toString();
    }

    static String currentClass(Context ctx) {
        String cls = ctx.getSharedPreferences(Store.PREFS, Context.MODE_PRIVATE).getString("cls", null);
        return cls == null || cls.isEmpty() ? null : cls;
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

    static Bitmap render(Context ctx, WidgetConfig cfg, String cls, boolean dark, Size s, Bitmap photo, Calendar now) {
        Typeface tf = typeface(ctx);
        try {
            ScheduleData d = ScheduleData.get(ctx.getAssets());
            return WidgetRenderer.render(d, cfg, cls, dark, tf, photo, s.w, s.h, s.dp, now);
        } catch (Exception e) {
            return WidgetRenderer.message("Не вдалося прочитати розклад", dark, tf, s.w, s.h, s.dp);
        }
    }

    private static synchronized Typeface typeface(Context ctx) {
        if (typeface == null) {
            try {
                typeface = Typeface.createFromAsset(ctx.getAssets(), "fonts/RozkladSerif-Bold.ttf");
            } catch (RuntimeException e) {
                typeface = Typeface.DEFAULT_BOLD;
            }
        }
        return typeface;
    }

    /* ---------------- фото для фону ---------------- */

    /** Своє фото віджета, а якщо його немає — шпалери застосунку. */
    private static File photoFile(Context ctx, WidgetConfig cfg) {
        if (!"app".equals(cfg.photo)) {
            File own = Store.bigFile(ctx, "wph_" + cfg.photo);
            if (own.isFile()) return own;
        }
        File app = Store.bigFile(ctx, "wallpaper");
        return app.isFile() ? app : null;
    }

    /** Кілька останніх розкодованих фото: не читати й не розкодовувати те саме фото щоразу. */
    private static final Map<String, Bitmap> PHOTOS = new LinkedHashMap<String, Bitmap>(4, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > 2;
        }
    };

    /** Фото, зменшене під розмір віджета: для чіткого фону — не менше за віджет, для розмитого — половина. */
    private static Bitmap loadPhoto(Context ctx, WidgetConfig cfg, Size s) {
        File f = photoFile(ctx, cfg);
        if (f == null) return null;
        int div = cfg.blur > 0 ? 2 : 1;
        int tw = Math.max(1, s.w / div), th = Math.max(1, s.h / div);
        String key = f.getPath() + ":" + f.lastModified() + ":" + f.length() + ":" + tw + "x" + th;
        synchronized (PHOTOS) {
            Bitmap hit = PHOTOS.get(key);
            if (hit != null) return hit;
        }
        String data = Store.readFile(f);
        int comma = data.indexOf(',');
        if (comma < 0) return null;
        byte[] bytes = Base64.decode(data.substring(comma + 1), Base64.DEFAULT);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= tw && bounds.outHeight / (sample * 2) >= th) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        if (b != null) {
            synchronized (PHOTOS) {
                PHOTOS.put(key, b);
            }
        }
        return b;
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

    /**
     * Найближчий початок чи кінець уроку, або початок наступної доби.
     * У вихідні й канікули підсвітка за день не змінюється — тоді одразу до півночі.
     */
    static long nextBoundary(Context ctx, long nowMs) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(nowMs);
        int now = c.get(Calendar.HOUR_OF_DAY) * 3600 + c.get(Calendar.MINUTE) * 60 + c.get(Calendar.SECOND);
        int best = Integer.MAX_VALUE;
        try {
            ScheduleData d = ScheduleData.get(ctx.getAssets());
            if (d.dayInfo(c).school) {
                for (int[] b : d.bells) {
                    for (int edge : new int[] {b[0] * 60, b[1] * 60}) if (edge > now && edge < best) best = edge;
                }
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

    /** Кому сказати, що прев’ю готове (сторінці застосунку). */
    interface PreviewReady {
        void ready(int id);
    }

    private static final Object PV_LOCK = new Object();
    private static int pvId;
    private static String pvJson;           // запит, що чекає; null — немає
    private static boolean pvDark;
    private static PreviewReady pvCallback;
    private static boolean pvBusy;
    private static volatile String pvResult = "";

    /**
     * Намалювати прев’ю у фоні. Поки малюється одне, нові запити не стають у чергу —
     * береться лише найсвіжіший (повзунок тягнуть — проміжні значення пропускаються).
     */
    static void requestPreview(Context ctx, int id, String json, boolean systemDark, PreviewReady callback) {
        final Context app = ctx.getApplicationContext();
        synchronized (PV_LOCK) {
            pvId = id;
            pvJson = json;
            pvDark = systemDark;
            pvCallback = callback;
            if (pvBusy) return;
            pvBusy = true;
        }
        PREVIEW.execute(() -> {
            while (true) {
                int id1;
                String json1;
                boolean dark1;
                PreviewReady cb1;
                synchronized (PV_LOCK) {
                    if (pvJson == null) {
                        pvBusy = false;
                        return;
                    }
                    id1 = pvId;
                    json1 = pvJson;
                    dark1 = pvDark;
                    cb1 = pvCallback;
                    pvJson = null;
                }
                pvResult = previewDataUrl(app, id1, json1, dark1);
                try {
                    cb1.ready(id1);
                } catch (Throwable t) {
                    // сторінки вже немає — не страшно
                }
            }
        });
    }

    /** Останнє готове прев’ю (картинка data:image/png). */
    static String takePreview() {
        return pvResult;
    }

    /** Прев’ю віджета з ще не збереженими налаштуваннями — тим самим кодом, що й сам віджет. */
    static String previewDataUrl(Context ctx, int id, String json, boolean systemDark) {
        try {
            if (!owns(ctx, id)) return "";
            AppWidgetManager m = AppWidgetManager.getInstance(ctx);
            WidgetConfig cfg = WidgetConfig.fromJson(json, kindOf(ctx, id));
            Size s = size(ctx, m, id, 350_000);
            boolean dark = "dark".equals(cfg.theme) || ("system".equals(cfg.theme) && systemDark);
            Bitmap photo = "photo".equals(cfg.bg) ? loadPhoto(ctx, cfg, s) : null;
            Bitmap b = render(ctx, cfg, currentClass(ctx), dark, s, photo, Calendar.getInstance());
            ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
            return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable t) {
            return "";
        }
    }
}
