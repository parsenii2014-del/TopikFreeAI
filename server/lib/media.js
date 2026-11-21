
'use strict';
// FreeAI image generation: OpenAI GPT Image or Google Gemini.
// Video generation remains on Pollinations.

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const llm = require('./llm');

const POLL = (process.env.POLLINATIONS_URL || 'https://gen.pollinations.ai').replace(/\/+$/, '');
const POLL_KEY = process.env.POLLINATIONS_KEY || '';

const OPENAI_KEY = process.env.OPENAI_API_KEY || '';
const OPENAI_MODEL = process.env.OPENAI_IMAGE_MODEL || 'gpt-image-2.5-flare';

const GEMINI_KEY = process.env.GEMINI_API_KEY || '';
const GEMINI_MODEL = process.env.GEMINI_IMAGE_MODEL || 'gemini-3.1-flash-image';

const DEFAULT_PROVIDER = (process.env.IMAGE_PROVIDER || 'openai').toLowerCase();

const MEDIA_DIR = path.join(
  process.env.DATA_DIR || path.join(__dirname, '..', 'data'),
  'media'
);

fs.mkdirSync(MEDIA_DIR, { recursive: true });

const clamp = (n, a, b) =>
  Math.min(b, Math.max(a, Math.round(Number(n) || a)));

const jobs = new Map();

const SYS = {
  image: 'You are an image prompt engineer. Rewrite the user request as one detailed English image prompt. Describe subject, composition, camera angle, lighting, materials, style, colors and quality. Preserve the original idea. Return only the prompt.',
  video: 'You are a video prompt engineer. Rewrite the request as one detailed English prompt describing the scene, action, camera movement, lighting and style. Preserve the original idea. Return only the prompt.'
};

async function enhance(prompt, kind) {
  try {
    const { text } = await llm.chatFallback(
      ['auto:fast', 'auto'],
      [
        { role: 'system', content: SYS[kind] },
        { role: 'user', content: prompt }
      ],
      0.7
    );

    const result = text.trim().replace(/^["'`]+|["'`]+$/g, '');
    return result && result.length < 1500 ? result : prompt;
  } catch {
    return prompt;
  }
}

function getOpenAISize(width, height) {
  if (width > height * 1.15) return '1536x1024';
  if (height > width * 1.15) return '1024x1536';
  return '1024x1024';
}

async function generateOpenAI(prompt, width, height) {
  if (!OPENAI_KEY) {
    throw new Error('Не задан OPENAI_API_KEY в Render');
  }

  const response = await fetch('https://api.openai.com/v1/images/generations', {
    method: 'POST',
    headers: {
      authorization: 'Bearer ' + OPENAI_KEY,
      'content-type': 'application/json'
    },
    body: JSON.stringify({
      model: OPENAI_MODEL,
      prompt,
      n: 1,
      size: getOpenAISize(width, height),
      quality: 'medium',
      output_format: 'png'
    }),
    signal: AbortSignal.timeout(240000)
  });

  const data = await response.json().catch(() => ({}));

  if (!response.ok) {
    throw new Error(
      'OpenAI: HTTP ' + response.status + ' — ' +
      (data.error?.message || 'ошибка генерации')
    );
  }

  const encoded = data.data?.[0]?.b64_json;

  if (!encoded) {
    throw new Error('OpenAI не вернул изображение');
  }

  return {
    buf: Buffer.from(encoded, 'base64'),
    mime: 'image/png'
  };
}

async function generateGemini(prompt, width, height) {
  if (!GEMINI_KEY) {
    throw new Error('Не задан GEMINI_API_KEY в Render');
  }

  let aspectRatio = '1:1';

  if (width > height * 1.15) aspectRatio = '16:9';
  else if (height > width * 1.15) aspectRatio = '9:16';

  const url =
    'https://generativelanguage.googleapis.com/v1beta/models/' +
    encodeURIComponent(GEMINI_MODEL) +
    ':generateContent?key=' +
    encodeURIComponent(GEMINI_KEY);

  const response = await fetch(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      contents: [{
        role: 'user',
        parts: [{ text: prompt }]
      }],
      generationConfig: {
        responseModalities: ['TEXT', 'IMAGE'],
        imageConfig: {
          aspectRatio
        }
      }
    }),
    signal: AbortSignal.timeout(240000)
  });

  const data = await response.json().catch(() => ({}));

  if (!response.ok) {
    throw new Error(
      'Gemini: HTTP ' + response.status + ' — ' +
      (data.error?.message || 'ошибка генерации')
    );
  }

  const parts = data.candidates?.[0]?.content?.parts || [];

  const imagePart = parts.find(
    part => part.inlineData?.data
  );

  if (!imagePart) {
    throw new Error(
      'Gemini не вернул изображение. Возможно, запрос был отклонён.'
    );
  }

  return {
    buf: Buffer.from(imagePart.inlineData.data, 'base64'),
    mime: imagePart.inlineData.mimeType || 'image/png'
  };
}

