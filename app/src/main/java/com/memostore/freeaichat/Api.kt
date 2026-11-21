package com.memostore.freeaichat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(val code: Int, message: String) : Exception(message)

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}

private fun messagesToJson(messages: List<Msg>): JSONArray {
    val arr = JSONArray()
    messages.forEach { m ->
        val o = JSONObject().put("role", m.role)
        if (m.image != null) {
            val parts = JSONArray()
                .put(JSONObject().put("type", "text").put("text", m.text))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", m.image)))
            o.put("content", parts)
        } else o.put("content", m.text)
        arr.put(o)
    }
    return arr
}

class Api(private val client: OkHttpClient) {
    val metaModels = listOf("auto", "auto:fast", "auto:smart", "auto:reliable")

    suspend fun listModels(baseUrl: String, key: String): List<String> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(baseUrl.trimEnd('/') + "/models?available=true")
            .header("Authorization", "Bearer $key").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ApiException(r.code, "HTTP ${r.code}")
            val data = JSONObject(r.body?.string() ?: "{}").optJSONArray("data") ?: JSONArray()
            val out = mutableListOf<String>()
            out.addAll(metaModels)
            for (i in 0 until data.length()) data.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            out.distinct()
        }
    }

    suspend fun chat(baseUrl: String, key: String, model: String, messages: List<Msg>): String {
        val body = JSONObject().put("model", model).put("messages", messagesToJson(messages)).put("stream", false)
        val req = Request.Builder().url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $key").header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val resp = client.newCall(req).await()
        return withContext(Dispatchers.IO) {
            resp.use { r ->
                val raw = r.body?.string() ?: ""
                if (!r.isSuccessful) throw ApiException(r.code, "HTTP ${r.code}: ${raw.take(300)}")
                val content = JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                if (content.isNullOrBlank()) throw ApiException(200, "Пустой ответ модели")
                content
            }
        }
    }

    /** Серверный «мозг» FreeAI Cloud: проверка ответа, совет моделей, агенты и инструменты выполняются на сервере. */
    suspend fun brain(baseUrl: String, key: String, model: String, messages: List<Msg>, freeai: JSONObject): AgentResult {
        val body = JSONObject().put("model", model).put("messages", messagesToJson(messages)).put("stream", false).put("freeai", freeai)
        val req = Request.Builder().url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $key").header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val resp = client.newBuilder().readTimeout(600, java.util.concurrent.TimeUnit.SECONDS).build().newCall(req).await()
        return withContext(Dispatchers.IO) {
            resp.use { r ->
                val raw = r.body?.string() ?: ""
                if (!r.isSuccessful) throw ApiException(r.code, "HTTP ${r.code}: ${raw.take(300)}")
                val root = JSONObject(raw)
                val content = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                if (content.isNullOrBlank()) throw ApiException(200, "Пустой ответ сервера")
                val f = root.optJSONObject("freeai")
                val trace = mutableListOf<String>()
                val t = f?.optJSONArray("trace")
                if (t != null) for (i in 0 until t.length()) trace.add(t.optString(i))
                AgentResult(content, root.optString("model", model), f?.optInt("toolCalls", 0) ?: 0, trace, f?.optString("kind", "") ?: "")
            }
        }
    }

    /** Пробует модели по очереди. Ошибки авторизации (401/403) пробрасываются сразу. */
    suspend fun chatWithFallback(baseUrl: String, key: String, candidates: List<String>, messages: List<Msg>): Pair<String, String> {
        var last: Exception? = null
        for (m in candidates.take(4)) {
            try {
                return Pair(chat(baseUrl, key, m, messages), m)
            } catch (e: ApiException) {
                if (e.code == 401 || e.code == 403) throw e
                last = e
            } catch (e: IOException) {
                last = e
            } catch (e: JSONException) {
                last = e
            }
        }
        throw last ?: Exception("Нет доступных моделей")
    }
}
