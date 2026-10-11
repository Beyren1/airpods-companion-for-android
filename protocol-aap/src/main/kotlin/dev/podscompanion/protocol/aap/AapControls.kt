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

    /** Скорость двойного/тройного нажатия (Универсальный доступ на iPhone). Max присылают её после подключения. */
    const val PRESS_SPEED = 0x17

    /** Сколько держать кнопку для «зажатия». Max присылают после подключения. */
    const val HOLD_DURATION = 0x18
    const val LISTENING_MODE_CYCLE = 0x1A

    /** Направление колёсика Digital Crown у Max. */
    const val CROWN_ROTATION = 0x1C

    /** Громкость звуковых сигналов, 0..100; у Max два одинаковых байта (журнал: 50 50 = 80 %). */
    const val TONE_VOLUME = 0x1F

    /** Скорость жеста громкости по ножке: 01 обычная, 02 дольше, 03 ещё дольше. */
    const val VOLUME_SWIPE_SPEED = 0x23

    /** Звонок: что делает одно нажатие (см. [CallControl]). Max присылают 00 03. */
    const val CALL_CONTROL = 0x24

    /** Громкость свайпом по ножке (Pro 2). */
    const val VOLUME_SWIPE = 0x25
    const val PERSONALIZED_VOLUME = 0x26
    const val CONVERSATIONAL_AWARENESS = 0x28
    const val ADAPTIVE_STRENGTH = 0x2E

    /** Разрешить режим «Выкл»: новые прошивки без этого не переключаются в «Выкл». */
    const val ALLOW_OFF = 0x34

    /** Пауза, когда вы уснули (свежие прошивки). */
    const val SLEEP_DETECTION = 0x35

    /**
     * Какие нажатия ножки наушники не выполняют сами, а присылают телефону (пакет [Opcode.STEM_PRESS]):
     * битовая маска, одно 01, двойное 02, тройное 04, зажатие 08.
     */
    const val STEM_CONFIG = 0x39

    /** Оптимизированная зарядка: не заряжать до 100 %, пока это не нужно. */
    const val OPTIMIZED_CHARGING = 0x3B

    /** Подпись для журнала. */
    fun name(id: Int): String = when (id) {
        MIC_MODE -> "микрофон"
        EAR_DETECTION -> "автоопределение уха"
        LISTENING_MODE -> "режим"
        PRESS_AND_HOLD -> "долгое нажатие"
        LISTENING_MODE_CYCLE -> "режимы по нажатию"
        CROWN_ROTATION -> "направление Digital Crown"
        PRESS_SPEED -> "скорость нажатия"
        HOLD_DURATION -> "длительность зажатия"
        TONE_VOLUME -> "громкость сигналов"
        PERSONALIZED_VOLUME -> "персонализированная громкость"
        CONVERSATIONAL_AWARENESS -> "адаптация к разговору"
        ADAPTIVE_STRENGTH -> "сила адаптивного режима"
        VOLUME_SWIPE_SPEED -> "скорость жеста громкости"
        CALL_CONTROL -> "управление звонком"
        VOLUME_SWIPE -> "жест громкости"
        ALLOW_OFF -> "разрешить «Выкл»"
        SLEEP_DETECTION -> "пауза во сне"
        STEM_CONFIG -> "нажатия для телефона"
        OPTIMIZED_CHARGING -> "оптимизированная зарядка"
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
        0x1E to "автоответ",
        0x20 to "автоподключение",
        0x27 to "выключение микрофона",
        0x29 to "SSL",
        0x2C to "слуховой аппарат",
        0x2F to "усиление жестом",
        0x30 to "пульсометр",
        0x31 to "звук в кейсе",
        0x32 to "сигналы Siri",
        0x33 to "помощь слуху",
        0x36 to "разрешить автоподключение",
    )
}

/** Настройки «вкл/выкл»: значение 01 — вкл, 02 — выкл. */
enum class AapToggle(val id: Int) {
    CONVERSATIONAL_AWARENESS(ControlId.CONVERSATIONAL_AWARENESS),
    PERSONALIZED_VOLUME(ControlId.PERSONALIZED_VOLUME),
    EAR_DETECTION(ControlId.EAR_DETECTION),
    VOLUME_SWIPE(ControlId.VOLUME_SWIPE),
    ALLOW_OFF(ControlId.ALLOW_OFF),
    SLEEP_DETECTION(ControlId.SLEEP_DETECTION),
    OPTIMIZED_CHARGING(ControlId.OPTIMIZED_CHARGING),
}

/**
 * Нажатия ножки во время звонка. Второй байт значения: 03 — одно нажатие выключает и включает
 * микрофон, два — завершают звонок (заводское); 02 — наоборот. Первый байт всегда 00.
 */
enum class CallControl(val code: Int) {
    MUTE_ON_SINGLE(0x03), MUTE_ON_DOUBLE(0x02);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code }
    }
}

/** Как долго вести пальцем по ножке, чтобы сменить громкость: 01 обычно, 02 дольше, 03 ещё дольше. */
enum class VolumeSwipeSpeed(val code: Int) {
    DEFAULT(0x01), LONGER(0x02), LONGEST(0x03);

    companion object {
        /** 00 некоторые наушники присылают вместо 01: это то же «обычно». */
        fun of(code: Int) = if (code == 0x00) DEFAULT else entries.firstOrNull { it.code == code }
    }
}

/** Нажатие ножки, которое наушники прислали телефону (см. [ControlId.STEM_CONFIG]). */
enum class StemPress(val code: Int, val bit: Int) {
    SINGLE(0x05, 0x01), DOUBLE(0x06, 0x02), TRIPLE(0x07, 0x04), LONG(0x08, 0x08);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code }

        /** Маска для [AapCommand.SetStemConfig]. */
        fun mask(presses: Set<StemPress>): Int = presses.sumOf { it.bit }
    }
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

