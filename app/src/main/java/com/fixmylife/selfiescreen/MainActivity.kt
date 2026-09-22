package com.fixmylife.selfiescreen

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

@SuppressLint("MissingPermission", "SetTextI18n", "ClickableViewAccessibility")
class MainActivity : ComponentActivity(), BleLink.Listener {

    private val tag = "SelfieScreen"
    private enum class Mode { PHOTO, VIDEO }

    // UI
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var connectBtn: TextView
    private lateinit var mirrorBtn: TextView
    private lateinit var micBtn: TextView
    private lateinit var flashBtn: TextView
    private lateinit var recTimer: TextView
    private lateinit var shutter: View
    private lateinit var shutterInner: View
    private lateinit var zoomRow: LinearLayout
    private lateinit var photoTab: TextView
    private lateinit var videoTab: TextView
    private val chipValues = mutableListOf<Float>()
    private val chipViews = mutableListOf<TextView>()

    // Camera
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var mode = Mode.PHOTO
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var torchOn = false
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val encodeExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var latestFrame: Bitmap? = null
    @Volatile private var lastFrameAt = 0L
    @Volatile private var frontFacing = false

    // Audio
    private var micDevice: AudioDeviceInfo? = null
    private var micLabel = "Phone mic"

    // Device / protocol state
    private lateinit var ble: BleLink
    private val sending = AtomicBoolean(false)
    @Volatile private var devW = 0
    @Volatile private var devH = 0
    @Volatile private var devRot = 0
    @Volatile private var devBuf = 0
    @Volatile private var mirrorOut = false
    private var framesSent = 0

