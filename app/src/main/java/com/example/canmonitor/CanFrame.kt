package com.example.canmonitor

data class CanFrame(
    val timestamp: Long,      // System.currentTimeMillis()
    val elapsedMs: Long,      // log baslangicindan itibaren
    val id: String,           // "250"
    val data: List<String>    // ["11", "34", "A2"]
) {
    val dataHex: String get() = data.joinToString(" ")
    val dlc: Int get() = data.size
}

object CanParser {

    private val NOISE = listOf(
        "SEARCHING", "STOPPED", "OK", "BUS INIT", "BUS ERROR", "BUS BUSY",
        "CAN ERROR", "BUFFER FULL", "NO DATA", "UNABLE", "ERR", "ELM327", "?"
    )

    private fun isHex(s: String) = s.isNotEmpty() && s.all { it.isDigit() || it in 'A'..'F' }

    /**
     * ATH1 + ATCAF0 + ATS1 modunda ATMA cikisi su sekildedir:
     *     250 11 34 A2
     * Ilk token header (CAN ID), kalanlar veri baytlari.
     *
     * @return gecerli bir frame degilse null (durum mesajlari, cop karakter vs.)
     */
    fun parse(raw: String, startedAt: Long): CanFrame? {
        val line = raw.trim().uppercase()
        if (line.isEmpty()) return null
        if (NOISE.any { line.startsWith(it) }) return null

        val parts = line.split(' ', '\t').filter { it.isNotBlank() }
        if (parts.size < 2) return null

        val id = parts[0]
        if (!isHex(id) || id.length > 8) return null

        val data = parts.drop(1).filter { it.length == 2 && isHex(it) }
        if (data.isEmpty() || data.size > 8) return null

        val now = System.currentTimeMillis()
        return CanFrame(now, now - startedAt, id, data)
    }
}
