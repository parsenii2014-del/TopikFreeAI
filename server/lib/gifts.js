'use strict';
const fs = require('fs');
/**
 * Цены на Telegram-подарки (только чтение).
 *  - Fragment: бесплатно, без ключей (открытый ежедневный датасет, CC BY 4.0 — Baltic Assets).
 *  - Portals: официальный Partner API (ключ PORTALS_API_KEY из мини-приложения → профиль → Partner API).
 *  - MRKT: неофициальный API, нужен токен твоей сессии MRKT_TOKEN (живёт не вечно).
 * Схемы ответов Portals/MRKT разобраны защитно: поля ищутся по нескольким именам.
 */
const PORTALS_URL = (process.env.PORTALS_URL || 'https://portal-market.com').replace(/\/+$/, '');
const PORTALS_KEY = process.env.PORTALS_API_KEY || '';
const MRKT_URL = (process.env.MRKT_URL || 'https://api.tgmrkt.io/api/v1').replace(/\/+$/, '');
const MRKT_TOKEN = process.env.MRKT_TOKEN || '';
const MRKT_TOKEN_FILE = process.env.MRKT_TOKEN_FILE || '';   // файл, который обновляет tools/mrkt_token.py
const MRKT_INIT = process.env.MRKT_INIT_DATA || '';          // initData мини-приложения MRKT: сервер сам получит токен через /auth
const FRAGMENT_CSV = process.env.FRAGMENT_CSV_URL || 'https://raw.githubusercontent.com/Atlannn/telegram-gifts-floor-price/main/data/floor_prices.csv';
const UA = 'FreeAI-Cloud/1.0 (read-only price checks)';
const ATTRIBUTION = 'Fragment: «Telegram gifts floor prices», Baltic Assets (balticassets.eu), CC BY 4.0';

const norm = (s) => String(s || '').toLowerCase().replace(/[^a-z0-9а-яё]/g, '');
const num = (v) => { const n = typeof v === 'string' ? parseFloat(v) : v; return Number.isFinite(n) ? n : null; };
const pick = (o, keys) => { for (const k of keys) { const n = o && num(o[k]); if (n != null) return n; } return null; };
const pickStr = (o, keys) => { for (const k of keys) if (o && typeof o[k] === 'string' && o[k]) return o[k]; return ''; };
const itemsOf = (j) => {
  if (Array.isArray(j)) return j;
  if (j && typeof j === 'object') for (const k of Object.keys(j)) if (Array.isArray(j[k]) && j[k].length && typeof j[k][0] === 'object') return j[k];
  return [];
};

const memo = new Map();
async function cached(key, ttl, fn) {
  const hit = memo.get(key);
  if (hit && Date.now() - hit.at < ttl) return hit.v;
  const v = await fn();
  memo.set(key, { at: Date.now(), v });
  return v;
}

async function http(url, opts = {}) {
  const r = await fetch(url, { ...opts, headers: { 'user-agent': UA, ...(opts.headers || {}) }, signal: AbortSignal.timeout(20000) });
  const text = await r.text();
  if (!r.ok) {
    const cf = r.status === 403 && /1010|cloudflare/i.test(text);
    throw new Error(cf ? 'доступ закрыт Cloudflare (HTTP 403): спроси разрешение у поддержки площадки' : 'HTTP ' + r.status);
  }
  try { return JSON.parse(text); } catch { return text; }
}

// ---------- Fragment (CSV-датасет) ----------
async function fragmentTable() {
  return cached('fragment', 30 * 60 * 1000, async () => {
    const csv = await http(FRAGMENT_CSV);
    if (typeof csv !== 'string') throw new Error('неожиданный формат датасета');
    const lines = csv.split(/\r?\n/).filter(Boolean);
    const head = lines.shift().split(',').map((h) => h.trim());
    const ix = (n) => head.indexOf(n);
    const [iD, iC, iF, iX, iL, iA] = ['date', 'collection', 'floor_gram', 'floor_fixed_gram', 'lots_seen', 'auctions_seen'].map(ix);
    if (iD < 0 || iC < 0 || iF < 0) throw new Error('неожиданные колонки датасета');
    let max = '';
    for (const l of lines) { const d = l.slice(0, l.indexOf(',')); if (d > max) max = d; }
    const rows = new Map();
    for (const l of lines) {
      const c = l.split(',');
      if (c[iD] !== max) continue;
      rows.set(norm(c[iC]), { slug: c[iC], floor: num(c[iF]), fixed: iX >= 0 ? num(c[iX]) : null, lots: iL >= 0 ? num(c[iL]) : null, auctions: iA >= 0 ? num(c[iA]) : null });
    }
    return { date: max, rows };
  });
}

