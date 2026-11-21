package com.memostore.freeaichat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

internal val bg = Color(0xFF171715)
internal val panel = Color(0xFF22221F)
internal val panel2 = Color(0xFF2C2C28)
internal val accent = Color(0xFFD6A982)
internal val fg = Color(0xFFF2F0EB)
internal val muted = Color(0xFFA7A39B)
internal val danger = Color(0xFFFF9292)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = accent, background = bg, surface = panel, onBackground = fg, onSurface = fg)) { FreeAiApp() } }
    }
}

private val specialties = listOf("Универсальный эксперт", "Программирование", "Наука и исследования", "Обучение", "Копирайтинг", "Бизнес", "Финансы", "Дизайн и творчество", "Аналитика", "Маркетинг")
private val modes = listOf("Чат", "Code", "Agents", "Студия", "Крипто")
private const val MAX_NOTES = 50
private const val MAX_CONTEXT_MESSAGES = 60
private val rememberRegex = Regex("^\\s*(запомни|remember)\\s*[:,\\-]?\\s*(.+)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

private fun fmtDate(ms: Long): String = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(ms))

private fun modelLabel(id: String): String = when (id) {
    "auto" -> "Авто (умный выбор)"
    "auto:fast" -> "Быстрая"
    "auto:smart" -> "Умная"
    "auto:reliable" -> "Надёжная"
    else -> id
}

private fun readCapped(ctx: android.content.Context, uri: Uri, max: Int): ByteArray? {
    val stream = ctx.contentResolver.openInputStream(uri) ?: return null
    return stream.use { s ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val n = s.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        out.toByteArray()
    }
}

private fun serverKindLabel(k: String): String = when (k) {
    "code" -> "код"
    "reason" -> "сложная задача"
    "fast" -> "быстрый ответ"
    "write" -> "текст"
    else -> "общий вопрос"
}

private val serverToolIds = setOf("search", "calc", "crypto", "gifts", "time")

/** Уменьшает фото до 1280 px и кодирует в data-URL для моделей с поддержкой изображений. */
private fun imageToDataUrl(ctx: android.content.Context, uri: Uri): String? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / sample > 2560 || bounds.outHeight / sample > 2560) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
    val longest = maxOf(bmp.width, bmp.height)
    val scaled = if (longest > 1280) {
        val k = 1280f / longest
        Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
    } else bmp
    val out = java.io.ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
    return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}

private data class Block(val text: String, val code: Boolean, val lang: String = "")

private fun splitBlocks(t: String): List<Block> {
    val out = mutableListOf<Block>()
    val re = Regex("```([a-zA-Z0-9_+#-]*)\\n?(.*?)(```|$)", RegexOption.DOT_MATCHES_ALL)
    var last = 0
    for (m in re.findAll(t)) {
        if (m.range.first > last) {
            val pre = t.substring(last, m.range.first).trim()
            if (pre.isNotEmpty()) out.add(Block(pre, false))
        }
        out.add(Block(m.groupValues[2].trimEnd('\n'), true, m.groupValues[1]))
        last = m.range.last + 1
    }
    if (last < t.length) {
        val rest = t.substring(last).trim()
        if (rest.isNotEmpty()) out.add(Block(rest, false))
    }
    if (out.isEmpty()) out.add(Block(t, false))
    return out
}

@Composable
private fun CodeBlock(code: String, lang: String) {
    val clip = LocalClipboardManager.current
    var preview by remember { mutableStateOf(false) }
    val canPreview = lang.lowercase() in setOf("html", "svg")
    Column(Modifier.fillMaxWidth().background(Color(0xFF111110), RoundedCornerShape(10.dp))) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (lang.isBlank()) "code" else lang, color = muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            if (canPreview) TextButton(onClick = { preview = true }) { Text("Предпросмотр", fontSize = 11.sp) }
            IconButton(onClick = { clip.setText(AnnotatedString(code)) }) {
                Icon(Icons.Default.ContentCopy, "Копировать код", tint = muted, modifier = Modifier.size(16.dp))
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
            Text(code, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = fg)
        }
    }
    if (preview) {
        AlertDialog(onDismissRequest = { preview = false }, containerColor = panel, title = { Text("Предпросмотр") },
            text = { HtmlView(wrapForView(code), Modifier.fillMaxWidth().height(420.dp)) },
            confirmButton = { TextButton(onClick = { preview = false }) { Text("Закрыть") } })
    }
}

