package com.hemant.plannerv1.agent

data class TargetAppDetection(
    val appName: String,
    val matchedText: String,
)

object AppTargetDetector {
    private const val MIN_MATCH_CHARS = 4

    private val excludedExactMatches = setOf(
        "account",
        "action",
        "ads",
        "alert",
        "admin",
        "alarm",
        "analytics",
        "android",
        "angel",
        "answer",
        "answers",
        "app",
        "apps",
        "apple",
        "assistant",
        "auth",
        "backup",
        "bank",
        "band",
        "basket",
        "benefit",
        "benefits",
        "bhim",
        "bill",
        "billing",
        "bills",
        "binance",
        "book",
        "booking",
        "books",
        "box",
        "browser",
        "budget",
        "business",
        "buy",
        "calculator",
        "calendar",
        "call",
        "camera",
        "card",
        "care",
        "cash",
        "cashapp",
        "center",
        "channel",
        "chat",
        "chats",
        "chase",
        "chrome",
        "cinema",
        "class",
        "clock",
        "cloud",
        "club",
        "coach",
        "coin",
        "coinbase",
        "college",
        "color",
        "community",
        "company",
        "connect",
        "contact",
        "contacts",
        "control",
        "coupon",
        "coupons",
        "course",
        "cred",
        "crypto",
        "daily",
        "data",
        "dash",
        "deal",
        "deals",
        "deliver",
        "delivery",
        "design",
        "digital",
        "district",
        "docs",
        "download",
        "downloader",
        "door",
        "drive",
        "driver",
        "eats",
        "education",
        "email",
        "enterprise",
        "event",
        "events",
        "express",
        "family",
        "fast",
        "file",
        "files",
        "find",
        "finance",
        "fitness",
        "flight",
        "flights",
        "folder",
        "food",
        "free",
        "gallery",
        "game",
        "games",
        "gemini",
        "gift",
        "gifts",
        "global",
        "gold",
        "google",
        "gpay",
        "green",
        "grocery",
        "groceries",
        "group",
        "groww",
        "guide",
        "health",
        "help",
        "home",
        "hotel",
        "hotels",
        "india",
        "insurance",
        "invest",
        "investment",
        "investments",
        "internet",
        "jar",
        "jobs",
        "kakaopay",
        "keep",
        "kids",
        "lens",
        "learn",
        "learning",
        "line",
        "lite",
        "live",
        "loan",
        "local",
        "login",
        "mail",
        "map",
        "maps",
        "market",
        "mart",
        "max",
        "meet",
        "media",
        "message",
        "messages",
        "messenger",
        "mini",
        "mobile",
        "money",
        "movie",
        "movies",
        "music",
        "naverpay",
        "network",
        "news",
        "note",
        "notes",
        "office",
        "online",
        "open",
        "order",
        "official",
        "outlook",
        "page",
        "park",
        "partner",
        "pass",
        "pay",
        "payco",
        "paypal",
        "payment",
        "paytm",
        "phone",
        "phonepe",
        "photo",
        "photos",
        "plan",
        "planner",
        "player",
        "play",
        "plus",
        "point",
        "points",
        "portal",
        "post",
        "prime",
        "print",
        "pro",
        "premium",
        "radio",
        "reader",
        "recharge",
        "red",
        "reward",
        "rewards",
        "ride",
        "rider",
        "robinhood",
        "room",
        "sale",
        "samsungpay",
        "scan",
        "scanner",
        "school",
        "search",
        "secure",
        "sell",
        "seller",
        "service",
        "services",
        "setting",
        "settings",
        "sheet",
        "sheets",
        "ship",
        "shipping",
        "shop",
        "shopping",
        "share",
        "show",
        "signal",
        "slack",
        "snow",
        "social",
        "space",
        "speed",
        "sports",
        "star",
        "stock",
        "stocks",
        "store",
        "student",
        "talk",
        "tax",
        "taxes",
        "taxi",
        "team",
        "target",
        "teams",
        "thanks",
        "threads",
        "ticket",
        "tickets",
        "toss",
        "trade",
        "trading",
        "train",
        "translate",
        "travel",
        "trip",
        "tube",
        "university",
        "upstox",
        "venmo",
        "video",
        "voice",
        "wallet",
        "watch",
        "weather",
        "web",
        "white",
        "wish",
        "work",
        "x",
        "zelle",
        "zoom",
    )

