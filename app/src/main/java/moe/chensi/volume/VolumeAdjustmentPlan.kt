package moe.chensi.volume

import android.media.AudioManager

internal data class VolumeAdjustmentPlan(val direction: Int, val steps: Int)

internal fun volumeAdjustmentPlan(current: Int, target: Int): VolumeAdjustmentPlan = when {
    target > current -> VolumeAdjustmentPlan(AudioManager.ADJUST_RAISE, target - current)
    target < current -> VolumeAdjustmentPlan(AudioManager.ADJUST_LOWER, current - target)
    else -> VolumeAdjustmentPlan(AudioManager.ADJUST_SAME, 0)
}

/** Writes Media volume with one relative AudioService adjustment per index step. */
internal fun writeStreamVolumeTarget(
    audioManager: AudioManager,
    streamType: Int,
    target: Int,
    adjustBySteps: Boolean
) {
    if (!adjustBySteps || streamType != AudioManager.STREAM_MUSIC) {
        audioManager.setStreamVolume(streamType, target, 0)
        return
    }

    val current = audioManager.getStreamVolume(streamType)
    val plan = volumeAdjustmentPlan(current, target)
    repeat(plan.steps) {
        audioManager.adjustStreamVolume(streamType, plan.direction, 0)
    }
}
