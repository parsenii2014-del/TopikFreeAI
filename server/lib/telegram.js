'use strict';
// Telegram-бот: чат с ИИ, код, агенты, курсы, цены подарков и алерты. Доступ только владельцам (TG_OWNER_IDS).
const brain = require('./brain');
const gifts = require('./gifts');
const tools = require('./tools');
const media = require('./media');

const API = (process.env.TELEGRAM_API || 'https://api.telegram.org').replace(/\/+$/, '');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const ALIAS = { btc: 'bitcoin', eth: 'ethereum', ton: 'the-open-network', gram: 'the-open-network', sol: 'solana', not: 'notcoin', usdt: 'tether' };

async function api(token, method, body, timeout = 40000) {
  const r = await fetch(`${API}/bot${token}/${method}`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body || {}), signal: AbortSignal.timeout(timeout) });
  const j = await r.json().catch(() => ({}));
  if (!r.ok || j.ok === false) throw new Error(`telegram ${method}: ${j.description || r.status}`);
  return j.result;
}

async function sendMessage(token, chat, text) {
  const s = String(text || '…');
  for (let i = 0; i < s.length; i += 3800) await api(token, 'sendMessage', { chat_id: chat, text: s.slice(i, i + 3800), disable_web_page_preview: true });
}

async function sendFile(token, chat, method, field, buf, mime, filename, caption) {
  const fd = new FormData();
  fd.append('chat_id', String(chat));
  if (caption) fd.append('caption', String(caption).slice(0, 1000));
  fd.append(field, new Blob([buf], { type: mime }), filename);
  const r = await fetch(`${API}/bot${token}/${method}`, { method: 'POST', body: fd, signal: AbortSignal.timeout(120000) });
  const j = await r.json().catch(() => ({}));
  if (!r.ok || j.ok === false) throw new Error(`telegram ${method}: ${j.description || r.status}`);
}

const HELP = `FreeAI в Telegram. Просто пиши — отвечу как ассистент (с поиском и инструментами).

/code задача — режим программирования с проверкой
/agent задача — агент: план → шаги → итог
/deep вопрос — глубокий ответ с проверкой
/search запрос — исследование с источниками
/image описание — сгенерировать фото
/video описание — сгенерировать видео
/design задача — логотип, постер, экран (файл SVG/HTML)
/plan задача — планировка квартиры или дома (SVG)
/task 60 что делать — автономная задача раз в 60 минут
/tasks, /taskrun N, /taskoff N, /stopall, /resume — управление задачами
/diag — проверка источников цен
/price btc ton — курсы
/gift plushpepe — флор-цены подарков
/top — самые дорогие коллекции (Fragment)
/watch plushpepe < 7000 — алерт на подарок
/watches, /unwatch N — список и удаление
/memory — что я помню о тебе
/new — новый разговор`;

