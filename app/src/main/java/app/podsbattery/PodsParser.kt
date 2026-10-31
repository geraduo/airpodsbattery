package app.podsbattery

/** Заряд и состояние AirPods, разобранные из рекламного пакета. Значения заряда в процентах, null — нет данных. */
data class PodsState(
    val model: String,
    val left: Int?,
    val right: Int?,
    val case: Int?,
    val chargingLeft: Boolean,
    val chargingRight: Boolean,
    val chargingCase: Boolean
)

object PodsParser {
    const val APPLE_COMPANY_ID = 0x004C

    /**
     * Разбор рекламного пакета Apple типа 0x07 (proximity pairing).
     * Байты данных производителя (без идентификатора компании):
     * 0 — тип, 1 — длина, 3–4 — модель, 5 — статус,
     * 6 — заряд наушников (по полубайту), 7 — флаги зарядки (старший полубайт) и заряд кейса (младший).
     * Заряд 0–10 соответствует 0–100 %, значение 15 означает «нет данных».
     */
    fun parse(d: ByteArray): PodsState? {
        if (d.size < 8 || d[0] != 0x07.toByte()) return null

        val model = ((d[3].toInt() and 0xFF) shl 8) or (d[4].toInt() and 0xFF)
        val status = d[5].toInt() and 0xFF
        val b6 = d[6].toInt() and 0xFF
        val b7 = d[7].toInt() and 0xFF

        val flip = (status and 0x20) == 0
        val hi = b6 shr 4
        val lo = b6 and 0x0F
        val leftV = if (flip) hi else lo
        val rightV = if (flip) lo else hi
        val caseV = b7 and 0x0F
        val cs = b7 shr 4

        return PodsState(
            model = when (model) {
                0x0220 -> "AirPods"
                0x0F20 -> "AirPods 2"
                0x1320 -> "AirPods 3"
                0x0E20 -> "AirPods Pro"
                0x1420 -> "AirPods Pro 2"
                else -> "AirPods"
            },
            left = percent(leftV),
            right = percent(rightV),
            case = percent(caseV),
            chargingLeft = if (flip) (cs and 2) != 0 else (cs and 1) != 0,
            chargingRight = if (flip) (cs and 1) != 0 else (cs and 2) != 0,
            chargingCase = (cs and 4) != 0
        )
    }

    private fun percent(raw: Int): Int? = if (raw in 0..10) raw * 10 else null
}
