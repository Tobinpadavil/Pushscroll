package com.teserakt.pushscroll

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Keeps the front camera running in the background (foreground service with a notification)
 * and counts pushups from your head / upper-body movement.
 */
class PushupService : LifecycleService() {

    companion object {
        const val ACTION_STOP = "com.teserakt.pushscroll.STOP"
        private const val CHANNEL = "pushscroll"
        private const val NOTIF_ID = 7
    }

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var faceDetector: FaceDetector? = null
    private var poseDetector: PoseDetector? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val counter = RepCounter()
    private var lastSeen = 0L
    private var frames = 0
    private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            if (startInForeground()) startCamera() else stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Pushup tracking", NotificationManager.IMPORTANCE_LOW)
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PushupService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("PushScroll is watching your pushups")
            .setContentText("Each pushup scrolls one YouTube Short")
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, "Stop", stop)
            .build()
        return try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
            ServiceCompat.startForeground(this, NOTIF_ID, n, type)
            true
        } catch (e: Exception) {
            PushupBus.status = "Couldn't start camera: ${e.message}"
            PushupBus.emit(PushupBus.EV_STATE)
            false
        }
    }

    private fun startCamera() {
        PushupBus.running = true
        PushupBus.status = "Starting camera…"
        PushupBus.emit(PushupBus.EV_STATE)
        counter.sensitivity = Prefs(this).sensitivity

        faceDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.1f)
                .build()
        )
        poseDetector = PoseDetection.getClient(
            PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build()
        )

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                @Suppress("DEPRECATION")
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(480, 640))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                PushupBus.status = "Looking for you…"
                PushupBus.emit(PushupBus.EV_STATE)
            } catch (e: Exception) {
                PushupBus.status = "Camera error: ${e.message}"
                PushupBus.emit(PushupBus.EV_STATE)
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        val fd = faceDetector
        if (media == null || fd == null) {
            proxy.close()
            return
        }
        val rot = proxy.imageInfo.rotationDegrees
        val w = (if (rot == 90 || rot == 270) proxy.height else proxy.width).toFloat()
        val h = (if (rot == 90 || rot == 270) proxy.width else proxy.height).toFloat()
        val input = InputImage.fromMediaImage(media, rot)

        fd.process(input)
            .addOnSuccessListener { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() }
                if (face != null) {
                    val b = face.boundingBox
                    proxy.close()
                    onSample(b.exactCenterY() / h, b.width() / w, "head")
                } else {
                    runPose(input, proxy, h)
                }
            }
            .addOnFailureListener { proxy.close() }
    }

    /** Fallback when the face isn't found: use body landmarks (nose, eyes, shoulders). */
    private fun runPose(input: InputImage, proxy: ImageProxy, h: Float) {
        val pd = poseDetector
        if (pd == null) {
            proxy.close()
            return
        }
        pd.process(input)
            .addOnSuccessListener { pose ->
                val head = listOf(PoseLandmark.NOSE, PoseLandmark.LEFT_EYE, PoseLandmark.RIGHT_EYE)
                    .mapNotNull { pose.getPoseLandmark(it) }
                    .filter { it.inFrameLikelihood > 0.5f }
                val shoulders = listOf(PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER)
                    .mapNotNull { pose.getPoseLandmark(it) }
                    .filter { it.inFrameLikelihood > 0.5f }
                val pts = if (head.isNotEmpty()) head else shoulders
                if (pts.isNotEmpty()) {
                    val y = pts.map { it.position.y }.average().toFloat() / h
                    onSample(y, null, "body")
                } else {
                    onSample(null, null, "")
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    /** Runs on the main thread (ML Kit callbacks). */
    private fun onSample(y: Float?, s: Float?, source: String) {
        if (!PushupBus.running) return
        val now = SystemClock.elapsedRealtime()
        if (++frames % 30 == 0) counter.sensitivity = Prefs(this).sensitivity

        if (y == null) {
            if (now - lastSeen > 1500) {
                counter.reset()
                PushupBus.tracking = false
                PushupBus.level = 0f
                PushupBus.status = "Can't see you — face the front camera"
                PushupBus.emit(PushupBus.EV_STATE)
            }
            return
        }
        lastSeen = now
        PushupBus.tracking = true
        PushupBus.status = if (source == "head") "Tracking your head ✓" else "Tracking your body ✓"
        if (counter.update(now, y, s)) PushupBus.rep(this)
        PushupBus.level = counter.level
        PushupBus.emit(PushupBus.EV_STATE)
    }

    override fun onDestroy() {
        PushupBus.running = false
        PushupBus.tracking = false
        PushupBus.level = 0f
        PushupBus.status = "Stopped"
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        faceDetector?.close()
        poseDetector?.close()
        analysisExecutor.shutdown()
        PushupBus.emit(PushupBus.EV_STATE)
        super.onDestroy()
    }
}
