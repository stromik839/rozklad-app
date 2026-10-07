package ua.school.rozklad;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Розбір JSON без сторонніх бібліотек: об’єкти, масиви, рядки, числа, true/false/null.
 * Навмисно поблажливий — приймає те саме, що JavaScript у data.js: коментарі // і /* ... *&#47;,
 * кому після останнього елемента, рядки в 'одинарних' лапках і назви полів без лапок.
 * Тож якщо розклад відкривається в застосунку, його прочитає й віджет.
 */
final class Json {

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    /** Увесь текст — одне значення. */
    static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw new IllegalArgumentException("JSON: зайві символи, позиція " + p.i);
        return v;
    }

    /** Значення, що починається з позиції start; усе, що після нього (крапка з комою, коментарі), пропускається. */
    static Object parseAt(String text, int start) {
        Json p = new Json(text);
        p.i = start;
        p.ws();
        return p.value();
    }

    /** Рядок у лапках для запису в JSON. */
    static String quote(String v) {
        StringBuilder b = new StringBuilder("\"");
        for (int k = 0; k < v.length(); k++) {
            char c = v.charAt(k);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    /** Пробіли, переноси і коментарі. */
    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == '﻿') {
                i++;
                continue;
            }
            if (c == '/' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == '/') {
                    int e = s.indexOf('\n', i);
                    i = e < 0 ? s.length() : e + 1;
                    continue;
                }
                if (n == '*') {
                    int e = s.indexOf("*/", i + 2);
                    if (e < 0) throw new IllegalArgumentException("JSON: незакритий коментар, позиція " + i);
                    i = e + 2;
                    continue;
                }
            }
            return;
        }
    }

    private char peek() {
        if (i >= s.length()) throw new IllegalArgumentException("JSON: несподіваний кінець");
        return s.charAt(i);
    }

    private Object value() {
        char c = peek();
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"' || c == '\'') return string();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        return number();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        while (true) {
            ws();
            if (peek() == '}') { i++; return m; }   // порожній об’єкт або кома після останнього поля
            String k = key();
            ws();
            expect(':');
            ws();
            m.put(k, value());
            ws();
            char c = peek();
            i++;
            if (c == '}') return m;
            if (c != ',') throw new IllegalArgumentException("JSON: очікувалась кома, позиція " + (i - 1));
        }
    }

    /** Назва поля: у лапках або без них, як дозволяє JavaScript. */
    private String key() {
        char c = peek();
        if (c == '"' || c == '\'') return string();
        int st = i;
        while (i < s.length()) {
            char k = s.charAt(i);
            if (!Character.isLetterOrDigit(k) && k != '_' && k != '$') break;
            i++;
        }
        if (st == i) throw new IllegalArgumentException("JSON: очікувалась назва поля, позиція " + i);
        return s.substring(st, i);
    }

    private List<Object> array() {
        List<Object> a = new ArrayList<>();
        i++;
        while (true) {
            ws();
            if (peek() == ']') { i++; return a; }   // порожній масив або кома після останнього елемента
            a.add(value());
            ws();
            char c = peek();
            i++;
            if (c == ']') return a;
            if (c != ',') throw new IllegalArgumentException("JSON: очікувалась кома, позиція " + (i - 1));
        }
    }

    private String string() {
        char q = peek();
        i++;
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = peek();
            i++;
            if (c == q) return b.toString();
            if (c != '\\') { b.append(c); continue; }
            char e = peek();
            i++;
            switch (e) {
                case 'n': b.append('\n'); break;
                case 't': b.append('\t'); break;
                case 'r': b.append('\r'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'u':
                    if (i + 4 > s.length()) throw new IllegalArgumentException("JSON: обірваний \\u, позиція " + i);
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default: b.append(e);   // \" \' \\ \/ та інші — сам символ
            }
        }
    }

    private Double number() {
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (st == i) throw new IllegalArgumentException("JSON: незрозумілий символ, позиція " + i);
        return Double.parseDouble(s.substring(st, i));
    }

    private void expect(char c) {
        if (peek() != c) throw new IllegalArgumentException("JSON: очікувалось '" + c + "', позиція " + i);
        i++;
    }
}
