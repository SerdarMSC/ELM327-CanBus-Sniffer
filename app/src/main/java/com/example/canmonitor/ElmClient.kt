package com.example.canmonitor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * ELM327 klonlari icin Bluetooth Classic (SPP) istemcisi.
 *
 * Akis:
 *   connect() -> initAdapter() -> startMonitor(id) ... stopMonitor() -> close()
 *
 * startMonitor() BLOKLAYICIDIR, mutlaka arka plan thread'inde cagrilmali.
 */
@SuppressLint("MissingPermission")
class ElmClient {

    companion object {
        /** Seri Port Profili (SPP) standart UUID'si */
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val PROMPT = '>'
    }

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    @Volatile
    var monitoring = false
        private set

    val connected: Boolean get() = socket?.isConnected == true

    // ---------------------------------------------------------------- baglanti

    @Throws(Exception::class)
    fun connect(device: BluetoothDevice) {
        close()

        var s: BluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
        try {
            s.connect()
        } catch (first: Exception) {
            // Ucuz ELM327 klonlarinin bir kismi standart SDP kaydina cevap vermez.
            // Gizli createRfcommSocket(1) metodu ile ikinci bir deneme yapiyoruz.
            try {
                s.close()
            } catch (_: Exception) {
            }
            try {
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                s = m.invoke(device, 1) as BluetoothSocket
                s.connect()
            } catch (second: Exception) {
                try {
                    s.close()
                } catch (_: Exception) {
                }
                throw first
            }
        }

        socket = s
        input = s.inputStream
        output = s.outputStream
    }

    fun close() {
        monitoring = false
        try {
            input?.close()
        } catch (_: Exception) {
        }
        try {
            output?.close()
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        input = null; output = null; socket = null
    }

    // ------------------------------------------------------------- komut / IO

    /** Tek bir AT/OBD komutu gonderir ve '>' promptuna kadar okur. */
    @Throws(Exception::class)
    fun sendCommand(cmd: String, timeoutMs: Long = 5000): String {
        val out = output ?: throw IllegalStateException("Baglanti yok")
        drain()
        out.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
        out.flush()
        return readUntilPrompt(timeoutMs)
    }

    private fun drain() {
        val i = input ?: return
        while (i.available() > 0) i.read()
    }

    private fun readUntilPrompt(timeoutMs: Long): String {
        val i = input ?: throw IllegalStateException("Baglanti yok")
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (i.available() > 0) {
                val c = i.read()
                if (c < 0) break
                val ch = c.toChar()
                if (ch == PROMPT) return sb.toString().trim()
                sb.append(ch)
            } else {
                Thread.sleep(4)
            }
        }
        return sb.toString().trim()
    }

    // ----------------------------------------------------------------- kurulum

    /**
     * @param protocol ATSP parametresi. "6" = ISO 15765-4 CAN 11 bit / 500 kbps.
     *                 "7"=29bit/500k, "8"=11bit/250k, "9"=29bit/250k, "0"=otomatik.
     * @return kullaniciya gosterilebilecek komut/cevap dokumu
     */
    @Throws(Exception::class)
    fun initAdapter(protocol: String = "6"): List<String> {
        val log = mutableListOf<String>()

        fun cmd(c: String, timeout: Long = 5000) {
            val r = sendCommand(c, timeout).replace("\r", " ").replace("\n", " ").trim()
            log += "$c  ->  $r"
        }

        cmd("ATZ", 8000)          // resetle
        Thread.sleep(300)
        cmd("ATE0")               // echo kapali
        cmd("ATL0")               // satir besleme kapali
        cmd("ATS1")               // baytlar arasi bosluk ACIK (parse etmesi kolay)
        cmd("ATH1")               // header (CAN ID) goster  <-- sart
        cmd("ATCAF0")             // otomatik ISO-TP formatlama kapali -> ham frame
        cmd("ATSP$protocol")      // protokol
        return log
    }

    // ----------------------------------------------------------------- monitor

    /**
     * Verilen CAN ID'sini donanim seviyesinde filtreleyip (ATCRA) ham frame akisini dinler.
     * Bu metod stopMonitor() cagrilana veya baglanti kopana kadar geri donmez.
     *
     * @param filterId "250" gibi hex ID. Bos birakilirsa tum trafik dinlenir.
     */
    @Throws(Exception::class)
    fun startMonitor(
        filterId: String,
        onLine: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val out = output ?: throw IllegalStateException("Baglanti yok")
        val i = input ?: throw IllegalStateException("Baglanti yok")

        val id = filterId.trim().uppercase()
        if (id.isNotEmpty()) {
            val r = sendCommand("ATCRA $id")
            if (r.contains("?")) onError("Adapter 'ATCRA $id' komutunu kabul etmedi")
        } else {
            sendCommand("ATCRA")   // filtreyi sifirla
        }

        monitoring = true
        out.write("ATMA\r".toByteArray(Charsets.US_ASCII))
        out.flush()

        val buf = ByteArray(1024)
        val sb = StringBuilder(64)
        try {
            while (monitoring) {
                val n = i.read(buf)          // bloklayici okuma - yuksek hizda daha verimli
                if (n <= 0) break
                for (k in 0 until n) {
                    when (val ch = buf[k].toInt().toChar()) {
                        '\r', '\n' -> {
                            if (sb.isNotEmpty()) {
                                onLine(sb.toString())
                                sb.setLength(0)
                            }
                        }
                        PROMPT -> sb.setLength(0)
                        else -> if (sb.length < 200) sb.append(ch)
                    }
                }
            }
        } catch (e: Exception) {
            if (monitoring) onError(e.message ?: "IO hatasi")
        } finally {
            monitoring = false
        }
    }

    /**
     * ATMA akisini keser. ELM327'de akisi durdurmanin yolu herhangi bir karakter gondermektir.
     * Baska bir thread'den cagrilabilir.
     */
    fun stopMonitor() {
        if (!monitoring) return
        monitoring = false
        try {
            output?.write(byteArrayOf(0x0D))   // CR -> "STOPPED"
            output?.flush()
            Thread.sleep(250)
            drain()
        } catch (_: Exception) {
        }
    }
}
