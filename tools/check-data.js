#!/usr/bin/env node
/*
  Проверка data.js перед сборкой.
  Читает файл так же, как приложение (JavaScript) и как виджеты (Json.java),
  проверяет содержимое и, если что-то не так, останавливает сборку с понятным
  сообщением: где ошибка и как её исправить.

  Запуск:  node tools/check-data.js app/src/main/assets/data.js
*/
"use strict";

const fs = require("fs");
const vm = require("vm");

const file = process.argv[2] || "app/src/main/assets/data.js";
const errors = [];
const warnings = [];
const err = (m) => errors.push(m);
const warn = (m) => warnings.push(m);

function finish() {
  for (const w of warnings) console.log("⚠ " + w);
  if (errors.length) {
    for (const e of errors) console.log("✗ " + e);
    console.log("\nСборка остановлена: исправьте data.js и отправьте снова (git push или «Commit changes» на GitHub).");
    process.exit(1);
  }
}

let text;
try {
  text = fs.readFileSync(file, "utf8").replace(/^﻿/, "");
} catch (e) {
  err("не найден файл " + file);
  finish();
}
const lines = text.split(/\r?\n/);

/* ---------- 1. Как читает приложение (JavaScript) ---------- */
let D;
try {
  const sandbox = { window: {} };
  vm.runInNewContext(text, sandbox, { filename: "data.js", timeout: 2000 });
  D = sandbox.window.ROZKLAD_DATA;
} catch (e) {
  const m = /data\.js:(\d+)/.exec(String(e.stack || ""));
  const line = m ? +m[1] : 0;
  err((line ? "строка " + line + ": " : "") + "файл не читается (" + e.message + ").\n" +
    (line ? "    " + (lines[line - 1] || "").trim() + "\n" : "") +
    "  Чаще всего это пропущенная запятая между элементами, лишняя или потерянная кавычка или скобка.\n" +
    "  Если ошибки в этой строке не видно — посмотрите строку выше.");
  finish();
}
if (!D || typeof D !== "object") {
  err("в файле нет строки «window.ROZKLAD_DATA = { ... };» — данные должны начинаться с неё.");
  finish();
}

