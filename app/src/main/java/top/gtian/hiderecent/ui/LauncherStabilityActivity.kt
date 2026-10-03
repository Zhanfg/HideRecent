package top.gtian.hiderecent.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import top.gtian.hiderecent.LauncherStabilityPrefs
import top.gtian.hiderecent.R

class LauncherStabilityActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher_stability)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.app_name)
        applyStatusBarSpacer()

        val prefs = getSharedPreferences(LauncherStabilityPrefs.PREFS_NAME, MODE_PRIVATE)

        bind(
            findViewById(R.id.switchHapticEffects),
            LauncherStabilityPrefs.KEY_HAPTIC_EFFECTS,
            prefs
        )
        bind(
            findViewById(R.id.switchHideTaskTitle),
            LauncherStabilityPrefs.KEY_HIDE_TASK_TITLE,
            prefs
        )
        bind(
            findViewById(R.id.switchHideTaskIcon),
            LauncherStabilityPrefs.KEY_HIDE_TASK_ICON,
            prefs
        )
        bind(
            findViewById(R.id.switchHideClearButton),
            LauncherStabilityPrefs.KEY_HIDE_CLEAR_BUTTON,
            prefs
        )

        findViewById<TextView>(R.id.statusText).text =
            getString(R.string.launcher_apply_note)
    }

    private fun bind(
        view: SwitchCompat,
        key: String,
        prefs: android.content.SharedPreferences
    ) {
        view.isChecked = prefs.getBoolean(key, false)
        view.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(key, checked).apply()
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
}
