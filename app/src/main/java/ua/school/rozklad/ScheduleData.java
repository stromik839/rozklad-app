package ua.school.rozklad;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Розклад, яким зараз користується телефон (отриманий від інших або складений у застосунку),
 * і та сама логіка «що зараз», що й на сторінці застосунку.
 */
final class ScheduleData {

    final int[][] bells;          // хвилини від півночі: {початок, кінець}
    final String[][] terms;       // {від, до, назва}
    final String[][] holidays;    // {від, до, назва}
    final Map<String, String[][]> schedule = new HashMap<>();

    /** Розкладу на телефоні ще немає: його треба отримати в застосунку. */
    static final class Missing extends IOException {
        private static final long serialVersionUID = 1L;

        Missing() {
            super("Розклад ще не отримано");
        }
    }

    private static ScheduleData cached;
    private static String cachedStamp = "";

    /** Розклад із пам’яті телефону; поки файл той самий — без повторного розбору. */
    static ScheduleData get(Context ctx) throws IOException {
        ScheduleStore.ensureMigrated(ctx);   // поза блокуванням нижче: так два блокування не перехрестяться
        File f = ScheduleStore.file(ctx);
        synchronized (ScheduleData.class) {
            if (!f.isFile()) {
                cached = null;
                cachedStamp = "";
                throw new Missing();
            }
            String stamp = f.lastModified() + ":" + f.length();
            if (cached == null || !stamp.equals(cachedStamp)) {
                String json = Store.readFile(f);
                if (json.isEmpty()) throw new Missing();
                cached = fromJson(json);
                cachedStamp = stamp;
            }
            return cached;
        }
    }

    /** Розклад змінився — наступне звернення прочитає його заново. */
    static synchronized void invalidate() {
        cached = null;
        cachedStamp = "";
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

    @SuppressWarnings("unchecked")
    static ScheduleData fromJson(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof Map)) throw new IllegalArgumentException("це не розклад");
        return new ScheduleData((Map<String, Object>) root);
    }

    /** Об’єкт після «ROZKLAD_DATA =» у data.js; коментарі й крапка з комою навколо нього не заважають. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> dataJsObject(String js) {
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
        return (Map<String, Object>) Json.parseAt(js, start);
    }

    /** Перевіряє все, на що спираються віджети; помилка — IllegalArgumentException з поясненням. */
    @SuppressWarnings("unchecked")
    ScheduleData(Map<String, Object> root) {
        Object b = root.get("bells");
        if (!(b instanceof List) || ((List<Object>) b).isEmpty()) throw new IllegalArgumentException("немає дзвінків");
        List<Object> bl = (List<Object>) b;
        bells = new int[bl.size()][2];
        for (int i = 0; i < bl.size(); i++) {
            Object p = bl.get(i);
            if (!(p instanceof List) || ((List<Object>) p).size() < 2) {
                throw new IllegalArgumentException("дзвінок " + (i + 1) + ": потрібні початок і кінець");
            }
            List<Object> pair = (List<Object>) p;
            bells[i][0] = minutes(String.valueOf(pair.get(0)));
            bells[i][1] = minutes(String.valueOf(pair.get(1)));
            if (bells[i][0] >= bells[i][1]) throw new IllegalArgumentException("дзвінок " + (i + 1) + ": початок не раніше кінця");
        }
        terms = table(root.get("terms"), "семестр");
        holidays = table(root.get("holidays"), "канікули");
        Object sch = root.get("schedule");
        if (!(sch instanceof Map)) throw new IllegalArgumentException("немає уроків");
        for (Map.Entry<String, Object> e : ((Map<String, Object>) sch).entrySet()) {
            if (!(e.getValue() instanceof List)) throw new IllegalArgumentException("клас " + e.getKey() + ": уроки записані неправильно");
            List<Object> days = (List<Object>) e.getValue();
            String[][] week = new String[days.size()][];
            for (int d = 0; d < days.size(); d++) {
                Object day = days.get(d);
                if (!(day instanceof List)) throw new IllegalArgumentException("клас " + e.getKey() + ": уроки записані неправильно");
                List<Object> l = (List<Object>) day;
                week[d] = new String[l.size()];
                for (int i = 0; i < l.size(); i++) week[d][i] = l.get(i) == null ? "" : String.valueOf(l.get(i));
            }
            schedule.put(e.getKey(), week);
        }
    }

    /** Список періодів {від, до, назва}; поля може й не бути. */
    @SuppressWarnings("unchecked")
    private static String[][] table(Object v, String what) {
        if (v == null) return new String[0][];
        if (!(v instanceof List)) throw new IllegalArgumentException(what + ": записано неправильно");
        List<Object> rows = (List<Object>) v;
        String[][] t = new String[rows.size()][];
        for (int i = 0; i < rows.size(); i++) {
            Object r = rows.get(i);
            if (!(r instanceof List) || ((List<Object>) r).size() < 3) {
                throw new IllegalArgumentException(what + " " + (i + 1) + ": потрібні дати й назва");
            }
            List<Object> row = (List<Object>) r;
            t[i] = new String[3];
            for (int k = 0; k < 3; k++) t[i][k] = String.valueOf(row.get(k));
            if (!isDate(t[i][0]) || !isDate(t[i][1])) {
                throw new IllegalArgumentException(what + " " + (i + 1) + ": дата має бути у вигляді 2027-01-13");
            }
        }
        return t;
    }

    private static boolean isDate(String s) {
        return s != null && s.matches("\\d{4}-\\d{2}-\\d{2}");
    }

    /* ---------------- час і дати ---------------- */

    static int minutes(String hm) {
        String[] p = hm.split(":");
        int h, m;
        try {
            if (p.length != 2) throw new NumberFormatException();
            h = Integer.parseInt(p[0].trim());
            m = Integer.parseInt(p[1].trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("час «" + hm + "» має бути у вигляді 08:30");
        }
        if (h < 0 || h > 23 || m < 0 || m > 59) throw new IllegalArgumentException("немає такого часу: " + hm);
        return h * 60 + m;
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

    /** Без семестрів навчальним вважається кожен будній день, крім канікул. */
    DayInfo dayInfo(Calendar c) {
        String k = key(c);
        DayInfo r = new DayInfo();
        for (String[] h : holidays) {
            if (k.compareTo(h[0]) >= 0 && k.compareTo(h[1]) <= 0) {
                r.text = h[2] + " до " + ddmm(h[1]);
                return r;
            }
        }
        if (terms.length > 0 && (k.compareTo(terms[0][0]) < 0 || k.compareTo(terms[terms.length - 1][1]) > 0)) {
            r.text = "Літні канікули";
            return r;
        }
        int wd = weekday(c);
        if (wd == 0 || wd == 6) {
            r.text = "Сьогодні вихідний";
            return r;
        }
        boolean inTerm = terms.length == 0;
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

    /** Розклад лише з дзвінків (класів немає): тоді кожен дзвінок — урок. */
    boolean noLessons() {
        return schedule.isEmpty();
    }

    /** «Уроки» для розкладу без класів: усі дзвінки. */
    String[] allBells() {
        String[] r = new String[bells.length];
        for (int i = 0; i < r.length; i++) r[i] = (i + 1) + "-й урок";
        return r;
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
