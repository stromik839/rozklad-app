package ua.school.rozklad;

import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Дані розкладу з assets/data.js (той самий файл читає сторінка застосунку)
 * і та сама логіка «що зараз», що й на сторінці.
 */
final class ScheduleData {

    final int[][] bells;          // хвилини від півночі: {початок, кінець}
    final String[][] terms;       // {від, до, назва}
    final String[][] holidays;    // {від, до, назва}
    final Map<String, String[][]> schedule = new HashMap<>();

    private static ScheduleData cached;

    static synchronized ScheduleData get(AssetManager am) throws IOException {
        if (cached == null) cached = parse(readAll(am.open("data.js")));
        return cached;
    }

    static String readAll(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** Об’єкт після «ROZKLAD_DATA =» у data.js; коментарі й крапка з комою навколо нього не заважають. */
    @SuppressWarnings("unchecked")
    static ScheduleData parse(String js) {
        int at = -1;
        for (int k = js.indexOf("ROZKLAD_DATA"); k >= 0; k = js.indexOf("ROZKLAD_DATA", k + 1)) {
            int e = k + "ROZKLAD_DATA".length();
            while (e < js.length() && Character.isWhitespace(js.charAt(e))) e++;
            if (e < js.length() && js.charAt(e) == '=') {
                at = e + 1;
                break;
            }
        }
        int start = js.indexOf('{', Math.max(0, at));
        if (start < 0) throw new IllegalArgumentException("data.js: не знайдено даних розкладу");
        return new ScheduleData((Map<String, Object>) Json.parseAt(js, start));
    }

    @SuppressWarnings("unchecked")
    private ScheduleData(Map<String, Object> root) {
        List<Object> b = (List<Object>) root.get("bells");
        bells = new int[b.size()][2];
        for (int i = 0; i < b.size(); i++) {
            List<Object> p = (List<Object>) b.get(i);
            bells[i][0] = minutes(String.valueOf(p.get(0)));
            bells[i][1] = minutes(String.valueOf(p.get(1)));
        }
        terms = table((List<Object>) root.get("terms"));
        holidays = table((List<Object>) root.get("holidays"));
        Map<String, Object> sch = (Map<String, Object>) root.get("schedule");
        for (Map.Entry<String, Object> e : sch.entrySet()) {
            List<Object> days = (List<Object>) e.getValue();
            String[][] week = new String[days.size()][];
            for (int d = 0; d < days.size(); d++) {
                List<Object> l = (List<Object>) days.get(d);
                week[d] = new String[l.size()];
                for (int i = 0; i < l.size(); i++) week[d][i] = l.get(i) == null ? "" : String.valueOf(l.get(i));
            }
            schedule.put(e.getKey(), week);
        }
    }

    @SuppressWarnings("unchecked")
    private static String[][] table(List<Object> rows) {
        String[][] t = new String[rows.size()][];
        for (int i = 0; i < rows.size(); i++) {
            List<Object> r = (List<Object>) rows.get(i);
            t[i] = new String[r.size()];
            for (int k = 0; k < r.size(); k++) t[i][k] = String.valueOf(r.get(k));
        }
        return t;
    }

    /* ---------------- час і дати ---------------- */

    static int minutes(String hm) {
        String[] p = hm.split(":");
        return Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim());
    }

    static String hm(int min) {
        return String.format(Locale.US, "%02d:%02d", min / 60, min % 60);
    }

    static String key(Calendar c) {
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    static String ddmm(String k) {
        return k.substring(8, 10) + "." + k.substring(5, 7);
    }

    /** 0 — неділя, 1 — понеділок … 6 — субота (як getDay() у JavaScript). */
    static int weekday(Calendar c) {
        return c.get(Calendar.DAY_OF_WEEK) - 1;
    }

    static final class DayInfo {
        boolean school;
        String text = "";
    }

    DayInfo dayInfo(Calendar c) {
        String k = key(c);
        DayInfo r = new DayInfo();
        for (String[] h : holidays) {
            if (k.compareTo(h[0]) >= 0 && k.compareTo(h[1]) <= 0) {
                r.text = h[2] + " до " + ddmm(h[1]);
                return r;
            }
        }
        if (terms.length == 0 || k.compareTo(terms[0][0]) < 0 || k.compareTo(terms[terms.length - 1][1]) > 0) {
            r.text = "Літні канікули";
            return r;
        }
        int wd = weekday(c);
        if (wd == 0 || wd == 6) {
            r.text = "Сьогодні вихідний";
            return r;
        }
        boolean inTerm = false;
        for (String[] t : terms) if (k.compareTo(t[0]) >= 0 && k.compareTo(t[1]) <= 0) inTerm = true;
        if (!inTerm) {
            r.text = "Канікули";
            return r;
        }
        r.school = true;
        return r;
    }

    Calendar nextSchoolDay(Calendar c) {
        Calendar x = (Calendar) c.clone();
        x.set(Calendar.HOUR_OF_DAY, 12);
        for (int i = 0; i < 400; i++) {
            x.add(Calendar.DAY_OF_MONTH, 1);
            if (dayInfo(x).school) return x;
        }
        return null;
    }

    /** Уроки класу на день тижня 1–5. */
    String[] lessons(String cls, int wd) {
        String[][] w = cls == null ? null : schedule.get(cls);
        if (w == null || wd < 1 || wd > w.length) return new String[0];
        return w[wd - 1];
    }

    /** Перший і останній непорожній урок, або {-1, -1}. */
    int[] span(String[] list) {
        int first = -1, last = -1;
        for (int i = 0; i < list.length && i < bells.length; i++) {
            if (list[i] != null && !list[i].isEmpty()) {
                if (first < 0) first = i;
                last = i;
            }
        }
        return new int[] {first, last};
    }

    static final class State {
        String type = "none";   // none, before, lesson, break, after, off, noclass
        int cur = -1, next = -1, after = -1;
        boolean last;
    }

    /** t — секунди від півночі. */
    State state(int t, String[] list) {
        State s = new State();
        int[] sp = span(list);
        if (sp[0] < 0) return s;
        if (t < bells[sp[0]][0] * 60) {
            s.type = "before";
            s.next = sp[0];
            return s;
        }
        for (int i = sp[0]; i <= sp[1]; i++) {
            int st = bells[i][0] * 60, en = bells[i][1] * 60;
            if (t >= st && t < en) {
                s.type = "lesson";
                s.cur = i;
                s.last = i == sp[1];
                s.next = s.last ? -1 : i + 1;
                return s;
            }
            if (i < sp[1] && t >= en && t < bells[i + 1][0] * 60) {
                s.type = "break";
                s.after = i;
                s.next = i + 1;
                return s;
            }
        }
        s.type = "after";
        return s;
    }
}