/* ---------- 2. Как читают виджеты (та же грамматика, что в Json.java) ---------- */
function lineCol(pos) {
  const before = text.slice(0, pos).split(/\r?\n/);
  return { line: before.length, col: before[before.length - 1].length + 1 };
}
function parseLikeWidgets(s, start) {
  let i = start;
  const fail = (msg) => { const e = new Error(msg); e.pos = i; throw e; };
  const isSpace = (c) => /[\s﻿\u001C-\u001F]/.test(c);
  function ws() {
    while (i < s.length) {
      const c = s[i];
      if (isSpace(c)) { i++; continue; }
      if (c === "/" && s[i + 1] === "/") { const e = s.indexOf("\n", i); i = e < 0 ? s.length : e + 1; continue; }
      if (c === "/" && s[i + 1] === "*") {
        const e = s.indexOf("*/", i + 2);
        if (e < 0) fail("незакрытый комментарий /*");
        i = e + 2;
        continue;
      }
      return;
    }
  }
  function peek() { if (i >= s.length) fail("файл неожиданно закончился"); return s[i]; }
  function expect(c) { if (peek() !== c) fail("ожидался символ «" + c + "»"); i++; }
  function value() {
    const c = peek();
    if (c === "{") return object();
    if (c === "[") return array();
    if (c === '"' || c === "'") return string();
    if (s.startsWith("true", i)) { i += 4; return true; }
    if (s.startsWith("false", i)) { i += 5; return false; }
    if (s.startsWith("null", i)) { i += 4; return null; }
    return number();
  }
  function object() {
    const m = {};
    i++;
    for (;;) {
      ws();
      if (peek() === "}") { i++; return m; }
      const k = key();
      ws(); expect(":"); ws();
      m[k] = value();
      ws();
      const c = peek(); i++;
      if (c === "}") return m;
      if (c !== ",") { i--; fail("ожидалась запятая или «}»"); }
    }
  }
  function key() {
    const c = peek();
    if (c === '"' || c === "'") return string();
    const st = i;
    while (i < s.length && /[\p{L}\p{Nd}_$]/u.test(s[i])) i++;
    if (st === i) fail("ожидалось название поля");
    return s.slice(st, i);
  }
  function array() {
    const a = [];
    i++;
    for (;;) {
      ws();
      if (peek() === "]") { i++; return a; }
      a.push(value());
      ws();
      const c = peek(); i++;
      if (c === "]") return a;
      if (c !== ",") { i--; fail("ожидалась запятая или «]»"); }
    }
  }
  function string() {
    const q = peek();
    i++;
    let b = "";
    for (;;) {
      const c = peek(); i++;
      if (c === q) return b;
      if (c !== "\\") { b += c; continue; }
      const e = peek(); i++;
      if (e === "n") b += "\n";
      else if (e === "t") b += "\t";
      else if (e === "r") b += "\r";
      else if (e === "b") b += "\b";
      else if (e === "f") b += "\f";
      else if (e === "u") {
        const hex = s.slice(i, i + 4);
        if (!/^[0-9a-fA-F]{4}$/.test(hex)) fail("неправильный код \\u");
        b += String.fromCharCode(parseInt(hex, 16));
        i += 4;
      } else b += e;
    }
  }
  function number() {
    const st = i;
    while (i < s.length && "+-0123456789.eE".includes(s[i])) i++;
    if (st === i) fail("непонятный символ «" + s[i] + "»");
    const v = Number(s.slice(st, i));
    if (Number.isNaN(v)) { i = st; fail("непонятное число «" + s.slice(st, i + 10) + "»"); }
    return v;
  }
  ws();
  return value();
}
function dataStart(s) {
  const re = /ROZKLAD_DATA\s*=/g;
  const m = re.exec(s);
  const from = m ? m.index + m[0].length : 0;
  return s.indexOf("{", from);
}
let W = null;
try {
  W = parseLikeWidgets(text, dataStart(text));
} catch (e) {
  const p = lineCol(e.pos || 0);
  err("строка " + p.line + ": приложение этот файл понимает, а виджеты — нет (" + e.message + ").\n" +
    "    " + (lines[p.line - 1] || "").trim() + "\n" +
    "  Пишите только текст в кавычках, числа, [ ], { }, запятые и комментарии // — без формул и других JavaScript-приёмов.");
}
function firstDiff(a, b, path) {
  if (a === b) return null;
  if (typeof a !== typeof b || a === null || b === null || Array.isArray(a) !== Array.isArray(b)) return path;
  if (Array.isArray(a)) {
    if (a.length !== b.length) return path;
    for (let k = 0; k < a.length; k++) { const d = firstDiff(a[k], b[k], path.concat(k)); if (d) return d; }
    return null;
  }
  if (typeof a === "object") {
    const ka = Object.keys(a), kb = Object.keys(b);
    if (ka.length !== kb.length) return path;
    for (const k of ka) {
      if (!Object.prototype.hasOwnProperty.call(b, k)) return path.concat(k);
      const d = firstDiff(a[k], b[k], path.concat(k));
      if (d) return d;
    }
    return null;
  }
  return path;
}
const DAY_NAMES = ["понедельник", "вторник", "среда", "четверг", "пятница"];
function placeName(path) {
  if (path[0] === "schedule" && path.length >= 2) {
    return "класс " + path[1] + (path.length >= 3 ? ", " + (DAY_NAMES[path[2]] || "день " + (path[2] + 1)) : "") +
      (path.length >= 4 ? ", урок " + (path[3] + 1) : "");
  }
  return path.map((p) => (typeof p === "number" ? "№" + (p + 1) : p)).join(" → ") || "весь файл";
}
if (W) {
  const d = firstDiff(JSON.parse(JSON.stringify(D)), W, []);
  if (d) err("приложение и виджеты прочитают по-разному это место: " + placeName(d) +
    ".\n  Скорее всего, там необычная запись строки (например, \\x или формула). Напишите текст просто в кавычках.");
}

/* ---------- 3. Содержимое ---------- */
const isTime = (s) => typeof s === "string" && /^([01]\d|2[0-3]):[0-5]\d$/.test(s);
const mins = (s) => +s.slice(0, 2) * 60 + +s.slice(3, 5);
function isDate(s) {
  if (typeof s !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(s)) return false;
  const d = new Date(+s.slice(0, 4), +s.slice(5, 7) - 1, +s.slice(8, 10));
  return d.getFullYear() === +s.slice(0, 4) && d.getMonth() === +s.slice(5, 7) - 1 && d.getDate() === +s.slice(8, 10);
}
const DAYS = ["понедельник", "вторник", "среда", "четверг", "пятница"];
// Латинские буквы, похожие на кириллические: частая причина «класс не найден»
const LOOKALIKE = { A: "А", B: "В", E: "Е", K: "К", M: "М", H: "Н", O: "О", P: "Р", C: "С", T: "Т", X: "Х", I: "І",
  a: "а", e: "е", o: "о", p: "р", c: "с", x: "х", y: "у", i: "і" };
