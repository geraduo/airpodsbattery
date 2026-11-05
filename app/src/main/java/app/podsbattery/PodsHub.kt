package app.podsbattery

object PodsHub {
    @Volatile
    var state: PodsState? = null
        private set

    @Volatile
    var dataAt = 0L
        private set

    @Volatile
    var listener: (() -> Unit)? = null

    fun publish(s: PodsState, time: Long) {
        state = s
        dataAt = time
        listener?.invoke()
    }
}