    private val excludedPhrases = setOf(
        "google assistant",
        "google calendar",
        "google chrome",
        "google contacts",
        "google docs",
        "google drive",
        "google gemini",
        "google keep",
        "google maps",
        "google meet",
        "google messages",
        "google news",
        "google pay",
        "google photos",
        "google play",
        "google sheets",
        "google wallet",
        "cash app",
        "microsoft outlook",
        "naver map",
        "naver pay",
        "kakao pay",
        "kakao t",
        "samsung calendar",
        "samsung clock",
        "samsung contacts",
        "samsung internet",
        "samsung messages",
        "samsung notes",
        "samsung pay",
    )

    fun detect(goal: String, installedAppNames: List<String>): TargetAppDetection? {
        val normalizedGoal = normalizePhrase(goal)
        if (normalizedGoal.isBlank()) return null

        val matches = installedAppNames
            .asSequence()
            .flatMap { appName ->
                candidatesForAppName(appName).asSequence().map { candidate ->
                    AppCandidate(appName = appName.trim(), normalizedCandidate = candidate)
                }
            }
            .filter { it.appName.isNotBlank() }
            .filter { containsExactPhrase(normalizedGoal, it.normalizedCandidate) }
            .distinctBy { "${it.appName.lowercase()}|${it.normalizedCandidate}" }
            .toList()

        if (matches.isEmpty()) return null

        val bestLength = matches.maxOf { it.normalizedCandidate.length }
        val bestMatches = matches.filter { it.normalizedCandidate.length == bestLength }
        val distinctApps = bestMatches.map { it.appName }.distinctBy { it.lowercase() }
        if (distinctApps.size != 1) return null

        val best = bestMatches.first()
        return TargetAppDetection(
            appName = best.appName,
            matchedText = best.normalizedCandidate,
        )
    }

    private fun candidatesForAppName(appName: String): Set<String> {
        val candidates = linkedSetOf<String>()
        val wordLists = linkedSetOf(
            tokenize(appName),
            tokenize(splitCamelCase(appName)),
        ).filter { it.isNotEmpty() }

        for (words in wordLists) {
            val fullPhrase = words.joinToString(" ")
            if (shouldSkipWordList(fullPhrase, words)) {
                continue
            }
            if (isAllowedCandidate(fullPhrase)) {
                candidates += fullPhrase
            }
            words.forEach { word ->
                if (isAllowedCandidate(word)) {
                    candidates += word
                }
            }
            words.windowed(size = 2).forEach { pair ->
                val phrase = pair.joinToString(" ")
                if (isAllowedCandidate(phrase)) {
                    candidates += phrase
                }
            }
        }
        return candidates
    }

    private fun shouldSkipWordList(fullPhrase: String, words: List<String>): Boolean {
        val normalized = normalizePhrase(fullPhrase)
        return normalized in excludedPhrases ||
            words.all { it in excludedExactMatches } ||
            (words.size == 1 && words.first() in excludedExactMatches)
    }

    private fun isAllowedCandidate(candidate: String): Boolean {
        val normalized = normalizePhrase(candidate)
        if (normalized.length < MIN_MATCH_CHARS) return false
        if (normalized in excludedPhrases) return false

        val words = normalized.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return false
        if (words.any { it.length < MIN_MATCH_CHARS }) return false
        if (words.all { it in excludedExactMatches }) return false
        if (words.size == 1 && words.first() in excludedExactMatches) return false
        return true
    }

    private fun containsExactPhrase(normalizedGoal: String, normalizedCandidate: String): Boolean {
        return " $normalizedGoal ".contains(" $normalizedCandidate ")
    }

    private fun tokenize(value: String): List<String> {
        return normalizePhrase(value)
            .split(' ')
            .filter { it.isNotBlank() }
    }

    private fun splitCamelCase(value: String): String {
        return value.replace(Regex("""(?<=[\p{Ll}\p{Nd}])(?=\p{Lu})"""), " ")
    }

    private fun normalizePhrase(value: String): String {
        return value
            .lowercase()
            .replace(Regex("""[^\p{L}\p{Nd}]+"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")
    }

    private data class AppCandidate(
        val appName: String,
        val normalizedCandidate: String,
    )
}