async function fragment(q) {
  const t = await fragmentTable();
  const row = t.rows.get(norm(q));
  if (!row) {
    const near = [...t.rows.values()].filter((r) => norm(r.slug).includes(norm(q)) || norm(q).includes(norm(r.slug))).slice(0, 5).map((r) => r.slug);
    throw new Error('нет в Fragment' + (near.length ? '; похожие: ' + near.join(', ') : ''));
  }
  const price = row.fixed != null ? row.fixed : row.floor;
  return { source: 'fragment', collection: row.slug, price, note: row.fixed != null ? 'фикс-цена, данные на ' + t.date : 'только аукционы, данные на ' + t.date };
}

async function fragmentTop(n = 10) {
  const t = await fragmentTable();
  return [...t.rows.values()].filter((r) => (r.fixed ?? r.floor) != null).sort((a, b) => (b.fixed ?? b.floor) - (a.fixed ?? a.floor)).slice(0, n)
    .map((r) => ({ collection: r.slug, price: r.fixed ?? r.floor, auction: r.fixed == null, date: t.date }));
}

// ---------- Portals (официальный Partner API) ----------
async function portals(q) {
  const j = await cached('portals', 60 * 1000, () => http(PORTALS_URL + '/partners/collections/preview', { headers: { authorization: 'partners ' + PORTALS_KEY, accept: 'application/json' } }));
  const want = norm(q);
  const hit = itemsOf(j).find((o) => ['short_name', 'shortName', 'slug', 'name', 'title'].some((k) => norm(o[k]) === want)) ||
    itemsOf(j).find((o) => ['short_name', 'shortName', 'slug', 'name', 'title'].some((k) => o[k] && (norm(o[k]).includes(want) || want.includes(norm(o[k])))));
  if (!hit) throw new Error('коллекция не найдена в Portals');
  const price = pick(hit, ['floor_price', 'floorPrice', 'floor', 'min_price', 'minPrice', 'price']);
  if (price == null) throw new Error('в ответе Portals нет поля цены (схема могла измениться)');
  return { source: 'portals', collection: pickStr(hit, ['name', 'title', 'short_name', 'slug']), price, note: 'флор коллекции, Partner API' };
}

// ---------- MRKT (неофициальный API, токен пользователя) ----------
let mrktAuth = { token: '', at: 0 };
function mrktStaticToken() {
  if (MRKT_TOKEN_FILE) { try { const t = fs.readFileSync(MRKT_TOKEN_FILE, 'utf8').trim(); if (t) return t; } catch { /* файла ещё нет */ } }
  return MRKT_TOKEN;
}
async function mrktToken(force) {
  const st = mrktStaticToken();
  if (st && !force) return st;
  if (!MRKT_INIT) return st;
  if (!force && mrktAuth.token && Date.now() - mrktAuth.at < 12 * 3600 * 1000) return mrktAuth.token;
  const j = await http(MRKT_URL + '/auth', { method: 'POST', headers: { 'content-type': 'application/json', referer: 'https://cdn.tgmrkt.io/' }, body: JSON.stringify({ data: MRKT_INIT }) });
  const t = j && j.token;
  if (!t) throw new Error('MRKT /auth не вернул токен (initData мог протухнуть)');
  mrktAuth = { token: t, at: Date.now() };
  return t;
}

