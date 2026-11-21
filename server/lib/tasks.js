'use strict';
/**
 * Автономные задачи по расписанию. Безопасная автономность: задача только ЧИТАЕТ (поиск, курсы, цены, страницы)
 * и ПИШЕТ тебе в Telegram. Покупок, платежей и действий от твоего имени нет — таких инструментов не существует.
 * Ограничения: минимальный интервал, суточный лимит запусков, аварийный выключатель (/stopall).
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const brain = require('./brain');

const FILE = path.join(process.env.DATA_DIR || path.join(__dirname, '..', 'data'), 'tasks.json');
const MIN_MIN = process.env.TASK_MIN_MINUTES != null ? Number(process.env.TASK_MIN_MINUTES) : 5;
const MAX_DAY = Number(process.env.AUTONOMY_MAX_RUNS_DAY || 48);
const TICK = Math.max(1, Number(process.env.TASK_TICK_SECONDS || 30)) * 1000;
const PREFIX = 'Это автономная задача по расписанию. Ты можешь только читать данные инструментами и сообщать результат; действовать от имени пользователя нельзя. Если ничего важного нет, начни ответ словами «БЕЗ ИЗМЕНЕНИЙ:» и одной строкой объясни. Если есть что-то важное или сработало условие, начни ответ словами «ВАЖНО:» и коротко изложи суть со ссылками. Задача: ';

const load = () => { try { return JSON.parse(fs.readFileSync(FILE, 'utf8')); } catch { return { paused: false, day: '', runs: 0, tasks: [] }; } };
const save = (s) => { const t = FILE + '.tmp'; fs.writeFileSync(t, JSON.stringify(s), { mode: 0o600 }); fs.renameSync(t, FILE); };
const today = () => new Date().toISOString().slice(0, 10);

function list() { const s = load(); return { paused: s.paused, runsToday: s.day === today() ? s.runs : 0, maxPerDay: MAX_DAY, tasks: s.tasks }; }

function add({ prompt, everyMin, title, notify }) {
  const p = String(prompt || '').trim().slice(0, 1000);
  const every = Number(everyMin);
  if (!p) throw Object.assign(new Error('prompt required'), { status: 400 });
  if (!Number.isFinite(every) || every < MIN_MIN) throw Object.assign(new Error(`интервал не меньше ${MIN_MIN} мин`), { status: 400 });
  const s = load();
  if (s.tasks.length >= 20) throw Object.assign(new Error('не больше 20 задач'), { status: 400 });
  const t = { id: crypto.randomBytes(4).toString('hex'), title: String(title || p.slice(0, 40)), prompt: p, everyMin: every, enabled: true, notify: notify === 'always' ? 'always' : 'important', lastAt: 0, lastText: '' };
  s.tasks.push(t); save(s);
  return t;
}

function remove(id) { const s = load(); const n = s.tasks.length; s.tasks = s.tasks.filter((t) => t.id !== id); save(s); return s.tasks.length < n; }
function toggle(id, enabled) { const s = load(); const t = s.tasks.find((x) => x.id === id); if (!t) return false; t.enabled = enabled; save(s); return true; }
function pause(paused) { const s = load(); s.paused = !!paused; save(s); return s.paused; }

async function runOne(id, notify, memory = () => ({ notes: [], style: '' })) {
  const s0 = load();
  const t0 = s0.tasks.find((x) => x.id === id);
  if (!t0) throw Object.assign(new Error('нет такой задачи'), { status: 404 });
  const m = memory();
  const r = await brain.run({ mode: 'agents', messages: [{ role: 'user', content: PREFIX + t0.prompt }], notes: m.notes, style: m.style });
  const s = load(); const t = s.tasks.find((x) => x.id === id);
  if (t) { t.lastAt = Date.now(); t.lastText = r.text.slice(0, 3500); }
  if (s.day !== today()) { s.day = today(); s.runs = 0; }
  s.runs++; save(s);
  const important = /^\s*ВАЖНО/i.test(r.text);
  if (notify && (t0.notify === 'always' || important)) await notify(`🤖 ${t0.title}\n\n${r.text}`);
  return { text: r.text, important };
}

function start({ notify, memory }) {
  let busy = false;
  setInterval(async () => {
    if (busy) return; busy = true;
    try {
      const s = load();
      if (s.paused) return;
      if (s.day === today() && s.runs >= MAX_DAY) return;
      const due = s.tasks.find((t) => t.enabled && Date.now() - t.lastAt >= t.everyMin * 60000);
      if (due) await runOne(due.id, notify, memory).catch((e) => console.error('task:', e.message));
    } finally { busy = false; }
  }, TICK).unref();
}

module.exports = { list, add, remove, toggle, pause, runOne, start };
