package moe.chensi.volume

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.system.Os
import android.view.MotionEvent
import android.view.View
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class DiagnosticSurface(val tag: String) {
    ACTIVITY("activity"),
    OVERLAY("overlay")
}

enum class MediaSliderImplementation(val tag: String) {
    CUSTOM("custom"),
    SEEKBAR("seekbar");

    fun other(): MediaSliderImplementation =
        if (this == CUSTOM) SEEKBAR else CUSTOM

    companion object {
        fun fromPreference(value: String?): MediaSliderImplementation =
            entries.firstOrNull { it.name == value } ?: CUSTOM
    }
}

internal object VolumeDiagnosticPreferences {
    private const val PREFERENCES_NAME = "volume_diagnostics"
    private const val OVERLAY_IMPLEMENTATION_KEY = "overlay_media_implementation"

    private fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun overlayImplementation(context: Context): MediaSliderImplementation {
        if (!BuildConfig.VOLUME_DIAGNOSTICS) {
            return MediaSliderImplementation.CUSTOM
        }
        return MediaSliderImplementation.fromPreference(
            preferences(context).getString(OVERLAY_IMPLEMENTATION_KEY, null)
        )
    }

    fun setOverlayImplementation(context: Context, implementation: MediaSliderImplementation) {
        if (BuildConfig.VOLUME_DIAGNOSTICS) {
            preferences(context).edit().putString(OVERLAY_IMPLEMENTATION_KEY, implementation.name).apply()
        }
    }

    fun observeOverlayImplementation(context: Context, onChanged: () -> Unit): () -> Unit {
        val sharedPreferences = preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == OVERLAY_IMPLEMENTATION_KEY) {
                onChanged()
            }
        }
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
        return { sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}

internal object VolumeDiagnosticTrace {
    private const val TRACE_FILE_NAME = "volume-diagnostics.log"
    private const val MAX_TRACE_CHARACTERS = 24 * 1024
    private const val READBACK_DELAY_MS = 350L
    private const val WRITE_COALESCE_MS = 100L

