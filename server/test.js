'use strict';
// Самопроверка: поднимает моки (LLM, CoinGecko, Telegram, Fragment/Portals/MRKT, DDG), затем сервер и прогоняет сценарии.
const http = require('http');
const { spawn } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const assert = require('assert');

const TOKEN = 'test-token-1234567890';
const sent = [];            // сообщения, отправленные в Telegram (очищается между сценариями)
const allSent = [];         // все сообщения за время теста
const llmCalls = [];        // вызовы LLM: {model, last}
let price = 50000;
const tgQueue = [
  { update_id: 1, message: { chat: { id: 42 }, from: { id: 42 }, text: '/gift plushpepe' } },
  { update_id: 2, message: { chat: { id: 7 }, from: { id: 7 }, text: 'привет' } },
  { update_id: 3, message: { chat: { id: 42 }, from: { id: 42 }, text: '/watch plushpepe < 7000' } },
  { update_id: 4, message: { chat: { id: 42 }, from: { id: 42 }, text: '/watches' } },
  { update_id: 5, message: { chat: { id: 42 }, from: { id: 42 }, text: 'привет, как дела' } },
  { update_id: 6, message: { chat: { id: 42 }, from: { id: 42 }, text: '/image рыжий кот' } },
  { update_id: 7, message: { chat: { id: 42 }, from: { id: 42 }, text: '/design логотип кофейни' } },
  { update_id: 8, message: { chat: { id: 42 }, from: { id: 42 }, text: '/diag' } }
];
const files = [];            // файлы, отправленные в Telegram (sendPhoto / sendDocument)
const PNG = Buffer.from('89504e470d0a1a0a0000000d49484452', 'hex');

const csv = 'date,collection,floor_gram,floor_fixed_gram,lots_seen,auctions_seen\n' +
  '2026-11-03,plushpepe,7000,,60,60\n2026-11-04,plushpepe,7500,,60,60\n2026-11-04,lolpop,12,12,30,0\n';

function llmReply(body) {
  const msgs = body.messages;
  const last = String(msgs[msgs.length - 1].content);
  const first = msgs.find((m) => m.role === 'user');
  llmCalls.push({ model: body.model, last: last.slice(0, 60) });
  if (last.includes('не содержит готового кода')) return '```svg\n<svg viewBox="0 0 10 10"><rect width="10" height="10"/></svg>\n```';
  if (last.includes('ЧЕРНОВИК')) return 'ИТОГ после проверки';
  if (last.includes('Составь план')) return '1. Первый шаг\n2. Второй шаг';
  if (last.includes('Результаты шагов')) return JSON.stringify(msgs).includes('важное-условие') ? 'ВАЖНО: сработало условие' : 'АГЕНТ-ИТОГ';
  if (last.includes('Выполни шаг')) return 'результат шага';
  if (last.includes('Результат инструмента calculator')) return 'Ответ: 8';
  if (String(first && first.content).includes('посчитай') && !msgs.some((m) => String(m.content).includes('Результат инструмента'))) return 'TOOL: calculator | 2+2*3';
  return 'echo:' + body.model;
}

