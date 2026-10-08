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
        PERSONALIZED_VOLUME -> "персонализированная громкость"
        CONVERSATIONAL_AWARENESS -> "адаптация к разговору"
        ADAPTIVE_STRENGTH -> "сила адаптивного режима"
        else -> "настройка 0x%02X".format(id)
    }
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

    /** Сила адаптивного режима, 0..100. */
    data class SetAdaptiveStrength(val value: Int) : AapCommand {
        override val bytes get() = control(ControlId.ADAPTIVE_STRENGTH, value.coerceIn(0, 100))
        override val label get() = "сила адаптивного режима $value"
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