async function mrktCall(path, method, body, retry = true) {
  const token = await mrktToken(false);
  if (!token) throw new Error('MRKT не настроен');
  try {
    return await http(MRKT_URL + path, {
      method, headers: { authorization: token, referer: 'https://cdn.tgmrkt.io/', 'content-type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined
    });
  } catch (e) {
    if (/HTTP 40[13]/.test(e.message)) {
      if (retry && MRKT_INIT) { await mrktToken(true); return mrktCall(path, method, body, false); }
      throw new Error('токен MRKT недействителен или просрочен — обнови его (tools/mrkt_token.py)');
    }
    throw e;
  }
}

async function mrkt(q) {
  let name = q;
  try {
    const cols = await cached('mrkt-cols', 60 * 60 * 1000, () => mrktCall('/gifts/collections', 'GET'));
    const hit = itemsOf(cols).find((o) => norm(pickStr(o, ['name', 'title', 'collectionName'])) === norm(q));
    if (hit) name = pickStr(hit, ['name', 'title', 'collectionName']);
  } catch (e) { if (/токен/.test(e.message)) throw e; }
  const j = await cached('mrkt:' + norm(name), 30 * 1000, () => mrktCall('/gifts/saling', 'POST', {
    collectionNames: [name], modelNames: [], backdropNames: [], symbolNames: [], ordering: 'Price', lowToHigh: true,
    maxPrice: null, minPrice: null, mintable: null, number: null, count: 5, cursor: '', query: null, promotedFirst: false
  }));
  const gifts = Array.isArray(j && j.gifts) ? j.gifts : [];
  const prices = gifts.map((g) => pick(g, ['salePrice', 'price', 'priceNano', 'priceInNano', 'amount'])).filter((p) => p != null).map((p) => (p > 1e6 ? p / 1e9 : p));
  if (!prices.length) throw new Error('лотов не найдено (проверь точное название коллекции, например «Plush Pepe»)');
  return { source: 'mrkt', collection: name, price: Math.min(...prices), note: 'самый дешёвый из ' + gifts.length + ' лотов, единицы определены эвристически' };
}

// ---------- сводка ----------
const mrktOn = () => !!(MRKT_TOKEN || MRKT_TOKEN_FILE || MRKT_INIT);
const configured = () => ({ fragment: true, portals: !!PORTALS_KEY, mrkt: mrktOn() });

async function giftFloor(query) {
  const q = String(query || '').trim().slice(0, 80);
  if (!q) throw new Error('укажи коллекцию, например plushpepe');
  const jobs = [['fragment', fragment(q)]];
  if (PORTALS_KEY) jobs.push(['portals', portals(q)]);
  if (mrktOn()) jobs.push(['mrkt', mrkt(q)]);
  const res = await Promise.allSettled(jobs.map((j) => j[1]));
  const sources = res.map((r, i) => (r.status === 'fulfilled' ? r.value : { source: jobs[i][0], error: r.reason.message }));
  const priced = sources.filter((s) => s.price != null);
  const best = priced.length ? priced.reduce((a, b) => (b.price < a.price ? b : a)) : null;
  return { query: q, unit: 'GRAM', sources, best, attribution: ATTRIBUTION };
}

function formatFloor(r) {
  const lines = [`Подарки «${r.query}» (цены продавцов, не сделок; единица — GRAM, бывший TON):`];
  for (const s of r.sources) lines.push(s.error ? `• ${s.source}: ${s.error}` : `• ${s.source}: ${s.price} — ${s.note}`);
  if (r.best && r.sources.length > 1) lines.push(`Минимум: ${r.best.price} (${r.best.source})`);
  lines.push('Комиссии площадок не учтены. ' + r.attribution);
  return lines.join('\n');
}

// ---------- диагностика: показывает форму ответов площадок, без секретов ----------
const shape = (j) => {
  const it = itemsOf(j);
  return { type: Array.isArray(j) ? 'array' : typeof j, keys: j && typeof j === 'object' && !Array.isArray(j) ? Object.keys(j).slice(0, 15) : undefined, items: it.length, sampleKeys: it[0] && typeof it[0] === 'object' ? Object.keys(it[0]).slice(0, 25) : undefined };
};
const MRKT_PROBE = { collectionNames: [], modelNames: [], backdropNames: [], symbolNames: [], ordering: 'Price', lowToHigh: true, maxPrice: null, minPrice: null, mintable: null, number: null, count: 2, cursor: '', query: null, promotedFirst: false };

async function diagnose() {
  const out = {};
  try { const t = await fragmentTable(); out.fragment = { ok: true, date: t.date, collections: t.rows.size }; } catch (e) { out.fragment = { ok: false, error: e.message }; }
  if (!PORTALS_KEY) out.portals = { ok: false, error: 'PORTALS_API_KEY не задан' };
  else try { out.portals = { ok: true, ...shape(await http(PORTALS_URL + '/partners/collections/preview', { headers: { authorization: 'partners ' + PORTALS_KEY, accept: 'application/json' } })) }; } catch (e) { out.portals = { ok: false, error: e.message }; }
  if (!mrktOn()) out.mrkt = { ok: false, error: 'MRKT_TOKEN / MRKT_TOKEN_FILE / MRKT_INIT_DATA не заданы' };
  else try { const j = await mrktCall('/gifts/saling', 'POST', MRKT_PROBE); out.mrkt = { ok: true, ...shape(j), giftsShape: shape({ x: j && j.gifts }) }; if (Array.isArray(j && j.gifts) && j.gifts[0]) out.mrkt.giftKeys = Object.keys(j.gifts[0]).slice(0, 30); } catch (e) { out.mrkt = { ok: false, error: e.message }; }
  return out;
}

/** Сырой фрагмент ответа (до 3000 символов) для отладки разбора; секреты не включаются. */
async function raw(source) {
  let j;
  if (source === 'portals') { if (!PORTALS_KEY) throw new Error('PORTALS_API_KEY не задан'); j = await http(PORTALS_URL + '/partners/collections/preview', { headers: { authorization: 'partners ' + PORTALS_KEY, accept: 'application/json' } }); }
  else if (source === 'mrkt') { if (!mrktOn()) throw new Error('MRKT не настроен'); j = await mrktCall('/gifts/saling', 'POST', MRKT_PROBE); }
  else throw new Error('source: portals или mrkt');
  return JSON.stringify(j).slice(0, 3000);
}

module.exports = { giftFloor, formatFloor, fragmentTop, configured, norm, diagnose, raw, ATTRIBUTION };
