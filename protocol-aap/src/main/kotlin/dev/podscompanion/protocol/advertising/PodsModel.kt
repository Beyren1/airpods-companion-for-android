package dev.podscompanion.protocol.advertising

/** Что умеет модель. Используется UI, чтобы не показывать недоступные настройки. */
enum class Capability {
    STEREO_BUDS,            // два наушника с отдельным зарядом (у Max одно значение)
    CHARGING_CASE,          // кейс с зарядом и крышкой
    EAR_DETECTION,
    NOISE_CONTROL,          // Off / NC / Transparency
    ADAPTIVE_AUDIO,
    CONVERSATIONAL_AWARENESS,
    HEAD_GESTURES,
}

/**
 * Модели по двум байтам model ID из пакета Proximity Pairing (как они идут в эфире, big-endian).
 * Значения взяты из публичного реверса; помеченные [verified] = false нужно сверить по дампам.
 */
enum class PodsModel(
    val modelId: Int,
    val displayName: String,
    val capabilities: Set<Capability>,
    val verified: Boolean = true,
) {
    // ---------- AirPods ----------
    AIRPODS_1(0x0220, "AirPods (1st gen)", BASIC),
    AIRPODS_2(0x0F20, "AirPods (2nd gen)", BASIC),
    AIRPODS_3(0x1320, "AirPods (3rd gen)", BASIC),
    AIRPODS_4(0x1920, "AirPods 4", BASIC + Capability.HEAD_GESTURES, verified = false),
    AIRPODS_4_ANC(
        0x1B20, "AirPods 4 (ANC)",
        BASIC + Capability.NOISE_CONTROL + Capability.ADAPTIVE_AUDIO +
            Capability.CONVERSATIONAL_AWARENESS + Capability.HEAD_GESTURES,
    ),
    AIRPODS_PRO(0x0E20, "AirPods Pro", BASIC + Capability.NOISE_CONTROL),
    AIRPODS_PRO_2(0x1420, "AirPods Pro 2", PRO_2),
    AIRPODS_PRO_2_USB_C(0x2420, "AirPods Pro 2 (USB-C)", PRO_2),
    AIRPODS_PRO_3(0x2720, "AirPods Pro 3", PRO_2, verified = false),
    AIRPODS_MAX(0x0A20, "AirPods Max", MAX),
    AIRPODS_MAX_USB_C(0x1F20, "AirPods Max (USB-C)", MAX),

    // ---------- Beats (тот же формат пакета) ----------
    // Внутриканальные с кейсом.
    POWERBEATS_PRO(0x0B20, "Powerbeats Pro", BASIC, verified = false),
    POWERBEATS_PRO_2(0x1D20, "Powerbeats Pro 2", BASIC + Capability.NOISE_CONTROL, verified = false),
    BEATS_STUDIO_BUDS(0x1120, "Beats Studio Buds", BUDS_NO_EAR + Capability.NOISE_CONTROL, verified = false),
    BEATS_STUDIO_BUDS_PLUS(0x1620, "Beats Studio Buds +", BUDS_NO_EAR + Capability.NOISE_CONTROL, verified = false),
    BEATS_FIT_PRO(0x1220, "Beats Fit Pro", BASIC + Capability.NOISE_CONTROL, verified = false),
    BEATS_SOLO_BUDS(0x2620, "Beats Solo Buds", BUDS_NO_EAR, verified = false),

    // Накладные и полноразмерные: одно значение заряда, как у Max.
    BEATS_SOLO_3(0x0620, "Beats Solo3", SINGLE, verified = false),
    BEATS_SOLO_PRO(0x0C20, "Beats Solo Pro", SINGLE + Capability.NOISE_CONTROL, verified = false),
    BEATS_SOLO_4(0x2520, "Beats Solo 4", SINGLE, verified = false),
    BEATS_STUDIO_3(0x0920, "Beats Studio3", SINGLE + Capability.NOISE_CONTROL, verified = false),
    BEATS_STUDIO_PRO(0x1720, "Beats Studio Pro", SINGLE + Capability.NOISE_CONTROL, verified = false),

    // С проводом между наушниками: одно значение заряда.
    POWERBEATS_3(0x0320, "Powerbeats3", SINGLE, verified = false),
    POWERBEATS_4(0x0D20, "Powerbeats", SINGLE, verified = false),
    BEATS_X(0x0520, "BeatsX", SINGLE, verified = false),
    BEATS_FLEX(0x1020, "Beats Flex", SINGLE, verified = false),
    ;

    companion object {
        fun fromId(modelId: Int): PodsModel? = entries.firstOrNull { it.modelId == modelId }
    }
}

// Наборы возможностей. Это свойства с геттером (get() =), они вычисляются при каждом обращении,
// поэтому порядок объявления относительно enum не важен (в C++ это был бы static-init order fiasco).
private val BASIC: Set<Capability>
    get() = setOf(Capability.STEREO_BUDS, Capability.CHARGING_CASE, Capability.EAR_DETECTION)
private val PRO_2: Set<Capability>
    get() = BASIC + Capability.NOISE_CONTROL + Capability.ADAPTIVE_AUDIO +
        Capability.CONVERSATIONAL_AWARENESS + Capability.HEAD_GESTURES
private val MAX: Set<Capability>
    get() = setOf(Capability.EAR_DETECTION, Capability.NOISE_CONTROL)
/** Наушники с кейсом, но без датчика уха. */
private val BUDS_NO_EAR: Set<Capability>
    get() = setOf(Capability.STEREO_BUDS, Capability.CHARGING_CASE)
/** Одно значение заряда, без кейса и датчика уха. */
private val SINGLE: Set<Capability>
    get() = emptySet()
