package moe.chensi.volume.compose

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.media.AudioManager
import android.view.MotionEvent
import android.widget.SeekBar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import moe.chensi.volume.BuildConfig
import moe.chensi.volume.DiagnosticSurface
import moe.chensi.volume.MediaSliderImplementation
import moe.chensi.volume.VolumeDiagnosticTrace
import moe.chensi.volume.ui.theme.Typography
import java.util.concurrent.atomic.AtomicInteger

private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"

@SuppressLint("StaticFieldLeak")
internal object VolumeChangeObserver {
    private val refCount = AtomicInteger(0)
    private var receiver: BroadcastReceiver? = null
    private var registeredContext: Context? = null
    private var _volumeChangedCount by mutableIntStateOf(0)
    val volumeChangedCount: Int get() = _volumeChangedCount

    @Synchronized
    fun startObserving(context: Context) {
        if (refCount.incrementAndGet() == 1) {
            registeredContext = context.applicationContext
            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    _volumeChangedCount++
                }
            }
            registeredContext!!.registerReceiver(
                receiver!!,
                IntentFilter(VOLUME_CHANGED_ACTION),
                Context.RECEIVER_NOT_EXPORTED
            )
        }
    }

    @Synchronized
    fun stopObserving() {
        if (refCount.decrementAndGet() == 0) {
            receiver?.let {
                registeredContext!!.unregisterReceiver(it)
                receiver = null
            }
            registeredContext = null
        }
    }

    fun notifyVolumeChanged() {
        _volumeChangedCount++
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamVolumeSlider(
    streamType: Int,
    icon: ImageVector,
    name: String,
    audioManager: AudioManager,
    footer: (@Composable () -> Unit)? = null,
    onChange: (() -> Unit)? = null,
    diagnosticSurface: DiagnosticSurface? = null,
    mediaImplementation: MediaSliderImplementation = MediaSliderImplementation.CUSTOM
) {
    val context = LocalContext.current
    var volume by remember { mutableIntStateOf(audioManager.getStreamVolume(streamType)) }
    var maxVolume by remember { mutableFloatStateOf(0f) }

    DisposableEffect(context) {
        VolumeChangeObserver.startObserving(context)
        onDispose {
            VolumeChangeObserver.stopObserving()
        }
    }

    LaunchedEffect(streamType) {
        maxVolume = audioManager.getStreamMaxVolume(streamType).toFloat()
    }

    val volumeChangedCount = VolumeChangeObserver.volumeChangedCount

    LaunchedEffect(volumeChangedCount) {
        volume = audioManager.getStreamVolume(streamType)
    }

    val diagnosticMedia = BuildConfig.VOLUME_DIAGNOSTICS &&
        streamType == AudioManager.STREAM_MUSIC && diagnosticSurface != null
    val activeImplementation = if (diagnosticMedia && diagnosticSurface == DiagnosticSurface.OVERLAY) {
        mediaImplementation
    } else {
        MediaSliderImplementation.CUSTOM
    }

    fun applyTarget(value: Float) {
        val target = value.toInt()
        if (diagnosticMedia) {
            VolumeDiagnosticTrace.record(
                context,
                diagnosticSurface!!,
                activeImplementation,
                "callback_target",
                "stream=STREAM_MUSIC target=$target current=$volume range=0..${maxVolume.toInt()}"
            )
        }
        if (volume == target) {
            if (diagnosticMedia) {
                VolumeDiagnosticTrace.record(
                    context,
                    diagnosticSurface!!,
                    activeImplementation,
                    "callback_early_return",
                    "stream=STREAM_MUSIC target=$target current=$volume"
                )
            }
            return
        }

        volume = target
        val setterId = if (diagnosticMedia) VolumeDiagnosticTrace.nextSetterId() else 0L
        if (diagnosticMedia) {
            VolumeDiagnosticTrace.record(
                context,
                diagnosticSurface!!,
                activeImplementation,
                "setter_begin",
                "setter_id=$setterId stream=STREAM_MUSIC target=$target"
            )
        }
        try {
            audioManager.setStreamVolume(streamType, target, 0)
        } catch (exception: Exception) {
            if (diagnosticMedia) {
                VolumeDiagnosticTrace.record(
                    context,
                    diagnosticSurface!!,
                    activeImplementation,
                    "setter_exception",
                    "setter_id=$setterId stream=STREAM_MUSIC target=$target " +
                        "type=${exception.javaClass.simpleName}"
                )
                VolumeDiagnosticTrace.recordImmediateReadback(
                    context,
                    audioManager,
                    diagnosticSurface!!,
                    activeImplementation,
                    streamType,
                    target,
                    setterId
                )
                VolumeDiagnosticTrace.scheduleReadbackAfterSetter(
                    context,
                    audioManager,
                    diagnosticSurface!!,
                    activeImplementation,
                    streamType,
                    target,
                    setterId
                )
            }
            throw exception
        }
        if (diagnosticMedia) {
            VolumeDiagnosticTrace.record(
                context,
                diagnosticSurface!!,
                activeImplementation,
                "setter_return",
                "setter_id=$setterId stream=STREAM_MUSIC target=$target"
            )
            VolumeDiagnosticTrace.recordImmediateReadback(
                context,
                audioManager,
                diagnosticSurface!!,
                activeImplementation,
                streamType,
                target,
                setterId
            )
            VolumeDiagnosticTrace.scheduleReadbackAfterSetter(
                context,
                audioManager,
                diagnosticSurface!!,
                activeImplementation,
                streamType,
                target,
                setterId
            )
        }
        onChange?.invoke()
    }

    val latestApplyTarget by rememberUpdatedState<(Float) -> Unit> { value -> applyTarget(value) }

    fun recordSeekBarDrag(phase: String, x: Float, y: Float, seekBar: SeekBar) {
        val location = IntArray(2)
        seekBar.getLocationOnScreen(location)
        VolumeDiagnosticTrace.record(
            context,
            diagnosticSurface!!,
            activeImplementation,
            "drag_$phase",
            "x=$x y=$y width=${seekBar.width} range=0..${seekBar.max} " +
                "bounds=${location[0]},${location[1]},${location[0] + seekBar.width},${location[1] + seekBar.height}"
        )
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (diagnosticMedia && activeImplementation == MediaSliderImplementation.SEEKBAR) {
            val trackColor = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer
            val progressColor = androidx.compose.material3.MaterialTheme.colorScheme.primary
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(trackColor)
            ) {
                AndroidView(
                    modifier = Modifier.matchParentSize(),
                    factory = { viewContext ->
                        var lastTouchX = 0f
                        var lastTouchY = 0f
                        var lastTouchAction = MotionEvent.ACTION_DOWN
                        SeekBar(viewContext).apply {
                            progressTintList = ColorStateList.valueOf(progressColor.toArgb())
                            progressBackgroundTintList = ColorStateList.valueOf(trackColor.toArgb())
                            thumbTintList = ColorStateList.valueOf(progressColor.toArgb())
                            max = maxVolume.toInt()
                            progress = volume.coerceIn(0, max)
                            setOnTouchListener { _, event ->
                                lastTouchX = event.x
                                lastTouchY = event.y
                                lastTouchAction = event.actionMasked
                                false
                            }
                            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                                override fun onProgressChanged(
                                    seekBar: SeekBar,
                                    progress: Int,
                                    fromUser: Boolean
                                ) {
                                    if (fromUser) {
                                        latestApplyTarget(progress.toFloat())
                                    }
                                }

                                override fun onStartTrackingTouch(seekBar: SeekBar) {
                                    recordSeekBarDrag("start", lastTouchX, lastTouchY, seekBar)
                                }

                                override fun onStopTrackingTouch(seekBar: SeekBar) {
                                    val phase = if (lastTouchAction == MotionEvent.ACTION_CANCEL) {
                                        "cancel"
                                    } else {
                                        "end"
                                    }
                                    recordSeekBarDrag(phase, lastTouchX, lastTouchY, seekBar)
                                }
                            })
                        }
                    },
                    update = { seekBar ->
                        seekBar.max = maxVolume.toInt()
                        val target = volume.coerceIn(0, seekBar.max)
                        if (seekBar.progress != target) {
                            seekBar.progress = target
                        }
                        seekBar.progressTintList = ColorStateList.valueOf(progressColor.toArgb())
                        seekBar.progressBackgroundTintList = ColorStateList.valueOf(trackColor.toArgb())
                        seekBar.thumbTintList = ColorStateList.valueOf(progressColor.toArgb())
                    }
                )
                CompositionLocalProvider(
                    LocalContentColor provides androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(16.dp, 8.dp)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = name,
                            modifier = Modifier.size(32.dp),
                        )
                        StreamSliderTextContent(name = name, valueText = "$volume/${maxVolume.toInt()}")
                    }
                }
            }
        } else {
            TrackSlider(
                modifier = Modifier.weight(1f),
                cornerRadius = 20.dp,
                value = volume.toFloat(),
                valueRange = 0f..maxVolume,
                onValueChange = { value ->
                    if (!diagnosticMedia) {
                        val target = value.toInt()
                        if (volume == target) {
                            return@TrackSlider
                        }

                        volume = target
                        audioManager.setStreamVolume(streamType, target, 0)
                        onChange?.invoke()
                    } else {
                        applyTarget(value)
                    }
                },
                onDiagnosticDragEvent = if (diagnosticMedia) {
                    { phase, x, y, width, rangeStart, rangeEnd ->
                        VolumeDiagnosticTrace.record(
                            context,
                            diagnosticSurface!!,
                            activeImplementation,
                            "drag_$phase",
                            "x=$x y=$y width=$width range=$rangeStart..$rangeEnd"
                        )
                    }
                } else {
                    null
                }
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(16.dp, 8.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = name,
                        modifier = Modifier.size(32.dp),
                    )
                    StreamSliderTextContent(name = name, valueText = "$volume/${maxVolume.toInt()}")
                }
            }
        }

        footer?.invoke()
    }
}

@Composable
internal fun RowScope.StreamSliderTextContent(name: String, valueText: String) {
    Text(
        text = name,
        modifier = Modifier.weight(1f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )

    Text(
        text = valueText,
        style = Typography.bodySmall,
        maxLines = 1,
    )
}
