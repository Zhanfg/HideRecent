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
    const val KEY_DISABLE_AUTO_FOCUS_NEXT_TASK = "disable_auto_focus_next_task"
    const val KEY_ENABLE_INDICATOR_ENTRY = "enable_indicator_entry"
    const val KEY_ENABLE_DOCK_BACKGROUND = "enable_dock_background"
    const val KEY_FORCE_DOCK_BLUR = "force_dock_blur"
    const val KEY_UNLIMIT_FOLDER_NAME = "unlimit_folder_name"
    const val KEY_DISABLE_ICON_SECONDARY_MENU = "disable_icon_secondary_menu"
    const val KEY_ALLOW_EXCLUDED_TASK_LOCK = "allow_excluded_task_lock"
    const val KEY_UNLOCK_TASK_LOCK_LIMIT = "unlock_task_lock_limit"

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
        KEY_DISABLE_AUTO_FOCUS_NEXT_TASK,
        KEY_ENABLE_INDICATOR_ENTRY,
        KEY_ENABLE_DOCK_BACKGROUND,
        KEY_FORCE_DOCK_BLUR,
        KEY_UNLIMIT_FOLDER_NAME,
        KEY_DISABLE_ICON_SECONDARY_MENU,
        KEY_ALLOW_EXCLUDED_TASK_LOCK,
        KEY_UNLOCK_TASK_LOCK_LIMIT,

        KEY_DOCK_ALPHA_ENABLED,
        KEY_BLUR_CORNER_ENABLED,
        KEY_DOCK_MAX_ENABLED,
        KEY_DEFAULT_HOME_ENABLED,
        KEY_FOLDER_GRID_ENABLED,
        KEY_DRAWER_GRID_ENABLED,
        KEY_FORCE_FOLD_MODE
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
        KEY_FOLD_MODE
    )

    val FLOAT_KEYS = arrayOf(
        KEY_DOCK_ALPHA,
        KEY_BLUR_CORNER_DP
    )

    val ALL_KEYS =
        BOOLEAN_KEYS.toSet() +
            STRING_KEYS.toSet() +
            INT_KEYS.toSet() +
            FLOAT_KEYS.toSet()
}