async function genImage(b) {
  const prompt = String(b.prompt || '').trim().slice(0, 1500);

  if (!prompt) {
    throw Object.assign(new Error('prompt required'), { status: 400 });
  }

  const width = clamp(b.width || 1024, 256, 2048);
  const height = clamp(b.height || 1024, 256, 2048);

  const used = b.enhance === false
    ? prompt
    : await enhance(prompt, 'image');

  const provider = String(
    b.provider || DEFAULT_PROVIDER
  ).toLowerCase();

  let result;

  if (provider === 'openai') {
    result = await generateOpenAI(used, width, height);
  } else if (provider === 'gemini') {
    result = await generateGemini(used, width, height);
  } else {
    throw new Error('Неизвестный генератор изображений: ' + provider);
  }

  if (!/^image\//.test(result.mime)) {
    throw new Error('Провайдер вернул не изображение');
  }

  if (result.buf.length > 12e6) {
    throw new Error('Изображение слишком большое');
  }

  return {
    mime: result.mime.split(';')[0],
    b64: result.buf.toString('base64'),
    prompt: used
  };
}

function startVideo(b) {
  const prompt = String(b.prompt || '').trim().slice(0, 1500);

  if (!prompt) {
    throw Object.assign(new Error('prompt required'), { status: 400 });
  }

  if (!POLL_KEY) {
    throw Object.assign(
      new Error('Для видео нужен POLLINATIONS_KEY'),
      { status: 503 }
    );
  }

  const id = crypto.randomBytes(8).toString('hex');

  jobs.set(id, { status: 'running', at: Date.now() });

  for (const k of [...jobs.keys()].slice(0, Math.max(0, jobs.size - 50))) {
    jobs.delete(k);
  }

  (async () => {
    try {
      const used = b.enhance === false
        ? prompt
        : await enhance(prompt, 'video');

      const q = new URLSearchParams({
        duration: String(clamp(b.duration || 5, 2, 10)),
        aspectRatio: /^\d+:\d+$/.test(b.aspectRatio || '')
          ? b.aspectRatio
          : '16:9'
      });

      if (b.model) q.set('model', String(b.model));

      const response = await fetch(
        `${POLL}/video/${encodeURIComponent(used)}?${q}`,
        {
          headers: { authorization: 'Bearer ' + POLL_KEY },
          signal: AbortSignal.timeout(600000)
        }
      );

      if (!response.ok) {
        throw new Error('Провайдер видео: HTTP ' + response.status);
      }

      const buf = Buffer.from(await response.arrayBuffer());

      if (buf.length > 80e6) {
        throw new Error('Видео слишком большое');
      }

      if (!/^video\//.test(response.headers.get('content-type') || '')) {
        throw new Error('Провайдер вернул не видео');
      }

      fs.writeFileSync(path.join(MEDIA_DIR, id + '.mp4'), buf);

      jobs.set(id, {
        status: 'done',
        prompt: used,
        bytes: buf.length
      });
    } catch (error) {
      jobs.set(id, {
        status: 'error',
        error: error.message
      });
    }
  })();

  return id;
}

const videoStatus = id =>
  jobs.get(id) ||
  (fs.existsSync(path.join(MEDIA_DIR, id + '.mp4'))
    ? { status: 'done' }
    : null);

const mediaPath = id => {
  const file = path.join(MEDIA_DIR, id + '.mp4');
  return fs.existsSync(file) ? file : null;
};

function cleanup() {
  const limit = Date.now() - 7 * 86400000;

  for (const file of fs.readdirSync(MEDIA_DIR)) {
    const fullPath = path.join(MEDIA_DIR, file);

    try {
      if (fs.statSync(fullPath).mtimeMs < limit) {
        fs.unlinkSync(fullPath);
      }
    } catch {
      // Ignore cleanup errors
    }
  }
}

const configured = () => ({
  image: {
    openai: !!OPENAI_KEY,
    gemini: !!GEMINI_KEY,
    default: DEFAULT_PROVIDER
  },
  video: !!POLL_KEY
});

module.exports = {
  genImage,
  startVideo,
  videoStatus,
  mediaPath,
  cleanup,
  configured
};
