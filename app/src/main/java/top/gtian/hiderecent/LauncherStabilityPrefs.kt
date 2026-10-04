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

    // LuckyTool / OShin inspired, verified against Launcher 17.3.12.
    const val KEY_FORCE_MEMORY_INFO = "force_memory_info"
    const val KEY_AUTO_CLOSE_FOLDER = "auto_close_folder"
    const val KEY_REMOVE_FOLDER_PREVIEW_BG = "remove_folder_preview_bg"
    const val KEY_REMOVE_UPDATE_GREEN_DOT = "remove_update_green_dot"
    const val KEY_HIDE_RECENTS_DOCK = "hide_recents_dock"
    const val KEY_RECENTS_LONG_PRESS_APP_INFO = "recents_long_press_app_info"
    const val KEY_DISABLE_AUTO_FOCUS_NEXT_TASK = "disable_auto_focus_next_task"
    const val KEY_ENABLE_INDICATOR_ENTRY = "enable_indicator_entry"
    const val KEY_ENABLE_DOCK_BACKGROUND = "enable_dock_background"
    const val KEY_FORCE_DOCK_BLUR = "force_dock_blur"
    const val KEY_UNLIMIT_FOLDER_NAME = "unlimit_folder_name"
    const val KEY_DISABLE_ICON_SECONDARY_MENU = "disable_icon_secondary_menu"
    const val KEY_ALLOW_EXCLUDED_TASK_LOCK = "allow_excluded_task_lock"
    const val KEY_UNLOCK_TASK_LOCK_LIMIT = "unlock_task_lock_limit"
    const val KEY_REMOVE_SHORTCUT_BADGE = "remove_shortcut_badge"
    const val KEY_REMOVE_WORK_BADGE = "remove_work_badge"
    const val KEY_REMOVE_CLONE_BADGE = "remove_clone_badge"

    // Tunables / experimental.
    const val KEY_DOCK_ALPHA_ENABLED = "dock_alpha_enabled"
    const val KEY_DOCK_ALPHA = "dock_alpha"
    const val KEY_BLUR_CORNER_ENABLED = "blur_corner_enabled"
    const val KEY_BLUR_CORNER_DP = "blur_corner_dp"
    const val KEY_DOCK_MAX_ENABLED = "dock_max_enabled"
    const val KEY_DOCK_MAX_ITEMS = "dock_max_items"
    const val KEY_DEFAULT_HOME_ENABLED = "default_home_enabled"
    const val KEY_DEFAULT_HOME_PAGE = "default_home_page"
    const val KEY_FOLDER_GRID_ENABLED = "folder_grid_enabled"
    const val KEY_FOLDER_ROWS = "folder_rows"
    const val KEY_FOLDER_COLUMNS = "folder_columns"
    const val KEY_DRAWER_GRID_ENABLED = "drawer_grid_enabled"
    const val KEY_DRAWER_COLUMNS = "drawer_columns"
    const val KEY_FORCE_FOLD_MODE = "force_fold_mode"
    const val KEY_FOLD_MODE = "fold_mode"
    const val KEY_CUSTOM_ICON_SIZE_ENABLED = "custom_icon_size_enabled"
    const val KEY_ICON_SIZE_DP = "icon_size_dp"

    // Experimental animation engine. All disabled by default.
    const val KEY_ANIM_ENGINE_ENABLED = "anim_engine_enabled"
    const val KEY_ANIM_ICON_PULSE = "anim_icon_pulse"
    const val KEY_ANIM_ICON_TILT = "anim_icon_tilt"
    const val KEY_ANIM_RECENTS_TILT = "anim_recents_tilt"
    const val KEY_ANIM_SPRING_TUNING = "anim_spring_tuning"
    const val KEY_ANIM_SNAP_TUNING = "anim_snap_tuning"
    const val KEY_ANIM_OVERSCROLL_TUNING = "anim_overscroll_tuning"
    const val KEY_ANIM_FLING_TUNING = "anim_fling_tuning"
    const val KEY_ANIM_RUNNING_SCALE = "anim_running_scale"
    const val KEY_ANIM_TRANSITION_TIMING = "anim_transition_timing"

    const val KEY_ANIM_ICON_PULSE_SCALE = "anim_icon_pulse_scale"
    const val KEY_ANIM_ICON_TILT_DEG = "anim_icon_tilt_deg"
    const val KEY_ANIM_RECENTS_TILT_DEG = "anim_recents_tilt_deg"
    const val KEY_ANIM_SPRING_STIFFNESS = "anim_spring_stiffness"
    const val KEY_ANIM_SPRING_DAMPING = "anim_spring_damping"
    const val KEY_ANIM_SNAP_MULTIPLIER = "anim_snap_multiplier"
    const val KEY_ANIM_OVERSCROLL_MULTIPLIER = "anim_overscroll_multiplier"
    const val KEY_ANIM_FLING_MULTIPLIER = "anim_fling_multiplier"
    const val KEY_ANIM_RUNNING_SCALE_VALUE = "anim_running_scale_value"
    const val KEY_ANIM_TRANSITION_MULTIPLIER = "anim_transition_multiplier"

    val BOOLEAN_KEYS = arrayOf(
        KEY_HAPTIC_EFFECTS,
        KEY_HIDE_WORKSPACE_LABELS,
        KEY_HIDE_DRAWER_LABELS,
        KEY_HIDE_PAGE_INDICATOR,
        KEY_HIDE_BOTTOM_SEARCH,
        KEY_HIDE_TASK_TITLE,
        KEY_HIDE_TASK_ICON,
        KEY_HIDE_CLEAR_BUTTON,

        KEY_FORCE_MEMORY_INFO,
        KEY_AUTO_CLOSE_FOLDER,
        KEY_REMOVE_FOLDER_PREVIEW_BG,
        KEY_REMOVE_UPDATE_GREEN_DOT,
        KEY_HIDE_RECENTS_DOCK,
        KEY_RECENTS_LONG_PRESS_APP_INFO,
        KEY_DISABLE_AUTO_FOCUS_NEXT_TASK,
        KEY_ENABLE_INDICATOR_ENTRY,
        KEY_ENABLE_DOCK_BACKGROUND,
        KEY_FORCE_DOCK_BLUR,
        KEY_UNLIMIT_FOLDER_NAME,
        KEY_DISABLE_ICON_SECONDARY_MENU,
        KEY_ALLOW_EXCLUDED_TASK_LOCK,
        KEY_UNLOCK_TASK_LOCK_LIMIT,
        KEY_REMOVE_SHORTCUT_BADGE,
        KEY_REMOVE_WORK_BADGE,
        KEY_REMOVE_CLONE_BADGE,

        KEY_DOCK_ALPHA_ENABLED,
        KEY_BLUR_CORNER_ENABLED,
        KEY_DOCK_MAX_ENABLED,
        KEY_DEFAULT_HOME_ENABLED,
        KEY_FOLDER_GRID_ENABLED,
        KEY_DRAWER_GRID_ENABLED,
        KEY_FORCE_FOLD_MODE,
        KEY_CUSTOM_ICON_SIZE_ENABLED,
        KEY_ANIM_ENGINE_ENABLED,
        KEY_ANIM_ICON_PULSE,
        KEY_ANIM_ICON_TILT,
        KEY_ANIM_RECENTS_TILT,
        KEY_ANIM_SPRING_TUNING,
        KEY_ANIM_SNAP_TUNING,
        KEY_ANIM_OVERSCROLL_TUNING,
        KEY_ANIM_FLING_TUNING,
        KEY_ANIM_RUNNING_SCALE,
        KEY_ANIM_TRANSITION_TIMING
    )

    val STRING_KEYS = arrayOf(
        KEY_DISMISS_HAPTIC_PROFILE,
        KEY_CLEAR_ALL_HAPTIC_PROFILE,
        KEY_RECENTS_ENTER_HAPTIC_PROFILE
    )

    val INT_KEYS = arrayOf(
        KEY_DOCK_MAX_ITEMS,
        KEY_DEFAULT_HOME_PAGE,
        KEY_FOLDER_ROWS,
        KEY_FOLDER_COLUMNS,
        KEY_DRAWER_COLUMNS,
        KEY_FOLD_MODE,
        KEY_ICON_SIZE_DP
    )

    val FLOAT_KEYS = arrayOf(
        KEY_DOCK_ALPHA,
        KEY_BLUR_CORNER_DP,
        KEY_ANIM_ICON_PULSE_SCALE,
        KEY_ANIM_ICON_TILT_DEG,
        KEY_ANIM_RECENTS_TILT_DEG,
        KEY_ANIM_SPRING_STIFFNESS,
        KEY_ANIM_SPRING_DAMPING,
        KEY_ANIM_SNAP_MULTIPLIER,
        KEY_ANIM_OVERSCROLL_MULTIPLIER,
        KEY_ANIM_FLING_MULTIPLIER,
        KEY_ANIM_RUNNING_SCALE_VALUE,
        KEY_ANIM_TRANSITION_MULTIPLIER
    )

    val ALL_KEYS =
        BOOLEAN_KEYS.toSet() +
            STRING_KEYS.toSet() +
            INT_KEYS.toSet() +
            FLOAT_KEYS.toSet()

    fun intDefault(key: String): Int = when (key) {
        KEY_DOCK_MAX_ITEMS -> 8
        KEY_DEFAULT_HOME_PAGE -> 0
        KEY_FOLDER_ROWS -> 4
        KEY_FOLDER_COLUMNS -> 3
        KEY_DRAWER_COLUMNS -> 4
        KEY_FOLD_MODE -> 0
        KEY_ICON_SIZE_DP -> 56
        else -> 0
    }

    fun floatDefault(key: String): Float = when (key) {
        KEY_DOCK_ALPHA -> 1f
        KEY_BLUR_CORNER_DP -> 28f
        KEY_ANIM_ICON_PULSE_SCALE -> 0.94f
        KEY_ANIM_ICON_TILT_DEG -> 4f
        KEY_ANIM_RECENTS_TILT_DEG -> 6f
        KEY_ANIM_SPRING_STIFFNESS -> 1f
        KEY_ANIM_SPRING_DAMPING -> 1f
        KEY_ANIM_SNAP_MULTIPLIER -> 1f
        KEY_ANIM_OVERSCROLL_MULTIPLIER -> 1f
        KEY_ANIM_FLING_MULTIPLIER -> 1f
        KEY_ANIM_RUNNING_SCALE_VALUE -> 1f
        KEY_ANIM_TRANSITION_MULTIPLIER -> 1f
        else -> 0f
    }
}
