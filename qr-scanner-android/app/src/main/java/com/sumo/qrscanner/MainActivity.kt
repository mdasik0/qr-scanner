package com.sumo.qrscanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.sumo.qrscanner.databinding.ActivityMainBinding
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MainActivity : AppCompatActivity() {
    private enum class Screen { SETUP, MODE, SCAN }
    private enum class ScanMode { SCANNER, SEE_DATA }

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: AppSettings
    private val api = QrApiClient()
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val sending = AtomicBoolean(false)
    private val lastSentAt = AtomicLong(0)
    private var lastPayload: String? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var screen = Screen.SETUP
    private var scanMode = ScanMode.SCANNER

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = AppSettings(this)

        binding.setupUrl.setText(
            settings.baseUrl.ifBlank { BuildConfig.QR_API_BASE_URL }
        )
        binding.setupUser.setText(settings.identifier)

        binding.continueButton.setOnClickListener { saveSetupAndShowMode() }
        binding.scannerModeButton.setOnClickListener { enterScan(ScanMode.SCANNER) }
        binding.seeDataModeButton.setOnClickListener { enterScan(ScanMode.SEE_DATA) }
        binding.changeUrlButton.setOnClickListener { showScreen(Screen.SETUP) }
        binding.changeModeButton.setOnClickListener {
            stopCamera()
            showScreen(Screen.MODE)
        }
        binding.scanAgainButton.setOnClickListener {
            clearResult()
            setStatusReady()
            ensureCameraPermissionAndStart()
        }

        showScreen(if (settings.hasSetup) Screen.MODE else Screen.SETUP)
    }

    private fun saveSetupAndShowMode() {
        val url = binding.setupUrl.text?.toString().orEmpty()
        val identifier = binding.setupUser.text?.toString().orEmpty().trim()
        val password = binding.setupPassword.text?.toString().orEmpty()
        if (!AppSettings.isValidHttpUrl(url)) {
            Toast.makeText(this, R.string.setup_url_required, Toast.LENGTH_LONG).show()
            return
        }
        if (identifier.isBlank() || password.isBlank()) {
            Toast.makeText(this, R.string.setup_login_required, Toast.LENGTH_LONG).show()
            return
        }
        binding.continueButton.isEnabled = false
        cameraExecutor.execute {
            try {
                val token = api.login(url, identifier, password)
                settings.baseUrl = url
                settings.identifier = identifier
                settings.accessToken = token
                runOnUiThread {
                    binding.continueButton.isEnabled = true
                    binding.setupPassword.setText("")
                    showScreen(Screen.MODE)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.continueButton.isEnabled = true
                    Toast.makeText(this, e.message ?: "Login failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun enterScan(mode: ScanMode) {
        scanMode = mode
        lastPayload = null
        sending.set(false)
        clearResult()
        setStatusReady()
        binding.scanModeLabel.setText(
            if (mode == ScanMode.SCANNER) R.string.scan_mode_scanner
            else R.string.scan_mode_see_data
        )
        showScreen(Screen.SCAN)
        ensureCameraPermissionAndStart()
    }

    private fun showScreen(next: Screen) {
        screen = next
        binding.setupLayer.visibility = visibleIf(next == Screen.SETUP)
        binding.modeLayer.visibility = visibleIf(next == Screen.MODE)
        binding.scanLayer.visibility = visibleIf(next == Screen.SCAN)
        if (next == Screen.MODE) {
            binding.connectedUrlText.text = getString(
                R.string.mode_connected,
                "${settings.identifier} @ ${settings.baseUrl}"
            )
        }
        if (next != Screen.SCAN) stopCamera()
    }

    private fun visibleIf(show: Boolean): Int = if (show) View.VISIBLE else View.GONE

    private fun ensureCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun deviceId(): String {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown-device"
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = binding.previewView.surfaceProvider
            }

            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            val scanner = BarcodeScanning.getClient(options)

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage == null) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                val image = InputImage.fromMediaImage(
                    mediaImage,
                    imageProxy.imageInfo.rotationDegrees
                )
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        val value = barcodes.firstOrNull()?.rawValue?.trim().orEmpty()
                        if (value.isNotEmpty()) {
                            onQrDecoded(value)
                        }
                    }
                    .addOnCompleteListener { imageProxy.close() }
            }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                Toast.makeText(this, "Camera failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onQrDecoded(payload: String) {
        val now = System.currentTimeMillis()
        if (payload == lastPayload && now - lastSentAt.get() < DEBOUNCE_MS) return
        if (!sending.compareAndSet(false, true)) return

        lastPayload = payload
        stopCamera()

        runOnUiThread {
            binding.statusText.setText(
                if (scanMode == ScanMode.SCANNER) R.string.status_sending
                else R.string.status_lookup
            )
            binding.scanAgainButton.visibility = View.GONE
        }

        val onSuccess: (TableResult) -> Unit = { table ->
            lastSentAt.set(System.currentTimeMillis())
            sending.set(false)
            runOnUiThread {
                binding.statusText.setTextColor(Color.parseColor("#D8F3DC"))
                binding.statusText.setText(
                    if (scanMode == ScanMode.SCANNER) R.string.status_ok
                    else R.string.status_lookup_ok
                )
                renderTable(table, warning = false)
                binding.scanAgainButton.visibility = View.VISIBLE
                Toast.makeText(
                    this,
                    if (scanMode == ScanMode.SCANNER) "Sent" else "Loaded",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        val onFormatWarning: () -> Unit = {
            lastSentAt.set(System.currentTimeMillis())
            sending.set(false)
            runOnUiThread {
                binding.statusText.setTextColor(Color.parseColor("#F4D35E"))
                binding.statusText.setText(R.string.status_format_warning)
                renderTable(TableResult.formatGuide(), warning = true)
                binding.scanAgainButton.visibility = View.GONE
                Toast.makeText(this, R.string.status_format_warning, Toast.LENGTH_SHORT).show()
                ensureCameraPermissionAndStart()
            }
        }
        val onError: (String) -> Unit = { message ->
            sending.set(false)
            runOnUiThread {
                binding.statusText.setTextColor(Color.parseColor("#D8F3DC"))
                binding.statusText.setText(R.string.status_error)
                binding.resultTitle.setTextColor(Color.parseColor("#4ADE80"))
                binding.resultTitle.visibility = View.VISIBLE
                binding.resultTitle.text = message
                binding.scanAgainButton.visibility = View.VISIBLE
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }

        cameraExecutor.execute {
            try {
                val json = if (scanMode == ScanMode.SCANNER) {
                    api.postScan(
                        baseUrl = settings.baseUrl,
                        token = settings.accessToken,
                        rawPayload = payload,
                        deviceId = deviceId(),
                        deviceLabel = android.os.Build.MODEL,
                    )
                } else {
                    api.postLookup(
                        baseUrl = settings.baseUrl,
                        token = settings.accessToken,
                        rawPayload = payload,
                        deviceId = deviceId(),
                        deviceLabel = android.os.Build.MODEL,
                    )
                }
                val table = TableResult.fromShowResponse(json)
                onSuccess(table)
            } catch (_: ShowFormatException) {
                onFormatWarning()
            } catch (e: Exception) {
                val message = e.message.orEmpty()
                if (message.contains("token", ignoreCase = true) ||
                    message.contains("401") ||
                    message.contains("Unauthorized", ignoreCase = true)
                ) {
                    settings.clearSession()
                    runOnUiThread { showScreen(Screen.SETUP) }
                }
                onError(message.ifBlank { "Request failed" })
            }
        }
    }

    private fun setStatusReady() {
        binding.statusText.setTextColor(Color.parseColor("#D8F3DC"))
        binding.statusText.setText(R.string.status_ready)
        binding.resultTitle.setTextColor(Color.parseColor("#4ADE80"))
    }

    private fun clearResult() {
        binding.resultTable.removeAllViews()
        binding.resultTitle.visibility = View.GONE
        binding.resultTitle.setTextColor(Color.parseColor("#4ADE80"))
        binding.tableScroll.visibility = View.GONE
        binding.scanAgainButton.visibility = View.GONE
    }

    private fun renderTable(table: TableResult, warning: Boolean = false) {
        binding.resultTitle.text = table.title
        binding.resultTitle.setTextColor(
            Color.parseColor(if (warning) "#F4D35E" else "#4ADE80")
        )
        binding.resultTitle.visibility = View.VISIBLE
        binding.resultTable.removeAllViews()
        binding.resultTable.addView(buildRow(table.columns, header = true, rowIndex = -1))
        table.rows.forEachIndexed { index, cells ->
            binding.resultTable.addView(buildRow(cells, header = false, rowIndex = index))
        }
        binding.tableScroll.visibility = View.VISIBLE
    }

    private fun buildRow(cells: List<String>, header: Boolean, rowIndex: Int): TableRow {
        val row = TableRow(this)
        val bg = when {
            header -> Color.parseColor("#1B4332")
            rowIndex % 2 == 0 -> Color.parseColor("#0F241C")
            else -> Color.parseColor("#163028")
        }
        row.setBackgroundColor(bg)
        val pad = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            8f,
            resources.displayMetrics
        ).toInt()
        cells.forEach { value ->
            val cell = TextView(this)
            cell.text = value
            cell.setTextColor(if (header) Color.parseColor("#4ADE80") else Color.parseColor("#D8F3DC"))
            cell.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            if (header) cell.typeface = Typeface.DEFAULT_BOLD
            cell.setPadding(pad, pad, pad, pad)
            cell.gravity = Gravity.CENTER_VERTICAL
            cell.minWidth = pad * 10
            row.addView(
                cell,
                TableRow.LayoutParams(
                    TableRow.LayoutParams.WRAP_CONTENT,
                    TableRow.LayoutParams.WRAP_CONTENT
                )
            )
        }
        return row
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCamera()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "QrScanner"
        private const val DEBOUNCE_MS = 2500L
    }
}
