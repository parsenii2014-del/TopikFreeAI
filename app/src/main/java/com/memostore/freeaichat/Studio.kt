package com.memostore.freeaichat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.util.Base64
import android.view.View
import android.webkit.WebView
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Клиент фото/видео FreeAI Cloud (серверные эндпоинты /v1/images, /v1/videos, /media). */
class StudioClient(client: OkHttpClient) {
    private val http = client.newBuilder().readTimeout(600, TimeUnit.SECONDS).build()

    private fun req(base: String, token: String, path: String) =
        Request.Builder().url(base.trimEnd('/') + path).header("Authorization", "Bearer $token")

    private suspend fun exec(r: Request): String = withContext(Dispatchers.IO) {
        http.newCall(r).execute().use { resp ->
            val raw = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                val msg = try { JSONObject(raw).optString("error") } catch (e: Exception) { "" }
                throw Exception(msg.ifBlank { "HTTP ${resp.code}" })
            }
            raw
        }
    }

    private fun json(o: JSONObject) = o.toString().toRequestBody("application/json".toMediaType())

    suspend fun image(base: String, token: String, prompt: String, w: Int, h: Int, enhance: Boolean): Pair<ByteArray, String> {
        val body = JSONObject().put("prompt", prompt).put("width", w).put("height", h).put("enhance", enhance)
        val o = JSONObject(exec(req(base, token, "/v1/images").post(json(body)).build()))
        return Pair(Base64.decode(o.getString("b64"), Base64.DEFAULT), o.optString("prompt"))
    }

    suspend fun startVideo(base: String, token: String, prompt: String, duration: Int, aspect: String): String {
        val body = JSONObject().put("prompt", prompt).put("duration", duration).put("aspectRatio", aspect)
        return JSONObject(exec(req(base, token, "/v1/videos").post(json(body)).build())).getString("id")
    }

    suspend fun videoStatus(base: String, token: String, id: String): Pair<String, String> {
        val o = JSONObject(exec(req(base, token, "/v1/videos/$id").build()))
        return Pair(o.optString("status"), o.optString("error"))
    }

    suspend fun downloadVideo(base: String, token: String, id: String, dest: File) = withContext(Dispatchers.IO) {
        http.newCall(req(base, token, "/media/$id.mp4").build()).execute().use { r ->
            if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
            val stream = r.body?.byteStream() ?: throw Exception("пустой ответ")
            dest.outputStream().use { out -> stream.copyTo(out) }
        }
    }
}

private val artifactMarker = Regex("<svg[\\s>]|<html[\\s>]|<!doctype html", RegexOption.IGNORE_CASE)

