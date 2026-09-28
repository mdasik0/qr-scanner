package com.sumo.qrscanner

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.util.Patterns
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
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
    private enum class Screen { SETUP, MODE, SETTINGS, SCAN }
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
    private var setupFromSettings = false
    private var reportSending = false
    private var settingsEditing = false
    private var templateEditing = false
    private var pendingTemplate = DisplayTemplate.TABLE

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
        binding.setupAccessToken.setText(settings.accessToken)

        binding.continueButton.setOnClickListener { saveSetupAndShowMode() }
        binding.scannerModeButton.setOnClickListener { enterScan(ScanMode.SCANNER) }
        binding.seeDataModeButton.setOnClickListener { enterScan(ScanMode.SEE_DATA) }
        binding.settingsButton.setOnClickListener { showScreen(Screen.SETTINGS) }
        binding.settingsChangeUrlButton.setOnClickListener { onSettingsConnectionButton() }
        binding.settingsChangeTemplateButton.setOnClickListener { onSettingsTemplateButton() }
        binding.settingsPasteTokenButton.setOnClickListener { pasteSettingsToken() }
        binding.pasteTokenButton.setOnClickListener { pasteAccessToken() }
        binding.templateTablePreview.setOnClickListener { pickTemplate(DisplayTemplate.TABLE) }
        binding.templateListPreview.setOnClickListener { pickTemplate(DisplayTemplate.LIST) }
        binding.scanAgainButton.setOnClickListener {
            clearResult()
            setStatusReady()
            ensureCameraPermissionAndStart()
        }
        binding.settingsBackButton.setOnClickListener { navigateBack() }
        binding.setupBackButton.setOnClickListener { navigateBack() }
        binding.scanBackButton.setOnClickListener { navigateBack() }
        binding.setupCancelButton.setOnClickListener { navigateBack() }
        binding.changeModeButton.setOnClickListener { navigateBack() }
        binding.githubButton.setOnClickListener { openGithub() }
        binding.reportButton.setOnClickListener { openReportModal() }
        binding.reportCancelButton.setOnClickListener {
            if (!reportSending) closeReportModal()
        }
        binding.reportDismissButton.setOnClickListener {
            if (!reportSending) closeReportModal()
        }
        binding.reportThanksDoneButton.setOnClickListener { closeReportModal() }
        binding.reportSendButton.setOnClickListener { sendReport() }
        binding.reportModal.setOnClickListener {
            if (!reportSending) closeReportModal()
        }
        binding.reportCard.setOnClickListener { }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!navigateBack()) finish()
                }
            },
        )

        showScreen(if (settings.hasSetup) Screen.MODE else Screen.SETUP)
    }

    private fun saveSetupAndShowMode() {
        val url = binding.setupUrl.text?.toString().orEmpty()
        if (!AppSettings.isValidHttpUrl(url)) {
            Toast.makeText(this, R.string.setup_url_required, Toast.LENGTH_LONG).show()
            return
        }
        settings.baseUrl = url
        settings.accessToken = binding.setupAccessToken.text?.toString().orEmpty()
        val next = if (setupFromSettings) Screen.SETTINGS else Screen.MODE
        setupFromSettings = false
        showScreen(next)
    }

    /** Returns false when this screen is the root, so the system can leave the app. */
    private fun navigateBack(): Boolean {
        if (binding.reportModal.visibility == View.VISIBLE) {
            if (!reportSending) closeReportModal()
            return true
        }
        return when (screen) {
            Screen.SCAN -> {
                stopCamera()
                showScreen(Screen.MODE)
                true
            }
            Screen.SETTINGS -> {
                showScreen(Screen.MODE)
                true
            }
            Screen.SETUP -> {
                if (!setupFromSettings) return false
                setupFromSettings = false
                showScreen(Screen.SETTINGS)
                true
            }
            Screen.MODE -> false
        }
    }

    private fun onSettingsConnectionButton() {
        if (settingsEditing) {
            saveSettingsConnection()
        } else {
            setConnectionEditing(true)
        }
    }

    private fun onSettingsTemplateButton() {
        if (templateEditing) {
            saveSettingsTemplate()
        } else {
            setTemplateEditing(true)
        }
    }

    private fun setConnectionEditing(editing: Boolean, focusToken: Boolean = false) {
        if (editing && templateEditing) setTemplateEditing(false)
        settingsEditing = editing
        setSettingsFieldEditable(binding.settingsConnectedUrl, editing, uri = true)
        setSettingsFieldEditable(binding.settingsAccessToken, editing, uri = false)
        binding.settingsPasteTokenButton.visibility = visibleIf(editing)
        val side = dp(14)
        val end = if (editing) dp(48) else side
        binding.settingsAccessToken.setPadding(side, side, end, side)
        styleSectionButton(
            binding.settingsChangeUrlButton,
            saving = editing,
            editLabel = R.string.settings_edit_connection,
        )
        if (editing) {
            val focus = if (focusToken) binding.settingsAccessToken else binding.settingsConnectedUrl
            showKeyboard(focus)
        } else {
            hideKeyboard()
            binding.settingsConnectedUrl.setText(settings.baseUrl)
            binding.settingsAccessToken.setText(settings.accessToken)
        }
    }

    private fun setTemplateEditing(editing: Boolean) {
        if (editing && settingsEditing) setConnectionEditing(false)
        templateEditing = editing
        pendingTemplate = settings.displayTemplate
        binding.settingsChangeTemplateHint.setText(
            if (editing) R.string.settings_change_template_hint_edit
            else R.string.settings_change_template_hint,
        )
        styleSectionButton(
            binding.settingsChangeTemplateButton,
            saving = editing,
            editLabel = R.string.settings_edit_template,
        )
        highlightTemplatePreviews()
    }

    private fun styleSectionButton(
        button: MaterialButton,
        saving: Boolean,
        editLabel: Int,
    ) {
        if (saving) {
            button.setText(R.string.settings_save_connection)
            button.setIconResource(R.drawable.ic_save)
            button.strokeWidth = 0
            button.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2D6A4F"))
            button.setTextColor(Color.parseColor("#F4FFF7"))
            button.iconTint = ColorStateList.valueOf(Color.parseColor("#F4FFF7"))
        } else {
            button.setText(editLabel)
            button.setIconResource(R.drawable.ic_edit)
            button.strokeWidth = dp(1)
            button.strokeColor = ColorStateList.valueOf(Color.parseColor("#25503C"))
            button.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            button.setTextColor(Color.parseColor("#95D5B2"))
            button.iconTint = ColorStateList.valueOf(Color.parseColor("#95D5B2"))
        }
    }

    private fun setSettingsFieldEditable(
        field: EditText,
        editing: Boolean,
        uri: Boolean,
    ) {
        field.isFocusable = editing
        field.isFocusableInTouchMode = editing
        field.isCursorVisible = editing
        field.inputType = when {
            !editing -> InputType.TYPE_NULL
            uri -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            else -> InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
    }

    /** Returns false when the URL is invalid so the user can fix it. */
    private fun saveSettingsConnection(): Boolean {
        val url = binding.settingsConnectedUrl.text?.toString().orEmpty()
        if (!AppSettings.isValidHttpUrl(url)) {
            Toast.makeText(this, R.string.setup_url_required, Toast.LENGTH_LONG).show()
            return false
        }
        settings.baseUrl = url
        settings.accessToken = binding.settingsAccessToken.text?.toString().orEmpty()
        setConnectionEditing(false)
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        return true
    }

    private fun saveSettingsTemplate() {
        settings.displayTemplate = pendingTemplate
        setTemplateEditing(false)
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
    }

    private fun showKeyboard(view: View) {
        view.post {
            view.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun clipboardText(): String {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        return clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            ?.trim()
            .orEmpty()
    }

    private fun pasteSettingsToken() {
        val text = clipboardText()
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.paste_empty, Toast.LENGTH_SHORT).show()
            return
        }
        if (!settingsEditing) return
        binding.settingsAccessToken.setText(text)
        binding.settingsAccessToken.setSelection(text.length)
    }

    private fun openGithub() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.github_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openReportModal() {
        reportSending = false
        binding.reportEmail.setText("")
        binding.reportInput.setText("")
        binding.reportSendButton.isEnabled = true
        binding.reportSendButton.setText(R.string.report_send)
        binding.reportForm.visibility = View.VISIBLE
        binding.reportThanks.visibility = View.GONE
        binding.reportModal.visibility = View.VISIBLE
    }

    private fun closeReportModal() {
        hideKeyboard()
        reportSending = false
        binding.reportModal.visibility = View.GONE
    }

    private fun showReportThanks() {
        hideKeyboard()
        binding.reportForm.visibility = View.GONE
        binding.reportThanks.visibility = View.VISIBLE
    }

    private fun sendReport() {
        val fromEmail = binding.reportEmail.text?.toString().orEmpty().trim()
        val message = binding.reportInput.text?.toString().orEmpty().trim()
        if (fromEmail.isEmpty()) {
            Toast.makeText(this, R.string.report_email_required, Toast.LENGTH_SHORT).show()
            return
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(fromEmail).matches()) {
            Toast.makeText(this, R.string.report_email_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        if (message.isEmpty()) {
            Toast.makeText(this, R.string.report_empty, Toast.LENGTH_SHORT).show()
            return
        }
        if (reportSending) return
        reportSending = true
        binding.reportSendButton.isEnabled = false
        binding.reportSendButton.setText(R.string.report_sending)
        val appLabel = getString(R.string.app_version_label)
        Thread({
            try {
                api.sendFeedback(fromEmail, message, appLabel)
                runOnUiThread {
                    reportSending = false
                    showReportThanks()
                }
            } catch (_: Exception) {
                runOnUiThread {
                    reportSending = false
                    binding.reportSendButton.isEnabled = true
                    binding.reportSendButton.setText(R.string.report_send)
                    Toast.makeText(this, R.string.report_send_failed, Toast.LENGTH_LONG).show()
                }
            }
        }, "report-mail").start()
    }

    private fun hideKeyboard() {
        val view = currentFocus ?: binding.root
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun enterScan(mode: ScanMode) {
        scanMode = mode
        lastPayload = null
        sending.set(false)
        clearResult()
        setStatusReady()
        showScreen(Screen.SCAN)
        ensureCameraPermissionAndStart()
    }

    private fun showScreen(next: Screen) {
        screen = next
        binding.setupLayer.visibility = visibleIf(next == Screen.SETUP)
        binding.modeLayer.visibility = visibleIf(next == Screen.MODE)
        binding.settingsLayer.visibility = visibleIf(next == Screen.SETTINGS)
        binding.scanLayer.visibility = visibleIf(next == Screen.SCAN)
        if (next == Screen.MODE) {
            binding.connectedUrlText.text = settings.baseUrl
                .removePrefix("https://")
                .removePrefix("http://")
            binding.connectedTemplateText.text = templateLabel(settings.displayTemplate)
        }
        if (next == Screen.SETTINGS) bindSettingsFields()
        if (next == Screen.SETUP) bindSetupFields()
        if (next != Screen.SCAN) stopCamera()
    }

    private fun bindSettingsFields() {
        binding.settingsConnectedUrl.setText(settings.baseUrl)
        binding.settingsAccessToken.setText(settings.accessToken)
        pendingTemplate = settings.displayTemplate
        setConnectionEditing(false)
        setTemplateEditing(false)
    }

    private fun pasteAccessToken() {
        val text = clipboardText()
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.paste_empty, Toast.LENGTH_SHORT).show()
            return
        }
        binding.setupAccessToken.setText(text)
        binding.setupAccessToken.setSelection(text.length)
    }

    private fun bindSetupFields() {
        binding.setupUrl.setText(
            settings.baseUrl.ifBlank { BuildConfig.QR_API_BASE_URL }
        )
        binding.setupAccessToken.setText(settings.accessToken)
        val changing = setupFromSettings
        binding.setupTitle.setText(
            if (changing) R.string.setup_change_title else R.string.setup_title
        )
        binding.setupHint.setText(
            if (changing) R.string.setup_change_hint else R.string.setup_hint
        )
        binding.continueButton.setText(
            if (changing) R.string.setup_save else R.string.setup_continue
        )
        binding.setupCancelButton.visibility = visibleIf(changing)
        binding.setupBackButton.visibility = visibleIf(changing)
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
            binding.statusText.visibility = View.VISIBLE
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
                renderResult(table, warning = false)
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
                binding.statusText.visibility = View.VISIBLE
                binding.statusText.setTextColor(Color.parseColor("#F4D35E"))
                binding.statusText.setText(R.string.status_format_warning)
                renderResult(TableResult.formatGuide(), warning = true, forceTable = true)
                binding.scanAgainButton.visibility = View.GONE
                Toast.makeText(this, R.string.status_format_warning, Toast.LENGTH_SHORT).show()
                ensureCameraPermissionAndStart()
            }
        }
        val onError: (String) -> Unit = { message ->
            sending.set(false)
            runOnUiThread {
                binding.statusText.visibility = View.GONE
                binding.resultTitle.setTextColor(Color.parseColor("#4ADE80"))
                binding.resultTitle.visibility = View.VISIBLE
                binding.resultTitle.text = message
                binding.scanAgainButton.visibility = View.VISIBLE
                coverScreenWithResult(true)
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }

        cameraExecutor.execute {
            try {
                if (scanMode == ScanMode.SCANNER) {
                    val json = api.postScan(settings.baseUrl, payload, settings.accessToken)
                    lastSentAt.set(System.currentTimeMillis())
                    sending.set(false)
                    runOnUiThread {
                        try {
                            renderResult(TableResult.fromShowResponse(json), warning = false)
                        } catch (_: ShowFormatException) {
                            binding.statusText.visibility = View.GONE
                            binding.resultTitle.visibility = View.VISIBLE
                            binding.resultTitle.text = json.optString("message").ifBlank { "Sent" }
                            coverScreenWithResult(true)
                        }
                        binding.scanAgainButton.visibility = View.VISIBLE
                        Toast.makeText(this, "Sent", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val json = api.postSeeData(settings.baseUrl, payload, settings.accessToken)
                    onSuccess(TableResult.fromShowResponse(json))
                }
            } catch (_: ShowFormatException) {
                onFormatWarning()
            } catch (e: Exception) {
                onError(e.message.orEmpty().ifBlank { "Request failed" })
            }
        }
    }

    private fun setStatusReady() {
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.setTextColor(Color.parseColor("#D8F3DC"))
        binding.statusText.setText(R.string.status_ready)
        binding.resultTitle.setTextColor(Color.parseColor("#4ADE80"))
    }

    private fun pickTemplate(template: DisplayTemplate) {
        if (!templateEditing) return
        pendingTemplate = template
        highlightTemplatePreviews()
    }

    private fun highlightTemplatePreviews() {
        val shown = if (templateEditing) pendingTemplate else settings.displayTemplate
        styleTemplatePreview(
            binding.templateTablePreview,
            selected = shown == DisplayTemplate.TABLE,
        )
        styleTemplatePreview(
            binding.templateListPreview,
            selected = shown == DisplayTemplate.LIST,
        )
    }

    private fun styleTemplatePreview(card: MaterialCardView, selected: Boolean) {
        card.isClickable = templateEditing
        card.isFocusable = templateEditing
        card.alpha = when {
            templateEditing -> 1f
            selected -> 1f
            else -> 0.55f
        }
        if (selected) {
            card.strokeColor = Color.parseColor("#4ADE80")
            card.strokeWidth = dp(2)
            card.setCardBackgroundColor(Color.parseColor("#1B4332"))
        } else {
            card.strokeColor = Color.parseColor("#1B4332")
            card.strokeWidth = dp(2)
            card.setCardBackgroundColor(Color.parseColor("#163028"))
        }
    }

    private fun templateLabel(template: DisplayTemplate): String = when (template) {
        DisplayTemplate.TABLE -> getString(R.string.template_table)
        DisplayTemplate.LIST -> getString(R.string.template_list)
    }

    private fun clearResult() {
        binding.resultTable.removeAllViews()
        binding.resultList.removeAllViews()
        binding.resultTitle.visibility = View.GONE
        binding.resultTitle.setTextColor(Color.parseColor("#4ADE80"))
        binding.tableScroll.visibility = View.GONE
        binding.listScroll.visibility = View.GONE
        binding.scanAgainButton.visibility = View.GONE
        coverScreenWithResult(false)
    }

    /** After a successful scan, hide the camera and let the result use the full screen. */
    private fun coverScreenWithResult(cover: Boolean) {
        binding.previewView.visibility = if (cover) View.GONE else View.VISIBLE
        val cardParams = binding.scanCard.layoutParams as FrameLayout.LayoutParams
        cardParams.height = if (cover) {
            FrameLayout.LayoutParams.MATCH_PARENT
        } else {
            FrameLayout.LayoutParams.WRAP_CONTENT
        }
        cardParams.gravity = Gravity.BOTTOM
        binding.scanCard.layoutParams = cardParams
        val pad = dp(16)
        binding.scanCard.setPadding(pad, pad, pad, pad)

        val scrollHeight = if (cover) 0 else LinearLayout.LayoutParams.WRAP_CONTENT
        val scrollWeight = if (cover) 1f else 0f
        listOf(binding.tableScroll, binding.listScroll).forEach { scroll ->
            val params = scroll.layoutParams as LinearLayout.LayoutParams
            params.height = scrollHeight
            params.weight = scrollWeight
            scroll.layoutParams = params
        }
    }

    private fun renderResult(
        table: TableResult,
        warning: Boolean = false,
        forceTable: Boolean = false,
    ) {
        binding.resultTitle.text = table.title
        binding.resultTitle.setTextColor(
            Color.parseColor(if (warning) "#F4D35E" else "#4ADE80")
        )
        binding.resultTitle.visibility = View.VISIBLE
        binding.statusText.visibility = if (warning) View.VISIBLE else View.GONE
        binding.resultTable.removeAllViews()
        binding.resultList.removeAllViews()
        val template = if (forceTable) DisplayTemplate.TABLE else settings.displayTemplate
        when (template) {
            DisplayTemplate.TABLE -> {
                binding.resultTable.addView(buildRow(table.columns, header = true, rowIndex = -1))
                table.rows.forEachIndexed { index, cells ->
                    binding.resultTable.addView(buildRow(cells, header = false, rowIndex = index))
                }
                binding.tableScroll.visibility = View.VISIBLE
                binding.listScroll.visibility = View.GONE
            }
            DisplayTemplate.LIST -> {
                records(table).forEachIndexed { index, record ->
                    if (index > 0) binding.resultList.addView(buildDivider())
                    record.forEach { (field, value) ->
                        binding.resultList.addView(buildListField(field, value))
                    }
                }
                binding.tableScroll.visibility = View.GONE
                binding.listScroll.visibility = View.VISIBLE
            }
        }
        coverScreenWithResult(!warning)
    }

    private fun records(table: TableResult): List<List<Pair<String, String>>> {
        val mapped = if (isFieldValueTable(table)) {
            listOf(
                table.rows.map { row ->
                    (row.getOrNull(0) ?: "").ifBlank { "—" } to (row.getOrNull(1) ?: "—")
                },
            )
        } else {
            table.rows.map { row ->
                table.columns.mapIndexed { index, column ->
                    column to (row.getOrNull(index) ?: "—")
                }
            }
        }
        return mapped.map { unwrapFieldValueRecord(it) }
    }

    private fun unwrapFieldValueRecord(
        record: List<Pair<String, String>>,
    ): List<Pair<String, String>> {
        if (record.size != 2) return record
        val map = record.associate { it.first.trim().lowercase() to it.second }
        val label = map["field"] ?: map["key"] ?: map["name"] ?: map["label"]
        val value = map["value"] ?: map["data"] ?: map["content"]
        if (label.isNullOrBlank() || value == null) return record
        return listOf(label to value)
    }

    private fun isFieldValueTable(table: TableResult): Boolean {
        if (table.columns.size != 2) return false
        val first = table.columns[0].trim().lowercase()
        val second = table.columns[1].trim().lowercase()
        return first in setOf("field", "key", "name", "label") &&
            second in setOf("value", "data", "content")
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()
    }

    private fun buildListField(field: String, value: String): LinearLayout {
        val block = LinearLayout(this)
        block.orientation = LinearLayout.VERTICAL
        block.setPadding(0, dp(8), 0, dp(4))
        val label = TextView(this)
        label.text = field
        label.setTextColor(Color.parseColor("#95D5B2"))
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        val body = TextView(this)
        body.text = value
        body.setTextColor(Color.parseColor("#D8F3DC"))
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        body.typeface = Typeface.DEFAULT_BOLD
        block.addView(label)
        block.addView(body)
        return block
    }

    private fun buildDivider(): View {
        val line = View(this)
        line.setBackgroundColor(Color.parseColor("#1B4332"))
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1),
        )
        params.topMargin = dp(8)
        params.bottomMargin = dp(8)
        line.layoutParams = params
        return line
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
        private const val GITHUB_URL = "https://github.com/mdasik0/qr-scanner"
    }
}
