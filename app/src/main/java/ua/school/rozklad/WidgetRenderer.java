package ua.school.rozklad;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Малює віджет у картинку: заголовок, рядок «що зараз» і таблицю в стилі листка
 * (жирний шрифт із засічками, рамки, зелена підсвітка «зараз» і жовта «далі»).
 */
final class WidgetRenderer {

    static final int NOW = 0xFF00C853;
    static final int NEXT = 0xFFFFD600;
    private static final String[] DAY_NAMES =
            {"неділя", "понеділок", "вівторок", "середа", "четвер", "п’ятниця", "субота"};
    private static final Locale UK = Locale.forLanguageTag("uk");

    private WidgetRenderer() {}

    /** Що показати: заголовок, стан і рядки таблиці. */
    private static final class Model {
        String title = "";
        String status = "";
        String[] head = new String[0];
        float[] weights = new float[0];
        boolean[] left = new boolean[0];
        final List<String[]> rows = new ArrayList<>();
        final List<Integer> rowColor = new ArrayList<>();
        int cellRow = -1, cellCol = -1, cellColor = 0;
    }

    static Bitmap render(ScheduleData d, WidgetConfig cfg, String cls, boolean dark, Typeface tf,
                         Bitmap photo, int w, int h, float dp, Calendar now) {
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        draw(c, model(d, cfg, cls, now), cfg, dark, tf, photo, w, h, dp);
        return bmp;
    }

    /** Запасна картинка з повідомленням, якщо дані не прочитались. */
    static Bitmap message(String text, boolean dark, Typeface tf, int w, int h, float dp) {
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(tf);
        p.setColor(dark ? Color.WHITE : Color.BLACK);
        p.setShadowLayer(2.5f * dp, 0, 0.6f * dp, dark ? 0xCC000000 : 0xCCFFFFFF);
        p.setTextSize(14 * dp);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(fit(p, text, w - 12 * dp), w / 2f, h / 2f, p);
        return bmp;
    }

    /* ---------------- зміст ---------------- */

    private static Model model(ScheduleData d, WidgetConfig cfg, String cls, Calendar now) {
        Model m = new Model();
        ScheduleData.DayInfo info = d.dayInfo(now);
        int t = now.get(Calendar.HOUR_OF_DAY) * 3600 + now.get(Calendar.MINUTE) * 60 + now.get(Calendar.SECOND);
        Calendar shownDay = now;
        Calendar next = info.school ? null : d.nextSchoolDay(now);
        if (next != null) shownDay = next;
        int wdToday = ScheduleData.weekday(now);
        int wdShown = ScheduleData.weekday(shownDay);
        String[] today = info.school ? d.lessons(cls, wdToday) : new String[0];
        String[] shown = d.lessons(cls, wdShown);

        ScheduleData.State st;
        if (!info.school) {
            st = new ScheduleData.State();
            st.type = "off";
        } else if (cls == null) {
            st = new ScheduleData.State();
            st.type = "noclass";
        } else {
            st = d.state(t, today);
        }
        boolean live = info.school;   // підсвічуємо лише сьогоднішні уроки

        if ("bells".equals(cfg.kind)) {
            m.title = "ДЗВІНКИ";
        } else {
            m.title = (cls == null ? "РОЗКЛАД" : cls.toUpperCase(UK) + " КЛАС") + ", "
                    + DAY_NAMES[wdShown].toUpperCase(UK);
        }
        m.status = status(d, st, info, next);

        int[] sp = d.span(shown);
        if ("bells".equals(cfg.kind)) {
            int rows = Math.max(7, Math.max(d.span(today)[1] + 1, sp[1] + 1));
            rows = Math.min(rows, d.bells.length);
            m.head = new String[] {"№", "Урок", "Перерва"};
            m.weights = new float[] {0.14f, 0.43f, 0.43f};
            m.left = new boolean[] {false, false, false};
            for (int i = 0; i < rows; i++) {
                String brk = i < rows - 1
                        ? ScheduleData.hm(d.bells[i][1]) + "–" + ScheduleData.hm(d.bells[i + 1][0]) : "—";
                m.rows.add(new String[] {String.valueOf(i + 1),
                        ScheduleData.hm(d.bells[i][0]) + "–" + ScheduleData.hm(d.bells[i][1]), brk});
                m.rowColor.add(live ? rowColor(st, i) : 0);
            }
            if (live && "break".equals(st.type)) {
                m.cellRow = st.after;
                m.cellCol = 2;
                m.cellColor = NOW;
            }
        } else {
            boolean both = "both".equals(cfg.kind);
            m.head = both ? new String[] {"№", "Час", "Предмет"} : new String[] {"№", "Предмет"};
            m.weights = both ? new float[] {0.12f, 0.36f, 0.52f} : new float[] {0.14f, 0.86f};
            m.left = both ? new boolean[] {false, false, true} : new boolean[] {false, true};
            boolean sameDay = live && wdShown == wdToday;
            for (int i = 0; i <= sp[1]; i++) {
                String subj = shown[i] == null || shown[i].isEmpty() ? "—" : shown[i];
                if (both) {
                    m.rows.add(new String[] {String.valueOf(i + 1),
                            ScheduleData.hm(d.bells[i][0]) + "–" + ScheduleData.hm(d.bells[i][1]), subj});
                } else {
                    m.rows.add(new String[] {String.valueOf(i + 1), subj});
                }
                m.rowColor.add(sameDay ? rowColor(st, i) : 0);
            }
        }
        return m;
    }

