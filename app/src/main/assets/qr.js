/*
  QR-коди без сторонніх бібліотек — за стандартом ISO/IEC 18004 (версії 1–40, рівні корекції L, M, Q, H).
  Уміє два види сегментів: байти (будь-який текст, UTF-8) і великі латинські літери з цифрами
  (компактніше — так записано код розкладу). Результат — квадрат із модулів, який сторінка малює як SVG.
*/
(function (root) {
  "use strict";

  // Кількість байтів корекції в одному блоці і кількість блоків: [рівень][версія] (версія 0 не існує).
  const ECC_PER_BLOCK = [
    [-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30],
    [-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28],
    [-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30],
    [-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30]
  ];
  const NUM_BLOCKS = [
    [-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25],
    [-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49],
    [-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68],
    [-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81]
  ];
  const LEVELS = ["L", "M", "Q", "H"];
  const FORMAT_BITS = [1, 0, 3, 2];            // як рівні L, M, Q, H записуються в службовій зоні
  const ALNUM = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";
  const MODE_BYTE = 0x4, MODE_ALNUM = 0x2;

  /* ---------- Сегменти ---------- */
  function utf8(str) {
    const out = [];
    for (let i = 0; i < str.length; i++) {
      let c = str.codePointAt(i);
      if (c > 0xffff) i++;
      if (c < 0x80) out.push(c);
      else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 63));
      else if (c < 0x10000) out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
      else out.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
    }
    return out;
  }
  function pushBits(arr, val, len) {
    for (let i = len - 1; i >= 0; i--) arr.push((val >>> i) & 1);
  }
  function byteSegment(str) {
    const data = utf8(String(str));
    const bits = [];
    for (const b of data) pushBits(bits, b, 8);
    return { mode: MODE_BYTE, count: data.length, bits };
  }
  const isAlnum = (str) => /^[0-9A-Z $%*+\-./:]*$/.test(str);
  function alnumSegment(str) {
    str = String(str);
    if (!isAlnum(str)) throw new Error("QR: недопустимі символи для буквено-цифрового сегмента");
    const bits = [];
    let i = 0;
    for (; i + 2 <= str.length; i += 2) pushBits(bits, ALNUM.indexOf(str[i]) * 45 + ALNUM.indexOf(str[i + 1]), 11);
    if (i < str.length) pushBits(bits, ALNUM.indexOf(str[i]), 6);
    return { mode: MODE_ALNUM, count: str.length, bits };
  }
  function countBits(mode, ver) {
    const k = ver <= 9 ? 0 : ver <= 26 ? 1 : 2;
    return mode === MODE_BYTE ? [8, 16, 16][k] : [9, 11, 13][k];
  }
  function totalBits(segs, ver) {
    let n = 0;
    for (const s of segs) {
      const cb = countBits(s.mode, ver);
      if (s.count >= (1 << cb)) return Infinity;
      n += 4 + cb + s.bits.length;
    }
    return n;
  }

  /* ---------- Місткість ---------- */
  function rawModules(ver) {
    let r = (16 * ver + 128) * ver + 64;
    if (ver >= 2) {
      const na = Math.floor(ver / 7) + 2;
      r -= (25 * na - 10) * na - 55;
      if (ver >= 7) r -= 36;
    }
    return r;
  }
  const dataCodewords = (ver, e) => Math.floor(rawModules(ver) / 8) - ECC_PER_BLOCK[e][ver] * NUM_BLOCKS[e][ver];

  /* ---------- Рід-Соломон у полі GF(256) ---------- */
  function rsMul(x, y) {
    let z = 0;
    for (let i = 7; i >= 0; i--) {
      z = (z << 1) ^ ((z >>> 7) * 0x11d);
      z ^= ((y >>> i) & 1) * x;
    }
    return z;
  }
  function rsDivisor(degree) {
    const r = new Array(degree).fill(0);
    r[degree - 1] = 1;
    let root = 1;
    for (let i = 0; i < degree; i++) {
      for (let j = 0; j < r.length; j++) {
        r[j] = rsMul(r[j], root);
        if (j + 1 < r.length) r[j] ^= r[j + 1];
      }
      root = rsMul(root, 0x02);
    }
    return r;
  }
  function rsRemainder(data, div) {
    const r = new Array(div.length).fill(0);
    for (const b of data) {
      const f = b ^ r.shift();
      r.push(0);
      for (let i = 0; i < div.length; i++) r[i] ^= rsMul(div[i], f);
    }
    return r;
  }

  /* ---------- Матриця ---------- */
  function alignPositions(ver, size) {
    if (ver === 1) return [];
    const na = Math.floor(ver / 7) + 2;
    const step = Math.floor((ver * 8 + na * 3 + 5) / (na * 4 - 4)) * 2;
    const out = [6];
    for (let pos = size - 7; out.length < na; pos -= step) out.splice(1, 0, pos);
    return out;
  }

  function build(ver, e, codewords, forcedMask) {
    const size = ver * 4 + 17;
    const mod = new Uint8Array(size * size);
    const fn = new Uint8Array(size * size);
    const set = (x, y, dark) => { mod[y * size + x] = dark ? 1 : 0; fn[y * size + x] = 1; };

    // Смуги синхронізації
    for (let i = 0; i < size; i++) { set(6, i, i % 2 === 0); set(i, 6, i % 2 === 0); }
    // Три пошукові квадрати з білою облямівкою
    const finder = (cx, cy) => {
      for (let dy = -4; dy <= 4; dy++) for (let dx = -4; dx <= 4; dx++) {
        const x = cx + dx, y = cy + dy;
        if (x < 0 || y < 0 || x >= size || y >= size) continue;
        const d = Math.max(Math.abs(dx), Math.abs(dy));
        set(x, y, d !== 2 && d !== 4);
      }
    };
    finder(3, 3); finder(size - 4, 3); finder(3, size - 4);
    // Вирівнювальні квадрати
    const al = alignPositions(ver, size);
    for (let i = 0; i < al.length; i++) for (let j = 0; j < al.length; j++) {
      if ((i === 0 && j === 0) || (i === 0 && j === al.length - 1) || (i === al.length - 1 && j === 0)) continue;
      for (let dy = -2; dy <= 2; dy++) for (let dx = -2; dx <= 2; dx++) {
        set(al[i] + dx, al[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
      }
    }
    // Місця під формат (заповнюються пізніше) і версію
    const drawFormat = (mask) => {
      const data = (FORMAT_BITS[e] << 3) | mask;
      let rem = data;
      for (let i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
      const bits = ((data << 10) | rem) ^ 0x5412;
      const bit = (i) => ((bits >>> i) & 1) !== 0;
      for (let i = 0; i <= 5; i++) set(8, i, bit(i));
      set(8, 7, bit(6));
      set(8, 8, bit(7));
      set(7, 8, bit(8));
      for (let i = 9; i < 15; i++) set(14 - i, 8, bit(i));
      for (let i = 0; i < 8; i++) set(size - 1 - i, 8, bit(i));
      for (let i = 8; i < 15; i++) set(8, size - 15 + i, bit(i));
      set(8, size - 8, true);
    };
    drawFormat(0);
    if (ver >= 7) {
      let rem = ver;
      for (let i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1f25);
      const bits = (ver << 12) | rem;
      for (let i = 0; i < 18; i++) {
        const dark = ((bits >>> i) & 1) !== 0;
        const a = size - 11 + (i % 3), b = Math.floor(i / 3);
        set(a, b, dark);
        set(b, a, dark);
      }
    }
    // Дані зигзагом знизу справа
    let i = 0;
    const nbits = codewords.length * 8;
    for (let right = size - 1; right >= 1; right -= 2) {
      if (right === 6) right = 5;
      for (let vert = 0; vert < size; vert++) {
        for (let j = 0; j < 2; j++) {
          const x = right - j;
          const up = ((right + 1) & 2) === 0;
          const y = up ? size - 1 - vert : vert;
          if (!fn[y * size + x] && i < nbits) {
            mod[y * size + x] = (codewords[i >>> 3] >>> (7 - (i & 7))) & 1;
            i++;
          }
        }
      }
    }
    const applyMask = (m) => {
      for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) {
        if (fn[y * size + x]) continue;
        let inv;
        switch (m) {
          case 0: inv = (x + y) % 2 === 0; break;
          case 1: inv = y % 2 === 0; break;
          case 2: inv = x % 3 === 0; break;
          case 3: inv = (x + y) % 3 === 0; break;
          case 4: inv = (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0; break;
          case 5: inv = ((x * y) % 2) + ((x * y) % 3) === 0; break;
          case 6: inv = (((x * y) % 2) + ((x * y) % 3)) % 2 === 0; break;
          default: inv = (((x + y) % 2) + ((x * y) % 3)) % 2 === 0;
        }
        if (inv) mod[y * size + x] ^= 1;
      }
    };
    let mask = forcedMask;
    if (mask === undefined || mask < 0) {
      let best = Infinity;
      for (let m = 0; m < 8; m++) {
        applyMask(m);
        drawFormat(m);
        const p = penalty(mod, size);
        if (p < best) { best = p; mask = m; }
        applyMask(m);   // повторне накладання прибирає маску
      }
    }
    applyMask(mask);
    drawFormat(mask);
    return { version: ver, size, ecc: LEVELS[e], mask, modules: mod };
  }

  // Штраф маски: однакові ряди, квадрати 2×2, схожість на пошукові квадрати, баланс темного і світлого.
  function penalty(mod, size) {
    let score = 0;
    const at = (x, y) => mod[y * size + x];
    const addHistory = (len, h) => { if (h[0] === 0) len += size; h.pop(); h.unshift(len); };
    const countPatterns = (h) => {
      const n = h[1];
      const core = n > 0 && h[2] === n && h[3] === n * 3 && h[4] === n && h[5] === n;
      return (core && h[0] >= n * 4 && h[6] >= n ? 1 : 0) + (core && h[6] >= n * 4 && h[0] >= n ? 1 : 0);
    };
    const finish = (color, len, h) => {
      if (color) { addHistory(len, h); len = 0; }
      len += size;
      addHistory(len, h);
      return countPatterns(h);
    };
    for (let pass = 0; pass < 2; pass++) {
      for (let a = 0; a < size; a++) {
        let color = 0, run = 0;
        const h = [0, 0, 0, 0, 0, 0, 0];
        for (let b = 0; b < size; b++) {
          const v = pass === 0 ? at(b, a) : at(a, b);
          if (v === color) {
            run++;
            if (run === 5) score += 3;
            else if (run > 5) score++;
          } else {
            addHistory(run, h);
            if (!color) score += countPatterns(h) * 40;
            color = v;
            run = 1;
          }
        }
        score += finish(color, run, h) * 40;
      }
    }
    for (let y = 0; y < size - 1; y++) for (let x = 0; x < size - 1; x++) {
      const c = at(x, y);
      if (c === at(x + 1, y) && c === at(x, y + 1) && c === at(x + 1, y + 1)) score += 3;
    }
    let dark = 0;
    for (let k = 0; k < mod.length; k++) dark += mod[k];
    const total = size * size;
    score += (Math.ceil(Math.abs(dark * 20 - total * 10) / total) - 1) * 10;
    return score;
  }

  /**
   * Найменший QR-код для сегментів. minEcc — найнижчий рівень корекції ("L", "M", "Q", "H");
   * якщо в тій самій версії вміщається вищий рівень, береться він. null — дані не вміщаються навіть у версію 40.
   */
  function encode(segs, minEcc, maxVersion, forcedMask) {
    let e = Math.max(0, LEVELS.indexOf(minEcc || "M"));
    const maxV = Math.min(40, maxVersion || 40);
    let ver = 0, used = 0;
    for (let v = 1; v <= maxV; v++) {
      const bits = totalBits(segs, v);
      if (bits <= dataCodewords(v, e) * 8) { ver = v; used = bits; break; }
    }
    if (!ver) return null;
    for (let k = e + 1; k < 4; k++) if (used <= dataCodewords(ver, k) * 8) e = k;

    const bits = [];
    for (const s of segs) {
      pushBits(bits, s.mode, 4);
      pushBits(bits, s.count, countBits(s.mode, ver));
      for (const b of s.bits) bits.push(b);
    }
    const cap = dataCodewords(ver, e) * 8;
    pushBits(bits, 0, Math.min(4, cap - bits.length));
    pushBits(bits, 0, (8 - (bits.length % 8)) % 8);
    for (let pad = 0xec; bits.length < cap; pad ^= 0xec ^ 0x11) pushBits(bits, pad, 8);
    const data = [];
    for (let i = 0; i < bits.length; i += 8) {
      let b = 0;
      for (let k = 0; k < 8; k++) b = (b << 1) | bits[i + k];
      data.push(b);
    }

    // Блоки з корекцією і перемежування
    const nb = NUM_BLOCKS[e][ver], eccLen = ECC_PER_BLOCK[e][ver];
    const raw = Math.floor(rawModules(ver) / 8);
    const nShort = nb - (raw % nb);
    const shortLen = Math.floor(raw / nb);
    const div = rsDivisor(eccLen);
    const blocks = [];
    for (let i = 0, k = 0; i < nb; i++) {
      const dat = data.slice(k, k + shortLen - eccLen + (i < nShort ? 0 : 1));
      k += dat.length;
      const ecc = rsRemainder(dat, div);
      if (i < nShort) dat.push(0);
      blocks.push(dat.concat(ecc));
    }
    const cw = [];
    for (let i = 0; i < blocks[0].length; i++) {
      for (let j = 0; j < blocks.length; j++) {
        if (i !== shortLen - eccLen || j >= nShort) cw.push(blocks[j][i]);
      }
    }
    return build(ver, e, cw, forcedMask);
  }

  /** Контур темних модулів одним шляхом SVG (поруч стоять зливаються в смуги). border — тиха зона в модулях. */
  function svgPath(qr, border) {
    const b = border === undefined ? 4 : border;
    let d = "";
    for (let y = 0; y < qr.size; y++) {
      let x = 0;
      while (x < qr.size) {
        if (!qr.modules[y * qr.size + x]) { x++; continue; }
        const x0 = x;
        while (x < qr.size && qr.modules[y * qr.size + x]) x++;
        d += "M" + (x0 + b) + " " + (y + b) + "h" + (x - x0) + "v1h-" + (x - x0) + "z";
      }
    }
    return d;
  }

  const api = { encode, bytes: byteSegment, alnum: alnumSegment, isAlnum, svgPath };
  root.QR = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})(typeof window !== "undefined" ? window : globalThis);
