package com.memostore.freeaichat

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

private val presetCoins = listOf("bitcoin", "ethereum", "the-open-network", "solana", "notcoin")

@Composable
private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(panel, RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        content()
    }
}

/**
 * Вкладка «Крипто»: курсы, TON-кошелёк (только чтение), Telegram-уведомления и ценовые алерты.
 * Алерты проверяются в приложении или, если включено, на собственном сервере 24/7.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CryptoTab(prefs: SharedPreferences, crypto: CryptoClient, cloudUrl: String, cloudToken: String, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var coins by remember { mutableStateOf((prefs.getString("coins", "bitcoin,the-open-network,ethereum") ?: "").split(",").filter { it.isNotBlank() }) }
    var prices by remember { mutableStateOf(emptyMap<String, Coin>()) }
    var status by remember { mutableStateOf("") }
    var addr by remember { mutableStateOf(prefs.getString("ton_addr", "") ?: "") }
    var wallet by remember { mutableStateOf<WalletInfo?>(null) }
    var walletMsg by remember { mutableStateOf("") }
    var tgToken by remember { mutableStateOf(prefs.getString("tg_token", "") ?: "") }
    var tgChat by remember { mutableStateOf(prefs.getString("tg_chat", "") ?: "") }
    var tgMsg by remember { mutableStateOf("") }
    var rules by remember { mutableStateOf(rulesFromJson(prefs.getString("rules", "[]") ?: "[]")) }
    var serverWatch by remember { mutableStateOf(prefs.getBoolean("server_watch", false)) }
    var newCoin by remember { mutableStateOf("") }
    var ruleCoin by remember { mutableStateOf(coins.firstOrNull() ?: "bitcoin") }
    var ruleAbove by remember { mutableStateOf(true) }
    var rulePrice by remember { mutableStateOf("") }
    var giftQuery by remember { mutableStateOf("") }
    var giftText by remember { mutableStateOf("") }
    var giftPrice by remember { mutableStateOf("") }

    fun saveCoins(list: List<String>) {
        coins = list
        prefs.edit().putString("coins", list.joinToString(",")).apply()
    }

    fun saveRules(list: List<AlertRule>) {
        rules = list
        prefs.edit().putString("rules", rulesToJson(list)).apply()
    }

    fun pushServer() {
        if (!serverWatch || cloudUrl.isBlank()) return
        val token = cloudToken
        val currentRules = rules
        scope.launch {
            try {
                crypto.pushAlerts(cloudUrl, token, tgToken, tgChat, currentRules)
                tgMsg = "Правила отправлены на сервер"
            } catch (e: Exception) {
                tgMsg = "Сервер: ${e.message}"
            }
        }
    }

    suspend fun checkRulesLocal() {
        var changed = false
        val updated = mutableListOf<AlertRule>()
        for (r in rules) {
            if (r.coin.startsWith("gift:")) { updated.add(r); continue } // алерты на подарки проверяет только сервер
            val p = prices[r.coin]?.usd
            val hit = p != null && !r.fired && ((r.above && p >= r.price) || (!r.above && p <= r.price))
            if (hit && p != null) {
                var delivered = true
                if (tgToken.isNotBlank() && tgChat.isNotBlank()) {
                    try {
                        crypto.tgSend(tgToken, tgChat, "🔔 ${r.coin}: ${fmtPrice(p)} USD (${if (r.above) "≥" else "≤"} ${fmtPrice(r.price)})")
                    } catch (e: Exception) {
                        delivered = false
                        tgMsg = "Telegram: ${e.message}"
                    }
                }
                if (delivered) {
                    changed = true
                    updated.add(r.copy(fired = true))
                    status = "Сработал алерт: ${r.coin}"
                } else updated.add(r)
            } else updated.add(r)
        }
        if (changed) saveRules(updated)
    }

    fun refresh() {
        scope.launch {
            try {
                val list = crypto.prices(coins)
                prices = list.associateBy { it.id }
                status = "Обновлено"
                if (serverWatch && cloudUrl.isNotBlank()) {
                    try {
                        val remote = crypto.pullAlerts(cloudUrl, cloudToken)
                        if (remote.isNotEmpty() || rules.isEmpty()) saveRules(remote)
                    } catch (e: Exception) {
                    }
                } else checkRulesLocal()
            } catch (e: Exception) {
                status = "Курсы: ${e.message}"
            }
        }
    }

    fun connectWallet() {
        val a = addr.trim()
        prefs.edit().putString("ton_addr", a).apply()
        if (a.isEmpty()) {
            wallet = null
            walletMsg = ""
            return
        }
        scope.launch {
            try {
                wallet = crypto.wallet(a)
                walletMsg = "Кошелёк подключён (только чтение)"
            } catch (e: Exception) {
                walletMsg = "Кошелёк: ${e.message}"
            }
        }
    }

    LaunchedEffect(Unit) {
        if (addr.isNotBlank()) connectWallet()
        while (true) {
            refresh()
            delay(60_000)
        }
    }

    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
        item {
            Panel("Курсы (USD)") {
                if (coins.isEmpty()) Text("Список пуст — добавь монету ниже.", color = muted, fontSize = 12.sp)
                coins.forEach { id ->
                    val c = prices[id]
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(id, Modifier.weight(1f), fontSize = 14.sp)
                        if (c != null) {
                            Text(fmtPrice(c.usd), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "  " + String.format(Locale.US, "%+.2f", c.change24h) + "%",
                                fontSize = 12.sp, color = if (c.change24h >= 0) Color(0xFF7ED38A) else danger
                            )
                        } else Text("…", color = muted)
                        IconButton(onClick = { saveCoins(coins.filter { it != id }) }) { Icon(Icons.Default.Close, "Убрать", tint = muted) }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    presetCoins.filter { it !in coins }.forEach { p ->
                        AssistChip(onClick = { saveCoins(coins + p); refresh() }, label = { Text("+ $p", fontSize = 11.sp) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newCoin, { newCoin = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("id монеты на CoinGecko", color = muted) })
                    IconButton(onClick = {
                        val id = newCoin.trim().lowercase()
                        if (id.isNotEmpty() && id !in coins) { saveCoins(coins + id); newCoin = ""; refresh() }
                    }) { Icon(Icons.Default.Add, "Добавить") }
                    IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, "Обновить") }
                }
                if (status.isNotBlank()) Text(status, color = muted, fontSize = 11.sp)
            }
        }
        item {
            Panel("Кошелёк GRAM (TON) и NFT-подарки") {
                Text("Вставь публичный адрес — приложение только читает баланс и NFT. Никогда не вводи seed-фразу или приватный ключ.", color = muted, fontSize = 11.sp)
                OutlinedTextField(addr, { addr = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("UQ… / EQ… адрес", color = muted) })
                Button(onClick = { connectWallet() }) { Text(if (wallet == null) "Подключить" else "Обновить") }
                if (walletMsg.isNotBlank()) Text(walletMsg, color = muted, fontSize = 11.sp)
                wallet?.let { w ->
                    Text("Баланс: " + String.format(Locale.US, "%.4f", w.tonBalance) + " GRAM (бывший TON)", fontWeight = FontWeight.Medium)
                    Text("NFT: ${w.nfts.size}", fontSize = 13.sp)
                    w.nfts.take(25).forEach { Text("• $it", fontSize = 12.sp, color = muted) }
                    if (w.nfts.size > 25) Text("… и ещё ${w.nfts.size - 25}", fontSize = 11.sp, color = muted)
                }
            }
        }
        item {
            Panel("Telegram-уведомления") {
                Text("Создай бота у @BotFather, вставь токен, отправь боту /start и нажми «Найти chat id».", color = muted, fontSize = 11.sp)
                OutlinedTextField(tgToken, { tgToken = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(), placeholder = { Text("Токен бота", color = muted) })
                OutlinedTextField(tgChat, { tgChat = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Chat id", color = muted) })
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            try {
                                val id = crypto.tgFindChat(tgToken)
                                if (id != null) { tgChat = id; tgMsg = "Chat id найден" } else tgMsg = "Сначала отправь боту /start"
                            } catch (e: Exception) { tgMsg = "Telegram: ${e.message}" }
                        }
                    }) { Text("Найти chat id") }
                    Button(onClick = {
                        prefs.edit().putString("tg_token", tgToken).putString("tg_chat", tgChat).apply()
                        scope.launch {
                            try {
                                crypto.tgSend(tgToken, tgChat, "✅ FreeAI подключён к Telegram")
                                tgMsg = "Тест отправлен, настройки сохранены"
                            } catch (e: Exception) { tgMsg = "Telegram: ${e.message}" }
                        }
                        pushServer()
                    }) { Text("Сохранить и проверить") }
                }
                if (tgMsg.isNotBlank()) Text(tgMsg, color = muted, fontSize = 11.sp)
            }
        }
        item {
            Panel("Telegram-подарки") {
                Text("Флор-цены с Fragment, а если на сервере заданы ключи, то ещё с Portals и MRKT. Это цены продавцов, не сделок; комиссии не учтены. Единица: GRAM (бывший TON).", color = muted, fontSize = 11.sp)
                OutlinedTextField(giftQuery, { giftQuery = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("коллекция, например plushpepe", color = muted) })
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            giftText = "Загружаю…"
                            giftText = try { crypto.giftFloor(cloudUrl, cloudToken, giftQuery) } catch (e: Exception) { "Ошибка: ${e.message}" }
                        }
                    }) { Text("Флор") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            giftText = "Загружаю…"
                            giftText = try { crypto.giftTop(cloudUrl, cloudToken) } catch (e: Exception) { "Ошибка: ${e.message}" }
                        }
                    }) { Text("Топ коллекций") }
                }
                if (giftText.isNotBlank()) Text(giftText, fontSize = 12.sp, color = fg)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(giftPrice, { giftPrice = it }, Modifier.weight(1f), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), placeholder = { Text("алерт: флор ≤ цена", color = muted) })
                    IconButton(onClick = {
                        val v = giftPrice.replace(',', '.').toDoubleOrNull()
                        val slug = giftQuery.trim().lowercase().replace(Regex("[^a-z0-9а-яё_-]"), "")
                        if (v != null && v > 0 && slug.isNotEmpty()) {
                            saveRules(rules + AlertRule(UUID.randomUUID().toString(), "gift:$slug", false, v))
                            giftPrice = ""
                            pushServer()
                        }
                    }) { Icon(Icons.Default.Add, "Добавить алерт на подарок") }
                }
                Text("Алерты на подарки проверяет только сервер: включи «Следить на сервере 24/7» ниже.", color = muted, fontSize = 10.sp)
            }
        }
        item {
            Panel("Ценовые алерты") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    coins.forEach { c -> FilterChip(selected = ruleCoin == c, onClick = { ruleCoin = c }, label = { Text(c, fontSize = 11.sp) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = ruleAbove, onClick = { ruleAbove = true }, label = { Text("≥ выше") })
                    FilterChip(selected = !ruleAbove, onClick = { ruleAbove = false }, label = { Text("≤ ниже") })
                    OutlinedTextField(rulePrice, { rulePrice = it }, Modifier.weight(1f), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), placeholder = { Text("цена USD", color = muted) })
                    IconButton(onClick = {
                        val v = rulePrice.replace(',', '.').toDoubleOrNull()
                        if (v != null && v > 0 && ruleCoin.isNotBlank()) {
                            saveRules(rules + AlertRule(UUID.randomUUID().toString(), ruleCoin, ruleAbove, v))
                            rulePrice = ""
                            pushServer()
                        }
                    }) { Icon(Icons.Default.Add, "Добавить алерт") }
                }
                if (rules.isEmpty()) Text("Алертов пока нет.", color = muted, fontSize = 12.sp)
                rules.forEach { r ->
                    Row(Modifier.fillMaxWidth().background(panel2, RoundedCornerShape(10.dp)).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${r.coin} ${if (r.above) "≥" else "≤"} ${fmtPrice(r.price)}" + if (r.fired) "  ✓ сработал" else "",
                            Modifier.weight(1f), fontSize = 13.sp, color = if (r.fired) muted else fg
                        )
                        IconButton(onClick = { saveRules(rules.filter { it.id != r.id }); pushServer() }) { Icon(Icons.Default.Delete, "Удалить", tint = muted) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Следить на сервере 24/7", fontSize = 13.sp)
                        Text(
                            if (cloudUrl.isBlank()) "Нужен облачный сервер (Настройки → Облако)" else "Токен бота будет передан твоему серверу",
                            color = muted, fontSize = 10.sp
                        )
                    }
                    Switch(checked = serverWatch, enabled = cloudUrl.isNotBlank(), onCheckedChange = { v ->
                        serverWatch = v
                        prefs.edit().putBoolean("server_watch", v).apply()
                        if (v) pushServer()
                    })
                }
                Text(
                    if (serverWatch) "Алерты проверяет сервер, телефон может быть выключен." else "Алерты проверяются, пока приложение открыто.",
                    color = muted, fontSize = 11.sp
                )
            }
        }
    }
}
