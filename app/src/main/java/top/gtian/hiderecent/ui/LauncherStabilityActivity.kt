package top.gtian.hiderecent.ui

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import top.gtian.hiderecent.LauncherHapticProfile
import top.gtian.hiderecent.LauncherStabilityPrefs
import top.gtian.hiderecent.R

class LauncherStabilityActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher_stability)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.app_name)
        applyStatusBarSpacer()

        prefs = getSharedPreferences(LauncherStabilityPrefs.PREFS_NAME, MODE_PRIVATE)

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

        findViewById<TextView>(R.id.statusText).text =
            getString(R.string.launcher_apply_note)
    }

    private fun bind(view: SwitchCompat, key: String) {
        view.isChecked = prefs.getBoolean(key, false)
        view.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(key, checked).apply()
        }
    }

    private fun buildHapticProfiles(container: LinearLayout) {
        addProfileSpinner(
            container,
            getString(R.string.haptic_profile_dismiss),
            LauncherStabilityPrefs.KEY_DISMISS_HAPTIC_PROFILE,
            LauncherHapticProfile.OEM_CLEAR_ALL
        )
        addProfileSpinner(
            container,
            getString(R.string.haptic_profile_clear_all),
            LauncherStabilityPrefs.KEY_CLEAR_ALL_HAPTIC_PROFILE,
            LauncherHapticProfile.OEM_CLEAR_ALL
        )
        addProfileSpinner(
            container,
            getString(R.string.haptic_profile_recents_enter),
            LauncherStabilityPrefs.KEY_RECENTS_ENTER_HAPTIC_PROFILE,
            LauncherHapticProfile.NONE
        )
    }

    private fun addProfileSpinner(
        container: LinearLayout,
        label: String,
        key: String,
        defaultProfile: LauncherHapticProfile
    ) {
        val labelView = TextView(this).apply {
            text = label
            textSize = 14f
            setPadding(0, dp(12), 0, dp(4))
        }
        container.addView(labelView)

        val profiles = LauncherHapticProfile.entries
        val spinner = Spinner(this)
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            profiles.map { it.title }
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.adapter = adapter

        val selected = LauncherHapticProfile.fromPref(
            prefs.getString(key, defaultProfile.prefValue)
        )
        spinner.setSelection(profiles.indexOf(selected).coerceAtLeast(0), false)
        spinner.onItemSelectedListener = SimpleItemSelectedListener { position ->
            val profile = profiles[position]
            prefs.edit().putString(key, profile.prefValue).apply()
        }
        container.addView(
            spinner,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun buildHapticLab(container: LinearLayout) {
        LauncherHapticProfile.previewable.forEach { profile ->
            val button = Button(this).apply {
                text = profile.title
                isAllCaps = false
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
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply {
                topMargin = dp(6)
            }
            container.addView(button, params)
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
            100,
            { value -> "${(value * 100).toInt()}%" }
        )
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
            100,
            { value -> String.format("%.0f dp", value) }
        )
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
            8,
            { value -> value.toString() }
        )

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
            0,
            { value -> (value + 1).toString() }
        )
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
            4,
            { value -> value.toString() }
        )
        addIntSlider(
            container,
            getString(R.string.folder_columns),
            LauncherStabilityPrefs.KEY_FOLDER_COLUMNS,
            2,
            8,
            3,
            { value -> value.toString() }
        )
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
            4,
            { value -> value.toString() }
        )
        addSwitch(
            container,
            getString(R.string.force_fold_mode),
            LauncherStabilityPrefs.KEY_FORCE_FOLD_MODE,
            getString(R.string.force_fold_mode_desc)
        )
        addIntSpinner(
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

    private fun addSection(container: LinearLayout, title: String) {
        container.addView(
            TextView(this).apply {
                text = title
                textSize = 15f
                setPadding(0, dp(20), 0, dp(4))
            }
        )
    }

    private fun addSwitch(
        container: LinearLayout,
        title: String,
        key: String,
        summary: String
    ) {
        val switch = SwitchCompat(this).apply {
            text = title
            minHeight = dp(52)
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
            ).apply { topMargin = dp(8) }
        )

        container.addView(
            TextView(this).apply {
                text = summary
                textSize = 12.5f
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
        val label = TextView(this).apply {
            textSize = 13.5f
            setPadding(0, dp(10), 0, 0)
        }
        val current = prefs.getInt(key, defaultValue).coerceIn(min, max)
        label.text = "$title · ${formatter(current)}"
        container.addView(label)

        val seekBar = SeekBar(this).apply {
            this.max = max - min
            progress = current - min
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                label.text = "$title · ${formatter(min + progress)}"
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit

            override fun onStopTrackingTouch(bar: SeekBar?) {
                val value = min + (bar?.progress ?: 0)
                prefs.edit().putInt(key, value).apply()
            }
        })
        container.addView(seekBar)
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
        val label = TextView(this).apply {
            textSize = 13.5f
            setPadding(0, dp(10), 0, 0)
        }
        val current = prefs.getFloat(key, defaultValue).coerceIn(min, max)
        fun progressToValue(progress: Int): Float =
            min + (max - min) * progress.toFloat() / steps.toFloat()
        fun valueToProgress(value: Float): Int =
            (((value - min) / (max - min)) * steps).toInt().coerceIn(0, steps)

        label.text = "$title · ${formatter(current)}"
        container.addView(label)

        val seekBar = SeekBar(this).apply {
            this.max = steps
            progress = valueToProgress(current)
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                label.text = "$title · ${formatter(progressToValue(progress))}"
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit

            override fun onStopTrackingTouch(bar: SeekBar?) {
                val value = progressToValue(bar?.progress ?: 0)
                prefs.edit().putFloat(key, value).apply()
            }
        })
        container.addView(seekBar)
    }

    private fun addIntSpinner(
        container: LinearLayout,
        title: String,
        key: String,
        options: List<Pair<Int, String>>,
        defaultValue: Int
    ) {
        container.addView(
            TextView(this).apply {
                text = title
                textSize = 13.5f
                setPadding(0, dp(10), 0, dp(3))
            }
        )

        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            options.map { it.second }
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val current = prefs.getInt(key, defaultValue)
        spinner.setSelection(options.indexOfFirst { it.first == current }.coerceAtLeast(0), false)
        spinner.onItemSelectedListener = SimpleItemSelectedListener { position ->
            prefs.edit().putInt(key, options[position].first).apply()
        }
        container.addView(spinner)
    }

    private fun applyStatusBarSpacer() {
        val spacer: View = findViewById(R.id.statusBarSpacer)
        val lp = spacer.layoutParams
        ViewCompat.setOnApplyWindowInsetsListener(spacer) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            if (lp.height != top) {
                lp.height = top
                v.layoutParams = lp
            }
            insets
        }
        ViewCompat.requestApplyInsets(spacer)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private class SimpleItemSelectedListener(
        private val onSelected: (Int) -> Unit
    ) : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(
            parent: android.widget.AdapterView<*>?,
            view: View?,
            position: Int,
            id: Long
        ) {
            onSelected(position)
        }

        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }
}
