package dev.podscompanion.data.calls

/** Куда направить звук звонка. */
enum class CallRoute { PHONE, HEADPHONES }

/**
 * Решает, куда вести звук звонка: наушники сняты (Max не на голове, оба наушника не в ушах) —
 * в телефон; надели обратно — снова в наушники, но только если в телефон звук увели мы.
 *
 * Android сам ведёт звонок в Bluetooth и после ответа на звонок может вернуть его туда ещё раз,
 * поэтому в первые [settleMs] после нашего переключения или смены состояния звонка переводим снова.
 * Если же звук вернули в наушники позже, это сделал человек кнопкой на экране звонка:
 * не спорим с ним, пока наушники снова не наденут или не снимут.
 * Класс без Android, тестируется на JVM.
 */
class CallRoutePolicy(
    private val settleMs: Long = 4_000,
    /** Предохранитель от «пинг-понга» с системой за один звонок. */
    private val maxMoves: Int = 6,
) {
    private var lastOffHead: Boolean? = null
    private var movedByUs = false
    private var userOverride = false
    private var lastMoveMs = Long.MIN_VALUE / 2
    private var lastCallChangeMs = Long.MIN_VALUE / 2
    private var moves = 0

    /**
     * @param offHead наушники сняты; null — неизвестно (не подключены или нет данных об ушах).
     * @param onHeadphones звук звонка сейчас идёт в эти наушники.
     * @param nowMs монотонное время (elapsedRealtime).
     */
    fun onUpdate(offHead: Boolean?, onHeadphones: Boolean, nowMs: Long): CallRoute? {
        if (offHead == null) return null
        val headChanged = lastOffHead != null && offHead != lastOffHead
        lastOffHead = offHead
        if (headChanged) userOverride = false
        if (moves >= maxMoves) return null

        if (offHead) {
            if (!onHeadphones || userOverride) return null
            val settling = nowMs - lastMoveMs < settleMs || nowMs - lastCallChangeMs < settleMs
            if (movedByUs && !headChanged && !settling) {
                userOverride = true
                return null
            }
            movedByUs = true
            lastMoveMs = nowMs
            moves++
            return CallRoute.PHONE
        }
        if (onHeadphones) {
            movedByUs = false
            return null
        }
        if (!movedByUs) return null
        movedByUs = false
        lastMoveMs = nowMs
        moves++
        return CallRoute.HEADPHONES
    }

    /** Звонок сменил состояние (ответили, поставили на удержание): система могла сама сменить маршрут. */
    fun onCallStateChanged(nowMs: Long) {
        lastCallChangeMs = nowMs
    }

    /** Звонков больше нет. */
    fun reset() {
        lastOffHead = null
        movedByUs = false
        userOverride = false
        lastMoveMs = Long.MIN_VALUE / 2
        lastCallChangeMs = Long.MIN_VALUE / 2
        moves = 0
    }
}
