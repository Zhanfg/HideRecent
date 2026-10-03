package top.gtian.hiderecent

object LauncherStabilityPrefs {
    const val PREFS_NAME = "launcher_stability"

    const val KEY_HAPTIC_EFFECTS = "haptic_effects"
    const val KEY_DISMISS_HAPTIC_PROFILE = "dismiss_haptic_profile"
    const val KEY_CLEAR_ALL_HAPTIC_PROFILE = "clear_all_haptic_profile"
    const val KEY_RECENTS_ENTER_HAPTIC_PROFILE = "recents_enter_haptic_profile"

    const val KEY_HIDE_WORKSPACE_LABELS = "hide_workspace_labels"
    const val KEY_HIDE_DRAWER_LABELS = "hide_drawer_labels"
    const val KEY_HIDE_PAGE_INDICATOR = "hide_page_indicator"
    const val KEY_HIDE_BOTTOM_SEARCH = "hide_bottom_search"

    const val KEY_HIDE_TASK_TITLE = "hide_task_title"
    const val KEY_HIDE_TASK_ICON = "hide_task_icon"
    const val KEY_HIDE_CLEAR_BUTTON = "hide_clear_button"

    val BOOLEAN_KEYS = arrayOf(
        KEY_HAPTIC_EFFECTS,
        KEY_HIDE_WORKSPACE_LABELS,
        KEY_HIDE_DRAWER_LABELS,
        KEY_HIDE_PAGE_INDICATOR,
        KEY_HIDE_BOTTOM_SEARCH,
        KEY_HIDE_TASK_TITLE,
        KEY_HIDE_TASK_ICON,
        KEY_HIDE_CLEAR_BUTTON
    )

    val STRING_KEYS = arrayOf(
        KEY_DISMISS_HAPTIC_PROFILE,
        KEY_CLEAR_ALL_HAPTIC_PROFILE,
        KEY_RECENTS_ENTER_HAPTIC_PROFILE
    )

    val ALL_KEYS = BOOLEAN_KEYS.toSet() + STRING_KEYS.toSet()
}
