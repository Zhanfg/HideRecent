package top.gtian.hiderecent.ui

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
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
            LauncherHapticProfile.IMPACT_SOFT
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