@Composable
private fun MessageBubble(m: Msg) {
    val isUser = m.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(color = if (isUser) Color(0xFF493B31) else panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 720.dp)) {
            SelectionContainer {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    splitBlocks(m.text).forEach { b ->
                        if (b.code) CodeBlock(b.text, b.lang) else Text(b.text, fontSize = 14.sp, lineHeight = 21.sp, color = fg)
                    }
                    if (m.meta.isNotBlank()) Text(m.meta, fontSize = 10.sp, color = muted)
                }
            }
        }
    }
}

/** Отдельный лёгкий запрос: вытаскивает из сообщения новые факты и стиль общения. */
private suspend fun extractMemory(client: OkHttpClient, baseUrl: String, apiKey: String, model: String, userText: String, mem: MemoryState): Pair<List<String>, String>? =
    withContext(Dispatchers.IO) {
        try {
            val sys = "Ты модуль долговременной памяти ассистента. По сообщению пользователя выдели только устойчивые факты о нём (имя, роль, проекты, технологии, цели, ограничения) и предпочтения по стилю общения (тон, длина, формат ответов). " +
                "Не сохраняй разовые вопросы, пароли, ключи, токены, адреса кошельков, номера карт и документов, а также медицинские, финансовые и другие чувствительные данные. " +
                "Уже известно: ${mem.notes.joinToString("; ")}. Текущий стиль: ${mem.style}. " +
                "Верни ТОЛЬКО JSON вида {\"facts\":[\"...\"],\"style\":\"...\"}: facts — только новые факты, style — обновлённое описание стиля или пустая строка, если не изменилось. Если сохранять нечего: {\"facts\":[],\"style\":\"\"}."
            val arr = JSONArray()
                .put(JSONObject().put("role", "system").put("content", sys))
                .put(JSONObject().put("role", "user").put("content", userText.take(2000)))
            val body = JSONObject().put("model", model).put("messages", arr).put("stream", false)
            val req = Request.Builder().url(baseUrl.trimEnd('/') + "/chat/completions")
                .header("Authorization", "Bearer $apiKey").header("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            val parsed: Pair<List<String>, String>? = client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) null
                else {
                    val raw = r.body?.string() ?: ""
                    val content = JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content") ?: ""
                    val i = content.indexOf('{')
                    val j = content.lastIndexOf('}')
                    if (i < 0 || j <= i) null
                    else {
                        val o = JSONObject(content.substring(i, j + 1))
                        val fa = o.optJSONArray("facts") ?: JSONArray()
                        val facts = mutableListOf<String>()
                        for (k in 0 until fa.length()) fa.optString(k).trim().take(200).takeIf { it.isNotBlank() }?.let { facts.add(it) }
                        val style = o.optString("style").trim().take(300)
                        if (facts.isEmpty() && style.isBlank()) null else Pair(facts.toList(), style)
                    }
                }
            }
            parsed
        } catch (e: Exception) {
            null
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FreeAiApp() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { Store(ctx) }
    val client = remember { OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build() }
    val api = remember { Api(client) }
    val crypto = remember { CryptoClient(client) }
    val syncer = remember { Sync(client) }
    val studio = remember { StudioClient(client) }
    val prefs = remember {
        val master = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(ctx, "freeai_secure", master, EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
    var baseUrl by remember { mutableStateOf(prefs.getString("url", "http://127.0.0.1:3001/v1") ?: "http://127.0.0.1:3001/v1") }
    var apiKey by remember { mutableStateOf(prefs.getString("key", "") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("model", "auto") ?: "auto") }
    var syncUrl by remember { mutableStateOf(prefs.getString("sync_url", "") ?: "") }
    var syncToken by remember { mutableStateOf(prefs.getString("sync_token", "") ?: "") }
    var models by remember { mutableStateOf(listOf("auto", "auto:fast", "auto:smart", "auto:reliable")) }
    var mode by remember { mutableStateOf("Чат") }
    var selectedSpecs by remember { mutableStateOf((prefs.getString("specs", null)?.split("|")?.filter { it.isNotBlank() }?.toSet()) ?: setOf("Универсальный эксперт")) }
    var enabledTools by remember { mutableStateOf((prefs.getString("tools", null)?.split(",")?.filter { it.isNotBlank() }?.toSet()) ?: allTools.map { it.id }.toSet()) }
    var chats by remember { mutableStateOf(store.loadChats()) }
    var currentId by remember { mutableStateOf<String?>(chats.firstOrNull()?.id) }
    var messages by remember { mutableStateOf(chats.firstOrNull()?.messages ?: listOf<Msg>()) }
    var memory by remember { mutableStateOf(store.loadMemory()) }
    var attachments by remember { mutableStateOf(emptyMap<String, String>()) }
    var sentFiles by remember { mutableStateOf(emptySet<String>()) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf("") }
    var settings by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showMemory by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var syncStatus by remember { mutableStateOf("") }
    var serverBrain by remember { mutableStateOf(prefs.getBoolean("server_brain", false)) }
    var deepMode by remember { mutableStateOf(prefs.getBoolean("deep_mode", false)) }
    var council by remember { mutableStateOf(prefs.getBoolean("council", false)) }
    var pendingImage by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    fun addNote(raw: String) {
        val n = raw.trim().take(200)
        if (n.isEmpty() || memory.notes.any { it.equals(n, true) }) return
        memory = store.saveMemory(memory.copy(notes = (memory.notes + n).takeLast(MAX_NOTES)))
    }

    fun persist(msgs: List<Msg>) {
        val clean = msgs.filter { it.text.isNotBlank() }
        val firstUser = clean.firstOrNull { it.role == "user" } ?: return
        val id = currentId ?: UUID.randomUUID().toString().also { currentId = it }
        val chat = Chat(id, firstUser.text.replace('\n', ' ').take(40), clean, System.currentTimeMillis())
        chats = listOf(chat) + chats.filter { it.id != id }
        store.saveChats(chats)
    }

    val tools = remember { ToolRunner(client, crypto, { attachments }, { n -> addNote(n) }, { q -> crypto.giftFloor(syncUrl, syncToken.ifBlank { apiKey }, q) }) }
    val agent = remember { Agent(api, tools) }

    fun refreshModels() {
        if (apiKey.isBlank()) return
        scope.launch {
            try {
                models = api.listModels(baseUrl, apiKey)
                error = ""
            } catch (e: Exception) {
                error = "Модели не загрузились: ${e.message}"
            }
        }
    }

    fun syncNow(manual: Boolean = false) {
        if (syncUrl.isBlank() || syncing) return
        val token = syncToken.ifBlank { apiKey }
        if (token.isBlank()) {
            if (manual) syncStatus = "Нужен токен облака"
            return
        }
        syncing = true
        scope.launch {
            try {
                val res = syncer.sync(syncUrl, token, chats, store.loadMemory(), store.loadDeleted())
                val deleted = store.loadDeleted() + res.deleted
                store.saveDeleted(deleted)
                chats = mergeChats(chats, res.chats, deleted)
                store.saveChats(chats)
                memory = store.saveMemory(if (res.memory.updated > memory.updated) res.memory else memory, keepTimestamp = true)
                syncStatus = "Синхронизировано " + fmtDate(System.currentTimeMillis())
            } catch (e: Exception) {
                syncStatus = "Ошибка синхронизации: ${e.message}"
            } finally {
                syncing = false
            }
        }
    }

    fun attach(uri: Uri) {
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (c.moveToFirst() && i >= 0) c.getString(i) else null
                    } ?: "файл"
                    val bytes = readCapped(ctx, uri, 400_000) ?: throw Exception("не удалось прочитать")
                    val text = String(bytes, Charsets.UTF_8)
                    val bad = text.count { it == '\uFFFD' || it == '\u0000' }
                    if (bad > text.length / 20 + 3) throw Exception("нужен текстовый файл (код, txt, json, csv, md)")
                    Pair(name, text)
                }
                attachments = attachments + result
                error = ""
            } catch (e: Exception) {
                error = "Файл не прикреплён: ${e.message}"
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) attach(uri) }

    fun attachImage(uri: Uri) {
        scope.launch {
            try {
                val data = withContext(Dispatchers.IO) { imageToDataUrl(ctx, uri) } ?: throw Exception("не удалось прочитать изображение")
                pendingImage = data
                error = ""
            } catch (e: Exception) {
                error = "Фото не прикреплено: ${e.message}"
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) attachImage(uri) }

    fun send() {
        val prompt = input.trim()
        if (prompt.isEmpty() || busy) return
        if (apiKey.isBlank()) { settings = true; error = "Добавь API-ключ FreeLLMAPI в настройках"; return }
        input = ""; error = ""

        // Явная команда «запомни: ...» работает всегда, даже при выключенном автообучении.
        val explicit = rememberRegex.find(prompt)
        explicit?.let { addNote(it.groupValues[2]) }

        val inlineLimit = if (serverBrain) 12000 else 4000
        val img = pendingImage
        pendingImage = null
        val newFiles = attachments.filterKeys { it !in sentFiles }
        val inline = newFiles.entries.joinToString("") { en ->
            if (en.value.length <= inlineLimit) "\n\n[Файл: ${en.key}]\n${en.value}" else "\n\n[Прикреплён большой файл: ${en.key} — читай его инструментом read_file]"
        }
        sentFiles = sentFiles + newFiles.keys
        val shown = prompt + (if (newFiles.isEmpty()) "" else "\n📎 " + newFiles.keys.joinToString(", ")) + (if (img != null) "\n🖼 фото" else "")
        val prior = messages.takeLast(MAX_CONTEXT_MESSAGES).map { Msg(it.role, it.text) }
        val userMessages = messages + Msg("user", shown)
        val llmUser = Msg("user", prompt + inline, image = img)
        messages = userMessages + Msg("assistant", "Думаю…")
        persist(userMessages)
        busy = true

        val kind = classify(prompt, mode)
        val candidates = if (model == "auto") rankModels(kind, models) else listOf(model, "auto:reliable").distinct()
        val enabled = enabledTools
        val specText = selectedSpecs.joinToString(", ")
        val modeInstruction = when (mode) {
            "Code" -> "Ты старший software engineer и ревьюер. Пиши рабочий, аккуратно оформленный код в блоках ``` с указанием языка, объясняй решения кратко, находи причины ошибок, предлагай тесты и граничные случаи. Не выдумывай результаты запуска: ты не можешь выполнять код (в приложении есть только предпросмотр блоков html и svg)."
            "Agents" -> "Ты агент-исполнитель: действуешь по плану и честно сообщаешь, что реально сделано инструментами, а что нет."
            "Tools" -> "Помогай использовать инструменты, анализировать предоставленные материалы и планировать действия. Не заявляй о доступе к файлам, интернету или устройству, если содержимое не получено через инструмент."
            else -> "Ты полезный универсальный ИИ-ассистент в стиле современного диалогового помощника."
        }
        val mem = memory
        val memText = if (mem.notes.isNotEmpty()) " Долговременная память о пользователе (учитывай, когда уместно, не пересказывай без нужды): " + mem.notes.joinToString("; ") + "." else ""
        val styleText = if (mem.style.isNotBlank()) " Стиль общения, который предпочитает пользователь: ${mem.style}." else ""
        val system = "Ты FreeAI — дружелюбный, точный ассистент. Активный режим: $mode. Специализации пользователя: $specText. $modeInstruction${toolPrompt(enabled, attachments.keys)}$memText$styleText Отвечай на языке пользователя."

        job = scope.launch {
            try {
                val events = mutableListOf<String>()
                val onEvent: (String) -> Unit = { e ->
                    events.add(e)
                    messages = userMessages + Msg("assistant", events.takeLast(8).joinToString("\n"))
                }
                val history = prior + llmUser
                val res = if (serverBrain) {
                    messages = userMessages + Msg("assistant", "🧠 Серверный мозг работает…")
                    val freeai = JSONObject()
                        .put("mode", when (mode) { "Code" -> "code"; "Agents" -> "agents"; "Tools" -> "tools"; else -> "chat" })
                        .put("tools", JSONArray(enabled.filter { it in serverToolIds }))
                        .put("notes", JSONArray(mem.notes))
                        .put("style", mem.style)
                        .put("specs", JSONArray(selectedSpecs.toList()))
                    if (deepMode) freeai.put("deep", true)
                    if (council) freeai.put("council", true)
                    api.brain(baseUrl, apiKey, model, history, freeai)
                } else if (mode == "Agents") agent.runAgent(baseUrl, apiKey, candidates, system, history, prompt + inline, enabled, onEvent)
                else agent.chat(baseUrl, apiKey, candidates, system, history, enabled, onEvent)
                val meta = buildString {
                    append(modelLabel(res.model)).append(" · ").append(if (res.kind.isNotBlank()) "🧠 " + serverKindLabel(res.kind) else kind.label)
                    if (res.toolCalls > 0) append(" · инструментов: ").append(res.toolCalls)
                    if (res.trace.isNotEmpty()) append("\n").append(res.trace.joinToString(" → "))
                }
                val finalMsgs = userMessages + Msg("assistant", res.text, meta)
                messages = finalMsgs
                persist(finalMsgs)
                syncNow()
                if (memory.auto && explicit == null) {
                    scope.launch {
                        val upd = extractMemory(client, baseUrl, apiKey, if (model == "auto") "auto:fast" else model, prompt, memory)
                        if (upd != null) {
                            val cur = memory
                            val add = upd.first.filter { f -> cur.notes.none { it.equals(f, true) } }
                            if (add.isNotEmpty() || upd.second.isNotBlank()) {
                                memory = store.saveMemory(
                                    cur.copy(
                                        notes = (cur.notes + add).takeLast(MAX_NOTES),
                                        style = if (upd.second.isNotBlank()) upd.second else cur.style
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                messages = userMessages
                throw e
            } catch (e: Exception) {
                messages = userMessages
                error = "Ошибка API: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshModels()
        syncNow()
    }
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty() && (mode == "Чат" || mode == "Code" || mode == "Agents")) listState.scrollToItem(messages.size - 1)
    }

    Scaffold(containerColor = bg, topBar = {
        TopAppBar(
            title = { Column { Text("FreeAI", fontWeight = FontWeight.Bold, fontSize = 21.sp); Text("Твоё ИИ-пространство", color = muted, fontSize = 11.sp) } },
            actions = {
                IconButton(onClick = { showHistory = true }, enabled = !busy) { Icon(Icons.Default.History, "История") }
                IconButton(onClick = { showMemory = true }) { Icon(Icons.Default.Psychology, "Память") }
                IconButton(onClick = { messages = emptyList(); currentId = null; error = ""; attachments = emptyMap(); sentFiles = emptySet() }, enabled = !busy) { Icon(Icons.Default.Add, "Новый чат") }
                IconButton(onClick = { settings = true }) { Icon(Icons.Default.Settings, "Настройки") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = bg)
        )
    }, bottomBar = {
        NavigationBar(containerColor = panel) {
            modes.forEach { item ->
                val icon = when (item) {
                    "Чат" -> Icons.Default.ChatBubbleOutline
                    "Code" -> Icons.Default.Code
                    "Agents" -> Icons.Default.AutoAwesome
                    "Студия" -> Icons.Default.Palette
                    else -> Icons.Default.Paid
                }
                NavigationBarItem(selected = mode == item, onClick = { mode = item }, icon = { Icon(icon, null) }, label = { Text(item, fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = accent, selectedTextColor = accent, indicatorColor = panel2, unselectedTextColor = muted))
            }
        }
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 14.dp)) {
            if (mode != "Крипто" && mode != "Студия") {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        OutlinedButton(onClick = { modelMenu = true }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp)) { Text("Модель: ${modelLabel(model)}", fontSize = 12.sp) }
                        DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                            models.take(80).forEach { DropdownMenuItem(text = { Text(modelLabel(it)) }, onClick = { model = it; prefs.edit().putString("model", it).apply(); modelMenu = false }) }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(if (busy) "Отвечает…" else if (serverBrain) "FreeAI Cloud · 🧠" else if (syncUrl.isNotBlank()) "FreeLLMAPI · облако" else "FreeLLMAPI", fontSize = 11.sp, color = muted)
                }
            }
            when (mode) {
                "Крипто" -> CryptoTab(prefs, crypto, syncUrl, syncToken.ifBlank { apiKey }, Modifier.weight(1f).fillMaxWidth())
                "Студия" -> StudioTab(api, studio, syncUrl, syncToken.ifBlank { apiKey }, Modifier.weight(1f).fillMaxWidth())
                else -> {
                    if (messages.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(if (mode == "Code") Icons.Default.Code else if (mode == "Agents") Icons.Default.AutoAwesome else Icons.Default.SmartToy, null, tint = accent, modifier = Modifier.size(48.dp))
                                Text(when (mode) { "Code" -> "Что будем создавать?"; "Agents" -> "Какую задачу выполнить?"; else -> "Чем могу помочь?" }, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                                Text("Режим: $mode · ${selectedSpecs.joinToString()}", color = muted, fontSize = 12.sp)
                                if (mode == "Agents") Text("Опиши цель — агент составит план и выполнит шаги, вызывая инструменты.", color = muted, fontSize = 12.sp)
                            }
                        }
                    } else {
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
                            items(messages.size) { idx -> MessageBubble(messages[idx]) }
                        }
                    }
                }
            }
            if (error.isNotBlank()) Text(error, color = danger, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
            if (mode == "Чат" || mode == "Code" || mode == "Agents") {
                if (mode == "Code") {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Объясни код" to "Объясни, как работает этот код:\n", "Найди баг" to "Найди и исправь баг:\n", "Рефакторинг" to "Сделай рефакторинг, сохранив поведение:\n", "Тесты" to "Напиши тесты для:\n", "Оптимизируй" to "Оптимизируй:\n")
                            .forEach { (label, text) -> AssistChip(onClick = { input = text }, label = { Text(label, fontSize = 11.sp) }) }
                    }
                }
                if (attachments.isNotEmpty() || pendingImage != null) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (pendingImage != null) InputChip(selected = false, onClick = { pendingImage = null }, label = { Text("🖼 фото", fontSize = 11.sp) },
                            trailingIcon = { Icon(Icons.Default.Close, "Убрать фото", modifier = Modifier.size(14.dp)) })
                        attachments.keys.forEach { n ->
                            InputChip(selected = false, onClick = { attachments = attachments - n }, label = { Text(n, fontSize = 11.sp, maxLines = 1) },
                                trailingIcon = { Icon(Icons.Default.Close, "Убрать файл", modifier = Modifier.size(14.dp)) })
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).background(panel, RoundedCornerShape(22.dp)).padding(7.dp), verticalAlignment = Alignment.Bottom) {
                    IconButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy) { Icon(Icons.Default.AttachFile, "Прикрепить файл", tint = muted) }
                    IconButton(onClick = { imagePicker.launch("image/*") }, enabled = !busy) { Icon(Icons.Default.Image, "Прикрепить фото", tint = muted) }
                    TextField(
                        value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                        placeholder = { Text(if (mode == "Code") "Опиши код или ошибку…" else if (mode == "Agents") "Поставь задачу агенту…" else "Напиши сообщение…", color = muted) },
                        maxLines = 5,
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent)
                    )
                    IconButton(onClick = { if (busy) job?.cancel() else send() }, enabled = busy || input.isNotBlank(), modifier = Modifier.background(accent, RoundedCornerShape(16.dp))) {
                        Icon(if (busy) Icons.Default.Stop else Icons.Default.ArrowUpward, if (busy) "Остановить" else "Отправить", tint = Color.Black)
                    }
                }
            }
        }
    }

    if (showHistory) {
        AlertDialog(onDismissRequest = { showHistory = false }, containerColor = panel, title = { Text("История чатов") },
            text = {
                if (chats.isEmpty()) Text("Пока пусто", color = muted)
                else LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(chats, key = { it.id }) { c ->
                        Row(Modifier.fillMaxWidth().background(panel2, RoundedCornerShape(12.dp)).clickable { currentId = c.id; messages = c.messages; error = ""; attachments = emptyMap(); sentFiles = emptySet(); showHistory = false }.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(c.title, maxLines = 1, fontSize = 13.sp); Text(fmtDate(c.updated), color = muted, fontSize = 10.sp) }
                            IconButton(onClick = {
                                store.saveDeleted(store.loadDeleted() + c.id)
                                chats = chats.filter { it.id != c.id }
                                store.saveChats(chats)
                                if (currentId == c.id) { currentId = null; messages = emptyList() }
                                syncNow()
                            }) { Icon(Icons.Default.Delete, "Удалить чат", tint = muted) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHistory = false }) { Text("Закрыть") } })
    }

    if (showMemory) {
        var styleDraft by remember { mutableStateOf(memory.style) }
        var noteDraft by remember { mutableStateOf("") }
        fun closeMemory() {
            memory = store.saveMemory(memory.copy(style = styleDraft.trim().take(300)))
            showMemory = false
            syncNow()
        }
        AlertDialog(onDismissRequest = { closeMemory() }, containerColor = panel, title = { Text("Память") },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("Запоминать автоматически", fontSize = 13.sp); Text("Отдельный запрос после ответа. Команда «запомни: …» работает всегда.", color = muted, fontSize = 10.sp) }
                        Switch(checked = memory.auto, onCheckedChange = { v -> memory = store.saveMemory(memory.copy(auto = v)) })
                    }
                    Text("Стиль общения", color = muted, fontSize = 12.sp)
                    OutlinedTextField(styleDraft, { styleDraft = it }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4, placeholder = { Text("Например: кратко, на «ты», без воды", color = muted) })
                    Text("Заметки (${memory.notes.size}/$MAX_NOTES)", color = muted, fontSize = 12.sp)
                    if (memory.notes.isEmpty()) Text("Пока ничего не сохранено.", color = muted, fontSize = 12.sp)
                    memory.notes.forEachIndexed { idx, n ->
                        Row(Modifier.fillMaxWidth().background(panel2, RoundedCornerShape(10.dp)).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(n, Modifier.weight(1f), fontSize = 12.sp)
                            IconButton(onClick = { memory = store.saveMemory(memory.copy(notes = memory.notes.filterIndexed { i, _ -> i != idx })) }) { Icon(Icons.Default.Delete, "Удалить заметку", tint = muted) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(noteDraft, { noteDraft = it }, modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text("Новая заметка", color = muted) })
                        IconButton(onClick = { addNote(noteDraft); noteDraft = "" }) { Icon(Icons.Default.Add, "Добавить") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { closeMemory() }) { Text("Готово") } },
            dismissButton = { TextButton(onClick = { memory = store.saveMemory(MemoryState(emptyList(), "", memory.auto)); showMemory = false; syncNow() }) { Text("Стереть всё", color = danger) } })
    }

    if (showTools) {
        AlertDialog(onDismissRequest = { showTools = false }, containerColor = panel, title = { Text("Инструменты и специализации") },
            text = {
                    LazyColumn(Modifier.heightIn(max = 460.dp), contentPadding = PaddingValues(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            Text("Инструменты", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                            Text("Включённые инструменты ИИ вызывает сам в любом режиме. Всё ниже работает по-настоящему.", color = muted, fontSize = 13.sp)
                        }
                        items(allTools, key = { it.id }) { t ->
                            Row(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(12.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text(t.title, fontWeight = FontWeight.Medium); Text(t.desc, fontSize = 11.sp, color = muted) }
                                Switch(checked = t.id in enabledTools, onCheckedChange = { v ->
                                    enabledTools = if (v) enabledTools + t.id else enabledTools - t.id
                                    prefs.edit().putString("tools", enabledTools.joinToString(",")).apply()
                                })
                            }
                        }
                        item {
                            Spacer(Modifier.height(6.dp))
                            Text("Специализации", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                            Text("Можно выбрать несколько направлений. Они учитываются в ответах.", color = muted, fontSize = 13.sp)
                        }
                        items(specialties, key = { it }) { s ->
                            Row(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(14.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text(s, fontWeight = FontWeight.Medium); Text(if (s == "Программирование") "Разработка, отладка, архитектура" else "Ответы с учётом выбранной области", fontSize = 11.sp, color = muted) }
                                Checkbox(checked = s in selectedSpecs, onCheckedChange = { checked ->
                                    selectedSpecs = if (checked) selectedSpecs + s else selectedSpecs - s
                                    prefs.edit().putString("specs", selectedSpecs.joinToString("|")).apply()
                                })
                            }
                        }
                    }
            },
            confirmButton = { TextButton(onClick = { showTools = false }) { Text("Готово") } })
    }

    if (settings) {
        var urlDraft by remember { mutableStateOf(baseUrl) }
        var keyDraft by remember { mutableStateOf(apiKey) }
        var cloudDraft by remember { mutableStateOf(syncUrl) }
        var cloudTokenDraft by remember { mutableStateOf(syncToken) }
        AlertDialog(onDismissRequest = { settings = false }, containerColor = panel, title = { Text("Подключение") },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("API URL (FreeLLMAPI или твой сервер: https://…/v1)", color = muted, fontSize = 12.sp)
                    OutlinedTextField(urlDraft, { urlDraft = it }, singleLine = true)
                    Text("Единый API-ключ / токен сервера", color = muted, fontSize = 12.sp)
                    OutlinedTextField(keyDraft, { keyDraft = it }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    Text("Облако: синхронизация и алерты 24/7", color = muted, fontSize = 12.sp)
                    OutlinedTextField(cloudDraft, { cloudDraft = it }, singleLine = true, placeholder = { Text("https://твой-сервер (без /v1)", color = muted) })
                    OutlinedTextField(cloudTokenDraft, { cloudTokenDraft = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), placeholder = { Text("Токен облака (пусто = API-ключ)", color = muted) })
                    OutlinedButton(onClick = { settings = false; showTools = true }) { Text("Инструменты и специализации") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("Серверный мозг", fontSize = 13.sp); Text("Проверка ответов, агенты и инструменты на твоём FreeAI Cloud. API URL должен вести на него.", color = muted, fontSize = 10.sp) }
                        Switch(checked = serverBrain, onCheckedChange = { v -> serverBrain = v; prefs.edit().putBoolean("server_brain", v).apply() })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("Глубокий режим", fontSize = 13.sp); Text("Всегда проверять ответ рецензентом (дольше, точнее). Только с серверным мозгом.", color = muted, fontSize = 10.sp) }
                        Switch(checked = deepMode, onCheckedChange = { v -> deepMode = v; prefs.edit().putBoolean("deep_mode", v).apply() })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("Совет моделей", fontSize = 13.sp); Text("Две разные модели отвечают параллельно, судья сводит итог. Медленнее и расходует лимиты.", color = muted, fontSize = 10.sp) }
                        Switch(checked = council, onCheckedChange = { v -> council = v; prefs.edit().putBoolean("council", v).apply() })
                    }
                    if (syncStatus.isNotBlank()) Text(syncStatus, color = muted, fontSize = 11.sp)
                    Text("Ключи хранятся в зашифрованном хранилище устройства. Для облака используй https.", color = muted, fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    baseUrl = urlDraft.trim().trimEnd('/'); apiKey = keyDraft.trim()
                    syncUrl = cloudDraft.trim().trimEnd('/'); syncToken = cloudTokenDraft.trim()
                    prefs.edit().putString("url", baseUrl).putString("key", apiKey).putString("sync_url", syncUrl).putString("sync_token", syncToken).apply()
                    settings = false
                    refreshModels()
                    syncNow(manual = true)
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { settings = false }) { Text("Отмена") } })
    }
}
