package top.gtian.hiderecent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericRecentTaskFilterTest {
    private data class FakeComponent(private val pkg: String) { fun getPackageName() = pkg }
    private data class FakeIntent(private val component: FakeComponent?) {
        fun getComponent() = component
        fun getPackage(): String? = null
    }
    private data class FakeRecentTaskInfo(
        val topActivity: FakeComponent? = null,
        val baseActivity: FakeComponent? = null,
        val baseIntent: FakeIntent? = null
    )
    private data class FakeGroup(private val one: FakeRecentTaskInfo, private val two: FakeRecentTaskInfo?) {
        fun getTaskInfo1() = one
        fun getTaskInfo2() = two
    }

    @Test fun filtersAospRecentTaskInfoInPlace() {
        val tasks = arrayListOf(
            FakeRecentTaskInfo(topActivity = FakeComponent("com.keep.one")),
            FakeRecentTaskInfo(baseIntent = FakeIntent(FakeComponent("com.hide.me"))),
            FakeRecentTaskInfo(baseActivity = FakeComponent("com.keep.two"))
        )
        val removed = RecentTaskListFilter.filterInPlace(tasks, setOf("com.hide.me"))
        assertEquals(1, removed)
        assertEquals(2, tasks.size)
        assertFalse(tasks.any { RecentTaskPackages.shouldHide(it, setOf("com.hide.me")) })
    }

    @Test fun filtersGroupedSplitTaskWhenEitherHalfIsHidden() {
        val groups = arrayListOf(
            FakeGroup(
                FakeRecentTaskInfo(topActivity = FakeComponent("com.visible")),
                FakeRecentTaskInfo(topActivity = FakeComponent("com.hidden"))
            ),
            FakeGroup(FakeRecentTaskInfo(topActivity = FakeComponent("com.keep")), null)
        )
        assertEquals(1, RecentTaskListFilter.filterInPlace(groups, setOf("com.hidden")))
        assertEquals(1, groups.size)
        assertTrue(RecentTaskPackages.collect(groups.single()).contains("com.keep"))
    }

    @Test fun filteredCopyDoesNotMutateRunningTasksBackingList() {
        val source = arrayListOf(
            FakeRecentTaskInfo(topActivity = FakeComponent("com.hidden")),
            FakeRecentTaskInfo(topActivity = FakeComponent("com.visible"))
        )
        val copy = RecentTaskListFilter.filteredArrayList(source, setOf("com.hidden"))!!
        assertEquals(2, source.size)
        assertEquals(1, copy.size)
    }
}
