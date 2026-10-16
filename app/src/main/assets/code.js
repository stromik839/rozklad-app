/*
  Код розкладу — та сама схема, що ShareCode.java (у застосунку коди робить він, а цей файл —
  запасний варіант для перевірки сторінки в браузері; таку саму копію має сторінка посилань docs/index.html).
  «RZ1» + стиснутий JSON із контрольною сумою CRC32, записаний цифрами й великими латинськими літерами:
  кожні 5 байтів → 8 символів за основою 36 (останні 1–4 байти → 2, 4, 5 або 7 символів).
*/
(function (root) {
  "use strict";
  const B36 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
  const TAIL = [0, 2, 4, 5, 7];
  const NOT_FOUND = "Тут немає коду розкладу. Скопіюйте посилання або код повністю.";
  const DAMAGED = "Код неповний або пошкоджений. Скопіюйте посилання чи код ще раз — повністю.";

  function toB36(bytes) {
    let s = "";
    for (let i = 0; i < bytes.length; i += 5) {
      const len = Math.min(5, bytes.length - i);
      let v = 0;
      for (let k = 0; k < len; k++) v = v * 256 + bytes[i + k];
      const n = len === 5 ? 8 : TAIL[len];
      let g = "";
      for (let k = 0; k < n; k++) { g = B36[v % 36] + g; v = Math.floor(v / 36); }
      s += g;
    }
    return s;
  }
  function fromB36(s) {
    const full = Math.floor(s.length / 8), rc = s.length % 8, rb = TAIL.indexOf(rc);
    if (rb < 0) return null;
    const out = new Uint8Array(full * 5 + rb);
    let o = 0;
    for (let g = 0; g <= full; g++) {
      const chars = g < full ? 8 : rc, bytes = g < full ? 5 : rb;
      if (!chars) break;
      let v = 0;
      for (let k = 0; k < chars; k++) {
        const d = B36.indexOf(s[g * 8 + k]);
        if (d < 0) return null;
        v = v * 36 + d;
      }
      if (v >= Math.pow(256, bytes)) return null;
      for (let k = bytes - 1; k >= 0; k--) { out[o + k] = v % 256; v = Math.floor(v / 256); }
      o += bytes;
    }
    return out;
  }
  let table = null;
  function crc32(bytes) {
    if (!table) {
      table = new Uint32Array(256);
      for (let n = 0; n < 256; n++) {
        let c = n;
        for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
        table[n] = c >>> 0;
      }
    }
    let c = 0xffffffff;
    for (let i = 0; i < bytes.length; i++) c = table[(c ^ bytes[i]) & 255] ^ (c >>> 8);
    return (c ^ 0xffffffff) >>> 0;
  }
  /** Код із тексту (посилання, повідомлення, сам код) або "". Регістр не важливий. */
  function find(text) {
    const m = String(text || "").toUpperCase().match(/RZ1[0-9A-Z]{8,}/g);
    return m ? m.reduce((a, b) => (b.length > a.length ? b : a)) : "";
  }
  async function pipe(bytes, stream) {
    const s = new Blob([bytes]).stream().pipeThrough(stream);
    return new Uint8Array(await new Response(s).arrayBuffer());
  }
  const supported = () => typeof CompressionStream === "function" && typeof DecompressionStream === "function";

  async function encode(json) {
    if (!supported()) throw new Error("Цей браузер не вміє стискати дані.");
    const raw = new TextEncoder().encode(json);
    const z = await pipe(raw, new CompressionStream("deflate-raw"));
    const c = crc32(raw);
    const all = new Uint8Array(z.length + 4);
    all.set(z);
    all.set([c >>> 24, (c >>> 16) & 255, (c >>> 8) & 255, c & 255], z.length);
    return "RZ1" + toB36(all);
  }
  async function decode(text) {
    const code = find(text);
    if (!code) throw new Error(NOT_FOUND);
    const bytes = fromB36(code.slice(3));
    if (!bytes || bytes.length < 6) throw new Error(DAMAGED);
    if (!supported()) throw new Error("Цей браузер не вміє розпаковувати дані.");
    let raw;
    try {
      raw = await pipe(bytes.slice(0, bytes.length - 4), new DecompressionStream("deflate-raw"));
    } catch (e) {
      throw new Error(DAMAGED);
    }
    const n = bytes.length;
    const stored = ((bytes[n - 4] << 24) | (bytes[n - 3] << 16) | (bytes[n - 2] << 8) | bytes[n - 1]) >>> 0;
    if (crc32(raw) !== stored) throw new Error(DAMAGED);
    return new TextDecoder().decode(raw);
  }

  const api = { find, toB36, fromB36, crc32, encode, decode };
  root.RozkladCode = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})(typeof window !== "undefined" ? window : globalThis);
