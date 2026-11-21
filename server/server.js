'use strict';
/**
 * FreeAI Cloud v0.5 — единый сервер без зависимостей (Node 18+).
 *   /v1/*              прокси к твоему FreeLLMAPI; с полем "freeai" в запросе включается серверный «мозг»
 *   /v1/search         веб-поиск, /v1/memory — память (из /sync)
 *   /sync              синхронизация чатов и памяти между устройствами
 *   /alerts            алерты по крипте и ценам подарков, проверка 24/7, уведомления в Telegram
 *   /gifts/*           цены Telegram-подарков (Fragment, Portals, MRKT)
 *   Telegram-бот       чат с ИИ, код, агенты, курсы, подарки (TG_BOT_TOKEN + TG_OWNER_IDS)
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { Readable } = require('stream');
const llm = require('./lib/llm');
const brain = require('./lib/brain');
const tools = require('./lib/tools');
const gifts = require('./lib/gifts');
const telegram = require('./lib/telegram');
const media = require('./lib/media');
const tasks = require('./lib/tasks');

const VERSION = '0.6.0';
const PORT = Number(process.env.PORT || 8080);
const TOKEN = process.env.TOKEN || '';
const UPSTREAM_URL = (process.env.UPSTREAM_URL || '').replace(/\/+$/, '');
const UPSTREAM_KEY = process.env.UPSTREAM_KEY || '';
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data');
const POLL_SECONDS = Math.max(15, Number(process.env.POLL_SECONDS || 60));
const COINGECKO_URL = (process.env.COINGECKO_URL || 'https://api.coingecko.com/api/v3').replace(/\/+$/, '');
const TELEGRAM_API = (process.env.TELEGRAM_API || 'https://api.telegram.org').replace(/\/+$/, '');
const BOT_TOKEN = process.env.TG_BOT_TOKEN || '';
const OWNERS = (process.env.TG_OWNER_IDS || '').split(',').map((s) => s.trim()).filter(Boolean);
const MAX_BODY = 5 * 1024 * 1024;

if (TOKEN.length < 16) {
  console.error('Задай TOKEN (минимум 16 символов): это пароль для приложения.');
  process.exit(1);
}
fs.mkdirSync(DATA_DIR, { recursive: true });
const SYNC_FILE = path.join(DATA_DIR, 'sync.json');
const ALERTS_FILE = path.join(DATA_DIR, 'alerts.json');

const sha = (s) => crypto.createHash('sha256').update(String(s)).digest();
const TOKEN_HASH = sha(TOKEN);
const fails = new Map();

const readJson = (file, fallback) => { try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return fallback; } };
function writeJson(file, obj) {
  const tmp = file + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(obj), { mode: 0o600 });
  fs.renameSync(tmp, file);
}
function send(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'content-length': Buffer.byteLength(body) });
  res.end(body);
}
function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = []; let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY) { reject(Object.assign(new Error('too large'), { status: 413 })); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function authorized(req) {
  const ip = req.socket.remoteAddress || '?';
  const f = fails.get(ip);
  if (f && f.n >= 20 && Date.now() - f.t < 600000) return null;
  const m = /^Bearer (.+)$/.exec(req.headers['authorization'] || '');
  const ok = !!m && crypto.timingSafeEqual(sha(m[1]), TOKEN_HASH);
  if (!ok) { fails.set(ip, { n: (f && Date.now() - f.t < 600000 ? f.n : 0) + 1, t: Date.now() }); return false; }
  fails.delete(ip);
  return true;
}

// ---------- прокси к апстриму ----------
async function proxy(req, res, bufferedBody) {
  if (!UPSTREAM_URL) return send(res, 503, { error: 'UPSTREAM_URL не задан' });
  const target = UPSTREAM_URL + req.url.replace(/^\/v1/, '');
  const headers = {};
  for (const k of ['content-type', 'accept']) if (req.headers[k]) headers[k] = req.headers[k];
  if (UPSTREAM_KEY) headers['authorization'] = 'Bearer ' + UPSTREAM_KEY;
  const hasBody = !['GET', 'HEAD'].includes(req.method);
  const ac = new AbortController();
  res.on('close', () => ac.abort());
  let r;
  try {
    const body = hasBody ? (bufferedBody != null ? bufferedBody : Readable.toWeb(req)) : undefined;
    r = await fetch(target, { method: req.method, headers, body, duplex: 'half', signal: ac.signal });
  } catch {
    if (!res.headersSent) send(res, 502, { error: 'upstream unreachable' });
    return;
  }
  const out = {};
  r.headers.forEach((v, k) => { if (!['content-encoding', 'content-length', 'transfer-encoding', 'connection'].includes(k)) out[k] = v; });
  res.writeHead(r.status, out);
  if (!r.body) return res.end();
  Readable.fromWeb(r.body).on('error', () => res.end()).pipe(res);
}

// ---------- алерты (крипта и подарки) ----------
function cleanRule(r) {
  if (!r || typeof r !== 'object') return null;
  const price = Number(r.price);
  const coin = String(r.coin || '').toLowerCase();
  if (!/^(gift:)?[a-z0-9а-яё_-]{1,60}$/.test(coin) || !Number.isFinite(price) || price <= 0) return null;
  return { id: String(r.id || crypto.randomUUID()).slice(0, 64), coin, above: !!r.above, price, fired: !!r.fired };
}
const loadAlerts = () => readJson(ALERTS_FILE, { telegram: { token: '', chat: '' }, rules: [] });
function tgTarget(data) {
  if (data.telegram.token && data.telegram.chat) return { token: data.telegram.token, chat: data.telegram.chat };
  if (BOT_TOKEN && OWNERS[0]) return { token: BOT_TOKEN, chat: OWNERS[0] };
  return null;
}

let polling = false;
async function pollAlerts() {
  if (polling) return;
  polling = true;
  try {
    const data = loadAlerts();
    const target = tgTarget(data);
    const active = data.rules.filter((r) => !r.fired);
    if (!active.length || !target) return;
    const hits = [];
    const coinRules = active.filter((r) => !r.coin.startsWith('gift:'));
    if (coinRules.length) {
      const ids = [...new Set(coinRules.map((r) => r.coin))];
      const resp = await fetch(`${COINGECKO_URL}/simple/price?ids=${encodeURIComponent(ids.join(','))}&vs_currencies=usd`);
      if (resp.ok) {
        const prices = await resp.json();
        for (const r of coinRules) {
          const p = prices[r.coin] && prices[r.coin].usd;
          if (typeof p === 'number' && ((r.above && p >= r.price) || (!r.above && p <= r.price))) hits.push([r, `🔔 ${r.coin}: ${p} USD (${r.above ? '≥' : '≤'} ${r.price})`]);
        }
      } else console.error('coingecko HTTP', resp.status);
    }
    for (const r of active.filter((x) => x.coin.startsWith('gift:'))) {
      try {
        const f = await gifts.giftFloor(r.coin.slice(5));
        if (f.best && ((r.above && f.best.price >= r.price) || (!r.above && f.best.price <= r.price))) {
          hits.push([r, `🎁 ${f.best.collection || r.coin.slice(5)}: ${f.best.price} GRAM на ${f.best.source} (${r.above ? '≥' : '≤'} ${r.price}). Цена продавца, комиссии не учтены.`]);
        }
      } catch (e) { console.error('gift alert:', e.message); }
    }
    const firedIds = new Set();
    for (const [r, text] of hits) {
      try { await telegram.sendMessage(target.token, target.chat, text); firedIds.add(r.id); } catch (e) { console.error('telegram:', e.message); }
    }
    if (firedIds.size) {
      const fresh = loadAlerts();
      for (const r of fresh.rules) if (firedIds.has(r.id)) r.fired = true;
      writeJson(ALERTS_FILE, fresh);
    }
  } catch (e) { console.error('poll:', e.message); } finally { polling = false; }
}

// ---------- HTTP ----------
const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://x');
    if (url.pathname === '/health') {
      return send(res, 200, { ok: true, freeai: true, version: VERSION, features: { upstream: llm.configured(), telegramBot: !!(BOT_TOKEN && OWNERS.length), gifts: gifts.configured(), media: media.configured(), tools: tools.ALL } });
    }
    const auth = authorized(req);
    if (auth === null) return send(res, 429, { error: 'too many attempts' });
    if (!auth) return send(res, 401, { error: 'unauthorized' });

    if (url.pathname === '/v1/chat/completions' && req.method === 'POST') {
      const raw = await readBody(req);
      let body; try { body = JSON.parse(raw); } catch { return send(res, 400, { error: 'bad json' }); }
      if (!body.freeai || typeof body.freeai !== 'object') return await proxy(req, res, raw);
      const f = body.freeai;
      const r = await brain.run({ mode: f.mode, model: body.model, messages: body.messages, tools: f.tools, notes: f.notes, style: f.style, specs: f.specs, deep: f.deep, council: f.council });
      return send(res, 200, {
        id: 'chatcmpl-' + crypto.randomBytes(5).toString('hex'), object: 'chat.completion', created: Math.floor(Date.now() / 1000), model: r.model,
        choices: [{ index: 0, message: { role: 'assistant', content: r.text }, finish_reason: 'stop' }],
        freeai: { toolCalls: r.toolCalls, trace: r.trace, kind: r.kind }
      });
    }
    if (url.pathname === '/v1/images' && req.method === 'POST') {
      const b = JSON.parse(await readBody(req) || '{}');
      try { return send(res, 200, await media.genImage(b)); } catch (e) { return send(res, e.status || 502, { error: e.message }); }
    }
    if (url.pathname === '/v1/videos' && req.method === 'POST') {
      const b = JSON.parse(await readBody(req) || '{}');
      try { return send(res, 202, { id: media.startVideo(b), status: 'running' }); } catch (e) { return send(res, e.status || 502, { error: e.message }); }
    }
    let mv = /^\/v1\/videos\/([a-f0-9]{16})$/.exec(url.pathname);
    if (mv && req.method === 'GET') { const st = media.videoStatus(mv[1]); return st ? send(res, 200, st) : send(res, 404, { error: 'unknown job' }); }
    mv = /^\/media\/([a-f0-9]{16})\.mp4$/.exec(url.pathname);
    if (mv && req.method === 'GET') {
      const p = media.mediaPath(mv[1]);
      if (!p) return send(res, 404, { error: 'not found' });
      res.writeHead(200, { 'content-type': 'video/mp4', 'content-length': fs.statSync(p).size });
      return fs.createReadStream(p).pipe(res);
    }
    if (url.pathname === '/tasks' && req.method === 'GET') return send(res, 200, tasks.list());
    if (url.pathname === '/tasks' && req.method === 'POST') {
      try { return send(res, 200, tasks.add(JSON.parse(await readBody(req) || '{}'))); } catch (e) { return send(res, e.status || 400, { error: e.message }); }
    }
    if (url.pathname === '/tasks/pause' && req.method === 'POST') return send(res, 200, { paused: tasks.pause(!!JSON.parse(await readBody(req) || '{}').paused) });
    mv = /^\/tasks\/([a-f0-9]{8})(\/run)?$/.exec(url.pathname);
    if (mv && req.method === 'DELETE') return send(res, tasks.remove(mv[1]) ? 200 : 404, { ok: true });
    if (mv && mv[2] && req.method === 'POST') {
      try { return send(res, 200, await tasks.runOne(mv[1], notifyOwner, savedMemory)); } catch (e) { return send(res, e.status || 502, { error: e.message }); }
    }
    if (url.pathname === '/gifts/diagnose') return send(res, 200, await gifts.diagnose());
    if (url.pathname === '/gifts/raw') {
      try { return send(res, 200, { source: url.searchParams.get('source'), raw: await gifts.raw(url.searchParams.get('source')) }); } catch (e) { return send(res, 400, { error: e.message }); }
    }
    if (url.pathname === '/v1/search' && req.method === 'POST') {
      const b = JSON.parse(await readBody(req) || '{}');
      const q = b.query || b.q;
      if (!q) return send(res, 400, { error: 'query required' });
      return send(res, 200, { query: q, result: await tools.webSearch(q) });
    }
    if (url.pathname === '/v1/memory') {
      const sync = readJson(SYNC_FILE, { chats: [], memory: null, deleted: [] });
      if (req.method === 'GET') return send(res, 200, sync.memory || { notes: [], style: '' });
      if (req.method === 'DELETE') { sync.memory = null; writeJson(SYNC_FILE, sync); return send(res, 200, { ok: true }); }
    }
    if (url.pathname === '/v1' || url.pathname.startsWith('/v1/')) return await proxy(req, res);

    if (url.pathname === '/gifts/floor' && req.method === 'GET') {
      try { const r = await gifts.giftFloor(url.searchParams.get('collection')); return send(res, 200, { ...r, text: gifts.formatFloor(r) }); }
      catch (e) { return send(res, 400, { error: e.message }); }
    }
    if (url.pathname === '/gifts/top' && req.method === 'GET') {
      try { return send(res, 200, { unit: 'GRAM', items: await gifts.fragmentTop(Math.min(30, Number(url.searchParams.get('n')) || 10)), attribution: gifts.ATTRIBUTION }); }
      catch (e) { return send(res, 502, { error: e.message }); }
    }
    if (url.pathname === '/gifts/sources') return send(res, 200, gifts.configured());

    if (url.pathname === '/sync') {
      if (req.method === 'GET') return send(res, 200, readJson(SYNC_FILE, { chats: [], memory: null, deleted: [] }));
      if (req.method === 'PUT') {
        const b = JSON.parse(await readBody(req));
        if (!b || !Array.isArray(b.chats)) return send(res, 400, { error: 'bad payload' });
        writeJson(SYNC_FILE, { chats: b.chats.slice(0, 200), memory: b.memory && typeof b.memory === 'object' ? b.memory : null, deleted: Array.isArray(b.deleted) ? b.deleted.slice(-500) : [] });
        return send(res, 200, { ok: true });
      }
    }
    if (url.pathname === '/alerts') {
      if (req.method === 'GET') return send(res, 200, { rules: loadAlerts().rules });
      if (req.method === 'PUT') {
        const b = JSON.parse(await readBody(req));
        const old = loadAlerts();
        const rules = (Array.isArray(b.rules) ? b.rules : []).slice(0, 200).map(cleanRule).filter(Boolean);
        for (const r of rules) { const o = old.rules.find((x) => x.id === r.id); if (o && o.fired) r.fired = true; }
        const tg = b.telegram || {};
        const telegramCfg = {
          token: typeof tg.token === 'string' && tg.token ? tg.token.slice(0, 200) : old.telegram.token,
          chat: typeof tg.chat === 'string' && tg.chat ? tg.chat.slice(0, 64) : old.telegram.chat
        };
        writeJson(ALERTS_FILE, { telegram: telegramCfg, rules });
        pollAlerts();
        return send(res, 200, { ok: true, rules: rules.length });
      }
    }
    send(res, 404, { error: 'not found' });
  } catch (e) {
    if (!res.headersSent) send(res, e.status || 400, { error: e.status === 413 ? 'payload too large' : (e.message || 'bad request').slice(0, 200) });
  }
});

const savedMemory = () => { const m = (readJson(SYNC_FILE, {}).memory) || {}; return { notes: Array.isArray(m.notes) ? m.notes : [], style: m.style || '' }; };
const notifyOwner = async (text) => { const t = tgTarget(loadAlerts()); if (t) await telegram.sendMessage(t.token, t.chat, text); };
server.listen(PORT, () => console.log(`FreeAI Cloud ${VERSION} слушает порт ${PORT}`));
tasks.start({ notify: notifyOwner, memory: savedMemory });
setInterval(media.cleanup, 3600 * 1000).unref();
setInterval(pollAlerts, POLL_SECONDS * 1000).unref();

if (BOT_TOKEN && OWNERS.length) {
  telegram.startBot({
    token: BOT_TOKEN, owners: OWNERS,
    host: {
      memory: savedMemory,
      tasks: { list: tasks.list, add: tasks.add, remove: tasks.remove, toggle: tasks.toggle, pause: tasks.pause, runOne: (id) => tasks.runOne(id, notifyOwner, savedMemory) },
      rules: () => loadAlerts().rules,
      addRule: (r) => { const d = loadAlerts(); const c = cleanRule({ id: crypto.randomUUID(), ...r }); if (c) { d.rules.push(c); writeJson(ALERTS_FILE, d); } },
      removeRule: (i) => { const d = loadAlerts(); if (!(i >= 0 && i < d.rules.length)) return false; d.rules.splice(i, 1); writeJson(ALERTS_FILE, d); return true; }
    }
  });
  console.log('Telegram-бот запущен');
}
