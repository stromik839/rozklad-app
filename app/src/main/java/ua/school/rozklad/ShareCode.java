package ua.school.rozklad;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Код розкладу для посилань, QR і пересилання текстом.
 * Розклад (JSON) стискається, до нього дописується контрольна сума CRC32, і все записується лише
 * цифрами й великими латинськими літерами. Такий код не ламається в месенджерах і посиланнях,
 * його не псує зміна регістру, а в QR він займає найменше місця (буквено-цифровий режим).
 *
 * Формат: «RZ1» + кожні 5 байтів → 8 символів (число за основою 36);
 * остання неповна група: 1 байт → 2 символи, 2 → 4, 3 → 5, 4 → 7.
 * Ту саму схему розуміють сторінка застосунку і сторінка посилань (docs/index.html).
 */
final class ShareCode {

    static final String PREFIX = "RZ1";
    private static final String DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    /** Скільки символів займає остання група з 0–4 байтів. */
    private static final int[] TAIL_CHARS = {0, 2, 4, 5, 7};
    /** Більшого розкладу не буває: захист від «бомби», що розпаковується в мегабайти. */
    static final int MAX_JSON_BYTES = 256 * 1024;
    private static final Pattern CODE = Pattern.compile("RZ1[0-9A-Z]{8,}");

    static final String NOT_FOUND = "Тут немає коду розкладу. Скопіюйте посилання або код повністю.";
    static final String DAMAGED = "Код неповний або пошкоджений. Скопіюйте посилання чи код ще раз — повністю.";

    private ShareCode() {}

    static String encode(String json) {
        byte[] raw = json.getBytes(StandardCharsets.UTF_8);
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION, true);
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 3 + 16);
        try {
            d.setInput(raw);
            d.finish();
            byte[] buf = new byte[4096];
            while (!d.finished()) {
                int n = d.deflate(buf);
                out.write(buf, 0, n);
            }
        } finally {
            d.end();
        }
        CRC32 crc = new CRC32();
        crc.update(raw, 0, raw.length);
        long c = crc.getValue();
        out.write((int) (c >>> 24) & 0xFF);
        out.write((int) (c >>> 16) & 0xFF);
        out.write((int) (c >>> 8) & 0xFF);
        out.write((int) c & 0xFF);
        return PREFIX + toBase36(out.toByteArray());
    }

    /** Код із тексту (посилання, повідомлення, сам код), або null. Регістр літер не важливий. */
    static String extract(String text) {
        if (text == null) return null;
        Matcher m = CODE.matcher(text.toUpperCase(Locale.ROOT));
        String best = null;
        while (m.find()) {
            if (best == null || m.group().length() > best.length()) best = m.group();
        }
        return best;
    }

    /** JSON розкладу з коду чи посилання. Помилка — IllegalArgumentException зі зрозумілим текстом. */
    static String decode(String text) {
        String code = extract(text);
        if (code == null) throw new IllegalArgumentException(NOT_FOUND);
        byte[] bytes = fromBase36(code.substring(PREFIX.length()));
        if (bytes == null || bytes.length < 6) throw new IllegalArgumentException(DAMAGED);
        // Даємо розпакувальнику і контрольну суму: дані після кінця стиснутого потоку він пропускає,
        // а «зайвий» байт у кінці потрібен zlib у режимі без заголовка.
        Inflater inf = new Inflater(true);
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length * 4);
        try {
            inf.setInput(bytes, 0, bytes.length);
            byte[] buf = new byte[8192];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) throw new IllegalArgumentException(DAMAGED);
                out.write(buf, 0, n);
                if (out.size() > MAX_JSON_BYTES) throw new IllegalArgumentException("Код завеликий для розкладу.");
            }
        } catch (DataFormatException e) {
            throw new IllegalArgumentException(DAMAGED);
        } finally {
            inf.end();
        }
        byte[] raw = out.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(raw, 0, raw.length);
        long c = crc.getValue();
        int n = bytes.length;
        long stored = ((bytes[n - 4] & 0xFFL) << 24) | ((bytes[n - 3] & 0xFFL) << 16)
                | ((bytes[n - 2] & 0xFFL) << 8) | (bytes[n - 1] & 0xFFL);
        if (stored != c) throw new IllegalArgumentException(DAMAGED);
        return new String(raw, StandardCharsets.UTF_8);
    }

    /* ---------------- основа 36 ---------------- */

    static String toBase36(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 8 / 5 + 8);
        char[] group = new char[8];
        for (int i = 0; i < b.length; i += 5) {
            int len = Math.min(5, b.length - i);
            long v = 0;
            for (int k = 0; k < len; k++) v = (v << 8) | (b[i + k] & 0xFF);
            int chars = len == 5 ? 8 : TAIL_CHARS[len];
            for (int k = chars - 1; k >= 0; k--) {
                group[k] = DIGITS.charAt((int) (v % 36));
                v /= 36;
            }
            s.append(group, 0, chars);
        }
        return s.toString();
    }

    /** null — якщо довжина чи символи не можуть бути кодом. */
    static byte[] fromBase36(String s) {
        int full = s.length() / 8;
        int restChars = s.length() % 8;
        int restBytes = -1;
        for (int k = 0; k < TAIL_CHARS.length; k++) if (TAIL_CHARS[k] == restChars) restBytes = k;
        if (restBytes < 0) return null;
        byte[] out = new byte[full * 5 + restBytes];
        int o = 0;
        for (int g = 0; g <= full; g++) {
            int chars = g < full ? 8 : restChars;
            int bytes = g < full ? 5 : restBytes;
            if (chars == 0) break;
            long v = 0;
            for (int k = 0; k < chars; k++) {
                int d = DIGITS.indexOf(s.charAt(g * 8 + k));
                if (d < 0) return null;
                v = v * 36 + d;
            }
            if (v >= (1L << (8 * bytes))) return null;
            for (int k = bytes - 1; k >= 0; k--) {
                out[o + k] = (byte) (v & 0xFF);
                v >>>= 8;
            }
            o += bytes;
        }
        return out;
    }
}