    private static int rowColor(ScheduleData.State st, int i) {
        if ("lesson".equals(st.type) && st.cur == i) return NOW;
        boolean nextOk = "lesson".equals(st.type) || "break".equals(st.type) || "before".equals(st.type);
        if (nextOk && st.next == i) return NEXT;
        return 0;
    }

    private static String status(ScheduleData d, ScheduleData.State st, ScheduleData.DayInfo info, Calendar next) {
        switch (st.type) {
            case "off":
                return next == null ? info.text : info.text + ", далі " + DAY_NAMES[ScheduleData.weekday(next)];
            case "noclass":
                return "Оберіть клас у застосунку";
            case "before":
                return "Уроки починаються о " + ScheduleData.hm(d.bells[st.next][0]);
            case "lesson":
                return st.last
                        ? "Останній урок, до " + ScheduleData.hm(d.bells[st.cur][1])
                        : "Зараз " + (st.cur + 1) + "-й урок, перерва о " + ScheduleData.hm(d.bells[st.cur][1]);
            case "break":
                return "Перерва, " + (st.next + 1) + "-й урок о " + ScheduleData.hm(d.bells[st.next][0]);
            case "after":
                return "Уроки на сьогодні закінчились";
            default:
                return "Сьогодні уроків немає";
        }
    }

    /* ---------------- малювання ---------------- */

    private static void draw(Canvas c, Model m, WidgetConfig cfg, boolean dark, Typeface tf,
                             Bitmap photo, int w, int h, float dp) {
        int fg = dark ? Color.WHITE : Color.BLACK;
        int shadow = dark ? 0xCC000000 : 0xCCFFFFFF;
        boolean bare = "none".equals(cfg.bg);
        // Тінь під текстом потрібна, коли крізь віджет видно шпалери або фото.
        boolean shaded = bare || "photo".equals(cfg.bg) || cfg.clear >= 50;
        float radius = cfg.radius * 0.24f * dp;
        RectF all = new RectF(0, 0, w, h);
        if (!bare) drawBackground(c, cfg, all, radius, bgColor(cfg, dark), photo, dp);

        float pad = (bare ? 3 : 10) * dp;
        float innerW = w - 2 * pad;
        float innerH = h - 2 * pad;
        int cols = m.head.length;
        boolean table = !m.rows.isEmpty();
        int rows = table ? m.rows.size() + 1 : 1;   // + шапка

        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTypeface(tf);
        text.setColor(fg);
        if (shaded) text.setShadowLayer(2.5f * dp, 0, 0.6f * dp, shadow);
        Paint onHl = new Paint(Paint.ANTI_ALIAS_FLAG);
        onHl.setTypeface(tf);
        onHl.setColor(Color.BLACK);

        // Розмір шрифту: за 100 % таблиця рівно заповнює віджет із просторими рядками.
        // Більше 100 % — текст більший, а рядки тісніші, але не тісніші за 1,3 висоти шрифту.
        float scale = cfg.scale / 100f;
        float roomy = innerH / (3.15f + 1.5f * rows);
        float tightest = innerH / (3.15f + 1.3f * rows);
        float byWidth = table ? fitWidth(text, m, innerW, dp) : Float.MAX_VALUE;
        float f = Math.min(Math.min(roomy, byWidth) * scale, Math.min(tightest, byWidth));
        f = Math.max(7 * dp, Math.min(22 * dp * Math.max(1f, scale), f));
        float cellPad = 4 * dp;

        float titleH = f * 1.45f;
        float statusH = f * 1.35f;
        float gap = f * 0.35f;

        // заголовок
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(f);
        float ts = f * Math.min(1f, innerW / Math.max(1f, text.measureText(m.title)));
        text.setTextSize(ts);
        drawLine(c, text, m.title, pad, pad, titleH);

        // що зараз
        text.setTextSize(f * 0.82f);
        drawLine(c, text, fit(text, m.status, innerW), pad, pad + titleH, statusH);
        if (!table) return;

        // таблиця
        float tx = pad;
        float ty = pad + titleH + statusH + gap;
        float rh = Math.min((h - pad - ty) / rows, f * 2.4f);
        float tw = innerW;
        float th = rh * rows;
        float[] x = new float[cols + 1];
        x[0] = tx;
        for (int k = 0; k < cols; k++) x[k + 1] = x[k] + tw * m.weights[k];
        x[cols] = tx + tw;
        float tRad = Math.min(radius, rh * 0.5f);
        RectF box = new RectF(tx, ty, tx + tw, ty + th);

        // заливки «зараз» і «далі» в межах заокругленої рамки
        c.save();
        c.clipPath(roundRect(box, tRad));
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int r = 0; r < m.rows.size(); r++) {
            int col = m.rowColor.get(r);
            if (col == 0) continue;
            fill.setColor(col);
            c.drawRect(tx, ty + (r + 1) * rh, tx + tw, ty + (r + 2) * rh, fill);
        }
        if (m.cellRow >= 0 && m.cellCol >= 0 && m.cellCol < cols) {
            fill.setColor(m.cellColor);
            c.drawRect(x[m.cellCol], ty + (m.cellRow + 1) * rh, x[m.cellCol + 1], ty + (m.cellRow + 2) * rh, fill);
        }
        c.restore();

