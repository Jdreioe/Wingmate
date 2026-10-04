package io.github.jdreioe.wingmate.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

enum class AccessHaptic { CONFIRM, REJECT, TICK }

/** Haptic feedback for access interactions; no-op if the view rejects it. */
fun View.performAccessHaptic(haptic: AccessHaptic) {
    val constant = when (haptic) {
        AccessHaptic.CONFIRM ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.VIRTUAL_KEY
            }
        AccessHaptic.REJECT ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.REJECT
            } else {
                HapticFeedbackConstants.LONG_PRESS
            }
        AccessHaptic.TICK -> HapticFeedbackConstants.CLOCK_TICK
    }
    performHapticFeedback(constant)
}
