package com.memostore.freeaichat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class SyncResult(val chats: List<Chat>, val memory: MemoryState, val deleted: Set<String>)

fun mergeChats(a: List<Chat>, b: List<Chat>, deleted: Set<String>): List<Chat> =
    (a + b).filter { it.id !in deleted }
        .groupBy { it.id }
        .map { e -> e.value.maxBy { c -> c.updated } }
        .sortedByDescending { it.updated }
        .take(200)

/** Синхронизация истории и памяти с собственным сервером (см. папку server/). Побеждает более новая версия. */
class Sync(private val client: OkHttpClient) {
    suspend fun sync(baseUrl: String, token: String, chats: List<Chat>, mem: MemoryState, deleted: Set<String>): SyncResult =
        withContext(Dispatchers.IO) {
            val url = baseUrl.trimEnd('/') + "/sync"
            val getReq = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
            val remote: JSONObject? = client.newCall(getReq).execute().use { r ->
                if (r.code == 404) null
                else {
                    if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
                    JSONObject(r.body?.string() ?: "{}")
                }
            }
            val rChats = Store.chatsFromJson(remote?.optJSONArray("chats") ?: JSONArray())
            val rMem = remote?.optJSONObject("memory")?.let { Store.memFromJson(it) }
            val rDelArr = remote?.optJSONArray("deleted") ?: JSONArray()
            val rDel = mutableSetOf<String>()
            for (i in 0 until rDelArr.length()) rDel.add(rDelArr.optString(i))

            val mergedDel = deleted + rDel
            val mergedChats = mergeChats(chats, rChats, mergedDel)
            val mergedMem = if (rMem != null && rMem.updated > mem.updated) rMem else mem

            val body = JSONObject()
                .put("chats", Store.chatsToJson(mergedChats))
                .put("memory", Store.memToJson(mergedMem))
                .put("deleted", JSONArray(mergedDel.toList().takeLast(500)))
            val putReq = Request.Builder().url(url).header("Authorization", "Bearer $token")
                .put(body.toString().toRequestBody("application/json".toMediaType())).build()
            client.newCall(putReq).execute().use { r ->
                if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
            }
            SyncResult(mergedChats, mergedMem, mergedDel)
        }
}