const mock = http.createServer((req, res) => {
  let body = ''; req.on('data', (c) => (body += c)); req.on('end', () => { try {
    const u = req.url;
    const json = (o) => { res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify(o)); };
    if (u.startsWith('/up/models')) return json({ data: [{ id: 'qwen-coder-32b' }, { id: 'gemini-pro' }, { id: 'llama-8b-instant' }] });
    if (u.startsWith('/up/chat/completions')) {
      assert.strictEqual(req.headers.authorization, 'Bearer UPKEY');
      return json({ choices: [{ message: { content: llmReply(JSON.parse(body)) } }] });
    }
    if (u.startsWith('/cg/simple/price')) return json({ bitcoin: { usd: price } });
    if (u.startsWith('/tg/botbottest/getUpdates')) {
      if (tgQueue.length) return json({ ok: true, result: tgQueue.splice(0) });
      return setTimeout(() => json({ ok: true, result: [] }), 400);
    }
    if (u.startsWith('/tg/botbottest/sendMessage')) { const m = JSON.parse(body); sent.push(m); allSent.push(m); return json({ ok: true, result: {} }); }
    if (u.startsWith('/pol/image/')) { assert.strictEqual(req.headers.authorization, 'Bearer PK'); res.writeHead(200, { 'content-type': 'image/png' }); return res.end(PNG); }
    if (u.startsWith('/pol/video/')) { assert.strictEqual(req.headers.authorization, 'Bearer PK'); res.writeHead(200, { 'content-type': 'video/mp4' }); return res.end(Buffer.from('FAKEMP4DATA')); }
    if (u.startsWith('/tg/botbottest/sendPhoto') || u.startsWith('/tg/botbottest/sendDocument')) { files.push({ m: u.split('/').pop(), ct: req.headers['content-type'], len: body.length }); return json({ ok: true, result: {} }); }
    if (u.startsWith('/frag.csv')) { res.writeHead(200, { 'content-type': 'text/plain' }); return res.end(csv); }
    if (u.startsWith('/portals/partners/collections/preview')) {
      if (req.headers.authorization !== 'partners PKEY') { res.writeHead(401); return res.end('no'); }
      return json({ collections: [{ short_name: 'plushpepe', name: 'Plush Pepe', floor_price: '7300.5' }] });
    }
    if (u.startsWith('/mrkt/auth')) return json(JSON.parse(body).data === 'INITDATA' ? { token: 'FROMINIT' } : {});
    if (u.startsWith('/mrkt/gifts/collections')) { assert.ok(['MTOKEN', 'FROMINIT'].includes(req.headers.authorization)); return json([{ name: 'Plush Pepe' }]); }
    if (u.startsWith('/mrkt/gifts/saling')) {
      assert.ok(['MTOKEN', 'FROMINIT'].includes(req.headers.authorization));
      const names = JSON.parse(body).collectionNames;
      return json(names[0] === 'Plush Pepe' || !names.length ? { gifts: [{ salePrice: 7200000000000, name: 'x' }, { salePrice: 7300000000000 }], cursor: null } : { gifts: [], cursor: null });
    }
    if (u.startsWith('/ddg')) { res.writeHead(200, { 'content-type': 'text/html' }); return res.end('<a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Fa&amp;rut=1">Заголовок <b>1</b></a><a class="result__snippet" href="x">Фрагмент текста</a>'); }
    res.writeHead(404); res.end();
  } catch (e) { console.error('mock error:', e.message); res.writeHead(500); res.end('mock error'); } });
});

const call = (p, o = {}) => fetch('http://127.0.0.1:18080' + p, { ...o, headers: { authorization: 'Bearer ' + TOKEN, 'content-type': 'application/json', ...(o.headers || {}) } });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const chat = (freeai, content) => call('/v1/chat/completions', { method: 'POST', body: JSON.stringify({ model: 'auto', messages: [{ role: 'user', content }], freeai }) }).then((r) => r.json());
async function waitFor(fn, label, ms = 6000) { const t = Date.now(); while (Date.now() - t < ms) { if (fn()) return; await sleep(100); } throw new Error('таймаут: ' + label); }

