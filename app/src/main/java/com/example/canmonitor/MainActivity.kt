package com.example.canmonitor

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.canmonitor.databinding.ActivityMainBinding
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executors

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private lateinit var ui: ActivityMainBinding

    private val elm = ElmClient()
    private lateinit var logger: CsvLogger
    private val adapterList = FrameAdapter()

    /** Kisa komutlar: baglan, durdur, paylas. */
    private val io = Executors.newSingleThreadExecutor()

    /**
     * ATMA dinleme dongusu icin AYRI havuz.
     * Ayni havuzda olsaydi startMonitor() tek thread'i suresiz mesgul eder,
     * STOP'un gorevi kuyrukta bekler ve hicbir zaman calismazdi.
     */
    private val monitorExec = Executors.newSingleThreadExecutor()

    private val main = Handler(Looper.getMainLooper())

    private var btAdapter: BluetoothAdapter? = null
    private var device: BluetoothDevice? = null

    /** UI'ya basilacak son satirlar: Triple(zaman, id, veri). Cozumlenemeyen
     *  adapter mesajlari da buraya "!" isaretiyle giriyor - sessizce yutulmuyor. */
    private val recent = ArrayDeque<Triple<String, String, String>>()
    private val lock = Any()

    private var totalFrames = 0L
    @Volatile private var unparsed = 0L
    private var startedAt = 0L
    private var lastFile: File? = null
    private var lastRawFile: File? = null
    private var lastInit: List<String> = emptyList()

    private val protocols = listOf(
        "6 - ISO 15765-4 CAN 11bit 500K",
        "7 - ISO 15765-4 CAN 29bit 500K",
        "8 - ISO 15765-4 CAN 11bit 250K",
        "9 - ISO 15765-4 CAN 29bit 250K",
        "0 - Otomatik"
    )

    // ------------------------------------------------------------------ yasam dongusu

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()          // super.onCreate'den ONCE cagrilmali
        super.onCreate(savedInstanceState)
        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyInsets()
        ui.tvVersion.text = "v${BuildConfig.VERSION_NAME}"

        logger = CsvLogger(File(getExternalFilesDir(null), "logs"))
        btAdapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter

        ui.spProtocol.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, protocols)
        ui.rvFrames.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        ui.rvFrames.adapter = adapterList

        ui.btnConnect.setOnClickListener { ensurePermissions { pickDevice() } }
        ui.btnStart.setOnClickListener { startLogging() }
        ui.btnStop.setOnClickListener { stopLogging() }
        ui.btnShare.setOnClickListener { shareLastFile() }

        main.post(uiTick)
        refreshButtons()
    }

    override fun onDestroy() {
        main.removeCallbacks(uiTick)
        elm.stopMonitor()
        logger.stop()
        elm.close()
        io.shutdownNow()
        monitorExec.shutdownNow()
        super.onDestroy()
    }

    /**
     * targetSdk 35'ten itibaren Android pencereyi zorla edge-to-edge cizer:
     * icerik durum cubugunun ve gezinme cubugunun ALTINA tasar.
     * Kok gorunume sistem cubugu yuksekliklerini padding olarak ekliyoruz.
     * Klavye acildiginda alt padding IME yuksekligine cikiyor.
     */
    private fun applyInsets() {
        val basePad = (14 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(ui.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(
                bars.left + basePad,
                bars.top + basePad,
                bars.right + basePad,
                maxOf(bars.bottom, ime.bottom) + basePad
            )
            insets
        }
    }

    // ------------------------------------------------------------------- izinler

    private var permCallback: (() -> Unit)? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) permCallback?.invoke()
        else status("Bluetooth izni verilmedi")
        permCallback = null
    }

    private fun ensurePermissions(then: () -> Unit) {
        val need = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        }
        val missing = need.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) then() else {
            permCallback = then
            permLauncher.launch(missing.toTypedArray())
        }
    }

    // -------------------------------------------------------------- cihaz secimi

    private fun pickDevice() {
        val bt = btAdapter
        if (bt == null || !bt.isEnabled) {
            status("Bluetooth kapali - once acin")
            return
        }
        val bonded = bt.bondedDevices.toList()
        if (bonded.isEmpty()) {
            status("Eslesmis cihaz yok - ELM327'yi telefon ayarlarindan eslestirin")
            return
        }
        val labels = bonded.map { "${it.name ?: "?"}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("ELM327 cihazini secin")
            .setItems(labels) { _, which -> connect(bonded[which]) }
            .show()
    }

    private fun connect(dev: BluetoothDevice) {
        device = dev
        status("Baglaniyor: ${dev.name ?: dev.address} ...")
        ui.btnConnect.isEnabled = false

        io.execute {
            try {
                elm.connect(dev)
                val proto = protocols[ui.spProtocol.selectedItemPosition].substringBefore(" ")
                val dump = elm.initAdapter(proto)
                main.post {
                    status("ELM327: Bagli  (${dev.name ?: dev.address})")
                    ui.btnConnect.isEnabled = true
                    refreshButtons()
                    adapterList.setLines(dump.map { "· $it" })
                    lastInit = dump
                }
            } catch (e: Exception) {
                elm.close()
                main.post {
                    status("Baglanti hatasi: ${e.message}")
                    ui.btnConnect.isEnabled = true
                    refreshButtons()
                }
            }
        }
    }

    // ------------------------------------------------------------------- kayit

    private fun startLogging() {
        if (!elm.connected) {
            status("Once ELM327'ye baglanin")
            return
        }
        val filterId = ui.etFilterId.text.toString().trim().uppercase()
        if (filterId.isNotEmpty() && !filterId.all { it.isDigit() || it in 'A'..'F' }) {
            ui.etFilterId.error = "Sadece hex karakter (0-9, A-F)"
            return
        }

        if (filterId.isBlank()) {
            AlertDialog.Builder(this)
                .setTitle("Filtresiz kayit")
                .setMessage(
                    "Filtre bos: tum CAN trafigi dinlenecek.\n\n" +
                    "500K'lik dolu bir hat adapterin seri baglantisindan hizlidir; " +
                    "adapter surekli tasar (BUFFER FULL) ve frame'lerin cogu kaybolur. " +
                    "Akis otomatik yeniden baslatiliyor ama kayit DELIKLI olur.\n\n" +
                    "Saglikli kayit icin bir ID gir."
                )
                .setPositiveButton("Yine de basla") { _, _ -> beginLogging("") }
                .setNegativeButton("Vazgec", null)
                .show()
            return
        }
        beginLogging(filterId)
    }

    private fun beginLogging(filterId: String) {
        synchronized(lock) { recent.clear() }
        totalFrames = 0
        unparsed = 0
        startedAt = System.currentTimeMillis()

        val f = logger.start(filterId)
        lastFile = f
        lastRawFile = logger.rawFile
        // init dokumunu ham loga da yaz - teshiste en kiymetli kisim
        lastInit.forEach { logger.writeRaw("# $it") }
        logger.writeRaw("# filtre = ${if (filterId.isBlank()) "YOK (tum trafik)" else filterId}")
        status("Kayit: ${f.name}")
        refreshButtons(logging = true)

        monitorExec.execute {
            try {
                elm.startMonitor(
                    filterId = filterId,
                    onLine = { line ->
                        logger.writeRaw(line)
                        val frame = CanParser.parse(line, startedAt)
                        val row = if (frame != null) {
                            logger.write(frame)
                            totalFrames++
                            Triple(
                                String.format("%.3f", frame.elapsedMs / 1000.0),
                                frame.id,
                                frame.dataHex
                            )
                        } else if (line.startsWith("#")) {
                            Triple("", "", line.trim())          // kendi bilgi notumuz
                        } else {
                            // Frame degil: "CAN ERROR", "?", "BUFFER FULL", "NO DATA"...
                            // Eskiden sessizce atiliyordu; artik ekranda gorunuyor.
                            unparsed++
                            Triple("", "!", line.trim())
                        }
                        synchronized(lock) {
                            recent.addLast(row)
                            while (recent.size > 300) recent.removeFirst()
                        }
                    },
                    onError = { msg -> main.post { status("Hata: $msg") } }
                )
            } catch (e: Exception) {
                main.post { status("Dinleme hatasi: ${e.message}") }
            } finally {
                main.post { onMonitorEnded() }
            }
        }
    }

    private fun stopLogging() {
        ui.btnStop.isEnabled = false
        status("Durduruluyor...")
        io.execute { elm.stopMonitor() }

        // Bekci: adapter CR'a cevap vermezse okuma cagrisi bloke kalir.
        // 3 sn icinde donmezse soketi kapatip kullaniciyi kilitli birakmiyoruz.
        main.postDelayed({
            if (elm.monitoring || logger.active) {
                io.execute { elm.close() }
                main.post {
                    status("Adapter cevap vermedi - baglanti kapatildi")
                    onMonitorEnded()
                }
            }
        }, 3000)
    }

    private fun onMonitorEnded() {
        val f = logger.stop()
        if (f != null) {
            lastFile = f
            status("Durduruldu - ${logger.count} frame -> ${f.name}")
        }
        refreshButtons(logging = false)
    }

    // ------------------------------------------------------------------ paylas

    private fun shareLastFile() {
        val f = lastFile
        if (f == null || !f.exists() || f.length() == 0L) {
            status("Paylasilacak dosya yok")
            return
        }
        if (logger.active) {
            status("Once kaydi durdurun")
            return
        }
        val uris = ArrayList<android.net.Uri>()
        uris += FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        lastRawFile?.takeIf { it.exists() && it.length() > 0 }?.let {
            uris += FileProvider.getUriForFile(this, "$packageName.fileprovider", it)
        }

        val send = if (uris.size > 1) {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/plain"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        }
        send.putExtra(Intent.EXTRA_SUBJECT, f.name)
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "CSV + ham log paylas"))
    }

    // --------------------------------------------------------------------- UI

    private val uiTick = object : Runnable {
        override fun run() {
            val snapshot = synchronized(lock) { recent.toList() }
            if (snapshot.isNotEmpty()) {
                adapterList.submitRows(snapshot)
                ui.rvFrames.scrollToPosition(adapterList.itemCount - 1)
            }
            ui.tvCount.text =
                if (unparsed > 0) "Frames: $totalFrames   |   cozumlenemeyen: $unparsed"
                else "Frames: $totalFrames"
            main.postDelayed(this, 250)
        }
    }

    private fun status(msg: String) {
        ui.tvStatus.text = msg
    }

    private fun refreshButtons(logging: Boolean = logger.active) {
        ui.btnStart.isEnabled = elm.connected && !logging
        ui.btnStop.isEnabled = logging
        ui.btnShare.isEnabled = !logging && lastFile != null
        ui.etFilterId.isEnabled = !logging
        ui.spProtocol.isEnabled = !elm.connected
    }
}
