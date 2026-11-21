package com.memostore.freeaichat

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** image — data-URL фото только для текущего запроса, на диск не сохраняется. */
data class Msg(val role: String, val text: String, val meta: String = "", val image: String? = null)

data class Chat(val id: String, val title: String, val messages: List<Msg>, val updated: Long)

data class MemoryState(
    val notes: List<String> = emptyList(),
    val style: String = "",
    val auto: Boolean = true,
    val updated: Long = 0L
)

/** Локальное хранилище: история чатов, долговременная память и «надгробия» удалённых чатов (для синхронизации). */
class Store(private val ctx: Context) {
    private val masterKey by lazy { MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build() }
    private val chatsFile = File(ctx.filesDir, "chats.json")
    private val memFile = File(ctx.filesDir, "memory.json")
    private val delFile = File(ctx.filesDir, "deleted.json")

    companion object {
        fun chatsToJson(chats: List<Chat>): JSONArray {
            val arr = JSONArray()
            chats.forEach { c ->
                val ms = JSONArray()
                c.messages.forEach { ms.put(JSONObject().put("role", it.role).put("text", it.text).put("meta", it.meta)) }
                arr.put(JSONObject().put("id", c.id).put("title", c.title).put("updated", c.updated).put("messages", ms))
            }
            return arr
        }

        fun chatsFromJson(arr: JSONArray): List<Chat> {
            val out = mutableListOf<Chat>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val ms = o.optJSONArray("messages") ?: JSONArray()
                val msgs = mutableListOf<Msg>()
                for (j in 0 until ms.length()) {
                    val m = ms.optJSONObject(j) ?: continue
                    msgs.add(Msg(m.optString("role"), m.optString("text"), m.optString("meta")))
                }
                val id = o.optString("id")
                if (id.isNotBlank()) out.add(Chat(id, o.optString("title"), msgs, o.optLong("updated")))
            }
            return out.sortedByDescending { it.updated }
        }

        fun memToJson(m: MemoryState): JSONObject {
            val arr = JSONArray()
            m.notes.forEach { arr.put(it) }
            return JSONObject().put("notes", arr).put("style", m.style).put("auto", m.auto).put("updated", m.updated)
        }

        fun memFromJson(o: JSONObject): MemoryState {
            val n = o.optJSONArray("notes") ?: JSONArray()
            val notes = mutableListOf<String>()
            for (i in 0 until n.length()) n.optString(i).takeIf { it.isNotBlank() }?.let { notes.add(it) }
            return MemoryState(notes, o.optString("style"), o.optBoolean("auto", true), o.optLong("updated"))
        }
    }

    private fun enc(file: File) = EncryptedFile.Builder(ctx, file, masterKey, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB).build()

    /** Читает зашифрованный файл; файлы старого формата (открытый JSON) тоже читаются и при следующей записи шифруются. */
    private fun readText(file: File): String? {
        if (!file.exists()) return null
        return try {
            enc(file).openFileInput().use { String(it.readBytes(), Charsets.UTF_8) }
        } catch (e: Exception) {
            try { file.readText(Charsets.UTF_8) } catch (e2: Exception) { null }
        }
    }

    /** Пишет зашифрованно (AES-256-GCM, ключ в Android Keystore). Если шифрование недоступно, пишет открытым текстом, чтобы не потерять данные. */
    private fun writeText(file: File, content: String) {
        try {
            if (file.exists()) file.delete()
            enc(file).openFileOutput().use { it.write(content.toByteArray(Charsets.UTF_8)) }
        } catch (e: Exception) {
            try { file.delete(); file.writeText(content, Charsets.UTF_8) } catch (e2: Exception) { }
        }
    }

    fun loadChats(): List<Chat> = try {
        val t = readText(chatsFile)
        if (t == null) emptyList() else chatsFromJson(JSONArray(t))
    } catch (e: Exception) {
        emptyList()
    }

    fun saveChats(chats: List<Chat>) = writeText(chatsFile, chatsToJson(chats).toString())

    fun loadMemory(): MemoryState = try {
        val t = readText(memFile)
        if (t == null) MemoryState() else memFromJson(JSONObject(t))
    } catch (e: Exception) {
        MemoryState()
    }

    /** Сохраняет память; без keepTimestamp обновляет метку времени (нужна для синхронизации). */
    fun saveMemory(m: MemoryState, keepTimestamp: Boolean = false): MemoryState {
        val saved = if (keepTimestamp && m.updated > 0) m else m.copy(updated = System.currentTimeMillis())
        writeText(memFile, memToJson(saved).toString())
        return saved
    }

    fun loadDeleted(): Set<String> = try {
        val t = readText(delFile)
        if (t == null) emptySet() else {
            val a = JSONArray(t)
            buildSet { for (i in 0 until a.length()) add(a.optString(i)) }
        }
    } catch (e: Exception) {
        emptySet()
    }

    fun saveDeleted(ids: Set<String>) {
        val a = JSONArray()
        ids.toList().takeLast(500).forEach { a.put(it) }
        writeText(delFile, a.toString())
    }
}
