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
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
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
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
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

    // UI
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var connectBtn: Button
    private lateinit var mirrorBtn: Button
    private lateinit var zoomLabel: TextView
    private lateinit var zoomRow: LinearLayout
    private val chipValues = mutableListOf<Float>()
    private val chipViews = mutableListOf<TextView>()

    // Camera
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val encodeExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var latestFrame: Bitmap? = null
    @Volatile private var lastFrameAt = 0L
    @Volatile private var frontFacing = false

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
        ble.disconnect()
        analysisExecutor.shutdown()
        encodeExecutor.shutdown()
    }

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 31) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            list += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return list.toTypedArray()
    }

    private fun hasBlePermissions(): Boolean = requiredPermissions()
        .filter { it != Manifest.permission.CAMERA }
        .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    // ------------------------------------------------------------------ UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))

        // Top bar
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(36), dp(12), dp(8))
            setBackgroundColor(0x88000000.toInt())
        }
        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            text = "Not connected"
        }
        mirrorBtn = Button(this).apply {
            text = "Mirror: off"
            setOnClickListener {
                mirrorOut = !mirrorOut
                text = if (mirrorOut) "Mirror: on" else "Mirror: off"
            }
        }
        connectBtn = Button(this).apply {
            text = "Connect"
            setOnClickListener { onConnectClicked() }
        }
        top.addView(statusText, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(mirrorBtn)
        top.addView(connectBtn)
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // Bottom: zoom label, zoom chips, controls
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(28))
        }
        zoomLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        zoomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(0x66000000)
            }
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }
        val flipBtn = Button(this).apply {
            text = "Flip"
            setOnClickListener { flipCamera() }
        }
        val shutter = Button(this).apply {
            text = "Photo"
            setOnClickListener { takePhoto() }
        }
        controls.addView(flipBtn)
        controls.addView(android.widget.Space(this), LinearLayout.LayoutParams(dp(32), 1))
        controls.addView(shutter)

        bottom.addView(zoomLabel)
        bottom.addView(zoomRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        bottom.addView(controls)
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        // Pinch to zoom (continuous, like the stock camera)
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
    }

    private fun setStatus(text: String) = runOnUiThread { statusText.text = text }

    private fun fmtZoom(r: Float): String =
        if (r < 1f || abs(r - Math.round(r)) > 0.05f) String.format(Locale.US, "%.1f", r)
        else Math.round(r).toString()

    /** Samsung-style chips: ultra-wide (e.g. 0.6) if the phone exposes it, then 1/2/3/5/10 within range. */
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
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x55000000)
                }
                setOnClickListener { setZoom(value) }
            }
            zoomRow.addView(chip, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                leftMargin = dp(3); rightMargin = dp(3)
            })
            chipViews += chip
        }
    }

    private fun updateZoomUi(state: ZoomState) {
        if (chipValues.isEmpty()) buildZoomChips(state)
        zoomLabel.text = fmtZoom(state.zoomRatio) + "x"
        val active = nearestChipIndex(state.zoomRatio)
        chipViews.forEachIndexed { i, v ->
            val on = i == active
            v.setTextColor(if (on) Color.rgb(255, 214, 0) else Color.WHITE)
            v.text = if (on) fmtZoom(state.zoomRatio) + "x" else fmtZoom(chipValues[i])
        }
    }

    private fun nearestChipIndex(ratio: Float): Int {
        // Active chip = largest preset <= current ratio (like Samsung's highlighting)
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
        val target = if (dir > 0) {
            if (cur > chipValues[i] + 0.05f) i + 1 else i + 1
        } else {
            if (cur > chipValues[i] + 0.05f) i else i - 1
        }
        setZoom(chipValues[target.coerceIn(0, chipValues.size - 1)])
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
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
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

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        try {
            provider.unbindAll()
            val cam = provider.bindToLifecycle(this, selector, preview, imageCapture, analysis)
            camera = cam
            cam.cameraInfo.zoomState.observe(this) { updateZoomUi(it) }
        } catch (e: Exception) {
            Log.e(tag, "bind failed", e)
            setStatus("Camera error: ${e.message}")
        }
    }

    private fun flipCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        bindCamera()
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
        setStatus("Scanning…")
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
            val star = if (isUart(r)) "★ " else ""
            "$star${r.device.name ?: "(no name)"}  ${r.device.address}  ${r.rssi}dBm"
        }.toTypedArray<CharSequence>()
        AlertDialog.Builder(this)
            .setTitle("Pick your selfie screen (★ = likely match)")
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
        setStatus("Connected (MTU ${ble.mtu}). Waiting for screen…")
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
            0x01 -> if (cmd.size >= 10) { // open camera: w, h, rotation, buffer size
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
            0x02 -> runOnUiThread { takePhoto() }
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
                if (framesSent % 10 == 0) setStatus("Streaming ${devW}x$devH · ${jpeg.size / 1024}KB/frame")
            } catch (e: Exception) {
                Log.w(tag, "encode failed", e)
            } finally {
                sending.set(false)
            }
        }
    }

    /** Rotate for the screen, cover-crop to its resolution, JPEG under its buffer limit (same as the official app). */
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