function startBot({ token, owners, host }) {
  let stopped = false;
  const histories = new Map();
  const warned = new Map();
  const reply = (chat, text) => sendMessage(token, chat, text).catch((e) => console.error('bot send:', e.message));

  async function ask(chat, mode, text, extra = {}) {
    api(token, 'sendChatAction', { chat_id: chat, action: 'typing' }).catch(() => {});
    const h = histories.get(chat) || [];
    h.push({ role: 'user', content: text });
    const mem = host.memory();
    try {
      const r = await brain.run({ mode, messages: h, notes: mem.notes, style: mem.style, ...extra });
      h.push({ role: 'assistant', content: r.text });
      histories.set(chat, h.slice(-12));
      await reply(chat, r.text + (r.toolCalls ? `\n\n— ${r.model} · инструментов: ${r.toolCalls}` : ''));
      if (mode === 'design' || mode === 'plan') {
        const art = brain.extractArtifact(r.text);
        if (art) await sendFile(token, chat, 'sendDocument', 'document', Buffer.from(art.code, 'utf8'), art.kind === 'svg' ? 'image/svg+xml' : 'text/html', (mode === 'plan' ? 'plan.' : 'design.') + art.kind, 'Файл открывается в браузере');
      }
    } catch (e) {
      h.pop();
      await reply(chat, 'Ошибка ИИ: ' + e.message);
    }
  }

  async function handle(u) {
    const m = u.message;
    if (!m || !m.text) return;
    const chat = m.chat.id;
    const from = m.from && m.from.id;
    if (!owners.includes(String(from))) {
      if (!warned.has(from) || Date.now() - warned.get(from) > 3600000) { warned.set(from, Date.now()); await reply(chat, `Нет доступа. Твой Telegram id: ${from}. Владелец добавляет его в TG_OWNER_IDS.`); }
      return;
    }
    const text = m.text.trim();
    const parts = text.split(/\s+/);
    const cmd = parts[0].startsWith('/') ? parts[0].slice(1).split('@')[0].toLowerCase() : '';
    const arg = text.slice(parts[0].length).trim();
    try {
      switch (cmd) {
        case '': return await ask(chat, 'chat', text);
        case 'start': case 'help': return await reply(chat, HELP);
        case 'new': histories.delete(chat); return await reply(chat, 'Начали с чистого листа.');
        case 'code': return arg ? await ask(chat, 'code', arg) : await reply(chat, 'Опиши задачу: /code напиши функцию…');
        case 'agent': return arg ? await ask(chat, 'agents', arg) : await reply(chat, 'Опиши цель: /agent …');
        case 'deep': return arg ? await ask(chat, 'chat', arg, { deep: true }) : await reply(chat, 'Задай вопрос: /deep …');
        case 'search': return arg ? await ask(chat, 'tools', arg) : await reply(chat, 'Что искать? /search …');
        case 'price': {
          const ids = (arg || 'btc ton').split(/[\s,]+/).filter(Boolean).map((x) => ALIAS[x.toLowerCase()] || x.toLowerCase());
          return await reply(chat, await tools.cryptoPrice(ids.join(',')));
        }
        case 'image': {
          if (!arg) return await reply(chat, 'Опиши картинку: /image рыжий кот в космосе');
          api(token, 'sendChatAction', { chat_id: chat, action: 'upload_photo' }).catch(() => {});
          const r = await media.genImage({ prompt: arg });
          return await sendFile(token, chat, 'sendPhoto', 'photo', Buffer.from(r.b64, 'base64'), r.mime, 'image.' + (r.mime.split('/')[1] || 'jpg'), r.prompt.slice(0, 300));
        }
        case 'video': {
          if (!arg) return await reply(chat, 'Опиши видео: /video закат над океаном');
          const id = media.startVideo({ prompt: arg });
          await reply(chat, 'Генерирую видео, это займёт до пары минут…');
          for (let i = 0; i < 150; i++) {
            await sleep(4000);
            const st = media.videoStatus(id);
            if (st && st.status === 'error') return await reply(chat, 'Видео не получилось: ' + st.error);
            if (st && st.status === 'done') return await sendFile(token, chat, 'sendVideo', 'video', require('fs').readFileSync(media.mediaPath(id)), 'video/mp4', 'video.mp4', (st.prompt || '').slice(0, 300));
          }
          return await reply(chat, 'Видео не успело сгенерироваться за 10 минут.');
        }
        case 'design': return arg ? await ask(chat, 'design', arg) : await reply(chat, 'Опиши задачу: /design логотип кофейни в стиле минимализм');
        case 'plan': return arg ? await ask(chat, 'plan', arg) : await reply(chat, 'Опиши задачу: /plan двушка 52 м², кухня-гостиная');
        case 'task': {
          const mm = /^(\d+(?:[.,]\d+)?)\s*(мин|м|m|min|ч|h)?\s+(.+)$/is.exec(arg);
          if (!mm) return await reply(chat, 'Формат: /task 60 следить за ценой plushpepe и писать, если ниже 7000');
          const every = parseFloat(mm[1].replace(',', '.')) * (/^(ч|h)$/i.test(mm[2] || '') ? 60 : 1);
          const t = host.tasks.add({ prompt: mm[3], everyMin: every });
          return await reply(chat, `Задача ${t.id} создана: каждые ${every} мин. Пишу только если найду важное. Выключить всё: /stopall`);
        }
        case 'tasks': {
          const l = host.tasks.list();
          return await reply(chat, (l.paused ? '⏸ Автономность на паузе (/resume)\n' : '') + (l.tasks.length ? l.tasks.map((t, i) => `${i + 1}. ${t.enabled ? '▶' : '⏸'} каждые ${t.everyMin} мин: ${t.title}`).join('\n') : 'Задач нет.') + `\nЗапусков сегодня: ${l.runsToday}/${l.maxPerDay}`);
        }
        case 'taskrun': {
          const t = host.tasks.list().tasks[parseInt(arg, 10) - 1];
          if (!t) return await reply(chat, 'Нет такого номера. Смотри /tasks');
          const r = await host.tasks.runOne(t.id);
          return await reply(chat, r.text);
        }
        case 'taskoff': {
          const t = host.tasks.list().tasks[parseInt(arg, 10) - 1];
          if (!t) return await reply(chat, 'Нет такого номера. Смотри /tasks');
          host.tasks.toggle(t.id, !t.enabled);
          return await reply(chat, t.enabled ? 'Задача выключена.' : 'Задача включена.');
        }
        case 'stopall': host.tasks.pause(true); return await reply(chat, '⏸ Все автономные задачи остановлены. Вернуть: /resume');
        case 'resume': host.tasks.pause(false); return await reply(chat, '▶ Автономные задачи снова работают.');
        case 'diag': {
          const d = await gifts.diagnose();
          return await reply(chat, Object.entries(d).map(([k, v]) => `${k}: ${v.ok ? 'ок' : 'ошибка: ' + v.error}${v.ok && v.sampleKeys ? ' · поля: ' + v.sampleKeys.join(', ') : ''}${v.giftKeys ? ' · поля лота: ' + v.giftKeys.join(', ') : ''}`).join('\n'));
        }
        case 'gift': return arg ? await reply(chat, gifts.formatFloor(await gifts.giftFloor(arg))) : await reply(chat, 'Укажи коллекцию: /gift plushpepe');
        case 'top': {
          const top = await gifts.fragmentTop(10);
          return await reply(chat, 'Топ флоров Fragment (GRAM):\n' + top.map((t, i) => `${i + 1}. ${t.collection}: ${t.price}${t.auction ? ' (аукцион)' : ''}`).join('\n') + '\n' + gifts.ATTRIBUTION);
        }
        case 'watch': {
          const mm = /^(\S+)\s*(<|>)?\s*([\d.,]+)$/.exec(arg);
          if (!mm) return await reply(chat, 'Формат: /watch plushpepe < 7000');
          const price = parseFloat(mm[3].replace(',', '.'));
          host.addRule({ coin: 'gift:' + gifts.norm(mm[1]), above: mm[2] === '>', price });
          return await reply(chat, `Слежу за ${mm[1]}: ${mm[2] === '>' ? '≥' : '≤'} ${price} GRAM.`);
        }
        case 'watches': {
          const r = host.rules();
          return await reply(chat, r.length ? r.map((x, i) => `${i + 1}. ${x.coin} ${x.above ? '≥' : '≤'} ${x.price}${x.fired ? ' ✓' : ''}`).join('\n') : 'Алертов нет.');
        }
        case 'unwatch': return await reply(chat, host.removeRule(parseInt(arg, 10) - 1) ? 'Удалено.' : 'Нет такого номера. Смотри /watches');
        case 'memory': {
          const mem = host.memory();
          return await reply(chat, mem.notes.length ? 'Помню:\n• ' + mem.notes.join('\n• ') + (mem.style ? '\nСтиль: ' + mem.style : '') : 'Память пуста. Заметки приходят из приложения (облачная синхронизация).');
        }
        default: return await ask(chat, 'chat', text);
      }
    } catch (e) { await reply(chat, 'Ошибка: ' + e.message); }
  }

  (async () => {
    let offset = 0;
    while (!stopped) {
      try {
        const ups = await api(token, 'getUpdates', { timeout: 25, offset, allowed_updates: ['message'] }, 35000);
        for (const u of ups) { offset = u.update_id + 1; handle(u).catch((e) => console.error('bot:', e.message)); }
      } catch (e) { console.error('bot poll:', e.message); await sleep(5000); }
    }
  })();

  return { stop: () => { stopped = true; }, sendMessage: (chat, text) => sendMessage(token, chat, text) };
}

module.exports = { startBot, sendMessage, api };