const toCyr = (s) => String(s).replace(/[A-Za-z]/g, (ch) => LOOKALIKE[ch] || ch);
function classHint(name, classes) {
  const c = toCyr(name);
  return c !== name && classes.indexOf(c) >= 0 ? " (похоже, там латинская буква вместо кириллической — должно быть «" + c + "»)" : "";
}
const isList = Array.isArray;
const str = (v) => typeof v === "string";

if (D.year !== undefined && !str(D.year)) err("year: должен быть текст в кавычках, например \"2027–2028 навчальний рік\"");

// Звонки
const bells = D.bells;
if (!isList(bells) || !bells.length) err("bells: нет звонков — нужен список вида [\"08:30\", \"09:15\"]");
else bells.forEach((b, k) => {
  const n = "звонок " + (k + 1);
  if (!isList(b) || b.length !== 2) return err(n + ": нужно два времени — [\"начало\", \"конец\"]");
  if (!isTime(b[0]) || !isTime(b[1])) return err(n + ": время пишется как \"08:30\" (две цифры, двоеточие, две цифры), а сейчас " + JSON.stringify(b));
  if (mins(b[0]) >= mins(b[1])) err(n + ": начало " + b[0] + " не раньше конца " + b[1]);
  if (k > 0 && isList(bells[k - 1]) && isTime(bells[k - 1][1]) && mins(b[0]) < mins(bells[k - 1][1]))
    err(n + ": начинается в " + b[0] + ", раньше, чем закончился предыдущий урок (" + bells[k - 1][1] + ")");
});
const bellCount = isList(bells) ? bells.length : 0;

// Семестры и каникулы
function periods(list, field, needOne) {
  if (!isList(list)) return err(field + ": нужен список [ [\"с\", \"по\", \"название\"], ... ]");
  if (needOne && !list.length) return err(field + ": нужен хотя бы один период");
  list.forEach((p, k) => {
    const n = field + " " + (k + 1);
    if (!isList(p) || p.length !== 3) return err(n + ": нужно [\"с\", \"по\", \"название\"], а сейчас " + JSON.stringify(p));
    if (!isDate(p[0]) || !isDate(p[1])) return err(n + ": дата пишется как \"2027-01-13\" (год-месяц-день) и должна существовать, а сейчас " + JSON.stringify(p.slice(0, 2)));
    if (p[0] > p[1]) err(n + ": начало " + p[0] + " позже конца " + p[1]);
    if (!str(p[2]) || !p[2].trim()) err(n + ": нет названия");
  });
}
periods(D.terms, "terms", true);
periods(D.holidays, "holidays", false);
if (isList(D.terms)) for (let k = 1; k < D.terms.length; k++) {
  const a = D.terms[k - 1], b = D.terms[k];
  if (isList(a) && isList(b) && isDate(a[1]) && isDate(b[0]) && b[0] <= a[1])
    err("terms: семестр " + (k + 1) + " начинается (" + b[0] + ") раньше, чем заканчивается предыдущий (" + a[1] + ")");
}

// Классы и уроки
const classes = isList(D.classes) ? D.classes : [];
if (!isList(D.classes) || !D.classes.length) err("classes: нет списка классов, например [\"5\", \"6-А\"]");
else {
  const seen = new Set();
  D.classes.forEach((c) => {
    if (!str(c) || !c.trim()) err("classes: пустое или не текстовое название класса " + JSON.stringify(c));
    else if (seen.has(c)) err("classes: класс «" + c + "» записан дважды");
    seen.add(c);
  });
}
const sch = D.schedule;
if (!sch || typeof sch !== "object" || isList(sch)) err("schedule: нет расписания уроков { \"5\": [ ... ], ... }");
else {
  for (const c of classes) {
    if (!str(c)) continue;
    if (!Object.prototype.hasOwnProperty.call(sch, c)) {
      const near = Object.keys(sch).find((k) => toCyr(k) === toCyr(c) && k !== c);
      err("schedule: нет уроков для класса «" + c + "»" + (near ? " (есть «" + near + "» — похоже, где-то латинская буква вместо кириллической)" : ""));
      continue;
    }
    const week = sch[c];
    if (!isList(week) || week.length !== 5) {
      err("класс " + c + ": нужно ровно 5 дней (понедельник–пятница), а сейчас " + (isList(week) ? week.length : "не список"));
      continue;
    }
    week.forEach((day, d) => {
      const n = "класс " + c + ", " + DAYS[d];
      if (!isList(day)) return err(n + ": уроки дня — это список в [ ]");
      day.forEach((l, k) => { if (!str(l)) err(n + ", урок " + (k + 1) + ": название пишется в кавычках, а сейчас " + JSON.stringify(l)); });
      if (bellCount && day.length > bellCount)
        err(n + ": уроков " + day.length + ", а звонков только " + bellCount + " — лишние уроки не будут видны. Добавьте звонок в bells или уберите урок.");
      if (!day.some((l) => str(l) && l.trim())) warn(n + ": ни одного урока");
    });
  }
  for (const k of Object.keys(sch)) if (classes.indexOf(k) < 0)
    warn("schedule: класс «" + k + "» есть в расписании, но его нет в списке classes — в приложении его не выбрать" + classHint(k, classes));
}
function checkClasses(where, list) {
  if (!isList(list)) return err(where + ": cls должен быть списком классов, например [\"7-А\", \"8\"]");
  for (const c of list) if (classes.indexOf(c) < 0) err(where + ": класса «" + c + "» нет в списке classes" + classHint(c, classes));
}

