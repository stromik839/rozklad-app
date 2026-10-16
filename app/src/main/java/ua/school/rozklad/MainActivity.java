package ua.school.rozklad;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Показує розклад з assets/index.html: передає сторінці системну тему і відступи
 * під системні смуги, зберігає налаштування й розклад, відкриває галерею для шпалер,
 * приймає розклад за посиланням чи файлом, ділиться ним і дає сторінці керувати віджетами на робочому столі.
 * Фото вибирається через системний вибір фото, тож доступу до файлів застосунок не просить.
 */
public class MainActivity extends Activity {

    private static final String PAGE = "file:///android_asset/index.html";
    private static final int REQ_PICK_FILE = 7;
    /** Більших файлів розкладу не буває (найбільше важать шпалери). */
    private static final int MAX_FILE = 25 * 1024 * 1024;

    private FrameLayout root;
    private WebView web;
    private SharedPreferences prefs;
    private ClipboardManager clipboard;
    private volatile boolean systemDark;
    private volatile String insetsCss = "0,0,0,0";
    /** Код розкладу з посилання, яким відкрили застосунок; сторінка забирає його сама. */
    private volatile String pendingCode;
    /** Вміст файлу «Розкладу», яким відкрили застосунок (з Telegram, файлового менеджера). */
    private volatile String pendingFile;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(Store.PREFS, MODE_PRIVATE);
        systemDark = isNight(getResources().getConfiguration());
        // Хто користувався застосунком до обміну розкладами, отримує вбудований розклад як свій.
        ScheduleStore.ensureMigrated(this);
        // Буфер обміну беремо тут, у головному потоці: на старих Android його не можна створити з іншого.
        clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (savedInstanceState == null) {
            pendingCode = incomingCode(getIntent());
            readIncomingFile(getIntent());
        }