        // рамки
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(fg);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Math.max(1f, 0.9f * dp));
        if (shaded) line.setShadowLayer(1.5f * dp, 0, 0, shadow);
        for (int r = 1; r < rows; r++) c.drawLine(tx, ty + r * rh, tx + tw, ty + r * rh, line);
        for (int k = 1; k < cols; k++) c.drawLine(x[k], ty, x[k], ty + th, line);
        Paint outer = new Paint(line);
        outer.setStrokeWidth(Math.max(1.5f, 1.6f * dp));
        float o = outer.getStrokeWidth() / 2;
        c.drawRoundRect(new RectF(tx + o, ty + o, tx + tw - o, ty + th - o), tRad, tRad, outer);

        // текст
        text.setTextSize(f * 0.78f);
        for (int k = 0; k < cols; k++) cell(c, text, m.head[k], x[k], x[k + 1], ty, rh, m.left[k], cellPad);
        text.setTextSize(f);
        onHl.setTextSize(f);
        for (int r = 0; r < m.rows.size(); r++) {
            boolean hl = m.rowColor.get(r) != 0;
            float top = ty + (r + 1) * rh;
            for (int k = 0; k < cols; k++) {
                boolean cellHl = hl || (r == m.cellRow && k == m.cellCol);
                cell(c, cellHl ? onHl : text, m.rows.get(r)[k], x[k], x[k + 1], top, rh, m.left[k], cellPad);
            }
        }
    }

    private static int bgColor(WidgetConfig cfg, boolean dark) {
        if (!cfg.color.isEmpty()) {
            try {
                return Color.parseColor(cfg.color);
            } catch (IllegalArgumentException e) {
                // некоректний колір — беремо колір теми
            }
        }
        return dark ? Color.BLACK : Color.WHITE;
    }

    /** Найбільший шрифт, за якого час і номери влазять у свої стовпці. */
    private static float fitWidth(Paint p, Model m, float innerW, float dp) {
        float saved = p.getTextSize();
        p.setTextSize(100);
        float best = Float.MAX_VALUE;
        for (int k = 0; k < m.head.length; k++) {
            if (m.left[k]) continue;   // назви предметів можна скоротити «…»
            float widest = 0;
            for (String[] r : m.rows) widest = Math.max(widest, p.measureText(r[k]));
            widest = Math.max(widest, p.measureText(m.head[k]) * 0.78f);
            float avail = innerW * m.weights[k] - 8 * dp;
            if (widest > 0) best = Math.min(best, avail * 100 / widest);
        }
        p.setTextSize(saved);
        return best;
    }

    private static void drawLine(Canvas c, Paint p, String s, float x, float top, float height) {
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(s, x, top + height / 2 - (fm.ascent + fm.descent) / 2, p);
    }

    private static void cell(Canvas c, Paint p, String s, float x0, float x1, float top, float h,
                             boolean left, float pad) {
        String t = fit(p, s, x1 - x0 - 2 * pad);
        Paint.FontMetrics fm = p.getFontMetrics();
        float y = top + h / 2 - (fm.ascent + fm.descent) / 2;
        if (left) {
            p.setTextAlign(Paint.Align.LEFT);
            c.drawText(t, x0 + pad, y, p);
        } else {
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText(t, (x0 + x1) / 2, y, p);
        }
        p.setTextAlign(Paint.Align.LEFT);
    }

    /** Скорочує рядок «…», якщо він не влазить. */
    static String fit(Paint p, String s, float max) {
        if (s == null) return "";
        if (p.measureText(s) <= max) return s;
        int n = s.length();
        while (n > 0 && p.measureText(s.substring(0, n) + "…") > max) n--;
        return n > 0 ? s.substring(0, n) + "…" : "…";
    }

    private static Path roundRect(RectF r, float rad) {
        Path p = new Path();
        p.addRoundRect(r, rad, rad, Path.Direction.CW);
        return p;
    }

    /* ---------------- фон ---------------- */

    private static void drawBackground(Canvas c, WidgetConfig cfg, RectF all, float radius, int bgc,
                                       Bitmap photo, float dp) {
        int alpha = Math.round(255 * (100 - cfg.clear) / 100f);
        if (alpha <= 0) return;
        c.save();
        c.clipPath(roundRect(all, radius));
        if ("photo".equals(cfg.bg) && photo != null) {
            Bitmap bg = blurCover(photo, Math.round(all.width()), Math.round(all.height()), cfg.blur, dp);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            p.setAlpha(alpha);
            c.drawBitmap(bg, new Rect(0, 0, bg.getWidth(), bg.getHeight()), all, p);
            if (cfg.dim > 0) {
                Paint dim = new Paint();
                dim.setColor(Color.argb(Math.round(alpha * cfg.dim / 100f), 0, 0, 0));
                c.drawRect(all, dim);
            }
        } else {
            Paint p = new Paint();
            p.setColor(bgc);
            p.setAlpha(alpha);
            c.drawRect(all, p);
        }
        c.restore();
    }

    /** Фото, обрізане під розмір віджета і розмите (у зменшеному вигляді — так швидше). */
    static Bitmap blurCover(Bitmap src, int w, int h, int blurPct, float dp) {
        float k = blurPct > 0 ? 0.25f : 1f;
        int sw = Math.max(1, Math.round(w * k));
        int sh = Math.max(1, Math.round(h * k));
        float s = Math.max((float) sw / src.getWidth(), (float) sh / src.getHeight());
        float cw = sw / s, ch = sh / s;
        Rect from = new Rect(Math.round((src.getWidth() - cw) / 2), Math.round((src.getHeight() - ch) / 2),
                Math.round((src.getWidth() + cw) / 2), Math.round((src.getHeight() + ch) / 2));
        Bitmap small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        new Canvas(small).drawBitmap(src, from, new RectF(0, 0, sw, sh), new Paint(Paint.FILTER_BITMAP_FLAG));
        int r = Math.round(blurPct * 0.2f * dp * k);
        if (r > 0) {
            int[] px = new int[sw * sh];
            small.getPixels(px, 0, sw, 0, 0, sw, sh);
            boxBlur(px, sw, sh, r);
            small.setPixels(px, 0, sw, 0, 0, sw, sh);
        }
        return small;
    }

    /** Три проходи прямокутного розмиття — майже як гаусове. */
    static void boxBlur(int[] px, int w, int h, int r) {
        int[] tmp = new int[px.length];
        for (int pass = 0; pass < 3; pass++) {
            blurPass(px, tmp, w, h, r, true);
            blurPass(tmp, px, w, h, r, false);
        }
    }

    private static void blurPass(int[] src, int[] dst, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h;
        int lines = horizontal ? h : w;
        int div = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int a = 0, rd = 0, g = 0, b = 0;
            for (int i = -r; i <= r; i++) {
                int c = src[index(line, clamp(i, len), w, horizontal)];
                a += c >>> 24; rd += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255;
            }
            for (int i = 0; i < len; i++) {
                dst[index(line, i, w, horizontal)] = ((a / div) << 24) | ((rd / div) << 16) | ((g / div) << 8) | (b / div);
                int out = src[index(line, clamp(i - r, len), w, horizontal)];
                int in = src[index(line, clamp(i + r + 1, len), w, horizontal)];
                a += (in >>> 24) - (out >>> 24);
                rd += ((in >> 16) & 255) - ((out >> 16) & 255);
                g += ((in >> 8) & 255) - ((out >> 8) & 255);
                b += (in & 255) - (out & 255);
            }
        }
    }

    private static int clamp(int i, int len) {
        return i < 0 ? 0 : (i >= len ? len - 1 : i);
    }

    private static int index(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }
}
