package dev.podscompanion.protocol.aap

/**
 * Настройки наушников. Все они ходят одним пакетом CONTROL (opcode 0x0009):
 * `04 00 04 00 09 00 <id> <v1> <v2> <v3> <v4>`. Наушники присылают текущие значения сразу после
 * подписки на уведомления и после каждого изменения, а команда на изменение — тот же пакет.
 *
 * Номера и значения взяты из публичного реверса и сверены не все: экран показывает настройку,
 * только если наушники сами о ней сообщили, а журнал AAP подписывает каждое значение.
 */
object ControlId {
    const val MIC_MODE = 0x01
    const val EAR_DETECTION = 0x0A
    const val LISTENING_MODE = 0x0D
    const val PRESS_AND_HOLD = 0x16
    const val LISTENING_MODE_CYCLE = 0x1A
    const val ONE_BUD_NOISE_CONTROL = 0x1B

    /** Направление колёсика Digital Crown у Max. */
    const val CROWN_ROTATION = 0x1C
    const val PERSONALIZED_VOLUME = 0x26
    const val CONVERSATIONAL_AWARENESS = 0x28
    const val ADAPTIVE_STRENGTH = 0x2E

    /** Подпись для журнала. */
    fun name(id: Int): String = when (id) {
        MIC_MODE -> "микрофон"
        EAR_DETECTION -> "автоопределение уха"
        LISTENING_MODE -> "режим"
        PRESS_AND_HOLD -> "долгое нажатие"
        LISTENING_MODE_CYCLE -> "режимы по нажатию"
        ONE_BUD_NOISE_CONTROL -> "шумоподавление в одном ухе"
        CROWN_ROTATION -> "направление Digital Crown"
        PERSONALIZED_VOLUME -> "персонализированная громкость"
        CONVERSATIONAL_AWARENESS -> "адаптация к разговору"
        ADAPTIVE_STRENGTH -> "сила адаптивного режима"
        else -> OTHER_NAMES[id]?.let { "$it (0x%02X)".format(id) } ?: "настройка 0x%02X".format(id)
    }

    /**
     * Настройки, которые приложение пока не показывает, — только для журнала: по ним видно, что прислали
     * наушники. Названия из публичного реверса, не сверены.
     */
    private val OTHER_NAMES = mapOf(
        0x05 to "режим кнопок",
        0x06 to "владелец соединения",
        0x12 to "голосовой вызов",
        0x14 to "одно нажатие",
        0x15 to "двойное нажатие",
        0x17 to "интервал двойного нажатия",
        0x18 to "длительность зажатия",
        0x1E to "автоответ",
        0x1F to "громкость сигналов",
        0x20 to "автоподключение",
        0x23 to "интервал жеста громкости",
        0x24 to "управление звонком",
        0x25 to "жест громкости",
        0x27 to "выключение микрофона",
        0x29 to "SSL",
        0x2C to "слуховой аппарат",
        0x2F to "усиление жестом",
        0x30 to "пульсометр",
        0x31 to "звук в кейсе",
        0x32 to "сигналы Siri",
        0x33 to "помощь слуху",
        0x34 to "разрешить «Выкл»",
        0x35 to "определение сна",
        0x36 to "разрешить автоподключение",
    )
}

/** Настройки «вкл/выкл»: значение 01 — вкл, 02 — выкл. */
enum class AapToggle(val id: Int) {
    CONVERSATIONAL_AWARENESS(ControlId.CONVERSATIONAL_AWARENESS),
    PERSONALIZED_VOLUME(ControlId.PERSONALIZED_VOLUME),
    EAR_DETECTION(ControlId.EAR_DETECTION),
    ONE_BUD_NOISE_CONTROL(ControlId.ONE_BUD_NOISE_CONTROL),
}

/** Какой наушник слушает микрофоном. */
enum class MicMode(val code: Int) {
    AUTO(0x00), ALWAYS_RIGHT(0x01), ALWAYS_LEFT(0x02);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code }
    }
}

/** Что делает долгое нажатие на ножку. */
enum class PressAction(val code: Int) {
    /** Перебирает режимы из [AapDeviceState.modeCycle]. */
    NOISE_CONTROL(0x05),

    /** На iPhone — Siri; на Android наушники отправляют команду голосового помощника. */
    VOICE_ASSISTANT(0x01);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code }
    }
}

/**
 * Куда крутить Digital Crown, чтобы стало громче. Как в настройках iPhone: «Сзади вперёд» (по умолчанию)
 * или «Спереди назад». Коды 01/02 из публичного реверса, сверить по iPhone.
 */
enum class CrownDirection(val code: Int) {
    BACK_TO_FRONT(0x01), FRONT_TO_BACK(0x02);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code }
    }
}

/** Долгое нажатие: первый байт значения — правый наушник, второй — левый (сверить по журналу). */
data class PressAndHold(val right: PressAction?, val left: PressAction?)

/** Команда наушникам. [label] пишется в журнал AAP. */
sealed interface AapCommand {
    val bytes: ByteArray
    val label: String

    data class SetListeningMode(val mode: ListeningMode) : AapCommand {
        override val bytes get() = Aap.setListeningMode(mode)
        override val label get() = "режим $mode"
    }