        root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        setupEdgeToEdge();
        applyBars(initialBackground());

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        // Сторінці не потрібні файли телефону (assets відкриваються й так) — закриваємо явно.
        s.setAllowFileAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // Посилання на автора відкриваємо в Telegram (або в браузері, якщо Telegram немає).
                if (url.startsWith("https://t.me/")) {
                    openExternal(url);
                    return true;
                }
                // Сторінка локальна; будь-які інші адреси не відкриваємо.
                return !url.startsWith("file:///android_asset/");
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                return openPicker(callback, wantsImage(params));
            }
        });
        web.loadUrl(PAGE);
    }

    private void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException ignored) {
            // нема чим відкрити — нічого не робимо
        }
    }

    /** Код розкладу з посилання (rozklad://import?c=… або сторінка розкладу на GitHub), або null. */
    private static String incomingCode(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return null;
        String data = intent.getDataString();
        if (data == null || !(data.startsWith("rozklad:") || data.startsWith("https:"))) return null;
        return ShareCode.extract(data);
    }

    /** Файл, яким відкрили застосунок («Відкрити за допомогою» чи «Поділитися»): читаємо у фоні й віддаємо сторінці. */
    private void readIncomingFile(Intent intent) {
        if (intent == null) return;
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri d = intent.getData();
            if (d != null && ("content".equals(d.getScheme()) || "file".equals(d.getScheme()))) uri = d;
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Parcelable p = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (p instanceof Uri) uri = (Uri) p;
        }
        if (uri == null) return;
        final Uri u = uri;
        final Context app = getApplicationContext();
        new Thread(() -> {
            final String text = readText(app, u);
            if (text == null || text.isEmpty()) return;
            runOnUiThread(() -> {
                pendingFile = text;
                if (web != null) web.evaluateJavascript("window.onIncomingCode && window.onIncomingCode()", null);
            });
        }, "rozklad-file").start();
    }

    private static String readText(Context ctx, Uri u) {
        try (InputStream in = ctx.getContentResolver().openInputStream(u)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_FILE) return null;
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;   // немає доступу чи файл зник — нічого не робимо
        }
    }

    /** Застосунок уже відкритий, а його відкрили ще одним посиланням чи файлом — передаємо сторінці. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readIncomingFile(intent);
        String code = incomingCode(intent);
        if (code == null) return;
        pendingCode = code;
        if (web != null) web.evaluateJavascript("window.onIncomingCode && window.onIncomingCode()", null);
    }

    private static boolean isNight(Configuration c) {
        return (c.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Колір фону з налаштувань сторінки — щоб при запуску не блимало. */
    private int initialBackground() {
        String theme = prefs.getString("theme", "system");
        boolean dark = "dark".equals(theme) || (!"light".equals(theme) && systemDark);
        String saved = prefs.getString(dark ? "bg_dark" : "bg_light", null);
        if (saved != null) {
            try {
                return Color.parseColor(saved);
            } catch (IllegalArgumentException ignored) {
                // некоректний колір — беремо стандартний
            }
        }
        return dark ? Color.BLACK : Color.WHITE;
    }

    /**
     * Сторінка малюється на весь екран (шпалери заходять під рядок стану),
     * а розміри системних смуг передаються їй, щоб текст не ховався під ними.
     */
    private void setupEdgeToEdge() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        } else {
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            w.setStatusBarContrastEnforced(false);
            w.setNavigationBarContrastEnforced(false);
        }
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                // Клавіатура теж: сторінка відсуне поле введення, щоб воно не ховалось під нею.
                Insets i = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                l = i.left;
                t = i.top;
                r = i.right;
                b = i.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft();
                t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight();
                b = insets.getSystemWindowInsetBottom();
            }
            float d = getResources().getDisplayMetrics().density;
            insetsCss = Math.round(t / d) + "," + Math.round(r / d) + ","
                    + Math.round(b / d) + "," + Math.round(l / d);
            if (web != null) {
                web.evaluateJavascript("window.onInsets && window.onInsets('" + insetsCss + "')", null);
            }
            return Build.VERSION.SDK_INT >= 30 ? WindowInsets.CONSUMED : insets.consumeSystemWindowInsets();
        });
    }

    private int barsColor;
    private boolean barsSet;

    /** Фон під сторінкою і колір іконок у рядку стану під вибраний колір фону. */
    private void applyBars(int bg) {
        if (root == null) return;
        if (barsSet && barsColor == bg) return;   // той самий колір — вікно не перемальовуємо
        barsSet = true;
        barsColor = bg;
        boolean light = isLight(bg);
        root.setBackgroundColor(bg);
        if (web != null) web.setBackgroundColor(bg);
        Window w = getWindow();
        w.getDecorView().setBackgroundColor(bg);
        if (Build.VERSION.SDK_INT < 35) {
            // На старих Android темних іконок немає — тоді на світлому фоні смуги лишаються чорними.
            w.setStatusBarColor(!light || Build.VERSION.SDK_INT >= 23 ? Color.TRANSPARENT : Color.BLACK);
            w.setNavigationBarColor(!light || Build.VERSION.SDK_INT >= 26 ? Color.TRANSPARENT : Color.BLACK);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(light ? mask : 0, mask);
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            View decor = w.getDecorView();
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            int cur = decor.getSystemUiVisibility();
            decor.setSystemUiVisibility(light ? (cur | flags) : (cur & ~flags));
        }
    }

    private static boolean isLight(int c) {
        return (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000 > 150;
    }

    /** Сторінка просить фото (шпалери) чи файл (розклад від інших)? */
    private static boolean wantsImage(WebChromeClient.FileChooserParams params) {
        String[] types = params == null ? null : params.getAcceptTypes();
        if (types == null) return true;
        boolean any = false;
        for (String t : types) {
            if (t == null || t.trim().isEmpty()) continue;
            any = true;
            if (t.trim().startsWith("image/")) return true;
        }
        return !any;
    }

    /**
     * Фото: системний вибір фото (Android 13+), інакше — галерея/файли.
     * Файл розкладу: системний вибір файлів (будь-який тип — JSON часто позначено як «невідомий»).
     */
    private boolean openPicker(ValueCallback<Uri[]> callback, boolean image) {
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        fileCallback = callback;
        Intent get = new Intent(Intent.ACTION_GET_CONTENT);
        get.addCategory(Intent.CATEGORY_OPENABLE);
        get.setType(image ? "image/*" : "*/*");
        Intent[] tries = image && Build.VERSION.SDK_INT >= 33
                ? new Intent[] { new Intent(MediaStore.ACTION_PICK_IMAGES), get }
                : new Intent[] { get };
        for (Intent i : tries) {
            try {
                startActivityForResult(i, REQ_PICK_FILE);
                return true;
            } catch (ActivityNotFoundException ignored) {
                // пробуємо наступний спосіб
            }
        }
        fileCallback = null;
        return false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_FILE || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getData() != null) {
                result = new Uri[] { data.getData() };
            } else if (data.getClipData() != null && data.getClipData().getItemCount() > 0) {
                result = new Uri[] { data.getClipData().getItemAt(0).getUri() };
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        boolean d = isNight(newConfig);
        if (d != systemDark) {
            systemDark = d;
            if (web != null) {
                web.evaluateJavascript("window.onSystemThemeChanged && window.onSystemThemeChanged()", null);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        systemDark = isNight(getResources().getConfiguration());
        if (web != null) {
            web.onResume();
            web.resumeTimers();
            web.evaluateJavascript("window.onAppResume && window.onAppResume()", null);
        }
    }

    @Override
    protected void onPause() {
        // Віджети тут не перемальовуємо: кожна зміна (клас, шпалери, налаштування віджета)
        // і так оновлює їх одразу, а перемальовування на виході гальмувало перехід на робочий стіл.
        if (web != null) {
            web.onPause();
            web.pauseTimers();
        }
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (web == null) {
            super.onBackPressed();
            return;
        }
        // Якщо відкриті налаштування — «Назад» їх закриває, інакше виходимо.
        web.evaluateJavascript("(window.onAndroidBack && window.onAndroidBack()) ? 1 : 0", value -> {
            if (!"1".equals(value)) MainActivity.super.onBackPressed();
        });
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            root.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /** Доступно зі сторінки як window.Android. */
    public final class Bridge {

        @JavascriptInterface
        public boolean isSystemDark() {
            return systemDark;
        }

        @JavascriptInterface
        public String getInsets() {
            return insetsCss;
        }

        @JavascriptInterface
        public String getPref(String key) {
            String v = prefs.getString(key, null);
            return v == null ? "" : v;
        }

        @JavascriptInterface
        public void setPref(String key, String value) {
            prefs.edit().putString(key, value).apply();
        }

        @JavascriptInterface
        public String getBig(String key) {
            return Store.readBig(MainActivity.this, key);
        }

        @JavascriptInterface
        public boolean setBig(String key, String value) {
            // Розклад пишеться лише через saveSchedule: там перевірка і оновлення віджетів.
            if (ScheduleStore.KEY.equals(key)) return false;
            return Store.writeBig(MainActivity.this, key, value);
        }

        /* ---------- Розклад: збереження, коди й посилання, буфер обміну ---------- */

        @JavascriptInterface
        public String getSchedule() {
            return ScheduleStore.read(MainActivity.this);
        }

        /** "" — збережено (віджети оновляться самі), інакше — текст помилки. */
        @JavascriptInterface
        public String saveSchedule(String json) {
            return ScheduleStore.save(getApplicationContext(), json);
        }

        @JavascriptInterface
        public String encodeCode(String json) {
            try {
                return ShareCode.encode(json);
            } catch (RuntimeException e) {
                return "";
            }
        }

        /** JSON розкладу; якщо не вийшло — «!» і пояснення. */
        @JavascriptInterface
        public String decodeCode(String text) {
            try {
                return ShareCode.decode(text);
            } catch (IllegalArgumentException e) {
                return "!" + e.getMessage();
            } catch (RuntimeException e) {
                return "!" + ShareCode.DAMAGED;
            }
        }

        /** Код із посилання, яким відкрили застосунок (один раз), або "". */
        @JavascriptInterface
        public String takeIncomingCode() {
            String c = pendingCode;
            pendingCode = null;
            return c == null ? "" : c;
        }

        /** Вміст файлу, яким відкрили застосунок (один раз), або "". */
        @JavascriptInterface
        public String takeIncomingFile() {
            String f = pendingFile;
            pendingFile = null;
            return f == null ? "" : f;
        }

        /**
         * Надіслати файл (розклад, оформлення, шпалери) через системне «Поділитися».
         * "" — вікно «Поділитися» відкрито, інакше — що не так.
         */
        @JavascriptInterface
        public String shareFile(String name, String content) {
            if (content == null || content.isEmpty()) return "Порожній файл.";
            // Без символів, недозволених у назвах файлів, і без «..» (такий файл ShareProvider не віддасть).
            String safe = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]+", " ")
                    .replaceAll("\\.{2,}", ".").trim();
            if (!safe.toLowerCase(Locale.ROOT).endsWith(".json")) safe = "Розклад.json";
            if (safe.length() > 80) safe = "Розклад.json";
            File dir = ShareProvider.dir(MainActivity.this);
            if (!dir.isDirectory() && !dir.mkdirs()) return "Не вдалося підготувати файл.";
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) f.delete();   // попередні вже не потрібні
            File out = new File(dir, safe);
            try (FileOutputStream o = new FileOutputStream(out)) {
                o.write(content.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                return "Не вдалося підготувати файл.";
            }
            final Uri uri = ShareProvider.uriFor(safe);
            runOnUiThread(() -> {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("application/json");
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.setClipData(ClipData.newRawUri("", uri));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(Intent.createChooser(send, "Надіслати файл"));
                } catch (ActivityNotFoundException ignored) {
                    // нема чим надіслати
                }
            });
            return "";
        }

        @JavascriptInterface
        public boolean copyText(String text) {
            try {
                clipboard.setPrimaryClip(ClipData.newPlainText("Розклад", text));
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }

        @JavascriptInterface
        public String readClipboard() {
            try {
                ClipData c = clipboard.getPrimaryClip();
                if (c == null || c.getItemCount() == 0) return "";
                CharSequence t = c.getItemAt(0).coerceToText(MainActivity.this);
                return t == null ? "" : t.toString();
            } catch (RuntimeException e) {
                return "";
            }
        }

        /** Системне «Поділитися»: Telegram, Viber, пошта тощо. */
        @JavascriptInterface
        public void shareText(final String text) {
            runOnUiThread(() -> {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, text);
                try {
                    startActivity(Intent.createChooser(send, "Поділитися розкладом"));
                } catch (ActivityNotFoundException ignored) {
                    // нема чим поділитися — сторінка запропонує скопіювати
                }
            });
        }

        /* ---------- Віджети ---------- */

        @JavascriptInterface
        public String getWidgets() {
            return WidgetUpdater.listJson(MainActivity.this);
        }

        /** Зберігає одразу, а перемальовує віджет у фоні — сторінка не чекає. */
        @JavascriptInterface
        public void setWidgetConfig(int id, String json) {
            Context app = getApplicationContext();
            if (!WidgetUpdater.owns(app, id)) return;
            WidgetConfig.save(app, id, json, WidgetUpdater.kindOf(app, id));
            WidgetUpdater.requestOne(app, id);
        }

        /** Прев’ю малюється у фоні; коли готове — сторінка отримує onWidgetPreviewReady(id). */
        @JavascriptInterface
        public void requestWidgetPreview(int id, String json) {
            WidgetUpdater.requestPreview(getApplicationContext(), id, json, systemDark, readyId -> runOnUiThread(() -> {
                if (web != null) {
                    web.evaluateJavascript("window.onWidgetPreviewReady && window.onWidgetPreviewReady(" + readyId + ")", null);
                }
            }));
        }

        @JavascriptInterface
        public String takeWidgetPreview() {
            return WidgetUpdater.takePreview();
        }

        /** Попросити робочий стіл додати віджет (Android 8+, якщо робочий стіл це вміє). */
        @JavascriptInterface
        public boolean pinWidget(String kind) {
            if (Build.VERSION.SDK_INT < 26) return false;
            AppWidgetManager m = AppWidgetManager.getInstance(MainActivity.this);
            if (!m.isRequestPinAppWidgetSupported()) return false;
            return m.requestPinAppWidget(new ComponentName(MainActivity.this, WidgetUpdater.providerFor(kind)), null, null);
        }

        @JavascriptInterface
        public void refreshWidgets() {
            WidgetUpdater.requestAll(MainActivity.this);
        }

        /** Версія застосунку для налаштувань (номер збірки на GitHub). */
        @JavascriptInterface
        public String getVersion() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void setBars(final String color) {
            runOnUiThread(() -> {
                try {
                    applyBars(Color.parseColor(color));
                } catch (IllegalArgumentException ignored) {
                    // некоректний колір — нічого не міняємо
                }
            });
        }
    }
}
