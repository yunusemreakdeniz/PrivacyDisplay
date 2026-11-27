package io.github.yunusemreakdeniz.privacydisplay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the screen shows about the running service. */
data class PrivacyStatus(
    val running: Boolean = false,
    val state: PrivacyState = PrivacyState.IDLE,
    val faces: Int = -1,
    val yaw: Float = 0f,
    val lux: Float = -1f,
    val lowLight: Boolean = false,
)

/**
 * Runs the roadmap's four-level state machine:
 * IDLE (screen off or not a watched app: nothing runs) -> SENSOR_WATCH (accelerometer and light
 * sensor at low rate, camera off) -> ACTIVE_CHECK (front camera at 320x240, ML Kit fast mode,
 * head yaw only) -> SHIELD_ACTIVE (curtain down until the view is safe again).
 */
class PrivacyService : LifecycleService(), SensorEventListener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var overlay: ShieldOverlay
    private lateinit var sensors: SensorManager
    private val decider = ShieldDecider()
    private val motion = MotionTracker()

    private var state = PrivacyState.IDLE
    private var screenOn = true
    private var foregroundPackage: String? = null
    private var lastUsageQuery = 0L
    private var lux = -1f
    private var moved = false
    private var lastCheckAt = 0L
    private var cameraStartedAt = 0L
    private var lastFrameAt = 0L

    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var analyzerExecutor: ExecutorService? = null
    private var detector: FaceDetector? = null

    @Volatile private var analyzeIntervalMs = 0L
    @Volatile private var nextAnalyzeAt = 0L
    @Volatile private var analyzing = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn = intent.action != Intent.ACTION_SCREEN_OFF
            evaluate()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            evaluate()
            main.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        overlay = ShieldOverlay(this)
        sensors = getSystemService(SensorManager::class.java)
        screenOn = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        if (!startInForeground()) {
            stopSelf()
            return
        }
        overlay.attach()
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        _status.value = PrivacyStatus(running = true)
        main.post(tick)
        requestTileUpdate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.enabled = false
                stopSelf()
            }
            ACTION_REFRESH -> {
                if (!prefs.smartMode) {
                    stopCamera()
                    decider.reset()
                    if (state != PrivacyState.IDLE) state = PrivacyState.SENSOR_WATCH
                }
                showLayer(force = true)
                evaluate()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenReceiver) }
        enterIdle()
        overlay.detach()
        detector?.close()
        analyzerExecutor?.shutdown()
        _status.value = PrivacyStatus(running = false)
        requestTileUpdate()
        super.onDestroy()
    }

    // ---- State machine -------------------------------------------------------------------

    private fun evaluate() {
        val now = SystemClock.elapsedRealtime()
        if (!screenOn || !watchedAppInFront(now)) {
            enterIdle()
            publish()
            return
        }
        if (state == PrivacyState.IDLE) enterWatch(now)

        if (!prefs.smartMode) {
            // Ghost filter only: no camera, no tilt rule.
            showLayer()
            publish()
            return
        }

        val lowLight = lux in 0f..LOW_LIGHT_LUX
        if (lowLight) {
            // Too dark for the camera to see anyone: decide from the tilt alone.
            stopCamera()
            decider.onTilt(motion.roll)
            applyShield()
            state = if (decider.shielded) PrivacyState.SHIELD_ACTIVE else PrivacyState.SENSOR_WATCH
            publish(lowLight = true)
            return
        }

        when (state) {
            PrivacyState.SENSOR_WATCH -> {
                // Continuous protection keeps the camera on; battery saver lets it sleep between checks.
                if (!prefs.batterySaver || moved || now - lastCheckAt >= prefs.idleCheckSeconds * 1000L) {
                    moved = false
                    motion.mark(now)
                    startCamera(now)
                    state = PrivacyState.ACTIVE_CHECK
                }
            }
            PrivacyState.ACTIVE_CHECK, PrivacyState.SHIELD_ACTIVE -> {
                // Something building up or the phone moving: every frame. Otherwise a steady pace,
                // fast enough to catch someone leaning in, slower in battery saver.
                analyzeIntervalMs = when {
                    decider.suspicious || motion.stillFor(now) < MOVING_WINDOW_MS -> 0L
                    prefs.batterySaver -> STILL_INTERVAL_MS
                    else -> STEADY_INTERVAL_MS
                }
                val sawFrame = lastFrameAt > cameraStartedAt
                if (prefs.batterySaver && !decider.suspicious && sawFrame && motion.stillFor(now) >= SETTLE_MS) {
                    // Safe and still for a while: put the camera back to sleep.
                    stopCamera()
                    lastCheckAt = now
                    state = PrivacyState.SENSOR_WATCH
                }
            }
            PrivacyState.IDLE -> Unit
        }
        showLayer()
        publish()
    }

    private fun enterWatch(now: Long) {
        state = PrivacyState.SENSOR_WATCH
        lastCheckAt = now - prefs.idleCheckSeconds * 1000L // check right away
        sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensors.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun enterIdle() {
        if (state == PrivacyState.IDLE && cameraProvider == null) return
        sensors.unregisterListener(this)
        stopCamera()
        decider.reset()
        overlay.clear(animate = false)
        shownLayer = null
        lux = -1f
        state = PrivacyState.IDLE
    }

    private fun applyShield() = showLayer()

    private var shownLayer: String? = null

    /** Curtain when someone else is looking, otherwise the ghost filter (if on), otherwise nothing. */
    private fun showLayer(force: Boolean = false) {
        val layer = when {
            state == PrivacyState.IDLE -> "none"
            decider.shielded -> "curtain"
            prefs.ghostMode -> "ghost"
            else -> "none"
        }
        if (layer == shownLayer && !force) return
        shownLayer = layer
        when (layer) {
            "curtain" -> overlay.curtain(prefs.intensity)
            "ghost" -> overlay.ghost(prefs.ghostStrength)
            else -> overlay.clear()
        }
    }

    private fun onReading(reading: FaceReading) {
        if (state != PrivacyState.ACTIVE_CHECK && state != PrivacyState.SHIELD_ACTIVE) return
        lastFrameAt = SystemClock.elapsedRealtime()
        decider.shieldWhenNoFace = prefs.shieldWhenNoFace
        decider.onFrame(reading, lastFrameAt)
        applyShield()
        state = if (decider.shielded) PrivacyState.SHIELD_ACTIVE else PrivacyState.ACTIVE_CHECK
        publish(faces = reading.faces, yaw = reading.yawDegrees)
    }

    // ---- Watched apps --------------------------------------------------------------------

    private fun watchedAppInFront(now: Long): Boolean {
        val pkg = currentPackage(now)
        if (pkg == packageName) return false // our own settings screen
        if (!prefs.onlySelectedApps) return true
        return pkg != null && pkg in prefs.selectedApps
    }

    /** The app in front, from usage events (needs usage access). Polled at most once a second. */
    private fun currentPackage(now: Long): String? {
        if (now - lastUsageQuery < APP_POLL_MS) return foregroundPackage
        lastUsageQuery = now
        val usage = getSystemService(UsageStatsManager::class.java) ?: return foregroundPackage
        val end = System.currentTimeMillis()
        val events = runCatching { usage.queryEvents(end - USAGE_LOOKBACK_MS, end) }.getOrNull() ?: return foregroundPackage
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val resumed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            } else {
                @Suppress("DEPRECATION")
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            }
            if (resumed) foregroundPackage = event.packageName
        }
        return foregroundPackage
    }

    // ---- Camera + ML Kit -----------------------------------------------------------------

    private fun startCamera(now: Long) {
        if (cameraProvider != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        cameraStartedAt = now
        analyzeIntervalMs = 0L
        nextAnalyzeAt = 0L
        val executor = analyzerExecutor ?: Executors.newSingleThreadExecutor().also { analyzerExecutor = it }
        val faceDetector = detector ?: FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(0.1f)
                .build(),
        ).also { detector = it }
        @Suppress("DEPRECATION")
        val useCase = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(executor) { image -> analyze(image, faceDetector) } }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            if (state == PrivacyState.IDLE) return@addListener
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, useCase)
            }.onSuccess {
                cameraProvider = provider
                analysis = useCase
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        analysis?.clearAnalyzer()
        cameraProvider?.unbindAll()
        cameraProvider = null
        analysis = null
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun analyze(image: ImageProxy, faceDetector: FaceDetector) {
        val now = SystemClock.elapsedRealtime()
        val media = image.image
        if (analyzing || now < nextAnalyzeAt || media == null) {
            image.close()
            return
        }
        analyzing = true
        faceDetector.process(InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees))
            .addOnSuccessListener { faces ->
                val largest = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                val reading = FaceReading(faces.size, largest?.headEulerAngleY ?: 0f)
                main.post { onReading(reading) }
            }
            .addOnCompleteListener {
                image.close()
                nextAnalyzeAt = SystemClock.elapsedRealtime() + analyzeIntervalMs
                analyzing = false
            }
    }

    // ---- Sensors -------------------------------------------------------------------------

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val now = SystemClock.elapsedRealtime()
                if (motion.onGravity(event.values[0], event.values[1], event.values[2], now)) {
                    moved = true
                    if (state == PrivacyState.SENSOR_WATCH) evaluate() // wake the camera at once
                }
            }
            Sensor.TYPE_LIGHT -> lux = event.values[0]
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ---- Notification, tile, status ------------------------------------------------------

    private fun startInForeground(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val camera = prefs.smartMode &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val type = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !camera -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && camera -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            else -> 0
        }
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification(), type)
            else startForeground(NOTIFICATION_ID, notification())
        }.isSuccess
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PrivacyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = getString(
            when (state) {
                PrivacyState.IDLE -> R.string.state_idle
                PrivacyState.SENSOR_WATCH -> R.string.state_watch
                PrivacyState.ACTIVE_CHECK -> R.string.state_check
                PrivacyState.SHIELD_ACTIVE -> R.string.state_shield
            },
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .build()
    }

    private var lastNotifiedState: PrivacyState? = null

    private fun publish(faces: Int = _status.value.faces, yaw: Float = _status.value.yaw, lowLight: Boolean = false) {
        _status.value = PrivacyStatus(true, state, faces, yaw, lux, lowLight)
        if (state != lastNotifiedState) {
            lastNotifiedState = state
            runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification()) }
        }
    }

    private fun requestTileUpdate() {
        runCatching { TileService.requestListeningState(this, ComponentName(this, PrivacyTileService::class.java)) }
    }

    companion object {
        const val ACTION_STOP = "io.github.yunusemreakdeniz.privacydisplay.STOP"
        const val ACTION_REFRESH = "io.github.yunusemreakdeniz.privacydisplay.REFRESH"
        private const val CHANNEL = "privacy"
        private const val NOTIFICATION_ID = 7
        private const val TICK_MS = 250L
        private const val APP_POLL_MS = 1_000L
        private const val USAGE_LOOKBACK_MS = 60_000L
        private const val LOW_LIGHT_LUX = 3f
        private const val MOVING_WINDOW_MS = 1_000L
        private const val STILL_INTERVAL_MS = 1_500L
        private const val STEADY_INTERVAL_MS = 300L
        private const val SETTLE_MS = 3_000L

        private val _status = MutableStateFlow(PrivacyStatus())
        val status: StateFlow<PrivacyStatus> = _status.asStateFlow()

        fun start(context: Context) {
            Prefs(context).enabled = true
            ContextCompat.startForegroundService(context, Intent(context, PrivacyService::class.java))
        }

        fun stop(context: Context) {
            Prefs(context).enabled = false
            context.stopService(Intent(context, PrivacyService::class.java))
        }

        /** Restarts the service, e.g. after smart mode changed (the foreground type depends on it). */
        fun restart(context: Context) {
            if (!status.value.running) return
            context.stopService(Intent(context, PrivacyService::class.java))
            ContextCompat.startForegroundService(context, Intent(context, PrivacyService::class.java))
        }

        fun refresh(context: Context) {
            if (status.value.running) {
                context.startService(Intent(context, PrivacyService::class.java).setAction(ACTION_REFRESH))
            }
        }
    }
}