    private val sessionId = UUID.randomUUID().toString().substringBefore('-')
    private val buffer = BoundedTraceBuffer(maxCharacters = MAX_TRACE_CHARACTERS)
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "volume-diagnostic-trace").apply { isDaemon = true }
    }
    private val writeScheduled = AtomicBoolean(false)
    private val setterSequence = AtomicLong(0L)
    private val fileLock = Any()
    private val loadLock = Any()
    private val readbackHandler = Handler(Looper.getMainLooper())
    private val readbackLock = Any()

    @Volatile
    private var loadAttempted = false
    @Volatile
    private var loadedSuccessfully = false
    private var clearGeneration = 0L
    private var pendingReadback: Runnable? = null

    fun nextSetterId(): Long = setterSequence.incrementAndGet()

    /** Load the small private trace before the first user touch is routed. */
    fun initialize(context: Context) {
        if (BuildConfig.VOLUME_DIAGNOSTICS) {
            ensureLoaded(context.applicationContext, retryOnFailure = true)
        }
    }

    fun record(
        context: Context,
        surface: DiagnosticSurface,
        implementation: MediaSliderImplementation,
        event: String,
        details: String
    ) {
        if (!BuildConfig.VOLUME_DIAGNOSTICS) {
            return
        }

        val applicationContext = context.applicationContext
        try {
            val canPersist = ensureLoaded(applicationContext)
            buffer.append(
                DiagnosticTraceFormatter.format(
                    timestampMs = SystemClock.elapsedRealtime(),
                    surface = surface.tag,
                    implementation = implementation.tag,
                    event = event,
                    details = "session=$sessionId $details"
                )
            )
            if (canPersist) {
                schedulePersist(applicationContext)
            }
        } catch (_: Exception) {
            // Diagnostics must not affect the volume controls.
        }
    }

    fun recordRootTouch(
        surface: DiagnosticSurface,
        implementation: MediaSliderImplementation,
        view: View,
        event: MotionEvent
    ) {
        if (!BuildConfig.VOLUME_DIAGNOSTICS) {
            return
        }

        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> "down"
            MotionEvent.ACTION_UP -> "up"
            MotionEvent.ACTION_CANCEL -> "cancel"
            MotionEvent.ACTION_OUTSIDE -> "outside"
            else -> return
        }
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val right = location[0] + view.width
        val bottom = location[1] + view.height
        record(
            context = view.context,
            surface = surface,
            implementation = implementation,
            event = "root_$action",
            details = "x=${formatFloat(event.x)} y=${formatFloat(event.y)} " +
                "bounds=${location[0]},${location[1]},$right,$bottom"
        )
    }

    fun recordImmediateReadback(
        context: Context,
        audioManager: AudioManager,
        surface: DiagnosticSurface,
        implementation: MediaSliderImplementation,
        streamType: Int,
        target: Int,
        setterId: Long
    ) {
        if (!BuildConfig.VOLUME_DIAGNOSTICS) {
            return
        }
        record(
            context,
            surface,
            implementation,
            "readback_after_setter",
            "setter_id=$setterId target=$target stream=STREAM_MUSIC " +
                "volume=${readVolume(audioManager, streamType)}"
        )
    }

    /** Debounce readback after every setter, not only drag callbacks. */
    fun scheduleReadbackAfterSetter(
        context: Context,
        audioManager: AudioManager,
        surface: DiagnosticSurface,
        implementation: MediaSliderImplementation,
        streamType: Int,
        target: Int,
        setterId: Long
    ) {
        if (!BuildConfig.VOLUME_DIAGNOSTICS) {
            return
        }

        val applicationContext = context.applicationContext
        synchronized(readbackLock) {
            pendingReadback?.let(readbackHandler::removeCallbacks)
            val capturedGeneration = clearGeneration
            val task = object : Runnable {
                override fun run() {
                    val shouldRecord = synchronized(readbackLock) {
                        if (capturedGeneration != clearGeneration || pendingReadback !== this) {
                            false
                        } else {
                            pendingReadback = null
                            true
                        }
                    }
                    if (shouldRecord) {
                        record(
                            applicationContext,
                            surface,
                            implementation,
                            "readback_delayed",
                            "setter_id=$setterId target=$target stream=STREAM_MUSIC " +
                                "delay_ms=$READBACK_DELAY_MS volume=${readVolume(audioManager, streamType)}"
                        )
                    }
                }
            }
            pendingReadback = task
            readbackHandler.postDelayed(task, READBACK_DELAY_MS)
        }
    }

    fun export(context: Context): String {
        if (!BuildConfig.VOLUME_DIAGNOSTICS) {
            return ""
        }
        val applicationContext = context.applicationContext
        val canPersist = ensureLoaded(applicationContext, retryOnFailure = true)
        val current = buffer.snapshot()
        if (canPersist) {
            schedulePersist(applicationContext)
        }
        return current
    }

    /** Returns true only after the private trace file has been cleared successfully. */
    fun clear(context: Context): Boolean {
        if (!BuildConfig.VOLUME_DIAGNOSTICS) {
            return false
        }
        val applicationContext = context.applicationContext
        if (!ensureLoaded(applicationContext, retryOnFailure = true)) {
            return false
        }

        synchronized(readbackLock) {
            clearGeneration++
            pendingReadback?.let(readbackHandler::removeCallbacks)
            pendingReadback = null
        }

        return synchronized(fileLock) {
            val previous = buffer.snapshot()
            buffer.clear()
            try {
                atomicWrite(applicationContext, "")
                loadedSuccessfully = true
                true
            } catch (_: Exception) {
                previous.lineSequence()
                    .filter(String::isNotEmpty)
                    .forEach(buffer::append)
                false
            }
        }
    }

    private fun ensureLoaded(context: Context, retryOnFailure: Boolean = false): Boolean {
        if (loadedSuccessfully) {
            return true
        }
        if (loadAttempted && !retryOnFailure) {
            return false
        }
        return synchronized(loadLock) {
            if (loadedSuccessfully) {
                true
            } else if (loadAttempted && !retryOnFailure) {
                false
            } else {
                loadAttempted = true
                try {
                    val file = File(context.noBackupFilesDir, TRACE_FILE_NAME)
                    if (file.isFile) {
                        file.readText(Charsets.UTF_8)
                            .takeLast(MAX_TRACE_CHARACTERS)
                            .lineSequence()
                            .filter(String::isNotEmpty)
                            .forEach(buffer::append)
                    }
                    loadedSuccessfully = true
                } catch (_: Exception) {
                    // Keep the existing file untouched if its contents cannot be read.
                    loadedSuccessfully = false
                }
                loadedSuccessfully
            }
        }
    }

    /** Coalesces bursts: at most one writer task is queued or running. */
    private fun schedulePersist(context: Context) {
        if (!writeScheduled.compareAndSet(false, true)) {
            return
        }
        try {
            writer.execute {
                var writtenRevision: Long? = null
                try {
                    Thread.sleep(WRITE_COALESCE_MS)
                    writtenRevision = synchronized(fileLock) {
                        val snapshot = buffer.snapshotWithRevision()
                        atomicWrite(context, snapshot.text)
                        snapshot.revision
                    }
                } catch (_: Exception) {
                    // Diagnostics must not affect the volume controls.
                } finally {
                    writeScheduled.set(false)
                    val needsRetry = writtenRevision?.let {
                        buffer.snapshotWithRevision().revision != it
                    } == true
                    if (needsRetry) {
                        schedulePersist(context)
                    }
                }
            }
        } catch (_: Exception) {
            writeScheduled.set(false)
        }
    }

    private fun atomicWrite(context: Context, contents: String) {
        val directory = context.noBackupFilesDir
        val target = File(directory, TRACE_FILE_NAME)
        val temporary = File(directory, "$TRACE_FILE_NAME.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(contents.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Os.rename(temporary.absolutePath, target.absolutePath)
        } finally {
            if (temporary.exists()) {
                temporary.delete()
            }
        }
    }

    private fun readVolume(audioManager: AudioManager, streamType: Int): String =
        try {
            audioManager.getStreamVolume(streamType).toString()
        } catch (exception: Exception) {
            "error:${exception.javaClass.simpleName}"
        }

    private fun formatFloat(value: Float): String =
        String.format(Locale.US, "%.1f", value)
}
