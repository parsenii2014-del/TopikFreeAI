package com.memostore.freeaichat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class Coin(val id: String, val usd: Double, val change24h: Double)

data class WalletInfo(val tonBalance: Double, val nfts: List<String>)

data class AlertRule(val id: String, val coin: String, val above: Boolean, val price: Double, val fired: Boolean = false)

fun rulesToJson(rules: List<AlertRule>): String {
    val a = JSONArray()
    rules.forEach { a.put(JSONObject().put("id", it.id).put("coin", it.coin).put("above", it.above).put("price", it.price).put("fired", it.fired)) }
    return a.toString()
}

fun rulesFromJson(s: String): List<AlertRule> = try {
    val a = JSONArray(s)
    val out = mutableListOf<AlertRule>()
    for (i in 0 until a.length()) {
        val o = a.optJSONObject(i) ?: continue
        out.add(AlertRule(o.optString("id"), o.optString("coin"), o.optBoolean("above"), o.optDouble("price"), o.optBoolean("fired")))
    }
    out
} catch (e: Exception) {
    emptyList()
}

fun fmtPrice(v: Double): String = if (v >= 1) String.format(java.util.Locale.US, "%,.2f", v) else String.format(java.util.Locale.US, "%.6f", v)

/** Курсы (CoinGecko), кошелёк TON только для чтения (tonapi.io), Telegram Bot API и связь с облачным сервером. */
class CryptoClient(client: OkHttpClient) {
    private val http = client.newBuilder().readTimeout(20, TimeUnit.SECONDS).build()

    private suspend fun request(req: Request): String = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { r ->
            val raw = r.body?.string() ?: ""
            if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
            raw
        }
    }

    suspend fun prices(ids: List<String>): List<Coin> {
        if (ids.isEmpty()) return emptyList()
        val enc = URLEncoder.encode(ids.joinToString(","), "UTF-8")
        val url = "https://api.coingecko.com/api/v3/simple/price?ids=$enc&vs_currencies=usd&include_24hr_change=true"
        val o = JSONObject(request(Request.Builder().url(url).header("Accept", "application/json").build()))
        val out = mutableListOf<Coin>()
        for (id in ids) {
            val c = o.optJSONObject(id) ?: continue
            val usd = c.optDouble("usd")
            if (!usd.isNaN()) out.add(Coin(id, usd, c.optDouble("usd_24h_change", 0.0).let { if (it.isNaN()) 0.0 else it }))
        }
        return out
    }

    suspend fun wallet(addr: String): WalletInfo {
        val a = addr.trim()
        if (!Regex("^[A-Za-z0-9_:\\-]{40,70}$").matches(a)) throw Exception("Похоже, это не TON-адрес")
        val enc = URLEncoder.encode(a, "UTF-8")
        val acc = JSONObject(request(Request.Builder().url("https://tonapi.io/v2/accounts/$enc").build()))
        val balance = acc.optLong("balance") / 1e9
        val names = mutableListOf<String>()
        try {
            val n = JSONObject(request(Request.Builder().url("https://tonapi.io/v2/accounts/$enc/nfts?limit=100&indirect_ownership=false").build()))
            val items = n.optJSONArray("nft_items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val nm = item.optJSONObject("metadata")?.optString("name").orEmpty().ifBlank { "NFT" }
                val col = item.optJSONObject("collection")?.optString("name").orEmpty()
                names.add(if (col.isBlank()) nm else "$nm · $col")
            }
        } catch (e: Exception) {
        }
        return WalletInfo(balance, names)
    }

    suspend fun tgSend(token: String, chat: String, text: String) {
        val body = JSONObject().put("chat_id", chat.trim()).put("text", text.take(4000)).put("disable_web_page_preview", true)
        val req = Request.Builder().url("https://api.telegram.org/bot${token.trim()}/sendMessage")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        request(req)
    }

    /** Находит chat id: напиши своему боту /start, затем вызови. */
    suspend fun tgFindChat(token: String): String? {
        val o = JSONObject(request(Request.Builder().url("https://api.telegram.org/bot${token.trim()}/getUpdates").build()))
        val res = o.optJSONArray("result") ?: return null
        var found: String? = null
        for (i in 0 until res.length()) {
            val id = res.optJSONObject(i)?.optJSONObject("message")?.optJSONObject("chat")?.optLong("id", 0L) ?: 0L
            if (id != 0L) found = id.toString()
        }
        return found
    }

    /** Флор-цены подарков через твой сервер (Fragment, Portals, MRKT). */
    suspend fun giftFloor(base: String, token: String, q: String): String {
        if (base.isBlank()) throw Exception("Нужен облачный сервер (Настройки → Облако)")
        val raw = request(Request.Builder().url(base.trimEnd('/') + "/gifts/floor?collection=" + URLEncoder.encode(q.trim(), "UTF-8")).header("Authorization", "Bearer $token").build())
        return JSONObject(raw).optString("text", raw)
    }

    suspend fun giftTop(base: String, token: String): String {
        if (base.isBlank()) throw Exception("Нужен облачный сервер (Настройки → Облако)")
        val o = JSONObject(request(Request.Builder().url(base.trimEnd('/') + "/gifts/top?n=10").header("Authorization", "Bearer $token").build()))
        val items = o.optJSONArray("items") ?: JSONArray()
        val sb = StringBuilder("Топ флоров Fragment (GRAM):\n")
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            sb.append("${i + 1}. ${it.optString("collection")}: ${it.opt("price")}").append(if (it.optBoolean("auction")) " (аукцион)\n" else "\n")
        }
        return sb.append(o.optString("attribution")).toString()
    }

    suspend fun pushAlerts(base: String, token: String, tgToken: String, tgChat: String, rules: List<AlertRule>) {
        val body = JSONObject()
            .put("telegram", JSONObject().put("token", tgToken).put("chat", tgChat))
            .put("rules", JSONArray(rulesToJson(rules)))
        request(
            Request.Builder().url(base.trimEnd('/') + "/alerts").header("Authorization", "Bearer $token")
                .put(body.toString().toRequestBody("application/json".toMediaType())).build()
        )
    }

    suspend fun pullAlerts(base: String, token: String): List<AlertRule> {
        val raw = request(Request.Builder().url(base.trimEnd('/') + "/alerts").header("Authorization", "Bearer $token").build())
        return rulesFromJson(JSONObject(raw).optJSONArray("rules")?.toString() ?: "[]")
    }
}