// Питание
if (D.meals !== undefined) {
  if (!isList(D.meals)) err("meals: нужен список");
  else D.meals.forEach((m, k) => {
    const n = "meals, строка " + (k + 1);
    if (!m || typeof m !== "object") return err(n + ": нужно { \"who\": ..., \"time\": ..., \"cls\": [...] }");
    if (!str(m.who)) err(n + ": нет «who» — кто ест");
    if (!isTime(m.time)) err(n + ": время пишется как \"10:05\", а сейчас " + JSON.stringify(m.time));
    checkClasses(n, m.cls);
  });
}
// Эвакуация
if (D.evacuation !== undefined) {
  if (!isList(D.evacuation)) err("evacuation: нужен список этажей");
  else D.evacuation.forEach((f, k) => {
    const n = "evacuation, этаж " + (k + 1);
    if (!f || typeof f !== "object" || !str(f.floor) || !isList(f.rows)) return err(n + ": нужно { \"floor\": \"I поверх\", \"rows\": [ ... ] }");
    f.rows.forEach((r, j) => {
      const nn = n + ", строка " + (j + 1);
      if (!r || typeof r !== "object" || !str(r.who) || !str(r.route)) return err(nn + ": нужно { \"who\": ..., \"route\": ..., \"cls\": [...] }");
      checkClasses(nn, r.cls);
    });
  });
}
// Дежурства
if (D.duty !== undefined) {
  if (!isList(D.duty)) err("duty: нужен список дней");
  else D.duty.forEach((r, k) => {
    const n = "duty, строка " + (k + 1);
    if (!r || typeof r !== "object" || ![1, 2, 3, 4, 5].includes(r.day)) return err(n + ": «day» — номер дня от 1 (понедельник) до 5 (пятница)");
    if (!isList(r.items)) return err(n + ": нужен список «items»");
    r.items.forEach((it, j) => {
      if (!it || typeof it !== "object" || !str(it.cls)) return err(n + ", пункт " + (j + 1) + ": нужно { \"cls\": \"8\", \"where\": \"\" }");
      if (classes.indexOf(it.cls) < 0) err(n + ": класса «" + it.cls + "» нет в списке classes" + classHint(it.cls, classes));
      if (it.where !== undefined && !str(it.where)) err(n + ", пункт " + (j + 1) + ": «where» пишется в кавычках");
    });
  });
}
// Автор
if (D.author !== undefined) {
  const a = D.author;
  if (!a || typeof a !== "object") err("author: нужно { \"name\": \"ваш ник\", \"telegram\": \"имя_в_telegram\" }");
  else {
    if (a.name !== undefined && !str(a.name)) err("author.name: пишется в кавычках");
    if (a.telegram !== undefined) {
      if (!str(a.telegram)) err("author.telegram: пишется в кавычках");
      else {
        const u = a.telegram.trim().replace(/^https?:\/\//, "").replace(/^(t|telegram)\.me\//, "").replace(/^@/, "");
        if (u && !/^[A-Za-z0-9_]{5,32}$/.test(u))
          err("author.telegram: «" + a.telegram + "» не похоже на имя в Telegram (5–32 латинские буквы, цифры или _; можно с @)");
      }
    }
  }
}

finish();
const count = (n, one, few, many) => n + " " + (n % 10 === 1 && n % 100 !== 11 ? one : n % 10 >= 2 && n % 10 <= 4 && (n % 100 < 12 || n % 100 > 14) ? few : many);
console.log("✓ data.js в порядке: " + count(classes.length, "класс", "класса", "классов") + ", " +
  count(bellCount, "звонок", "звонка", "звонков") + ", " + count(D.terms.length, "семестр", "семестра", "семестров") + ", " +
  count((D.holidays || []).length, "период", "периода", "периодов") + " каникул" +
  (D.author && D.author.name ? ", автор: " + D.author.name : "") + ".");
