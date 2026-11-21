'use strict';
// Доступ к апстриму (FreeLLMAPI или любой OpenAI-совместимый API) + эвристический выбор модели.
const URL_ = (process.env.UPSTREAM_URL || '').replace(/\/+$/, '');
const KEY = process.env.UPSTREAM_KEY || '';
const auth = () => (KEY ? { authorization: 'Bearer ' + KEY } : {});

let cache = { at: 0, ids: [] };
async function listModels() {
  if (!URL_) return [];
  if (cache.ids.length && Date.now() - cache.at < 300000) return cache.ids;
  try {
    const r = await fetch(URL_ + '/models?available=true', { headers: auth(), signal: AbortSignal.timeout(15000) });
    if (r.ok) { const j = await r.json(); cache = { at: Date.now(), ids: (j.data || []).map((m) => m.id).filter(Boolean) }; }
  } catch { /* список моделей необязателен */ }
  return cache.ids;
}

async function chatOnce(model, messages, temperature) {
  if (!URL_) throw Object.assign(new Error('UPSTREAM_URL не задан'), { status: 503 });
  const body = { model, messages, stream: false };
  if (temperature != null) body.temperature = temperature;
  const r = await fetch(URL_ + '/chat/completions', {
    method: 'POST', headers: { ...auth(), 'content-type': 'application/json' }, body: JSON.stringify(body), signal: AbortSignal.timeout(150000)
  });
  const raw = await r.text();
  if (!r.ok) throw Object.assign(new Error('upstream HTTP ' + r.status + ': ' + raw.slice(0, 200)), { status: r.status });
  let text = '';
  try { text = JSON.parse(raw).choices[0].message.content; } catch { /* ниже */ }
  if (!text || !String(text).trim()) throw Object.assign(new Error('пустой ответ модели'), { status: 502 });
  return String(text);
}

/** Пробует модели по очереди; 401/403 пробрасываются сразу. */
async function chatFallback(candidates, messages, temperature) {
  let last;
  for (const m of candidates.slice(0, 4)) {
    try { return { text: await chatOnce(m, messages, temperature), model: m }; }
    catch (e) { last = e; if (e.status === 401 || e.status === 403) throw e; }
  }
  throw last || new Error('нет доступных моделей');
}

// ---------- эвристика выбора модели (по названиям) ----------
const FAST = ['flash', 'mini', 'haiku', 'small', '8b', '7b', 'lite', 'instant', 'nano', 'fast', 'turbo'];
const SMART = ['pro', 'opus', 'sonnet', '70b', '72b', '405b', 'large', 'r1', 'reason', 'think', 'o1', 'o3', 'gpt-4', 'gemini', 'qwen3', 'deepseek', 'llama-3.3', 'maverick'];
const CODE = ['coder', 'code', 'codestral', 'deepseek', 'qwen'];
const has = (s, list) => list.some((w) => s.includes(w));

function classify(prompt, mode) {
  const p = String(prompt || '').toLowerCase();
  if (mode === 'code') return 'code';
  if (mode === 'agents' || mode === 'design' || mode === 'plan') return 'reason';
  if (['```', 'def ', 'class ', 'function', 'функци', 'ошибк', 'баг', ' код', 'kotlin', 'python', 'java', 'sql', 'javascript', 'компил', 'stacktrace', 'exception', 'gradle', 'скрипт', 'регулярк', 'алгоритм'].some((w) => p.includes(w))) return 'code';
  if (['напиши текст', 'статью', ' пост', 'письмо', 'сочинение', 'рассказ', 'сценарий', 'перепиши', 'отредактируй', 'слоган'].some((w) => p.includes(w))) return 'write';
  if (p.length > 500 || ['докажи', 'обоснуй', 'проанализируй', 'сравни', 'стратег', 'почему', 'разбери', 'оцени', 'спланируй', 'рассчитай', 'посчитай', 'реши', 'исследуй', 'пошагово', 'анализ'].some((w) => p.includes(w))) return 'reason';
  if (p.length < 70) return 'fast';
  return 'general';
}

function score(kind, id) {
  const s = id.toLowerCase(); let v = 0;
  if (kind === 'code') { if (has(s, CODE)) v += 3; if (has(s, SMART)) v += 1; }
  else if (kind === 'reason') { if (has(s, SMART)) v += 3; }
  else if (kind === 'fast') { if (has(s, FAST)) v += 3; }
  else if (kind === 'write') { if (has(s, SMART)) v += 2; }
  if (kind !== 'fast' && has(s, FAST)) v -= 1;
  return v;
}

function rankModels(kind, available) {
  const real = available.filter((m) => !m.startsWith('auto'));
  const top = real.map((m) => [m, score(kind, m)]).filter((x) => x[1] >= 3).sort((a, b) => b[1] - a[1]).slice(0, 2).map((x) => x[0]);
  const alias = kind === 'code' || kind === 'reason' ? 'auto:smart' : kind === 'fast' ? 'auto:fast' : 'auto';
  return [...new Set([...top, alias, 'auto:reliable'])].slice(0, 4);
}

/** Два разных кандидата для «совета моделей». */
function distinctModels(available, kind) {
  const real = available.filter((m) => !m.startsWith('auto')).map((m) => [m, score(kind, m)]).sort((a, b) => b[1] - a[1]).map((x) => x[0]);
  return real.length >= 2 ? [real[0], real[1]] : ['auto:smart', 'auto'];
}

module.exports = { listModels, chatOnce, chatFallback, classify, rankModels, distinctModels, configured: () => !!URL_ };
