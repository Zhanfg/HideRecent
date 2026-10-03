package top.gtian.hiderecent

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Launcher haptic vocabulary.
 *
 * Telegram-inspired profiles are re-expressed as data, while OEM_CLEAR_ALL keeps
 * the ColorOS/OPlus motor path available for launcher-process hooks.
 *
 * Reference:
 * DrKLO/Telegram - BotWebViewVibrationEffect.java (Apache-2.0)
 */
enum class LauncherHapticProfile(
    val prefValue: String,
    val title: String,
    val timings: LongArray?,
    val amplitudes: IntArray?,
    val fallbackTimings: LongArray?
) {
    NONE(
        "none",
        "None",
        null,
        null,
        null
    ),
    OEM_CLEAR_ALL(
        "oem_clear_all",
        "ColorOS Clear All",
        null,
        null,
        null
    ),
    IMPACT_LIGHT(
        "impact_light",
        "Impact Light",
        longArrayOf(7),
        intArrayOf(65),
        longArrayOf(60)
    ),
    IMPACT_MEDIUM(
        "impact_medium",
        "Impact Medium",
        longArrayOf(7),
        intArrayOf(145),
        longArrayOf(70)
    ),
    IMPACT_HEAVY(
        "impact_heavy",
        "Impact Heavy",
        longArrayOf(7),
        intArrayOf(255),
        longArrayOf(80)
    ),
    IMPACT_RIGID(
        "impact_rigid",
        "Impact Rigid",
        longArrayOf(3),
        intArrayOf(225),
        longArrayOf(50)
    ),
    IMPACT_SOFT(
        "impact_soft",
        "Impact Soft",
        longArrayOf(10),
        intArrayOf(175),
        longArrayOf(55)
    ),
    NOTIFICATION_ERROR(
        "notification_error",
        "Notification Error",
        longArrayOf(14, 48, 14, 48, 14, 48, 20),
        intArrayOf(200, 0, 200, 0, 255, 0, 145),
        longArrayOf(40, 60, 40, 60, 65, 60, 40)
    ),
    NOTIFICATION_SUCCESS(
        "notification_success",
        "Notification Success",
        longArrayOf(14, 65, 14),
        intArrayOf(175, 0, 255),
        longArrayOf(50, 60, 65)
    ),
    NOTIFICATION_WARNING(
        "notification_warning",
        "Notification Warning",
        longArrayOf(14, 64, 14),
        intArrayOf(225, 0, 175),
        longArrayOf(65, 60, 40)
    ),
    SELECTION_CHANGE(
        "selection_change",
        "Selection Change",
        longArrayOf(1),
        intArrayOf(65),
        longArrayOf(30)
    ),
    APP_ERROR(
        "app_error",
        "App Error",
        longArrayOf(30, 10, 150, 10),
        intArrayOf(0, 100, 0, 100),
        longArrayOf(40, 60, 40, 60, 65, 60, 40)
    );

    fun vibrate(context: Context): Boolean {
        if (this == NONE || this == OEM_CLEAR_ALL) return false

        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return false

        if (!vibrator.hasVibrator()) return false

        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (vibrator.hasAmplitudeControl() && timings != null && amplitudes != null) {
                    VibrationEffect.createWaveform(timings, amplitudes, -1)
                } else {
                    VibrationEffect.createWaveform(fallbackTimings ?: longArrayOf(30), -1)
                }
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(fallbackTimings ?: longArrayOf(30), -1)
            }
            true
        }.getOrDefault(false)
    }

    companion object {
        fun fromPref(value: String?): LauncherHapticProfile =
            entries.firstOrNull { it.prefValue == value } ?: OEM_CLEAR_ALL

        val previewable: List<LauncherHapticProfile>
            get() = entries.filterNot { it == NONE || it == OEM_CLEAR_ALL }
    }
}
