/*
  Збірка: робить код сторінки застосунку нечитабельним у готовому APK (стискає й перейменовує
  внутрішні імена), не чіпаючи вихідні файли в репозиторії. Запускається в GitHub Actions
  ПЕРЕД складанням APK — тож на телефон потрапляє стиснена версія, а правити ви далі правите звичайну.

  Запуск:  node tools/minify-assets.mjs [корінь-проєкту]
  Потрібен esbuild (CI ставить його сам).

  Що стискається: вбудований скрипт у app/src/main/assets/index.html, app.js, code.js, qr.js
  і сторінка посилань docs/index.html. data.js лишається як є — його ви редагуєте, і збірка його перевіряє.
  Межа між файлами — лише через window.* (ці імена esbuild не чіпає), тож стискання нічого не ламає.
*/
import { transform } from "esbuild";
import { readFile, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import path from "node:path";

const root = process.argv[2] || ".";
const opts = { minify: true, target: "es2015", legalComments: "none" };

async function minifyJs(code) {
  const r = await transform(code, opts);
  return r.code.replace(/\n+$/, "");
}

// Стискає кожен вбудований <script> … </script> (без src) у HTML-файлі.
async function minifyHtml(file) {
  let html = await readFile(file, "utf8");
  const parts = [];
  const re = /<script>([\s\S]*?)<\/script>/g;
  let m, last = 0, changed = false;
  while ((m = re.exec(html)) !== null) {
    const body = m[1];
    parts.push(html.slice(last, m.index));
    if (body.trim()) {
      parts.push("<script>" + (await minifyJs(body)) + "</script>");
      changed = true;
    } else {
      parts.push(m[0]);
    }
    last = re.lastIndex;
  }
  parts.push(html.slice(last));
  if (changed) await writeFile(file, parts.join(""));
  return changed;
}

async function minifyFile(file) {
  if (!existsSync(file)) return;
  const before = (await readFile(file, "utf8")).length;
  if (file.endsWith(".html")) {
    const changed = await minifyHtml(file);
    if (changed) console.log("  " + path.basename(file) + ": вбудований скрипт стиснено");
    return;
  }
  const code = await minifyJs(await readFile(file, "utf8"));
  await writeFile(file, code);
  console.log("  " + path.basename(file) + ": " + before + " → " + code.length + " байтів");
}

const assets = path.join(root, "app/src/main/assets");
console.log("Стискаю код сторінки застосунку:");
for (const f of ["app.js", "code.js", "qr.js"]) await minifyFile(path.join(assets, f));
await minifyFile(path.join(assets, "index.html"));
await minifyFile(path.join(root, "docs/index.html"));
console.log("Готово. Вихідні файли в репозиторії не змінено — стиснено лише копію для складання APK.");
