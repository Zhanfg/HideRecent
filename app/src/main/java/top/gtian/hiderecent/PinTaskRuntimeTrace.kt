package top.gtian.hiderecent

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import java.util.concurrent.Executors

/**
 * Low-frequency cross-process runtime trace for the ColorOS PinTask chain.
 *
 * Only known system participants may write. No hook reads this provider on a hot path.
 */
object PinTaskRuntimeTrace {
    const val AUTHORITY = "cc.axymorrsen.launcherstability.runtime"
    const val METHOD_RECORD = "record_pin_trace"

    private const val PREFS = "pin_task_runtime_trace"

    const val STAGE_LAUNCHER = "launcher"
    const val STAGE_SYSTEMUI = "systemui"
    const val STAGE_ATHENA_LIST = "athena_list"
    const val STAGE_ATHENA_MATCH = "athena_match"

    data class Item(
        val id: String,
        val hit: Boolean,
        val at: Long,
        val taskId: Int,
        val packageName: String?,
        val detail: String
    )

    data class Snapshot(
        val items: List<Item>
    )

    fun read(context: Context): Snapshot {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun item(id: String): Item {
            val at = p.getLong("${id}_at", 0L)
            return Item(
                id = id,
                hit = at > 0L,
                at = at,
                taskId = p.getInt("${id}_task", -1),
                packageName = p.getString("${id}_pkg", null),
                detail = p.getString("${id}_detail", null).orEmpty()
            )
        }

        return Snapshot(
            listOf(
                item(STAGE_LAUNCHER),
                item(STAGE_SYSTEMUI),
                item(STAGE_ATHENA_LIST),
                item(STAGE_ATHENA_MATCH)
            )
        )
    }

    internal fun recordLocal(
        context: Context,
        stage: String,
        taskId: Int,
        packageName: String?,
        detail: String,
        reset: Boolean
    ) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val e = p.edit()
        if (reset) e.clear()
        e.putLong("${stage}_at", System.currentTimeMillis())
        e.putInt("${stage}_task", taskId)
        if (packageName != null) {
            e.putString("${stage}_pkg", packageName)
        }
        e.putString("${stage}_detail", detail.take(320))
        e.apply()
    }
}

class PinTaskRuntimeTraceProvider : ContentProvider() {
    private val allowedPackages = setOf(
        "com.android.launcher",
        "com.android.systemui",
        "com.oplus.athena",
        "com.coloros.smartsidebar"
    )

    override fun onCreate(): Boolean = true

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?
    ): Bundle {
        val ctx = context ?: return Bundle().apply {
            putBoolean("ok", false)
        }

        if (method != PinTaskRuntimeTrace.METHOD_RECORD ||
            !isAllowedCaller(ctx)
        ) {
            return Bundle().apply { putBoolean("ok", false) }
        }

        val stage = extras?.getString("stage").orEmpty()
        if (stage !in setOf(
                PinTaskRuntimeTrace.STAGE_LAUNCHER,
                PinTaskRuntimeTrace.STAGE_SYSTEMUI,
                PinTaskRuntimeTrace.STAGE_ATHENA_LIST,
                PinTaskRuntimeTrace.STAGE_ATHENA_MATCH
            )
        ) {
            return Bundle().apply { putBoolean("ok", false) }
        }

        PinTaskRuntimeTrace.recordLocal(
            context = ctx,
            stage = stage,
            taskId = extras?.getInt("taskId", -1) ?: -1,
            packageName = extras?.getString("packageName"),
            detail = extras?.getString("detail").orEmpty(),
            reset = extras?.getBoolean("reset", false) == true
        )

        return Bundle().apply { putBoolean("ok", true) }
    }

    private fun isAllowedCaller(context: Context): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return true

        val packages = context.packageManager
            .getPackagesForUid(uid)
            ?.toSet()
            .orEmpty()

        return packages.any { it in allowedPackages }
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0
}

internal object PinTaskRuntimeTraceReporter {
    private val uri = Uri.parse(
        "content://${PinTaskRuntimeTrace.AUTHORITY}"
    )

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LauncherStabilityPinTrace").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    fun record(
        context: Context?,
        stage: String,
        taskId: Int = -1,
        packageName: String? = null,
        detail: String = "",
        reset: Boolean = false
    ) {
        val app = context?.applicationContext ?: context ?: return

        executor.execute {
            runCatching {
                val extras = Bundle().apply {
                    putString("stage", stage)
                    putInt("taskId", taskId)
                    putString("packageName", packageName)
                    putString("detail", detail.take(320))
                    putBoolean("reset", reset)
                }

                app.contentResolver.call(
                    uri,
                    PinTaskRuntimeTrace.METHOD_RECORD,
                    null,
                    extras
                )
            }
        }
    }
}
