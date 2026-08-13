package com.example.canmonitor

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Frame'leri tamponlu sekilde CSV'ye yazar.
 * Ayrac ';' -> Turkce Excel/Sheets dosyayi cift tiklamayla dogru acar.
 */
class CsvLogger(private val dir: File) {

    private val stampFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val nameFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private var writer: BufferedWriter? = null
    private var lastFlush = 0L

    var file: File? = null
        private set

    var count = 0L
        private set

    val active: Boolean get() = writer != null

    fun start(filterId: String): File {
        stop()
        if (!dir.exists()) dir.mkdirs()

        val tag = if (filterId.isBlank()) "ALL" else filterId.uppercase()
        val f = File(dir, "can_${tag}_${nameFmt.format(Date())}.csv")

        val w = BufferedWriter(OutputStreamWriter(FileOutputStream(f), Charsets.UTF_8), 64 * 1024)
        w.write("\uFEFF")   // Excel'in UTF-8 anlamasi icin BOM
        w.write("zaman;gecen_ms;can_id;dlc;veri_hex;b0;b1;b2;b3;b4;b5;b6;b7")
        w.newLine()

        writer = w
        file = f
        count = 0
        lastFlush = System.currentTimeMillis()
        return f
    }

    fun write(frame: CanFrame) {
        val w = writer ?: return
        val sb = StringBuilder(96)
        sb.append(stampFmt.format(Date(frame.timestamp))).append(';')
        sb.append(frame.elapsedMs).append(';')
        sb.append(frame.id).append(';')
        sb.append(frame.dlc).append(';')
        sb.append(frame.dataHex)
        for (k in 0 until 8) {
            sb.append(';').append(frame.data.getOrElse(k) { "" })
        }
        w.write(sb.toString())
        w.newLine()
        count++

        // Uygulama beklenmedik sekilde kapanirsa veri kaybi olmasin diye periyodik flush
        val now = System.currentTimeMillis()
        if (now - lastFlush > 2000) {
            w.flush()
            lastFlush = now
        }
    }

    /** @return kapatilan dosya, hic acilmadiysa null */
    fun stop(): File? {
        val w = writer ?: return null
        try {
            w.flush(); w.close()
        } catch (_: Exception) {
        }
        writer = null
        return file
    }
}
