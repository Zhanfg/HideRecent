package top.gtian.hiderecent.ui

import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.R as MaterialR
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import top.gtian.hiderecent.LauncherHapticProfile
import top.gtian.hiderecent.LauncherStabilityPrefs
import top.gtian.hiderecent.R
import kotlin.math.round
import kotlin.math.roundToInt

class LauncherStabilityActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(
            LauncherStabilityPrefs.PREFS_NAME,
            MODE_PRIVATE
        )
        applyDynamicColorsIfEnabled()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_launcher_stability)

        configureSystemBars()

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.app_name)
        applyInsets()

        bindDynamicColor(findViewById(R.id.switchUseDynamicColor))

        bind(
            findViewById(R.id.switchHapticEffects),
            LauncherStabilityPrefs.KEY_HAPTIC_EFFECTS
        )
        bind(
            findViewById(R.id.switchHideWorkspaceLabels),
            LauncherStabilityPrefs.KEY_HIDE_WORKSPACE_LABELS
        )
        bind(
            findViewById(R.id.switchHideDrawerLabels),
            LauncherStabilityPrefs.KEY_HIDE_DRAWER_LABELS
        )
        bind(
            findViewById(R.id.switchHidePageIndicator),
            LauncherStabilityPrefs.KEY_HIDE_PAGE_INDICATOR
        )
        bind(
            findViewById(R.id.switchHideBottomSearch),
            LauncherStabilityPrefs.KEY_HIDE_BOTTOM_SEARCH
        )
        bind(
            findViewById(R.id.switchHideTaskTitle),
            LauncherStabilityPrefs.KEY_HIDE_TASK_TITLE
        )
        bind(
            findViewById(R.id.switchHideTaskIcon),
            LauncherStabilityPrefs.KEY_HIDE_TASK_ICON
        )
        bind(
            findViewById(R.id.switchHideClearButton),
            LauncherStabilityPrefs.KEY_HIDE_CLEAR_BUTTON
        )

        buildHapticProfiles(findViewById(R.id.hapticProfileContainer))
        buildHapticLab(findViewById(R.id.hapticLabContainer))
        buildExtendedFeatures(findViewById(R.id.extendedFeatureContainer))
        buildPerformanceFeatures(findViewById(R.id.performanceFeatureContainer))
        buildAnimationFeatures(findViewById(R.id.animationFeatureContainer))

        findViewById<TextView>(R.id.statusText).text =
            getString(R.string.launcher_apply_note)
    }

    private fun applyDynamicColorsIfEnabled() {
        if (!prefs.getBoolean(
                LauncherStabilityPrefs.KEY_USE_DYNAMIC_COLOR,
                false
            )
        ) {
            return
        }

        runCatching {
            val cls = Class.forName(
                "com.google.android.material.color.DynamicColors"
            )
            val method = cls.getMethod(
                "applyToActivityIfAvailable",
                android.app.Activity::class.java
            )
            method.invoke(null, this)
        }
    }

    private fun bindDynamicColor(view: MaterialSwitch) {
        view.isChecked = prefs.getBoolean(
            LauncherStabilityPrefs.KEY_USE_DYNAMIC_COLOR,
            false
        )
        view.setOnCheckedChangeListener { _, checked ->
            prefs.edit()
                .putBoolean(
                    LauncherStabilityPrefs.KEY_USE_DYNAMIC_COLOR,
                    checked
                )
                .apply()
            recreate()
        }
    }

    private fun configureSystemBars() {
        val isNight =
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isNight
            isAppearanceLightNavigationBars = !isNight
        }
    }

    private fun bind(view: SwitchCompat, key: String) {
        view.isChecked = prefs.getBoolean(key, false)
        view.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(key, checked).apply()
        }
    }

    private fun buildHapticProfiles(container: LinearLayout) {
        addProfileDropdown(
            container,
            getString(R.string.haptic_profile_dismiss),
            LauncherStabilityPrefs.KEY_DISMISS_HAPTIC_PROFILE,
            LauncherHapticProfile.OEM_CLEAR_ALL
        )
        addProfileDropdown(
            container,
            getString(R.string.haptic_profile_clear_all),
            LauncherStabilityPrefs.KEY_CLEAR_ALL_HAPTIC_PROFILE,
            LauncherHapticProfile.OEM_CLEAR_ALL
        )
        addProfileDropdown(
            container,
            getString(R.string.haptic_profile_recents_enter),
            LauncherStabilityPrefs.KEY_RECENTS_ENTER_HAPTIC_PROFILE,
            LauncherHapticProfile.NONE
        )
    }

    private fun addProfileDropdown(
        container: LinearLayout,
        label: String,
        key: String,
        defaultProfile: LauncherHapticProfile
    ) {
        val profiles = LauncherHapticProfile.entries
        val selected = LauncherHapticProfile.fromPref(
            prefs.getString(key, defaultProfile.prefValue)
        )

        val field = TextInputLayout(
            this,
            null,
            MaterialR.attr.textInputOutlinedExposedDropdownMenuStyle
        ).apply {
            hint = label
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
        }

        val dropdown = MaterialAutoCompleteTextView(this).apply {
            inputType = InputType.TYPE_NULL
            setAdapter(
                ArrayAdapter(
                    this@LauncherStabilityActivity,
                    android.R.layout.simple_dropdown_item_1line,
                    profiles.map { it.title }
                )
            )
            setText(selected.title, false)
            setOnItemClickListener { _, _, position, _ ->
                prefs.edit()
                    .putString(key, profiles[position].prefValue)
                    .apply()
            }
            setOnClickListener { showDropDown() }
        }

        field.addView(
            dropdown,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            field,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        )
    }

    private fun buildHapticLab(container: LinearLayout) {
        LauncherHapticProfile.previewable.forEach { profile ->
            val button = MaterialButton(
                this,
                null,
                MaterialR.attr.materialButtonOutlinedStyle
            ).apply {
                text = profile.title
                isAllCaps = false
                cornerRadius = dp(18)
                setOnClickListener {
                    val ok = profile.vibrate(this@LauncherStabilityActivity)
                    if (!ok) {
                        Toast.makeText(
                            this@LauncherStabilityActivity,
                            R.string.haptic_preview_failed,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            container.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(50)
                ).apply { topMargin = dp(7) }
            )
        }
    }

    private fun buildExtendedFeatures(container: LinearLayout) {
        addSection(container, getString(R.string.ext_stable_title))

        addSwitch(
            container,
            getString(R.string.force_memory_info),
            LauncherStabilityPrefs.KEY_FORCE_MEMORY_INFO,
            getString(R.string.force_memory_info_desc)
        )
        addSwitch(
            container,
            getString(R.string.auto_close_folder),
            LauncherStabilityPrefs.KEY_AUTO_CLOSE_FOLDER,
            getString(R.string.auto_close_folder_desc)
        )
        addSwitch(
            container,
            getString(R.string.remove_folder_preview_bg),
            LauncherStabilityPrefs.KEY_REMOVE_FOLDER_PREVIEW_BG,
            getString(R.string.remove_folder_preview_bg_desc)
        )
        addSwitch(
            container,
            getString(R.string.remove_update_green_dot),
            LauncherStabilityPrefs.KEY_REMOVE_UPDATE_GREEN_DOT,
            getString(R.string.remove_update_green_dot_desc)
        )
        addSwitch(
            container,
            getString(R.string.hide_recents_dock),
            LauncherStabilityPrefs.KEY_HIDE_RECENTS_DOCK,
            getString(R.string.hide_recents_dock_desc)
        )
        addSwitch(
            container,
            getString(R.string.disable_auto_focus_next_task),
            LauncherStabilityPrefs.KEY_DISABLE_AUTO_FOCUS_NEXT_TASK,
            getString(R.string.disable_auto_focus_next_task_desc)
        )
        addSwitch(
            container,
            getString(R.string.enable_indicator_entry),
            LauncherStabilityPrefs.KEY_ENABLE_INDICATOR_ENTRY,
            getString(R.string.enable_indicator_entry_desc)
        )
        addSwitch(
            container,
            getString(R.string.unlimit_folder_name),
            LauncherStabilityPrefs.KEY_UNLIMIT_FOLDER_NAME,
            getString(R.string.unlimit_folder_name_desc)
        )
        addSwitch(
            container,
            getString(R.string.disable_icon_secondary_menu),
            LauncherStabilityPrefs.KEY_DISABLE_ICON_SECONDARY_MENU,
            getString(R.string.disable_icon_secondary_menu_desc)
        )
        addSwitch(
            container,
            getString(R.string.allow_excluded_task_lock),
            LauncherStabilityPrefs.KEY_ALLOW_EXCLUDED_TASK_LOCK,
            getString(R.string.allow_excluded_task_lock_desc)
        )
        addSwitch(
            container,
            getString(R.string.unlock_task_lock_limit),
            LauncherStabilityPrefs.KEY_UNLOCK_TASK_LOCK_LIMIT,
            getString(R.string.unlock_task_lock_limit_desc)
        )

        addSection(container, getString(R.string.ext_icon_title))

        addSwitch(
            container,
            getString(R.string.custom_icon_size_enabled),
            LauncherStabilityPrefs.KEY_CUSTOM_ICON_SIZE_ENABLED,
            getString(R.string.custom_icon_size_enabled_desc)
        )
        addIntSlider(
            container,
            getString(R.string.icon_size_dp),
            LauncherStabilityPrefs.KEY_ICON_SIZE_DP,
            36,
            96,
            56
        ) { "$it dp" }
        addSwitch(
            container,
            getString(R.string.remove_shortcut_badge),
            LauncherStabilityPrefs.KEY_REMOVE_SHORTCUT_BADGE,
            getString(R.string.remove_shortcut_badge_desc)
        )
        addSwitch(
            container,
            getString(R.string.remove_work_badge),
            LauncherStabilityPrefs.KEY_REMOVE_WORK_BADGE,
            getString(R.string.remove_work_badge_desc)
        )
        addSwitch(
            container,
            getString(R.string.remove_clone_badge),
            LauncherStabilityPrefs.KEY_REMOVE_CLONE_BADGE,
            getString(R.string.remove_clone_badge_desc)
        )

        addSection(container, getString(R.string.ext_dock_title))

        addSwitch(
            container,
            getString(R.string.enable_dock_background),
            LauncherStabilityPrefs.KEY_ENABLE_DOCK_BACKGROUND,
            getString(R.string.enable_dock_background_desc)
        )
        addSwitch(
            container,
            getString(R.string.force_dock_blur),
            LauncherStabilityPrefs.KEY_FORCE_DOCK_BLUR,
            getString(R.string.force_dock_blur_desc)
        )
        addSwitch(
            container,
            getString(R.string.dock_alpha_enabled),
            LauncherStabilityPrefs.KEY_DOCK_ALPHA_ENABLED,
            getString(R.string.dock_alpha_enabled_desc)
        )
        addFloatSlider(
            container,
            getString(R.string.dock_alpha),
            LauncherStabilityPrefs.KEY_DOCK_ALPHA,
            0f,
            1f,
            1f,
            100
        ) { "${(it * 100).roundToInt()}%" }
        addSwitch(
            container,
            getString(R.string.blur_corner_enabled),
            LauncherStabilityPrefs.KEY_BLUR_CORNER_ENABLED,
            getString(R.string.blur_corner_enabled_desc)
        )
        addFloatSlider(
            container,
            getString(R.string.blur_corner),
            LauncherStabilityPrefs.KEY_BLUR_CORNER_DP,
            0f,
            100f,
            28f,
            100
        ) { String.format("%.0f dp", it) }
        addSwitch(
            container,
            getString(R.string.dock_max_enabled),
            LauncherStabilityPrefs.KEY_DOCK_MAX_ENABLED,
            getString(R.string.dock_max_enabled_desc)
        )
        addIntSlider(
            container,
            getString(R.string.dock_max_items),
            LauncherStabilityPrefs.KEY_DOCK_MAX_ITEMS,
            5,
            20,
            8
        ) { it.toString() }

        addSection(container, getString(R.string.ext_experimental_title))

        addSwitch(
            container,
            getString(R.string.default_home_enabled),
            LauncherStabilityPrefs.KEY_DEFAULT_HOME_ENABLED,
            getString(R.string.default_home_enabled_desc)
        )
        addIntSlider(
            container,
            getString(R.string.default_home_page),
            LauncherStabilityPrefs.KEY_DEFAULT_HOME_PAGE,
            0,
            19,
            0
        ) { (it + 1).toString() }
        addSwitch(
            container,
            getString(R.string.folder_grid_enabled),
            LauncherStabilityPrefs.KEY_FOLDER_GRID_ENABLED,
            getString(R.string.folder_grid_enabled_desc)
        )
        addIntSlider(
            container,
            getString(R.string.folder_rows),
            LauncherStabilityPrefs.KEY_FOLDER_ROWS,
            2,
            8,
            4
        ) { it.toString() }
        addIntSlider(
            container,
            getString(R.string.folder_columns),
            LauncherStabilityPrefs.KEY_FOLDER_COLUMNS,
            2,
            8,
            3
        ) { it.toString() }
        addSwitch(
            container,
            getString(R.string.drawer_grid_enabled),
            LauncherStabilityPrefs.KEY_DRAWER_GRID_ENABLED,
            getString(R.string.drawer_grid_enabled_desc)
        )
        addIntSlider(
            container,
            getString(R.string.drawer_columns),
            LauncherStabilityPrefs.KEY_DRAWER_COLUMNS,
            3,
            10,
            4
        ) { it.toString() }
        addSwitch(
            container,
            getString(R.string.force_fold_mode),
            LauncherStabilityPrefs.KEY_FORCE_FOLD_MODE,
            getString(R.string.force_fold_mode_desc)
        )
        addIntDropdown(
            container,
            getString(R.string.fold_mode),
            LauncherStabilityPrefs.KEY_FOLD_MODE,
            listOf(
                0 to getString(R.string.fold_mode_expanded),
                1 to getString(R.string.fold_mode_folded)
            ),
            0
        )
    }

    private fun buildPerformanceFeatures(container: LinearLayout) {
        addSection(container, getString(R.string.perf_master_section))
        addSwitch(
            container,
            getString(R.string.perf_engine_enabled),
            LauncherStabilityPrefs.KEY_PERF_ENGINE_ENABLED,
            getString(R.string.perf_engine_enabled_desc)
        )

        addSection(container, getString(R.string.perf_recents_section))
        addSwitch(
            container,
            getString(R.string.perf_adaptive_recents),
            LauncherStabilityPrefs.KEY_PERF_ADAPTIVE_RECENTS,
            getString(R.string.perf_adaptive_recents_desc)
        )
        addFloatSlider(
            container,
            getString(R.string.perf_recents_settle_floor),
            LauncherStabilityPrefs.KEY_PERF_RECENTS_SETTLE_FLOOR,
            0.52f,
            0.90f,
            0.64f,
            38
        ) { String.format("%.2f×", it) }

        addSwitch(
            container,
            getString(R.string.perf_decisive_fling),
            LauncherStabilityPrefs.KEY_PERF_DECISIVE_FLING,
            getString(R.string.perf_decisive_fling_desc)
        )
        addFloatSlider(
            container,
            getString(R.string.perf_fling_gain),
            LauncherStabilityPrefs.KEY_PERF_FLING_GAIN,
            1.00f,
            1.35f,
            1.10f,
            35
        ) { String.format("%.2f×", it) }

        addSection(container, getString(R.string.perf_workspace_section))
        addSwitch(
            container,
            getString(R.string.perf_workspace_drag_paging),
            LauncherStabilityPrefs.KEY_PERF_WORKSPACE_DRAG_PAGING,
            getString(R.string.perf_workspace_drag_paging_desc)
        )
        addFloatSlider(
            container,
            getString(R.string.perf_drag_page_multiplier),
            LauncherStabilityPrefs.KEY_PERF_DRAG_PAGE_MULTIPLIER,
            0.55f,
            1.00f,
            0.72f,
            45
        ) { String.format("%.2f×", it) }
    }

    private fun buildAnimationFeatures(container: LinearLayout) {
        addSection(container, getString(R.string.anim_master_section))
        addSwitch(
            container,
            getString(R.string.anim_engine_enabled),
            LauncherStabilityPrefs.KEY_ANIM_ENGINE_ENABLED,
            getString(R.string.anim_engine_enabled_desc)
        )

        addSection(container, getString(R.string.anim_app_section))
        addSwitch(
            container,
            getString(R.string.anim_icon_pulse),
            LauncherStabilityPrefs.KEY_ANIM_ICON_PULSE,
            getString(R.string.anim_icon_pulse_desc)
        )
        addFloatSlider(
            container,
            getString(R.string.anim_icon_pulse_scale),
            LauncherStabilityPrefs.KEY_ANIM_ICON_PULSE_SCALE,
            0.88f,
            1f,
            0.94f,
            12
        ) { String.format("%.2f×", it) }

        addSwitch(
            container,
            getString(R.string.anim_icon_tilt),
            LauncherStabilityPrefs.KEY_ANIM_ICON_TILT,
            getString(R.string.anim_icon_tilt_desc)
        )
        addFloatSlider(
            container,
            getString(R.string.anim_icon_tilt_deg),
            LauncherStabilityPrefs.KEY_ANIM_ICON_TILT_DEG,
            0f,
            12f,
            4f,
            24
        ) { String.format("%.1f°", it) }

        addSwitch(
            container,
            getString(R.string.anim_transition_timing),
            LauncherStabilityPrefs.KEY_ANIM_TRANSITION_TIMING,
            getString(R.string.anim_transition_timing_desc)
        )
        addFloatSlider(
            container,
            getString(R.string.anim_transition_multiplier),
            LauncherStabilityPrefs.KEY_ANIM_TRANSITION_MULTIPLIER,
            0.70f,
            1.35f,
            1f,
            65
        ) { String.format("%.2f×", it) }

        addSection(container, getString(R.string.anim_recents_section))

        addSwitch(
            container,
            getString(R.string.anim_recents_tilt),
            LauncherStabilityPrefs.KEY_ANIM_RECENTS_TILT,
            getString(R.string.anim_recents_tilt_desc)
        )
        addFloatSlider(
            container,
            getString(R.string.anim_recents_tilt_deg),
            LauncherStabilityPrefs.KEY_ANIM_RECENTS_TILT_DEG,
            0f,
            16f,
            6f,
            32
        ) { String.format("%.1f°", it) }

        addSwitch(
            container,
            getString(R.string.anim_running_scale),
            LauncherStabilityPrefs.KEY_ANIM_RUNNING_SCALE,
            getString(R.string.anim_running_scale_desc)
        )
        addFloatSlider(
            container,
            getString(R.string.anim_running_scale_value),
            LauncherStabilityPrefs.KEY_ANIM_RUNNING_SCALE_VALUE,
            0.90f,
            1.06f,
            1f,
            16
        ) { String.format("%.2f×", it) }

        addSwitch(
            container,
            getString(R.string.anim_snap_tuning),
            LauncherStabilityPrefs.KEY_ANIM_SNAP_TUNING,
            getString(R.string.anim_snap_tuning_desc)
        )
        addFloatSlider(
            container,
            getString(R.string.anim_snap_multiplier),
            LauncherStabilityPrefs.KEY_ANIM_SNAP_MULTIPLIER,
            0.65f,
            1.45f,
            1f,
            80
        ) { String.format("%.2f×", it) }

        addSwitch(
            container,
            getString(R.string.anim_overscroll_tuning),
            LauncherStabilityPrefs.KEY_ANIM_OVERSCROLL_TUNING,
            getString(R.string.anim_overscroll_tuning_desc)
        )
        addFloatSlider(
            container,
            getString(R.string.anim_overscroll_multiplier),
            LauncherStabilityPrefs.KEY_ANIM_OVERSCROLL_MULTIPLIER,
            0.55f,
            1.45f,
            1f,
            90
        ) { String.format("%.2f×", it) }

        addSwitch(
            container,
            getString(R.string.anim_fling_tuning),
            LauncherStabilityPrefs.KEY_ANIM_FLING_TUNING,
            getString(R.string.anim_fling_tuning_desc)
        )
        addFloatSlider(
            container,
            getString(R.string.anim_fling_multiplier),
            LauncherStabilityPrefs.KEY_ANIM_FLING_MULTIPLIER,
            0.65f,
            1.50f,
            1f,
            85
        ) { String.format("%.2f×", it) }

        addSwitch(
            container,
            getString(R.string.anim_spring_tuning),
            LauncherStabilityPrefs.KEY_ANIM_SPRING_TUNING,
            getString(R.string.anim_spring_tuning_desc)
        )
        addFloatSlider(
            container,
            getString(R.string.anim_spring_stiffness),
            LauncherStabilityPrefs.KEY_ANIM_SPRING_STIFFNESS,
            0.65f,
            1.35f,
            1f,
            70
        ) { String.format("%.2f×", it) }
        addFloatSlider(
            container,
            getString(R.string.anim_spring_damping),
            LauncherStabilityPrefs.KEY_ANIM_SPRING_DAMPING,
            0.75f,
            1.25f,
            1f,
            50
        ) { String.format("%.2f×", it) }
    }

    private fun addSection(container: LinearLayout, title: String) {
        container.addView(
            TextView(this).apply {
                text = title
                textSize = 14f
                setTextColor(
                    MaterialColors.getColor(
                        this,
                        MaterialR.attr.colorPrimary,
                        Color.DKGRAY
                    )
                )
                setPadding(0, dp(22), 0, dp(4))
            }
        )
    }

    private fun addSwitch(
        container: LinearLayout,
        title: String,
        key: String,
        summary: String
    ) {
        val switch = MaterialSwitch(this).apply {
            text = title
            minHeight = dp(56)
            isChecked = prefs.getBoolean(key, false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
            }
        }
        container.addView(
            switch,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )

        container.addView(
            TextView(this).apply {
                text = summary
                textSize = 12.5f
                setTextColor(
                    MaterialColors.getColor(
                        this,
                        MaterialR.attr.colorOnSurfaceVariant,
                        Color.GRAY
                    )
                )
                setPadding(dp(4), 0, dp(4), dp(2))
            }
        )
    }

    private fun addIntSlider(
        container: LinearLayout,
        title: String,
        key: String,
        min: Int,
        max: Int,
        defaultValue: Int,
        formatter: (Int) -> String
    ) {
        val current = prefs.getInt(key, defaultValue).coerceIn(min, max)
        val label = sliderLabel("$title · ${formatter(current)}")
        container.addView(label)

        val slider = Slider(this).apply {
            valueFrom = min.toFloat()
            valueTo = max.toFloat()
            stepSize = 1f
            value = current.toFloat()
        }
        slider.addOnChangeListener { _, value, _ ->
            label.text = "$title · ${formatter(value.roundToInt())}"
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit

            override fun onStopTrackingTouch(slider: Slider) {
                prefs.edit().putInt(key, slider.value.roundToInt()).apply()
            }
        })
        container.addView(slider)
    }

    private fun addFloatSlider(
        container: LinearLayout,
        title: String,
        key: String,
        min: Float,
        max: Float,
        defaultValue: Float,
        steps: Int,
        formatter: (Float) -> String
    ) {
        val step = (max - min) / steps.toFloat()
        val raw = prefs.getFloat(key, defaultValue).coerceIn(min, max)
        val aligned = (
            min + round((raw - min) / step) * step
        ).coerceIn(min, max)

        val label = sliderLabel("$title · ${formatter(aligned)}")
        container.addView(label)

        val slider = Slider(this).apply {
            valueFrom = min
            valueTo = max
            stepSize = step
            value = aligned
        }
        slider.addOnChangeListener { _, value, _ ->
            label.text = "$title · ${formatter(value)}"
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit

            override fun onStopTrackingTouch(slider: Slider) {
                prefs.edit().putFloat(key, slider.value).apply()
            }
        })
        container.addView(slider)
    }

    private fun sliderLabel(textValue: String): TextView =
        TextView(this).apply {
            text = textValue
            textSize = 13.5f
            setTextColor(
                MaterialColors.getColor(
                    this,
                    MaterialR.attr.colorOnSurface,
                    Color.DKGRAY
                )
            )
            setPadding(0, dp(12), 0, 0)
        }

    private fun addIntDropdown(
        container: LinearLayout,
        title: String,
        key: String,
        options: List<Pair<Int, String>>,
        defaultValue: Int
    ) {
        val current = prefs.getInt(key, defaultValue)
        val selectedIndex =
            options.indexOfFirst { it.first == current }.coerceAtLeast(0)

        val field = TextInputLayout(
            this,
            null,
            MaterialR.attr.textInputOutlinedExposedDropdownMenuStyle
        ).apply {
            hint = title
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
        }

        val dropdown = MaterialAutoCompleteTextView(this).apply {
            inputType = InputType.TYPE_NULL
            setAdapter(
                ArrayAdapter(
                    this@LauncherStabilityActivity,
                    android.R.layout.simple_dropdown_item_1line,
                    options.map { it.second }
                )
            )
            setText(options[selectedIndex].second, false)
            setOnItemClickListener { _, _, position, _ ->
                prefs.edit().putInt(key, options[position].first).apply()
            }
            setOnClickListener { showDropDown() }
        }

        field.addView(
            dropdown,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            field,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        )
    }

    private fun applyInsets() {
        val spacer: View = findViewById(R.id.statusBarSpacer)
        val content: View = findViewById(R.id.contentRoot)
        val spacerLp = spacer.layoutParams
        val baseBottom = dp(32)

        ViewCompat.setOnApplyWindowInsetsListener(spacer) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            if (spacerLp.height != top) {
                spacerLp.height = top
                v.layoutParams = spacerLp
            }
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(
                v.paddingLeft,
                v.paddingTop,
                v.paddingRight,
                baseBottom + bottom
            )
            insets
        }

        ViewCompat.requestApplyInsets(spacer)
        ViewCompat.requestApplyInsets(content)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()
}
