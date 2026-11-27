package moe.chensi.volume

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeAdjustmentPlanTest {
    @Test
    fun plansOneRaisePerVolumeStep() {
        assertEquals(
            VolumeAdjustmentPlan(AudioManager.ADJUST_RAISE, 3),
            volumeAdjustmentPlan(current = 5, target = 8)
        )
    }

    @Test
    fun plansOneLowerPerVolumeStep() {
        assertEquals(
            VolumeAdjustmentPlan(AudioManager.ADJUST_LOWER, 4),
            volumeAdjustmentPlan(current = 9, target = 5)
        )
    }

    @Test
    fun plansNoCallWhenAlreadyAtTarget() {
        assertEquals(
            VolumeAdjustmentPlan(AudioManager.ADJUST_SAME, 0),
            volumeAdjustmentPlan(current = 5, target = 5)
        )
    }
}