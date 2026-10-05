package top.gtian.hiderecent

import android.content.Context
import android.content.pm.PackageManager

object ColorOSChainHealth {
    const val FLUID_CLOUD_AUTHORITY =
        "com.oplus.card.server.systemui.provider"
    const val SEEDLING_SERVICE_AUTHORITY =
        "com.oplusos.provider.SeedlingServiceProvider"

    data class Item(
        val id: String,
        val available: Boolean,
        val detail: String
    )

    data class Snapshot(
        val items: List<Item>
    ) {
        val healthyCount: Int
            get() = items.count { it.available }

        val totalCount: Int
            get() = items.size

        val allCriticalHealthy: Boolean
            get() = items
                .filter {
                    it.id in setOf(
                        "fluid_cloud",
                        "flex_ui",
                        "smart_sidebar",
                        "seedling"
                    )
                }
                .all { it.available }
    }

    fun inspect(context: Context): Snapshot {
        val pm = context.packageManager

        val fluidProvider = resolveProvider(
            pm,
            FLUID_CLOUD_AUTHORITY
        )
        val seedlingProvider = resolveProvider(
            pm,
            SEEDLING_SERVICE_AUTHORITY
        )

        return Snapshot(
            listOf(
                Item(
                    id = "fluid_cloud",
                    available =
                        fluidProvider?.packageName == "com.android.systemui",
                    detail = if (fluidProvider != null) {
                        "SystemUI · ${fluidProvider.name}"
                    } else {
                        "Provider 未解析"
                    }
                ),
                Item(
                    id = "flex_ui",
                    available = packageExists(
                        pm,
                        "com.oplus.pscanvas"
                    ),
                    detail = "OplusFlexibleWindowUI · com.oplus.pscanvas"
                ),
                Item(
                    id = "smart_sidebar",
                    available = packageExists(
                        pm,
                        "com.coloros.smartsidebar"
                    ),
                    detail = "SmartSidebar · com.coloros.smartsidebar"
                ),
                Item(
                    id = "seedling",
                    available =
                        seedlingProvider?.packageName ==
                            "com.oplus.securitypermission",
                    detail = if (seedlingProvider != null) {
                        "SeedlingServiceProvider · ${seedlingProvider.packageName}"
                    } else {
                        "Seedling Provider 未解析"
                    }
                ),
                Item(
                    id = "pantanal",
                    available = packageExists(
                        pm,
                        "com.oplus.pantanal.ums"
                    ),
                    detail = "Pantanal UMS"
                ),
                Item(
                    id = "athena",
                    available = packageExists(
                        pm,
                        "com.oplus.athena"
                    ),
                    detail = "Athena / Hans / recent-lock"
                )
            )
        )
    }

    fun isFluidCloudDownstreamAvailable(
        context: Context
    ): Boolean {
        val provider = resolveProvider(
            context.packageManager,
            FLUID_CLOUD_AUTHORITY
        )
        return provider?.packageName == "com.android.systemui"
    }

    fun isFlexibleWindowUiAvailable(
        context: Context
    ): Boolean =
        packageExists(
            context.packageManager,
            "com.oplus.pscanvas"
        )

    @Suppress("DEPRECATION")
    private fun resolveProvider(
        pm: PackageManager,
        authority: String
    ) = runCatching {
        pm.resolveContentProvider(
            authority,
            PackageManager.MATCH_SYSTEM_ONLY
        )
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun packageExists(
        pm: PackageManager,
        packageName: String
    ): Boolean = runCatching {
        pm.getApplicationInfo(
            packageName,
            PackageManager.MATCH_SYSTEM_ONLY
        )
        true
    }.getOrDefault(false)
}