mock.listen(18081, async () => {
  const M = 'http://127.0.0.1:18081';
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'freeai-'));
  const env = { ...process.env, PORT: '18080', TOKEN, DATA_DIR: dir, UPSTREAM_URL: M + '/up', UPSTREAM_KEY: 'UPKEY', COINGECKO_URL: M + '/cg', TELEGRAM_API: M + '/tg',
    POLL_SECONDS: '15', FRAGMENT_CSV_URL: M + '/frag.csv', PORTALS_URL: M + '/portals', PORTALS_API_KEY: 'PKEY', MRKT_URL: M + '/mrkt',
    TG_BOT_TOKEN: 'bottest', TG_OWNER_IDS: '42', DDG_URL: M + '/ddg', POLLINATIONS_URL: M + '/pol', POLLINATIONS_KEY: 'PK', TASK_MIN_MINUTES: '0', TASK_TICK_SECONDS: '1', MRKT_TOKEN_FILE: path.join(dir, 'mrkt.token') };
  fs.writeFileSync(env.MRKT_TOKEN_FILE, 'MTOKEN\n'); delete env.MRKT_TOKEN;
  Object.assign(process.env, { DDG_URL: env.DDG_URL, FRAGMENT_CSV_URL: env.FRAGMENT_CSV_URL });
  const srv = spawn('node', ['server.js'], { cwd: __dirname, env, stdio: 'inherit' });
  try {
    await waitFor(() => true, 'start'); await sleep(900);
    const health = await (await fetch('http://127.0.0.1:18080/health')).json();
    assert.ok(health.freeai && health.features.gifts.portals && health.features.gifts.mrkt && health.features.telegramBot, 'health');
    assert.strictEqual((await fetch('http://127.0.0.1:18080/sync')).status, 401, 'без токена нельзя');

    // прокси без поля freeai
    let r = await call('/v1/chat/completions', { method: 'POST', body: JSON.stringify({ model: 'auto:smart', messages: [{ role: 'user', content: 'x' }] }) });
    assert.strictEqual((await r.json()).choices[0].message.content, 'echo:auto:smart', 'прокси');

    // цены подарков: три источника, берётся последняя дата Fragment, нано-единицы MRKT нормализуются
    const g = await (await call('/gifts/floor?collection=plushpepe')).json();
    const by = Object.fromEntries(g.sources.map((s) => [s.source, s.price]));
    assert.deepStrictEqual(by, { fragment: 7500, portals: 7300.5, mrkt: 7200 }, 'цены источников');
    assert.strictEqual(g.best.source, 'mrkt'); assert.ok(g.text.includes('Baltic Assets'), 'атрибуция');
    const top = await (await call('/gifts/top?n=5')).json(); assert.strictEqual(top.items[0].collection, 'plushpepe');
    assert.strictEqual((await call('/gifts/floor?collection=nope')).status, 200, 'частичные ошибки не роняют ответ');

    // мозг: инструмент калькулятор
    let b = await chat({ mode: 'chat', deep: false }, 'посчитай 2+2*3');
    assert.strictEqual(b.choices[0].message.content, 'Ответ: 8'); assert.strictEqual(b.freeai.toolCalls, 1);
    // мозг: код с проверкой
    b = await chat({ mode: 'code' }, 'напиши функцию');
    assert.strictEqual(b.choices[0].message.content, 'ИТОГ после проверки'); assert.ok(b.freeai.trace.includes('проверка'));
    assert.ok(llmCalls.some((c) => c.model === 'qwen-coder-32b'), 'для кода выбрана coder-модель');
    // мозг: агент
    b = await chat({ mode: 'agents' }, 'найди и ответь');
    assert.strictEqual(b.choices[0].message.content, 'АГЕНТ-ИТОГ'); assert.ok(b.freeai.trace[0].startsWith('шагов: 2'));
    // мозг: совет моделей
    llmCalls.length = 0;
    b = await chat({ mode: 'chat', council: true }, 'сравни два подхода');
    assert.strictEqual(b.choices[0].message.content, 'ИТОГ после проверки'); assert.ok(b.freeai.trace.some((t) => t.startsWith('совет')));
    assert.ok(new Set(llmCalls.filter((c) => !c.last.includes('ЧЕРНОВИК')).map((c) => c.model)).size >= 2, 'две разные модели');

    // поиск и безопасность
    const s = await (await call('/v1/search', { method: 'POST', body: JSON.stringify({ query: 'тест' }) })).json();
    assert.ok(s.result.includes('https://example.org/a') && s.result.includes('Фрагмент текста'), 'поиск');
    const tools = require('./lib/tools');
    await assert.rejects(() => tools.fetchUrl('http://127.0.0.1:18081/x'), /локальные/, 'SSRF заблокирован');
    await assert.rejects(() => tools.fetchUrl('http://169.254.169.254/latest/meta-data'), /локальные/, 'metadata заблокирован');
    await assert.rejects(() => tools.fetchUrl('file:///etc/passwd'), /http/, 'file:// запрещён');

    // Telegram-бот
    await waitFor(() => sent.some((m) => m.chat_id === 42 && /plushpepe/.test(m.text) && /Минимум/.test(m.text)), 'ответ на /gift');
    await waitFor(() => sent.some((m) => m.chat_id === 7 && /Нет доступа/.test(m.text)), 'отказ чужому');
    await waitFor(() => sent.some((m) => m.chat_id === 42 && /Слежу/.test(m.text)), '/watch');
    await waitFor(() => sent.some((m) => m.chat_id === 42 && /gift:plushpepe/.test(m.text)), '/watches');
    await waitFor(() => sent.some((m) => m.chat_id === 42 && /echo:/.test(m.text)), 'чат через мозг');

    // серверные алерты: крипта и подарки
    sent.length = 0;
    await call('/alerts', { method: 'PUT', body: JSON.stringify({ telegram: { token: 'bottest', chat: '42' }, rules: [
      { id: 'r1', coin: 'bitcoin', above: true, price: 60000 }, { id: 'g1', coin: 'gift:plushpepe', above: false, price: 7250 }, { id: 'bad', coin: '../x', above: true, price: 1 }] }) });
    await waitFor(() => sent.some((m) => /🎁/.test(m.text)), 'алерт на подарок');
    assert.ok(!sent.some((m) => /bitcoin/.test(m.text)), 'биткоин ниже порога');
    price = 61000;
    await call('/alerts', { method: 'PUT', body: JSON.stringify({ rules: [{ id: 'r1', coin: 'bitcoin', above: true, price: 60000 }, { id: 'g1', coin: 'gift:plushpepe', above: false, price: 7250, fired: false }] }) });
    await waitFor(() => sent.some((m) => /bitcoin/.test(m.text)), 'алерт на крипту');
    const n = sent.length; await sleep(600); assert.strictEqual(sent.length, n, 'повторно не срабатывает');
    const rules = (await (await call('/alerts')).json()).rules;
    assert.ok(rules.find((x) => x.id === 'g1').fired && rules.find((x) => x.id === 'r1').fired);
    assert.ok(!JSON.stringify(rules).includes('bottest'), 'токен бота не отдаётся наружу');

    // дизайн и планировка: код достаётся из ответа, при отсутствии модель просит исправить
    b = await chat({ mode: 'design' }, 'логотип кофейни');
    assert.ok(/<svg/.test(b.choices[0].message.content) && b.freeai.trace.includes('дизайн'), 'дизайн');
    b = await chat({ mode: 'plan' }, 'двушка 52 м2');
    assert.ok(b.freeai.trace.includes('планировка'), 'планировка');
    const { extractArtifact } = require('./lib/brain');
    assert.strictEqual(extractArtifact('текст ```html\n<html><body>x</body></html>\n``` ещё').kind, 'html');

    // диагностика площадок: видно поля ответов, секретов нет
    const dg = await (await call('/gifts/diagnose')).json();
    assert.ok(dg.fragment.ok && dg.portals.ok && dg.mrkt.ok, 'diagnose ok');
    assert.ok(dg.portals.sampleKeys.includes('short_name') && dg.mrkt.giftKeys.includes('salePrice'), 'поля видны');
    assert.ok(!JSON.stringify(dg).includes('PKEY') && !JSON.stringify(dg).includes('MTOKEN'), 'секреты не светятся');

    // фото и видео
    const im = await (await call('/v1/images', { method: 'POST', body: JSON.stringify({ prompt: 'кот' }) })).json();
    assert.strictEqual(im.mime, 'image/png'); assert.ok(Buffer.from(im.b64, 'base64').equals(PNG), 'фото'); assert.ok(im.prompt.startsWith('echo:'), 'промпт улучшен моделью');
    const vj = await (await call('/v1/videos', { method: 'POST', body: JSON.stringify({ prompt: 'закат' }) })).json();
    assert.ok(/^[a-f0-9]{16}$/.test(vj.id));
    let vs; await waitFor(async () => false, 'noop', 1).catch(() => {});
    for (let i = 0; i < 40; i++) { vs = await (await call('/v1/videos/' + vj.id)).json(); if (vs.status !== 'running') break; await sleep(150); }
    assert.strictEqual(vs.status, 'done'); const vb = Buffer.from(await (await call('/media/' + vj.id + '.mp4')).arrayBuffer()); assert.strictEqual(vb.toString(), 'FAKEMP4DATA');
    assert.strictEqual((await call('/v1/videos/' + 'f'.repeat(16))).status, 404);

    // бот: фото, дизайн-файл, диагностика
    await waitFor(() => files.some((f) => f.m === 'sendPhoto' && /multipart/.test(f.ct)), 'бот прислал фото');
    await waitFor(() => files.some((f) => f.m === 'sendDocument'), 'бот прислал SVG-файл');
    await waitFor(() => allSent.some((m) => /portals: ок/.test(m.text) && /mrkt: ок/.test(m.text)), '/diag');

    // автономные задачи: расписание, важное уведомление, лимиты и аварийный выключатель
    sent.length = 0;
    assert.strictEqual((await call('/tasks', { method: 'POST', body: JSON.stringify({ prompt: 'проверь важное-условие', everyMin: -1 }) })).status, 400, 'интервал валидируется');
    const task = await (await call('/tasks', { method: 'POST', body: JSON.stringify({ prompt: 'проверь важное-условие', everyMin: 0.5, title: 'Тест' }) })).json();
    await waitFor(() => sent.some((m) => /🤖/.test(m.text) && /ВАЖНО/.test(m.text)), 'задача по расписанию прислала важное');
    const tl = await (await call('/tasks')).json(); assert.ok(tl.tasks[0].lastAt > 0 && tl.runsToday >= 1);
    const rr = await (await call('/tasks/' + task.id + '/run', { method: 'POST' })).json(); assert.ok(rr.important, 'ручной запуск');
    assert.strictEqual((await (await call('/tasks/pause', { method: 'POST', body: JSON.stringify({ paused: true }) })).json()).paused, true);
    assert.strictEqual((await call('/tasks/' + task.id, { method: 'DELETE' })).status, 200);

    // токен MRKT через initData (отдельный процесс)
    const { execFile } = require('child_process');
    const out = await new Promise((resolve, reject) => execFile('node', ['-e', "const g=require('./lib/gifts.js');g.giftFloor('plushpepe').then(r=>console.log(JSON.stringify(r.sources.find(s=>s.source==='mrkt'))))"],
      { cwd: __dirname, env: { ...process.env, FRAGMENT_CSV_URL: env.FRAGMENT_CSV_URL, MRKT_URL: env.MRKT_URL, MRKT_INIT_DATA: 'INITDATA' } }, (e, so) => (e ? reject(e) : resolve(so))));
    assert.strictEqual(JSON.parse(out).price, 7200, 'MRKT через /auth по initData: ' + out);

    // синхронизация
    assert.strictEqual((await call('/sync', { method: 'PUT', body: JSON.stringify({ chats: [{ id: 'a', title: 't', updated: 1, messages: [] }], memory: { notes: ['x'], updated: 5 }, deleted: ['z'] }) })).status, 200);
    const sy = await (await call('/sync')).json(); assert.strictEqual(sy.chats.length, 1); assert.deepStrictEqual(sy.deleted, ['z']);
    assert.deepStrictEqual((await (await call('/v1/memory')).json()).notes, ['x']);
    assert.strictEqual((await call('/sync', { method: 'PUT', body: '{"nope":1}' })).status, 400);

    console.log('ВСЕ ПРОВЕРКИ СЕРВЕРА ПРОЙДЕНЫ');
  } catch (e) { console.error('ПРОВАЛ:', e.stack || e.message); process.exitCode = 1; }
  srv.kill(); mock.close();
});
