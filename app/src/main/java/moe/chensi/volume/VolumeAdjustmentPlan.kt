package moe.chensi.volume

import android.media.AudioManager

internal data class VolumeAdjustmentPlan(val direction: Int, val steps: Int)

internal fun volumeAdjustmentPlan(current: Int, target: Int): VolumeAdjustmentPlan = when {
    target > current -> VolumeAdjustmentPlan(AudioManager.ADJUST_RAISE, target - current)
    target < current -> VolumeAdjustmentPlan(AudioManager.ADJUST_LOWER, current - target)
    else -> VolumeAdjustmentPlan(AudioManager.ADJUST_SAME, 0)
}
