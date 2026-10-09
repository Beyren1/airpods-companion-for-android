package dev.podscompanion.data.media

import dev.podscompanion.protocol.aap.ListeningMode

/**
 * Умное продолжение: если наушники сняли больше чем на [awayMs], то при надевании трек
 * отматывается на [rewindMs] назад — чтобы не потерять нить подкаста или книги.
 * Класс без Android, тестируется на JVM.
 */
class SmartResume(
    private val awayMs: Long = 2 * 60_000L,
    val rewindMs: Long = 5_000L,
) {
    private var pausedAtMs: Long? = null

    /** Автопауза: наушники сняли. */
    fun onPaused(nowMs: Long) {
        pausedAtMs = nowMs
    }

    /** Наушники надели и музыка сейчас продолжится: true — сначала отмотать назад. */
    fun shouldRewind(nowMs: Long): Boolean {
        val at = pausedAtMs ?: return false
        pausedAtMs = null
        return nowMs - at >= awayMs
    }
}

/**
 * Режим шумоподавления под приложение: когда начинает играть другое приложение, для которого
 * выбран режим, включаем его. Только при смене приложения: если пользователь сам поменял режим,
 * пауза и продолжение в том же приложении его не перебьют.
 */
class AppModeSwitcher {
    private var lastPackage: String? = null

    /** [playingPackage] — что играет сейчас (null — ничего). Возвращает режим, который пора включить. */
    fun onPlaying(playingPackage: String?, rules: Map<String, ListeningMode>): ListeningMode? {
        if (playingPackage == null || playingPackage == lastPackage) return null
        lastPackage = playingPackage
        return rules[playingPackage]
    }

    /** Наушники отключили: при следующем подключении правило сработает снова. */
    fun reset() {
        lastPackage = null
    }
}

/** Правила «приложение → режим» хранятся в настройках набором строк «пакет=РЕЖИМ». */
object AppModeRulesCodec {
    fun encode(rules: Map<String, ListeningMode>): Set<String> =
        rules.filterValues { it != ListeningMode.UNKNOWN }.map { (pkg, mode) -> "$pkg=${mode.name}" }.toSet()

    fun decode(lines: Set<String>): Map<String, ListeningMode> = lines.mapNotNull { line ->
        val pkg = line.substringBefore('=', "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val mode = ListeningMode.entries.firstOrNull { it.name == line.substringAfter('=') && it != ListeningMode.UNKNOWN }
            ?: return@mapNotNull null
        pkg to mode
    }.toMap()
}
