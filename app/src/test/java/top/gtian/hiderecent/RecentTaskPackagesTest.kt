package top.gtian.hiderecent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentTaskPackagesTest {
    private data class FakeComponent(private val pkg: String) { fun getPackageName() = pkg }
    private data class FakeIntent(private val c: FakeComponent?) { fun getComponent() = c; fun getPackage(): String? = null }
    private data class FakeTaskInfo(val topActivity: FakeComponent?, val baseIntent: FakeIntent?)
    private data class FakeBaseGroup(private val a: FakeTaskInfo?, private val b: FakeTaskInfo?) {
        fun getTaskInfo1() = a
        fun getTaskInfo2() = b
    }
    private data class FakeGrouped(private val base: FakeBaseGroup) { fun getBaseGroupedTask() = base }
    private data class LegacyTask(private val pkg: String) { fun getPackageName() = pkg }
    private data class LegacyGroup(val task1: LegacyTask, val task2: LegacyTask?)

    @Test fun collectsColorOs16GroupedTaskInfoBothHalves() {
        val grouped = FakeGrouped(FakeBaseGroup(
            FakeTaskInfo(FakeComponent("com.example.visible"), null),
            FakeTaskInfo(null, FakeIntent(FakeComponent("com.example.hidden")))
        ))
        assertEquals(setOf("com.example.visible", "com.example.hidden"), RecentTaskPackages.collect(grouped))
        assertTrue(RecentTaskPackages.shouldHide(grouped, setOf("com.example.hidden")))
    }

    @Test fun collectsLegacyGroupTask() {
        val group = LegacyGroup(LegacyTask("com.legacy.one"), LegacyTask("com.legacy.two"))
        assertTrue(RecentTaskPackages.shouldHide(group, setOf("com.legacy.two")))
        assertFalse(RecentTaskPackages.shouldHide(group, setOf("com.none")))
    }
}