/** Скорость нажатия: 00 — обычная, 01 — медленнее, 02 — самая медленная (как в Универсальном доступе iPhone). */
enum class PressSpeed(val code: Int) {
    DEFAULT(0x00), SLOWER(0x01), SLOWEST(0x02);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code }
    }
}

/** Длительность зажатия: 00 — обычная, 01 — короче, 02 — самая короткая. */
enum class HoldDuration(val code: Int) {
    DEFAULT(0x00), SHORTER(0x01), SHORTEST(0x02);

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

    data class SetPressSpeed(val speed: PressSpeed) : AapCommand {
        override val bytes get() = control(ControlId.PRESS_SPEED, speed.code)
        override val label get() = "скорость нажатия $speed"
    }

    data class SetHoldDuration(val duration: HoldDuration) : AapCommand {
        override val bytes get() = control(ControlId.HOLD_DURATION, duration.code)
        override val label get() = "длительность зажатия $duration"
    }

    /** Громкость сигналов, 0..100: оба байта одинаковые, как присылают сами Max. */
    data class SetToneVolume(val value: Int) : AapCommand {
        override val bytes get() = value.coerceIn(0, 100).let { control(ControlId.TONE_VOLUME, it, it) }
        override val label get() = "громкость сигналов $value"
    }

    /** Сила адаптивного режима, 0..100. */
    data class SetAdaptiveStrength(val value: Int) : AapCommand {
        override val bytes get() = control(ControlId.ADAPTIVE_STRENGTH, value.coerceIn(0, 100))
        override val label get() = "сила адаптивного режима $value"
    }

    data class SetCallControl(val value: CallControl) : AapCommand {
        override val bytes get() = control(ControlId.CALL_CONTROL, 0x00, value.code)
        override val label get() = "управление звонком $value"
    }

    data class SetVolumeSwipeSpeed(val speed: VolumeSwipeSpeed) : AapCommand {
        override val bytes get() = control(ControlId.VOLUME_SWIPE_SPEED, speed.code)
        override val label get() = "скорость жеста громкости $speed"
    }

    /** Эти нажатия наушники перестанут выполнять сами и будут присылать телефону. Пустой набор — всё как обычно. */
    data class SetStemConfig(val presses: Set<StemPress>) : AapCommand {
        override val bytes get() = control(ControlId.STEM_CONFIG, StemPress.mask(presses))
        override val label get() = "нажатия для телефона ${presses.ifEmpty { "нет" }}"
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

val AapDeviceState.pressSpeed: PressSpeed?
    get() = controls[ControlId.PRESS_SPEED]?.firstOrNull()?.let(PressSpeed::of)

val AapDeviceState.holdDuration: HoldDuration?
    get() = controls[ControlId.HOLD_DURATION]?.firstOrNull()?.let(HoldDuration::of)

val AapDeviceState.toneVolume: Int?
    get() = controls[ControlId.TONE_VOLUME]?.firstOrNull()?.takeIf { it in 0..100 }

val AapDeviceState.callControl: CallControl?
    get() = controls[ControlId.CALL_CONTROL]?.getOrNull(1)?.let(CallControl::of)

val AapDeviceState.volumeSwipeSpeed: VolumeSwipeSpeed?
    get() = controls[ControlId.VOLUME_SWIPE_SPEED]?.firstOrNull()?.let(VolumeSwipeSpeed::of)

/** Маска нажатий, которые сейчас уходят телефону; 0 — наушники всё делают сами. */
val AapDeviceState.stemConfigMask: Int
    get() = controls[ControlId.STEM_CONFIG]?.firstOrNull() ?: 0

val AapDeviceState.crownDirection: CrownDirection?
    get() = controls[ControlId.CROWN_ROTATION]?.firstOrNull()?.let(CrownDirection::of)

/**
 * Заводские значения настроек Max. По журналу Max после подключения присылают режим, 0x17, 0x18, 0x1B,
 * 0x1F и 0x24, а кнопку шумоподавления, Crown и определение надевания — нет. Пока наушники не сообщили
 * своё, экран показывает эти.
 * Кнопка шумоподавления: шумоподавление + прозрачность (02 + 04); надевание определяется; Crown сзади вперёд.
 */
val MAX_DEFAULT_CONTROLS: Map<Int, List<Int>> = mapOf(
    ControlId.LISTENING_MODE_CYCLE to listOf(0x06, 0x00, 0x00, 0x00),
    ControlId.EAR_DETECTION to listOf(0x01, 0x00, 0x00, 0x00),
    ControlId.CROWN_ROTATION to listOf(CrownDirection.BACK_TO_FRONT.code, 0x00, 0x00, 0x00),
)

/**
 * Заводской набор режимов для зажатия ножки (шумоподавление + прозрачность) — для наушников с
 * шумоподавлением, которые не сообщили свой набор: чтобы его всё равно можно было выбрать.
 */
val NOISE_CONTROL_DEFAULT_CONTROLS: Map<Int, List<Int>> = mapOf(
    ControlId.LISTENING_MODE_CYCLE to listOf(0x06, 0x00, 0x00, 0x00),
)

/** Состояние, где недостающие настройки взяты из [defaults]; то, что прислали наушники, важнее. */
fun AapDeviceState.withDefaults(defaults: Map<Int, List<Int>>): AapDeviceState =
    if (defaults.keys.all { it in controls }) this else copy(controls = defaults + controls)
