package top.gtian.hiderecent

object LauncherStabilityPrefs {
    const val PREFS_NAME = "launcher_stability"

    const val KEY_HAPTIC_EFFECTS = "haptic_effects"
    const val KEY_HIDE_TASK_TITLE = "hide_task_title"
    const val KEY_HIDE_TASK_ICON = "hide_task_icon"
    const val KEY_HIDE_CLEAR_BUTTON = "hide_clear_button"

    val BOOLEAN_KEYS = arrayOf(
        KEY_HAPTIC_EFFECTS,
        KEY_HIDE_TASK_TITLE,
        KEY_HIDE_TASK_ICON,
        KEY_HIDE_CLEAR_BUTTON
    )
}
