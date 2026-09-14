package dev.pranav.applock.core.intruder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import dev.pranav.applock.data.repository.IntruderCaptureMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Takes a front-camera photo or a short video for an intruder alert, with nothing shown on screen
 * but Android's camera dot.
 *
 * Android only lets an app use the camera while it counts as in use. The accessibility service is
 * bound with the system's capabilities while the screen is on, which covers the lock overlay; the
 * other backends' lock screen is an activity in the foreground. The camera is bound to a lifecycle
 * of its own for just the capture, so the lock screen closing doesn't cut it short.
 *
 * Nothing here throws: whatever goes wrong comes back as a reason for the email.
 */
object IntruderCapture {

    sealed interface Result {
        /** [note] says what was missing from a capture that still worked, such as the sound. */
        data class Captured(val file: File, val note: String? = null) : Result
        data class Failed(val reason: String) : Result
    }

    private const val TIMEOUT_MS = 20_000L
    private const val VIDEO_DURATION_MS = 5_000L

    // Without a preview nothing has run the camera yet, so give auto-exposure a moment to settle.
    private const val EXPOSURE_SETTLE_MS = 600L
    private const val JPEG_QUALITY = 85
    private val PHOTO_SIZE = Size(1280, 960)

    /** Finalize errors that still leave a playable file, holding what was recorded until then. */
    private val USABLE_VIDEO_ERRORS = setOf(
        VideoRecordEvent.Finalize.ERROR_NONE,
        VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
        VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
        VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE
    )

    fun hasCameraPermission(context: Context): Boolean =
        isGranted(context, Manifest.permission.CAMERA)

    fun hasMicrophonePermission(context: Context): Boolean =
        isGranted(context, Manifest.permission.RECORD_AUDIO)

    fun extensionFor(mode: IntruderCaptureMode): String = when (mode) {
        IntruderCaptureMode.PHOTO -> IntruderOutbox.PHOTO_EXTENSION
        IntruderCaptureMode.VIDEO -> IntruderOutbox.VIDEO_EXTENSION
    }

    /** Writes a photo or video to [file], which is deleted again if nothing usable was captured. */
    suspend fun capture(context: Context, mode: IntruderCaptureMode, file: File): Result {
        if (!hasCameraPermission(context)) return Result.Failed("camera permission not granted")
        val app = context.applicationContext

        return try {
            withTimeout(TIMEOUT_MS) {
                // CameraX binds and the lifecycle moves only on the main thread; nothing here blocks it.
                withContext(Dispatchers.Main) {
                    val provider = cameraProvider(app)
                    val owner = CaptureLifecycle()
                    owner.start()
                    try {
                        when (mode) {
                            IntruderCaptureMode.PHOTO -> takePhoto(app, provider, owner, file)
                            IntruderCaptureMode.VIDEO -> recordVideo(app, provider, owner, file)
                        }
                    } finally {
                        owner.destroy()
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            file.delete()
            Result.Failed("the camera didn't respond in time")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            file.delete()
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun takePhoto(
        context: Context,
        provider: ProcessCameraProvider,
        owner: LifecycleOwner,
        file: File
    ): Result {
        val imageCapture = ImageCapture.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            PHOTO_SIZE,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()
            )
            .setJpegQuality(JPEG_QUALITY)
            .build()

        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, imageCapture)
        try {
            delay(EXPOSURE_SETTLE_MS)
            suspendCancellableCoroutine<Unit> { continuation ->
                imageCapture.takePicture(
                    ImageCapture.OutputFileOptions.Builder(file).build(),
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            continuation.resume(Unit)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            continuation.resumeWithException(exception)
                        }
                    }
                )
            }
            return Result.Captured(file)
        } finally {
            provider.unbind(imageCapture)
        }
    }

    @SuppressLint("MissingPermission") // Sound is only asked for once RECORD_AUDIO is granted.
    private suspend fun recordVideo(
        context: Context,
        provider: ProcessCameraProvider,
        owner: LifecycleOwner,
        file: File
    ): Result {
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(Quality.SD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
            )
            .build()
        val videoCapture = VideoCapture.withOutput(recorder)
        val withSound = hasMicrophonePermission(context)

        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, videoCapture)
        var recording: Recording? = null
        try {
            val finalize = suspendCancellableCoroutine<VideoRecordEvent.Finalize> { continuation ->
                val pending = recorder.prepareRecording(
                    context,
                    FileOutputOptions.Builder(file).setDurationLimitMillis(VIDEO_DURATION_MS).build()
                )
                recording = (if (withSound) pending.withAudioEnabled() else pending)
                    .start(ContextCompat.getMainExecutor(context)) { event ->
                        if (event is VideoRecordEvent.Finalize && continuation.isActive) {
                            continuation.resume(event)
                        }
                    }
            }

            if (finalize.error !in USABLE_VIDEO_ERRORS || file.length() == 0L) {
                file.delete()
                val cause = finalize.cause?.message?.let { ": $it" }.orEmpty()
                return Result.Failed("recording failed with error ${finalize.error}$cause")
            }

            val note = when {
                finalize.error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE ->
                    "the recording stopped early, when the camera was closed"

                !withSound -> "no sound: microphone permission not granted"
                else -> null
            }
            return Result.Captured(file, note)
        } finally {
            // Stops a recording the timeout interrupted; does nothing once it has finished.
            recording?.close()
            provider.unbind(videoCapture)
        }
    }

    private suspend fun cameraProvider(context: Context): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    try {
                        continuation.resume(future.get())
                    } catch (e: Exception) {
                        continuation.resumeWithException(e.cause ?: e)
                    }
                },
                ContextCompat.getMainExecutor(context)
            )
        }

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Resumed only while one capture runs. Main thread only, like any LifecycleRegistry. */
    private class CaptureLifecycle : LifecycleOwner {
        private val registry = LifecycleRegistry(this)

        override val lifecycle: Lifecycle
            get() = registry

        fun start() {
            registry.currentState = Lifecycle.State.RESUMED
        }

        /** Destroying the lifecycle also unbinds anything CameraX still has bound to it. */
        fun destroy() {
            if (registry.currentState != Lifecycle.State.INITIALIZED) {
                registry.currentState = Lifecycle.State.DESTROYED
            }
        }
    }
}
