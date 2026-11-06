package app.podsbattery

class PodsStabilizer {
    private val window = ArrayList<PodsState>()
    private var current: PodsState? = null
    private var lastAt = 0L

    fun push(s: PodsState, now: Long): PodsState? {
        if (now - lastAt > GAP_MS) window.clear()
        lastAt = now
        window.add(s)
        if (window.size > SIZE) window.removeAt(0)
        if (window.size < MIN) return current
        val base = current
        val known = base != null
        val next = PodsState(
            model = pick(base?.model ?: "", known) { it.model },
            left = pick(base?.left, known) { it.left },
            right = pick(base?.right, known) { it.right },
            case = pick(base?.case, known) { it.case },
            chargingLeft = pick(base?.chargingLeft ?: false, known) { it.chargingLeft },
            chargingRight = pick(base?.chargingRight ?: false, known) { it.chargingRight },
            chargingCase = pick(base?.chargingCase ?: false, known) { it.chargingCase }
        )
        current = next
        return next
    }

    private fun <T> pick(previous: T, known: Boolean, selector: (PodsState) -> T): T {
        val counts = LinkedHashMap<T, Int>()
        for (item in window) {
            val value = selector(item)
            counts[value] = (counts[value] ?: 0) + 1
        }
        var best: T = selector(window.last())
        var bestCount = 0
        for ((value, count) in counts) {
            if (count > bestCount) {
                best = value
                bestCount = count
            }
        }
        if (!known) return best
        if (best == previous) return previous
        return if (bestCount >= STRONG) best else previous
    }

    companion object {
        private const val SIZE = 7
        private const val MIN = 3
        private const val STRONG = 5
        private const val GAP_MS = 10_000L
    }
}