    private val presets = floatArrayOf(1f, 2f, 3f, 5f, 10f)
    private val main = Handler(Looper.getMainLooper())
    private var recStartedAt = 0L
    private val tick = object : Runnable {
        override fun run() {
            if (recording != null) {
                val s = (SystemClock.elapsedRealtime() - recStartedAt) / 1000
                recTimer.text = String.format(Locale.US, "\u25CF %02d:%02d", s / 60, s % 60)
                main.postDelayed(this, 500)
            }
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.CAMERA] == true) startCamera()
        else setStatus("Camera permission is required")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ble = BleLink(applicationContext, this)
        buildUi()
        permLauncher.launch(requiredPermissions())
    }

    override fun onDestroy() {
        super.onDestroy()
        recording?.stop()
        clearAudioRoute()
        ble.disconnect()
        analysisExecutor.shutdown()
        encodeExecutor.shutdown()
    }

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            list += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return list.toTypedArray()
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun hasBlePermissions(): Boolean = requiredPermissions()
        .filter { it != Manifest.permission.CAMERA && it != Manifest.permission.RECORD_AUDIO }
        .all { granted(it) }

    // ------------------------------------------------------------------ UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pill(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        setTextColor(Color.WHITE)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(0x66000000)
        }
        setOnClickListener { onClick() }
    }

    private fun circle(size: Int, color: Int): View = View(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        layoutParams = FrameLayout.LayoutParams(dp(size), dp(size), Gravity.CENTER)
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))

        // ---- top bar: status + flash + mirror + mic + connect
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(40), dp(12), dp(8))
        }
        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            text = "Not connected"
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        flashBtn = pill("\u26A1 Off") { cycleFlash() }
        mirrorBtn = pill("Mirror") {
            mirrorOut = !mirrorOut
            mirrorBtn.setTextColor(if (mirrorOut) Color.rgb(255, 214, 0) else Color.WHITE)
        }
        micBtn = pill("Mic") { showMicPicker() }
        connectBtn = pill("Connect") { onConnectClicked() }
        top.addView(statusText, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(flashBtn)
        top.addView(mirrorBtn, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        top.addView(micBtn, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        top.addView(connectBtn, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // ---- recording timer
        recTimer = TextView(this).apply {
            setTextColor(Color.rgb(255, 60, 60))
            textSize = 15f
            visibility = View.GONE
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        root.addView(recTimer, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(84)
        })

        // ---- bottom stack: zoom chips, shutter row, mode tabs
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(10), 0, dp(20))
        }
        zoomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val shutterRow = FrameLayout(this)
        val shutterView = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            setOnClickListener { onShutter() }
        }
        shutter = shutterView
        shutterInner = circle(30, Color.rgb(255, 60, 60)).apply { visibility = View.GONE }
        shutterView.addView(shutterInner)
        shutterRow.addView(shutterView, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER))

        val flip = TextView(this).apply {
            text = "\u27F3"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x55FFFFFF)
            }
            setOnClickListener { flipCamera() }
        }
        shutterRow.addView(flip, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER_VERTICAL or Gravity.END).apply {
            rightMargin = dp(36)
        })

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }
        photoTab = TextView(this).apply {
            text = "PHOTO"
            textSize = 13f
            setPadding(dp(16), 0, dp(16), 0)
            setOnClickListener { setMode(Mode.PHOTO) }
        }
        videoTab = TextView(this).apply {
            text = "VIDEO"
            textSize = 13f
            setPadding(dp(16), 0, dp(16), 0)
            setOnClickListener { setMode(Mode.VIDEO) }
        }
        tabs.addView(photoTab)
        tabs.addView(videoTab)

        bottom.addView(zoomRow)
        bottom.addView(shutterRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        bottom.addView(tabs)
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cur = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                setZoom(cur * detector.scaleFactor)
                return true
            }
        })
        previewView.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            true
        }

        setContentView(root)
        updateModeUi()
    }

    private fun setStatus(text: String) = runOnUiThread { statusText.text = text }

    // --------------------------------------------------------------- Flash

    /** Photo mode cycles Off -> Auto -> On; video mode toggles the torch. */
    private fun cycleFlash() {
        if (mode == Mode.VIDEO) {
            torchOn = !torchOn
            camera?.cameraControl?.enableTorch(torchOn)
        } else {
            flashMode = when (flashMode) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                else -> ImageCapture.FLASH_MODE_OFF
            }
            imageCapture?.flashMode = flashMode
        }
        updateFlashUi()
    }

    private fun updateFlashUi() {
        val hasFlash = camera?.cameraInfo?.hasFlashUnit() ?: true
        flashBtn.visibility = if (hasFlash) View.VISIBLE else View.GONE
        val on: Boolean
        if (mode == Mode.VIDEO) {
            flashBtn.text = if (torchOn) "\u26A1 Torch" else "\u26A1 Off"
            on = torchOn
        } else {
            flashBtn.text = when (flashMode) {
                ImageCapture.FLASH_MODE_ON -> "\u26A1 On"
                ImageCapture.FLASH_MODE_AUTO -> "\u26A1 Auto"
                else -> "\u26A1 Off"
            }
            on = flashMode != ImageCapture.FLASH_MODE_OFF
        }
        flashBtn.setTextColor(if (on) Color.rgb(255, 214, 0) else Color.WHITE)
    }

    private fun fmtZoom(r: Float): String =
        if (r < 1f) String.format(Locale.US, "%.1f", r).removePrefix("0")
        else if (abs(r - Math.round(r)) > 0.05f) String.format(Locale.US, "%.1f", r)
        else Math.round(r).toString()

    /** Samsung-style chips: ultra-wide (e.g. .6) if the phone exposes it, then 1/2/3/5/10 within range. */
    private fun buildZoomChips(state: ZoomState) {
        zoomRow.removeAllViews()
        chipValues.clear()
        chipViews.clear()
        if (state.minZoomRatio < 0.99f) chipValues += state.minZoomRatio
        presets.filter { it <= state.maxZoomRatio + 0.01f }.forEach { chipValues += it }

        for (value in chipValues) {
            val chip = TextView(this).apply {
                text = fmtZoom(value)
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setShadowLayer(4f, 0f, 0f, Color.BLACK)
                setOnClickListener { setZoom(value) }
            }
            zoomRow.addView(chip, LinearLayout.LayoutParams(dp(42), dp(42)))
            chipViews += chip
        }
    }

    private fun updateZoomUi(state: ZoomState) {
        if (chipValues.isEmpty()) buildZoomChips(state)
        val active = nearestChipIndex(state.zoomRatio)
        chipViews.forEachIndexed { i, v ->
            val on = i == active
            v.text = if (on) fmtZoom(state.zoomRatio) + "\u00D7" else fmtZoom(chipValues[i])
            v.background = if (on) GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC202020.toInt())
            } else null
        }
    }

    private fun nearestChipIndex(ratio: Float): Int {
        var idx = 0
        chipValues.forEachIndexed { i, v -> if (ratio + 0.01f >= v) idx = i }
        return idx
    }

    private fun setZoom(ratio: Float) {
        val st = camera?.cameraInfo?.zoomState?.value ?: return
        camera?.cameraControl?.setZoomRatio(ratio.coerceIn(st.minZoomRatio, st.maxZoomRatio))
    }

    private fun stepZoom(dir: Int) {
        if (chipValues.isEmpty()) return
        val cur = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
        val i = nearestChipIndex(cur)
        val target = if (dir > 0) i + 1 else if (cur > chipValues[i] + 0.05f) i else i - 1
        setZoom(chipValues[target.coerceIn(0, chipValues.size - 1)])
    }

    private fun setMode(m: Mode) {
        if (mode == m || recording != null) return
        mode = m
        updateModeUi()
        bindCamera()
    }

    private fun updateModeUi() {
        photoTab.setTextColor(if (mode == Mode.PHOTO) Color.WHITE else 0x99FFFFFF.toInt())
        videoTab.setTextColor(if (mode == Mode.VIDEO) Color.WHITE else 0x99FFFFFF.toInt())
        micBtn.visibility = if (mode == Mode.VIDEO) View.VISIBLE else View.GONE
        shutterInner.visibility = if (mode == Mode.VIDEO) View.VISIBLE else View.GONE
        updateFlashUi()
    }

    // -------------------------------------------------------------- Camera

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        camera?.cameraInfo?.zoomState?.removeObservers(this)
        chipValues.clear()
        frontFacing = lensFacing == CameraSelector.LENS_FACING_FRONT

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    ).build()
            )
            .build()
        analysis.setAnalyzer(analysisExecutor) { proxy ->
            try {
                val now = SystemClock.elapsedRealtime()
                if (ble.ready && now - lastFrameAt > 60) {
                    lastFrameAt = now
                    val bmp = proxy.toBitmap()
                    val m = Matrix().apply {
                        postRotate(proxy.imageInfo.rotationDegrees.toFloat())
                        if (frontFacing) postScale(-1f, 1f)
                    }
                    latestFrame = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                }
            } catch (e: Exception) {
                Log.w(tag, "analyze failed", e)
            } finally {
                proxy.close()
            }
        }

        val third: UseCase = if (mode == Mode.PHOTO) {
            ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setFlashMode(flashMode)
                .build().also { imageCapture = it; videoCapture = null }
        } else {
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
                ).build()
            VideoCapture.withOutput(recorder).also { videoCapture = it; imageCapture = null }
        }

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        try {
            provider.unbindAll()
            val cam = provider.bindToLifecycle(this, selector, preview, analysis, third)
            camera = cam
            cam.cameraInfo.zoomState.observe(this) { updateZoomUi(it) }
            if (mode == Mode.VIDEO && torchOn) cam.cameraControl.enableTorch(true) else torchOn = false
            updateFlashUi()
        } catch (e: Exception) {
            Log.e(tag, "bind failed", e)
            setStatus("Camera error: ${e.message}")
        }
    }

    private fun flipCamera() {
        if (recording != null) return
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        bindCamera()
    }

    private fun onShutter() {
        if (mode == Mode.PHOTO) takePhoto() else toggleRecording()
    }

    private fun takePhoto() {
        val ic = imageCapture ?: return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "SelfieScreen_${System.currentTimeMillis()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/SelfieScreen")
        }
        val opts = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()
        ic.takePicture(opts, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                Toast.makeText(this@MainActivity, "Photo saved", Toast.LENGTH_SHORT).show()
            }
            override fun onError(exc: ImageCaptureException) {
                Toast.makeText(this@MainActivity, "Photo failed: ${exc.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    // --------------------------------------------------------------- Video

    private fun toggleRecording() {
        val active = recording
        if (active != null) {
            active.stop()
            return
        }
        val vc = videoCapture ?: return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "SelfieScreen_${System.currentTimeMillis()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/SelfieScreen")
        }
        val opts = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()

        applyAudioRoute()
        var pending = vc.output.prepareRecording(this, opts)
        if (granted(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()

        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    recStartedAt = SystemClock.elapsedRealtime()
                    recTimer.visibility = View.VISIBLE
                    main.post(tick)
                    setShutterRecording(true)
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    recTimer.visibility = View.GONE
                    setShutterRecording(false)
                    clearAudioRoute()
                    if (event.hasError()) {
                        Toast.makeText(this, "Recording failed (${event.error})", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Video saved", Toast.LENGTH_SHORT).show()
                    }
                }
                else -> {}
            }
        }
    }

    private fun setShutterRecording(on: Boolean) {
        val lp = shutterInner.layoutParams as FrameLayout.LayoutParams
        lp.width = if (on) dp(26) else dp(30)
        lp.height = lp.width
        shutterInner.layoutParams = lp
        shutterInner.background = GradientDrawable().apply {
            shape = if (on) GradientDrawable.RECTANGLE else GradientDrawable.OVAL
            cornerRadius = dp(5).toFloat()
            setColor(Color.rgb(255, 60, 60))
        }
        shutterInner.visibility = if (mode == Mode.VIDEO) View.VISIBLE else View.GONE
    }

    // --------------------------------------------------------------- Audio

    private fun audioManager(): AudioManager? = getSystemService(AudioManager::class.java)

    private fun micOptions(): List<AudioDeviceInfo> {
        val am = audioManager() ?: return emptyList()
        return if (Build.VERSION.SDK_INT >= 31) am.availableCommunicationDevices
        else am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
    }

    private fun micName(d: AudioDeviceInfo): String {
        val kind = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone mic"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth mic"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB mic"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired mic"
            else -> "Audio input"
        }
        val product = d.productName?.toString()?.trim().orEmpty()
        return if (product.isEmpty()) kind else "$kind \u2014 $product"
    }

    private fun showMicPicker() {
        val options = micOptions()
        val labels = (listOf("Default (phone mic)") + options.map { micName(it) }).toTypedArray<CharSequence>()
        AlertDialog.Builder(this)
            .setTitle("Microphone")
            .setItems(labels) { _, which ->
                if (which == 0) {
                    micDevice = null
                    micLabel = "Phone mic"
                } else {
                    micDevice = options[which - 1]
                    micLabel = micName(options[which - 1])
                }
                micBtn.text = if (micDevice == null) "Mic" else "Mic \u2713"
                Toast.makeText(this, micLabel, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Route capture to the chosen input. Needs Android 12+ for setCommunicationDevice. */
    private fun applyAudioRoute() {
        val am = audioManager() ?: return
        val dev = micDevice ?: return
        if (Build.VERSION.SDK_INT >= 31) {
            val ok = am.setCommunicationDevice(dev)
            if (!ok) Toast.makeText(this, "Could not switch to $micLabel", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Mic selection needs Android 12+", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearAudioRoute() {
        val am = audioManager() ?: return
        if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
    }

    // ----------------------------------------------------------------- BLE

    private fun onConnectClicked() {
        if (ble.ready) {
            ble.disconnect()
            onDisconnected()
            return
        }
        if (!hasBlePermissions()) {
            permLauncher.launch(requiredPermissions())
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Turn on Bluetooth first", Toast.LENGTH_SHORT).show()
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: return
        val found = LinkedHashMap<String, ScanResult>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                found[result.device.address] = result
            }
        }
        setStatus("Scanning\u2026")
        connectBtn.isEnabled = false
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        main.postDelayed({
            scanner.stopScan(cb)
            connectBtn.isEnabled = true
            showDevicePicker(found.values.toList())
        }, 5000)
    }

    private fun isUart(r: ScanResult) =
        r.scanRecord?.serviceUuids?.any { it.uuid == BleLink.SERVICE } == true

    private fun showDevicePicker(results: List<ScanResult>) {
        val list = results
            .filter { isUart(it) || !it.device.name.isNullOrBlank() }
            .sortedWith(compareByDescending<ScanResult> { isUart(it) }.thenByDescending { it.rssi })
        if (list.isEmpty()) {
            setStatus("No devices found. Is the screen on?")
            return
        }
        val labels = list.map { r ->
            val star = if (isUart(r)) "\u2605 " else ""
            "$star${r.device.name ?: "(no name)"}  ${r.device.address}  ${r.rssi}dBm"
        }.toTypedArray<CharSequence>()
        AlertDialog.Builder(this)
            .setTitle("Pick your selfie screen (\u2605 = likely match)")
            .setItems(labels) { _, which -> connectTo(list[which].device) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun connectTo(device: BluetoothDevice) {
        framesSent = 0
        ble.connect(device)
    }

    override fun onStatus(text: String) = setStatus(text)

    override fun onReady() {
        setStatus("Connected (MTU ${ble.mtu})")
        runOnUiThread { connectBtn.text = "Disconnect" }
    }

    override fun onDisconnected() {
        devW = 0
        latestFrame = null
        setStatus("Disconnected")
        runOnUiThread { connectBtn.text = "Connect"; connectBtn.isEnabled = true }
    }

    override fun onCommand(cmd: ByteArray) {
        when (cmd[0].toInt() and 0xFF) {
            0x01 -> if (cmd.size >= 10) {
                val b = ByteBuffer.wrap(cmd, 1, 9).order(ByteOrder.LITTLE_ENDIAN)
                devW = b.short.toInt() and 0xFFFF
                devH = b.short.toInt() and 0xFFFF
                devRot = when (b.get().toInt()) { 1 -> 90; 2 -> 180; 3 -> 270; else -> 0 }
                devBuf = b.int
                if (ble.mtu < 300) devBuf = minOf(devBuf, 5120)
                setStatus("Streaming to ${devW}x$devH")
                ble.sendCommand(0x01, 1)
            }
            0x03 -> sendFrame()
            0x00 -> {
                ble.sendCommand(0x00, 1)
                setStatus("Screen closed camera")
            }
            0x02 -> runOnUiThread { onShutter() }
            0x04, 0x05 -> runOnUiThread {
                if (mode != Mode.VIDEO) setMode(Mode.VIDEO) else toggleRecording()
            }
            0x06 -> runOnUiThread { flipCamera() }
            0x0A -> runOnUiThread { stepZoom(-1) }
            0x0B -> runOnUiThread { stepZoom(+1) }
            0xF7 -> if (ble.mtu < 300 && devBuf > 0) devBuf = minOf(devBuf, 5120)
            else -> Log.d(tag, "device cmd ${cmd.joinToString(" ") { "%02X".format(it) }}")
        }
    }

    private fun sendFrame() {
        if (devW <= 0 || devH <= 0 || devBuf <= 0) return
        if (!sending.compareAndSet(false, true)) return
        encodeExecutor.execute {
            try {
                val src = latestFrame ?: return@execute
                if (ble.pendingPackets > 0) return@execute
                val jpeg = encode(src) ?: return@execute
                ble.send(jpeg)
                framesSent++
                if (framesSent % 10 == 0) setStatus("Streaming ${devW}x$devH \u00B7 ${jpeg.size / 1024}KB/frame")
            } catch (e: Exception) {
                Log.w(tag, "encode failed", e)
            } finally {
                sending.set(false)
            }
        }
    }

    /** Rotate for the screen, cover-crop to its resolution, JPEG under its buffer limit. */
    private fun encode(src: Bitmap): ByteArray? {
        val m = Matrix().apply {
            postRotate(devRot.toFloat())
            if (mirrorOut) postScale(-1f, 1f)
        }
        val oriented = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        val out = Bitmap.createBitmap(devW, devH, Bitmap.Config.RGB_565)
        val scale = max(devW / oriented.width.toFloat(), devH / oriented.height.toFloat())
        val place = Matrix().apply {
            setScale(scale, scale)
            postTranslate((devW - oriented.width * scale) / 2f, (devH - oriented.height * scale) / 2f)
        }
        Canvas(out).drawBitmap(oriented, place, Paint(Paint.FILTER_BITMAP_FLAG))

        val limit = devBuf - 100
        var quality = 80
        var bytes: ByteArray
        do {
            val bos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, quality, bos)
            bytes = bos.toByteArray()
            if (bytes.size <= limit) return bytes
            quality -= 10
        } while (quality > 0)
        return if (bytes.size <= devBuf) bytes else null
    }
}
