package com.memostore.freeaichat

enum class Kind(val label: String) {
    CODE("код"), REASON("сложная задача"), FAST("быстрый ответ"), WRITE("текст"), GENERAL("общий вопрос")
}

private val codeWords = listOf("```", "def ", "class ", "function", "функци", "ошибк", "баг", "код", "kotlin", "python", "java", "sql", "javascript", "компил", "stacktrace", "exception", "gradle", "скрипт", "регулярк", "алгоритм")
private val reasonWords = listOf("докажи", "обоснуй", "проанализируй", "сравни", "стратег", "почему", "разбери", "оцени", "спланируй", "рассчитай", "посчитай", "реши", "исследуй", "пошагово", "анализ")
private val writeWords = listOf("напиши текст", "статью", "пост", "письмо", "сочинение", "рассказ", "сценарий", "перепиши", "отредактируй", "слоган", "описание")

private val fastHints = listOf("flash", "mini", "haiku", "small", "8b", "7b", "lite", "instant", "nano", "fast", "turbo")
private val smartHints = listOf("pro", "opus", "sonnet", "70b", "72b", "405b", "large", "r1", "reason", "think", "o1", "o3", "gpt-4", "gemini", "qwen3", "deepseek", "llama-3.3", "maverick")
private val codeHints = listOf("coder", "code", "codestral", "deepseek", "qwen")

/** Грубая эвристическая классификация запроса. */
fun classify(prompt: String, mode: String): Kind {
    val p = prompt.lowercase()
    if (mode == "Code") return Kind.CODE
    if (mode == "Agents") return Kind.REASON
    if (codeWords.any { p.contains(it) }) return Kind.CODE
    if (writeWords.any { p.contains(it) }) return Kind.WRITE
    if (p.length > 500 || reasonWords.any { p.contains(it) }) return Kind.REASON
    if (p.length < 70) return Kind.FAST
    return Kind.GENERAL
}

private fun score(kind: Kind, id: String): Int {
    val s = id.lowercase()
    var v = 0
    when (kind) {
        Kind.CODE -> {
            if (codeHints.any { s.contains(it) }) v += 3
            if (smartHints.any { s.contains(it) }) v += 1
        }
        Kind.REASON -> if (smartHints.any { s.contains(it) }) v += 3
        Kind.FAST -> if (fastHints.any { s.contains(it) }) v += 3
        Kind.WRITE -> if (smartHints.any { s.contains(it) }) v += 2
        Kind.GENERAL -> {}
    }
    if (kind != Kind.FAST && fastHints.any { s.contains(it) }) v -= 1
    return v
}

/** Список кандидатов по порядку: лучшие по названию модели, затем авто-алиас, затем надёжный запасной вариант. */
fun rankModels(kind: Kind, available: List<String>): List<String> {
    val real = available.filter { !it.startsWith("auto") }
    val top = real.map { it to score(kind, it) }.filter { it.second >= 3 }.sortedByDescending { it.second }.take(2).map { it.first }
    val alias = when (kind) {
        Kind.CODE, Kind.REASON -> "auto:smart"
        Kind.FAST -> "auto:fast"
        else -> "auto"
    }
    return (top + listOf(alias, "auto:reliable")).distinct().take(4)
}
