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

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var btAdapter: BluetoothAdapter? = null
    private var device: BluetoothDevice? = null

    /** UI'ya basilacak son frame'ler (ekrani 500 fps ile guncellemiyoruz) */
    private val recent = ArrayDeque<CanFrame>()
    private val lock = Any()

    private var totalFrames = 0L
    private var startedAt = 0L
    private var lastFile: File? = null

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
        super.onDestroy()
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

        synchronized(lock) { recent.clear() }
        adapterList.clear()
        totalFrames = 0
        startedAt = System.currentTimeMillis()

        val f = logger.start(filterId)
        lastFile = f
        status("Kayit: ${f.name}")
        refreshButtons(logging = true)

        io.execute {
            try {
                elm.startMonitor(
                    filterId = filterId,
                    onLine = { line ->
                        val frame = CanParser.parse(line, startedAt) ?: return@startMonitor
                        logger.write(frame)
                        totalFrames++
                        synchronized(lock) {
                            recent.addLast(frame)
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
        io.execute { elm.stopMonitor() }
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
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, f.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "CSV dosyasini paylas"))
    }

    // --------------------------------------------------------------------- UI

    private val uiTick = object : Runnable {
        override fun run() {
            val snapshot = synchronized(lock) { recent.toList() }
            if (snapshot.isNotEmpty()) {
                adapterList.submit(snapshot)
                ui.rvFrames.scrollToPosition(adapterList.itemCount - 1)
            }
            ui.tvCount.text = "Frames: $totalFrames"
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