/** Достаёт готовый SVG/HTML из ответа модели: из блока кода или «голого» тега. */
fun extractArtifact(text: String): String? {
    for (m in Regex("```(?:svg|html|xml)?\\s*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
        val c = m.groupValues[1]
        if (artifactMarker.containsMatchIn(c)) return c.trim()
    }
    Regex("<svg.*</svg>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(text)?.let { return it.value }
    Regex("<html.*</html>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(text)?.let { return it.value }
    return null
}

/** Заворачивает голый SVG в страницу, чтобы он масштабировался по ширине экрана. */
fun wrapForView(code: String): String =
    if (Regex("<html[\\s>]|<!doctype html", RegexOption.IGNORE_CASE).containsMatchIn(code)) code
    else "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'><style>html,body{margin:0;background:#fff}svg{width:100%;height:auto;display:block}</style></head><body>$code</body></html>"

/** Безопасный предпросмотр: без JavaScript, без сети, без доступа к файлам. */
@Composable
fun HtmlView(html: String, modifier: Modifier = Modifier, onReady: (WebView) -> Unit = {}) {
    AndroidView(modifier = modifier, factory = { c ->
        WebView(c).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            setBackgroundColor(android.graphics.Color.WHITE)
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            onReady(this)
        }
    }, update = { w ->
        if (w.tag != html) {
            w.tag = html
            w.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }
    })
}

private fun captureWebView(w: WebView): ByteArray? {
    if (w.width <= 0 || w.height <= 0) return null
    val bmp = Bitmap.createBitmap(w.width, w.height, Bitmap.Config.ARGB_8888)
    w.draw(Canvas(bmp))
    val out = java.io.ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
    return out.toByteArray()
}

private suspend fun writeTo(ctx: android.content.Context, uri: Uri, bytes: ByteArray) = withContext(Dispatchers.IO) {
    ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
}

private val fenceRe = Regex("```.*?```", RegexOption.DOT_MATCHES_ALL)

/**
 * Студия: фото, видео, дизайн и планировка. Всё идёт через твой FreeAI Cloud.
 * Дизайн и планировка — это SVG/HTML-код, который модель рисует и правит по твоим замечаниям.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioTab(api: Api, studio: StudioClient, cloudUrl: String, cloudToken: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf("Фото") }
    var prompt by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var enhance by remember { mutableStateOf(true) }
    var ratio by remember { mutableStateOf("1:1") }
    var duration by remember { mutableStateOf(5) }
    var imageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var usedPrompt by remember { mutableStateOf("") }
    var videoFile by remember { mutableStateOf<File?>(null) }
    var code by remember { mutableStateOf<String?>(null) }
    var reply by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }

    val savePng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) scope.launch {
            val bytes = imageBytes?.takeIf { kind == "Фото" && it.size > 4 && it[1] == 'P'.code.toByte() } ?: webView?.let { captureWebView(it) }
            if (bytes != null) { writeTo(ctx, uri, bytes); status = "Сохранено" } else status = "Нечего сохранять"
        }
    }
    val saveJpg = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        if (uri != null) scope.launch { imageBytes?.let { writeTo(ctx, uri, it); status = "Сохранено" } }
    }
    val saveVideo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        if (uri != null) scope.launch { videoFile?.let { f -> writeTo(ctx, uri, withContext(Dispatchers.IO) { f.readBytes() }); status = "Сохранено" } }
    }
    val saveCode = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        if (uri != null) scope.launch { code?.let { writeTo(ctx, uri, it.toByteArray(Charsets.UTF_8)); status = "Сохранено" } }
    }

    fun generate() {
        val p = prompt.trim()
        if (p.isEmpty() || busy) return
        if (cloudUrl.isBlank()) { status = "Нужен FreeAI Cloud: Настройки → Облако"; return }
        busy = true
        status = ""
        scope.launch {
            try {
                when (kind) {
                    "Фото" -> {
                        status = "Генерирую фото…"
                        val (w, h) = when (ratio) { "16:9" -> 1280 to 720; "9:16" -> 720 to 1280; else -> 1024 to 1024 }
                        val (bytes, used) = studio.image(cloudUrl, cloudToken, p, w, h, enhance)
                        imageBytes = bytes; usedPrompt = used; status = ""
                    }
                    "Видео" -> {
                        status = "Видео генерируется, это до пары минут…"
                        val id = studio.startVideo(cloudUrl, cloudToken, p, duration, if (ratio == "9:16") "9:16" else "16:9")
                        var done = false
                        for (i in 0 until 150) {
                            delay(4000)
                            val (st, err) = studio.videoStatus(cloudUrl, cloudToken, id)
                            if (st == "error") throw Exception(err.ifBlank { "ошибка провайдера" })
                            if (st == "done") { done = true; break }
                        }
                        if (!done) throw Exception("не успело за 10 минут")
                        val f = File(ctx.cacheDir, "studio_$id.mp4")
                        studio.downloadVideo(cloudUrl, cloudToken, id, f)
                        videoFile = f; status = ""
                    }
                    else -> {
                        status = if (kind == "Планировка") "Чертим планировку…" else "Рисуем дизайн…"
                        val mode = if (kind == "Планировка") "plan" else "design"
                        val current = code
                        val text = if (current != null) "Текущий код:\n```\n$current\n```\nПравка: $p" else p
                        val res = api.brain(cloudUrl.trimEnd('/') + "/v1", cloudToken, "auto", listOf(Msg("user", text)), JSONObject().put("mode", mode))
                        reply = res.text.replace(fenceRe, "").trim()
                        code = extractArtifact(res.text)
                        status = if (code == null) "Модель не вернула код, попробуй переформулировать" else ""
                    }
                }
            } catch (e: Exception) {
                status = "Ошибка: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Студия", fontSize = 23.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Фото", "Видео", "Дизайн", "Планировка").forEach { k ->
                FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k) })
            }
        }
        Text(
            when (kind) {
                "Фото" -> "Опиши картинку, ИИ сам доработает промпт. Работает через Pollinations или твой API изображений на сервере."
                "Видео" -> "Короткое видео из текста. Нужен ключ Pollinations на сервере (POLLINATIONS_KEY)."
                "Дизайн" -> "Логотип, иконка, постер, баннер (SVG) или экран приложения/сайта (HTML). Потом правь словами: «сделай темнее», «крупнее заголовок»."
                else -> "Эскиз планировки в SVG: комнаты, стены, двери, окна, размеры и площади. Правь словами: «сдвинь кухню к окну», «добавь санузел». Это эскиз для обсуждения, не проект для строительства."
            }, color = muted, fontSize = 12.sp
        )
        if (kind == "Фото" || kind == "Видео") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("1:1", "16:9", "9:16").filter { kind == "Фото" || it != "1:1" }.forEach { r ->
                    FilterChip(selected = ratio == r || (kind == "Видео" && ratio == "1:1" && r == "16:9"), onClick = { ratio = r }, label = { Text(r) })
                }
                if (kind == "Видео") listOf(5, 8).forEach { d -> FilterChip(selected = duration == d, onClick = { duration = d }, label = { Text("$d с") }) }
                if (kind == "Фото") FilterChip(selected = enhance, onClick = { enhance = !enhance }, label = { Text("Улучшить промпт") })
            }
        }
        OutlinedTextField(
            prompt, { prompt = it }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 6,
            placeholder = { Text(if (code != null && (kind == "Дизайн" || kind == "Планировка")) "Что поправить?" else "Опиши, что нужно", color = muted) }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { generate() }, enabled = !busy && prompt.isNotBlank()) { Text(if (code != null && kind != "Фото" && kind != "Видео") "Поправить" else "Создать") }
            if (code != null) OutlinedButton(onClick = { code = null; reply = ""; prompt = "" }, enabled = !busy) { Text("Новый") }
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        if (status.isNotBlank()) Text(status, color = if (status.startsWith("Ошибка")) danger else muted, fontSize = 12.sp)

        if (kind == "Фото") imageBytes?.let { bytes ->
            val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            if (bmp != null) {
                Image(bmp.asImageBitmap(), "Результат", Modifier.fillMaxWidth())
                if (usedPrompt.isNotBlank()) Text(usedPrompt, color = muted, fontSize = 10.sp)
                val isPng = bytes.size > 4 && bytes[1] == 'P'.code.toByte()
                OutlinedButton(onClick = { if (isPng) savePng.launch("freeai.png") else saveJpg.launch("freeai.jpg") }) { Text("Сохранить") }
            }
        }
        if (kind == "Видео") videoFile?.let { f ->
            AndroidView(modifier = Modifier.fillMaxWidth().height(260.dp), factory = { c ->
                VideoView(c).apply {
                    val mc = MediaController(c)
                    mc.setAnchorView(this)
                    setMediaController(mc)
                    setVideoURI(Uri.fromFile(f))
                    setOnPreparedListener { mp -> mp.isLooping = true; start() }
                }
            }, update = { v ->
                if (v.tag != f.path) { v.tag = f.path; v.setVideoURI(Uri.fromFile(f)); v.start() }
            })
            OutlinedButton(onClick = { saveVideo.launch("freeai.mp4") }) { Text("Сохранить видео") }
        }
        if (kind == "Дизайн" || kind == "Планировка") code?.let { c ->
            HtmlView(wrapForView(c), Modifier.fillMaxWidth().height(420.dp)) { webView = it }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { savePng.launch("freeai.png") }) { Text("Сохранить PNG") }
                OutlinedButton(onClick = { saveCode.launch(if (c.trimStart().startsWith("<svg", true)) "freeai.svg" else "freeai.html") }) { Text("Сохранить код") }
            }
            if (reply.isNotBlank()) Text(reply, fontSize = 12.sp)
        }
        Spacer(Modifier.height(16.dp))
    }
}
