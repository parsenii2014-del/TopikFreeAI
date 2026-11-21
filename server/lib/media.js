'use strict';
// Фото и видео: Pollinations (gen.pollinations.ai, ключ enter.pollinations.ai) или любой OpenAI-совместимый API изображений.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const llm = require('./llm');

const POLL = (process.env.POLLINATIONS_URL || 'https://gen.pollinations.ai').replace(/\/+$/, '');
const POLL_KEY = process.env.POLLINATIONS_KEY || '';
const LEGACY = (process.env.POLLINATIONS_LEGACY_URL || 'https://image.pollinations.ai').replace(/\/+$/, '');
const IMG_URL = (process.env.IMAGE_API_URL || '').replace(/\/+$/, '');
const IMG_KEY = process.env.IMAGE_API_KEY || '';
const IMG_MODEL = process.env.IMAGE_MODEL || '';
const MEDIA_DIR = path.join(process.env.DATA_DIR || path.join(__dirname, '..', 'data'), 'media');
fs.mkdirSync(MEDIA_DIR, { recursive: true });

const clamp = (n, a, b) => Math.min(b, Math.max(a, Math.round(Number(n) || a)));
const jobs = new Map();

const SYS = {
  image: 'Ты промпт-инженер для генерации изображений. Перепиши запрос пользователя в одну подробную строку на английском: объект, композиция, ракурс, освещение, материалы, стиль, палитра, качество. Сохрани замысел пользователя и ничего не добавляй сверх него. Верни только промпт.',
  video: 'Ты промпт-инженер для генерации коротких видео. Перепиши запрос в одну подробную строку на английском: сцена, действие по времени, движение камеры, освещение, стиль. Сохрани замысел пользователя. Верни только промпт.'
};

async function enhance(prompt, kind) {
  try {
    const { text } = await llm.chatFallback(['auto:fast', 'auto'], [{ role: 'system', content: SYS[kind] }, { role: 'user', content: prompt }], 0.7);
    const t = text.trim().replace(/^["'`]+|["'`]+$/g, '');
    return t && t.length < 1500 ? t : prompt;
  } catch { return prompt; }
}

async function genImage(b) {
  const prompt = String(b.prompt || '').trim().slice(0, 1500);
  if (!prompt) throw Object.assign(new Error('prompt required'), { status: 400 });
  const width = clamp(b.width || 1024, 256, 2048), height = clamp(b.height || 1024, 256, 2048);
  const used = b.enhance === false ? prompt : await enhance(prompt, 'image');
  let buf, mime;
  if (IMG_URL) {
    const r = await fetch(IMG_URL + '/images/generations', {
      method: 'POST', headers: { ...(IMG_KEY ? { authorization: 'Bearer ' + IMG_KEY } : {}), 'content-type': 'application/json' },
      body: JSON.stringify({ model: b.model || IMG_MODEL || undefined, prompt: used, n: 1, size: `${width}x${height}`, response_format: 'b64_json' }), signal: AbortSignal.timeout(180000)
    });
    const j = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error('провайдер изображений: HTTP ' + r.status);
    const d = j.data && j.data[0];
    if (d && d.b64_json) { buf = Buffer.from(d.b64_json, 'base64'); mime = 'image/png'; }
    else if (d && d.url) { const ir = await fetch(d.url, { signal: AbortSignal.timeout(60000) }); mime = ir.headers.get('content-type') || 'image/png'; buf = Buffer.from(await ir.arrayBuffer()); }
    else throw new Error('провайдер не вернул изображение');
  } else {
    const q = new URLSearchParams({ width, height, nologo: 'true' });
    if (b.model) q.set('model', String(b.model)); if (b.seed != null) q.set('seed', String(b.seed));
    const url = POLL_KEY ? `${POLL}/image/${encodeURIComponent(used)}?${q}` : `${LEGACY}/prompt/${encodeURIComponent(used)}?${q}`;
    const r = await fetch(url, { headers: POLL_KEY ? { authorization: 'Bearer ' + POLL_KEY } : {}, signal: AbortSignal.timeout(180000) });
    if (!r.ok) throw new Error('провайдер изображений: HTTP ' + r.status + (POLL_KEY ? '' : ' (без ключа лимит жёсткий: возьми ключ на enter.pollinations.ai и задай POLLINATIONS_KEY)'));
    mime = r.headers.get('content-type') || 'image/jpeg';
    buf = Buffer.from(await r.arrayBuffer());
  }
  if (!/^image\//.test(mime)) throw new Error('провайдер вернул не изображение');
  if (buf.length > 12e6) throw new Error('изображение слишком большое');
  return { mime: mime.split(';')[0], b64: buf.toString('base64'), prompt: used };
}

function startVideo(b) {
  const prompt = String(b.prompt || '').trim().slice(0, 1500);
  if (!prompt) throw Object.assign(new Error('prompt required'), { status: 400 });
  if (!POLL_KEY) throw Object.assign(new Error('Для видео нужен POLLINATIONS_KEY (enter.pollinations.ai)'), { status: 503 });
  const id = crypto.randomBytes(8).toString('hex');
  jobs.set(id, { status: 'running', at: Date.now() });
  for (const k of [...jobs.keys()].slice(0, Math.max(0, jobs.size - 50))) jobs.delete(k);
  (async () => {
    try {
      const used = b.enhance === false ? prompt : await enhance(prompt, 'video');
      const q = new URLSearchParams({ duration: String(clamp(b.duration || 5, 2, 10)), aspectRatio: /^\d+:\d+$/.test(b.aspectRatio || '') ? b.aspectRatio : '16:9' });
      if (b.model) q.set('model', String(b.model));
      const r = await fetch(`${POLL}/video/${encodeURIComponent(used)}?${q}`, { headers: { authorization: 'Bearer ' + POLL_KEY }, signal: AbortSignal.timeout(600000) });
      if (!r.ok) throw new Error('провайдер видео: HTTP ' + r.status);
      const buf = Buffer.from(await r.arrayBuffer());
      if (buf.length > 80e6) throw new Error('видео слишком большое');
      if (!/^video\//.test(r.headers.get('content-type') || '')) throw new Error('провайдер вернул не видео');
      fs.writeFileSync(path.join(MEDIA_DIR, id + '.mp4'), buf);
      jobs.set(id, { status: 'done', prompt: used, bytes: buf.length });
    } catch (e) { jobs.set(id, { status: 'error', error: e.message }); }
  })();
  return id;
}

const videoStatus = (id) => jobs.get(id) || (fs.existsSync(path.join(MEDIA_DIR, id + '.mp4')) ? { status: 'done' } : null);
const mediaPath = (id) => { const p = path.join(MEDIA_DIR, id + '.mp4'); return fs.existsSync(p) ? p : null; };

function cleanup() {
  const limit = Date.now() - 7 * 86400000;
  for (const f of fs.readdirSync(MEDIA_DIR)) { const p = path.join(MEDIA_DIR, f); try { if (fs.statSync(p).mtimeMs < limit) fs.unlinkSync(p); } catch { /* ignore */ } }
}

const configured = () => ({ image: !!(IMG_URL || POLL_KEY) ? 'ключ задан' : 'анонимный бесплатный (жёсткий лимит)', video: !!POLL_KEY });

module.exports = { genImage, startVideo, videoStatus, mediaPath, cleanup, configured, MEDIA_DIR };