    data class SetToggle(val toggle: AapToggle, val enabled: Boolean) : AapCommand {
        override val bytes get() = control(toggle.id, if (enabled) 0x01 else 0x02)
        override val label get() = "${ControlId.name(toggle.id)} ${if (enabled) "вкл" else "выкл"}"
    }

    data class SetMicMode(val mode: MicMode) : AapCommand {
        override val bytes get() = control(ControlId.MIC_MODE, mode.code)
        override val label get() = "микрофон $mode"
    }

    data class SetPressAndHold(val right: PressAction, val left: PressAction) : AapCommand {
        override val bytes get() = control(ControlId.PRESS_AND_HOLD, right.code, left.code)
        override val label get() = "долгое нажатие: правый $right, левый $left"
    }

    /** Какие режимы перебирает долгое нажатие: битовая маска, выкл 01, шумоподавление 02, прозрачность 04, адаптивный 08. */
    data class SetModeCycle(val modes: Set<ListeningMode>) : AapCommand {
        override val bytes get() = control(ControlId.LISTENING_MODE_CYCLE, modeCycleMask(modes))
        override val label get() = "режимы по нажатию $modes"
    }

    data class SetCrownDirection(val direction: CrownDirection) : AapCommand {
        override val bytes get() = control(ControlId.CROWN_ROTATION, direction.code)
        override val label get() = "Digital Crown $direction"
    }

    /** Сила адаптивного режима, 0..100. */
    data class SetAdaptiveStrength(val value: Int) : AapCommand {
        override val bytes get() = control(ControlId.ADAPTIVE_STRENGTH, value.coerceIn(0, 100))
        override val label get() = "сила адаптивного режима $value"
    }

    /** Новое имя наушников. Android может показывать старое до переподключения. */
    data class Rename(val name: String) : AapCommand {
        override val bytes get() = Aap.rename(name)
        override val label get() = "имя «$name»"
    }

    data object StartHeadTracking : AapCommand {
        override val bytes get() = Aap.START_HEAD_TRACKING
        override val label get() = "датчики головы: вкл"
    }

    data object StopHeadTracking : AapCommand {
        override val bytes get() = Aap.STOP_HEAD_TRACKING
        override val label get() = "датчики головы: выкл"
    }

    companion object {
        fun control(id: Int, v1: Int, v2: Int = 0, v3: Int = 0, v4: Int = 0): ByteArray =
            Aap.packet(Opcode.CONTROL, id, v1, v2, v3, v4)
    }
}

private val CYCLE_BITS = mapOf(
    ListeningMode.OFF to 0x01,
    ListeningMode.NOISE_CANCELLATION to 0x02,
    ListeningMode.TRANSPARENCY to 0x04,
    ListeningMode.ADAPTIVE to 0x08,
)

internal fun modeCycleMask(modes: Set<ListeningMode>): Int = modes.sumOf { CYCLE_BITS[it] ?: 0 }

internal fun modeCycleOf(mask: Int): Set<ListeningMode> =
    CYCLE_BITS.filterValues { mask and it != 0 }.keys

// ---------- Текущие значения из AapDeviceState.controls ----------

/** null — наушники не сообщали эту настройку (модель её не умеет или ещё не прислала). */
fun AapDeviceState.toggle(toggle: AapToggle): Boolean? = controls[toggle.id]?.firstOrNull()?.let { it == 0x01 }

val AapDeviceState.micMode: MicMode? get() = controls[ControlId.MIC_MODE]?.firstOrNull()?.let(MicMode::of)

val AapDeviceState.pressAndHold: PressAndHold?
    get() = controls[ControlId.PRESS_AND_HOLD]?.let { PressAndHold(PressAction.of(it[0]), PressAction.of(it.getOrElse(1) { 0 })) }

val AapDeviceState.modeCycle: Set<ListeningMode>?
    get() = controls[ControlId.LISTENING_MODE_CYCLE]?.firstOrNull()?.let(::modeCycleOf)

val AapDeviceState.adaptiveStrength: Int?
    get() = controls[ControlId.ADAPTIVE_STRENGTH]?.firstOrNull()

val AapDeviceState.crownDirection: CrownDirection?
    get() = controls[ControlId.CROWN_ROTATION]?.firstOrNull()?.let(CrownDirection::of)

/**
 * Заводские значения настроек Max: Max после подключения не присылают текущие значения, но команды
 * на изменение принимают. Пока наушники не сообщили своё, экран показывает эти.
 * Кнопка шумоподавления: шумоподавление + прозрачность (02 + 04); надевание определяется; Crown сзади вперёд.
 */
val MAX_DEFAULT_CONTROLS: Map<Int, List<Int>> = mapOf(
    ControlId.LISTENING_MODE_CYCLE to listOf(0x06, 0x00, 0x00, 0x00),
    ControlId.EAR_DETECTION to listOf(0x01, 0x00, 0x00, 0x00),
    ControlId.CROWN_ROTATION to listOf(CrownDirection.BACK_TO_FRONT.code, 0x00, 0x00, 0x00),
)

/** Состояние, где недостающие настройки взяты из [defaults]; то, что прислали наушники, важнее. */
fun AapDeviceState.withDefaults(defaults: Map<Int, List<Int>>): AapDeviceState =
    if (defaults.keys.all { it in controls }) this else copy(controls = defaults + controls)
