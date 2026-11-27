package moe.chensi.volume

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

internal enum class DiagnosticSurface(val tag: String) {
    ACTIVITY("activity"),
    OVERLAY("overlay")
}

internal enum class MediaSliderImplementation(val tag: String) {
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

    private val buffer = BoundedTraceBuffer(maxCharacters = MAX_TRACE_CHARACTERS)
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "volume-diagnostic-trace").apply { isDaemon = true }
    }

    @Volatile
    private var clearedSinceStart = false

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

        try {
            val snapshot = buffer.append(
                DiagnosticTraceFormatter.format(
                    timestampMs = SystemClock.elapsedRealtime(),
                    surface = surface.tag,
                    implementation = implementation.tag,
                    event = event,
                    details = details
                )
            )
            clearedSinceStart = false
            val applicationContext = context.applicationContext
            writer.execute { writeSnapshot(applicationContext, snapshot) }
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
        streamType: Int
    ) {
        if (!BuildConfig.VOLUME_DIAGNOSTICS) {
            return
        }
        record(
            context,
            surface,
            implementation,
            "readback_after_setter",
            "stream=STREAM_MUSIC volume=${readVolume(audioManager, streamType)}"
        )
    }

    fun scheduleReadbackAfterGesture(
        context: Context,
        audioManager: AudioManager,
        surface: DiagnosticSurface,
        implementation: MediaSliderImplementation,
        streamType: Int,
        gesture: String
    ) {
        if (!BuildConfig.VOLUME_DIAGNOSTICS) {
            return
        }
        val applicationContext = context.applicationContext
        Handler(Looper.getMainLooper()).postDelayed({
            record(
                applicationContext,
                surface,
                implementation,
                "readback_after_gesture",
                "stream=STREAM_MUSIC delay_ms=$READBACK_DELAY_MS " +
                    "gesture=$gesture volume=${readVolume(audioManager, streamType)}"
            )
        }, READBACK_DELAY_MS)
    }

    fun export(context: Context): String {
        if (!BuildConfig.VOLUME_DIAGNOSTICS) {
            return ""
        }
        val current = buffer.snapshot()
        if (current.isNotEmpty() || clearedSinceStart) {
            return current
        }
        return try {
            File(context.applicationContext.noBackupFilesDir, TRACE_FILE_NAME)
                .takeIf { it.isFile }
                ?.readText(Charsets.UTF_8)
                ?.takeLast(MAX_TRACE_CHARACTERS)
                .orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    fun clear(context: Context) {
        if (!BuildConfig.VOLUME_DIAGNOSTICS) {
            return
        }
        buffer.clear()
        clearedSinceStart = true
        val applicationContext = context.applicationContext
        writer.execute {
            try {
                File(applicationContext.noBackupFilesDir, TRACE_FILE_NAME).delete()
            } catch (_: Exception) {
                // Diagnostics must not affect the volume controls.
            }
        }
    }

    private fun writeSnapshot(context: Context, snapshot: String) {
        try {
            File(context.noBackupFilesDir, TRACE_FILE_NAME).writeText(
                snapshot.take(MAX_TRACE_CHARACTERS),
                Charsets.UTF_8
            )
        } catch (_: Exception) {
            // Diagnostics must not affect the volume controls.
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
