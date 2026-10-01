package top.gtian.hiderecent

import java.util.ArrayList

/**
 * Runtime-neutral filtering helpers shared by system_server and launcher hooks.
 *
 * The implementation deliberately mutates a returned mutable task list in place when possible.
 * That preserves vendor/private subclasses such as Launcher3 TaskLoadResult while still removing
 * hidden entries. Callers that must not mutate a backing collection can use [filteredArrayList].
 */
internal object RecentTaskListFilter {

    /** Remove hidden task/group entries from a mutable List-like return value. */
    fun filterInPlace(value: Any?, hidden: Set<String>): Int {
        if (hidden.isEmpty() || value !is MutableList<*>) return 0
        @Suppress("UNCHECKED_CAST")
        val list = value as MutableList<Any?>
        var removed = 0
        return try {
            val iterator = list.listIterator()
            while (iterator.hasNext()) {
                if (RecentTaskPackages.shouldHide(iterator.next(), hidden)) {
                    iterator.remove()
                    removed++
                }
            }
            removed
        } catch (_: Throwable) {
            // Some ROMs may return read-only/proxy lists. Never break recents because filtering
            // could not mutate a vendor container.
            0
        }
    }

    /** Build a plain ArrayList copy with hidden task/group entries removed. */
    fun filteredArrayList(value: Any?, hidden: Set<String>): ArrayList<Any?>? {
        if (value !is Iterable<*>) return null
        val out = ArrayList<Any?>()
        for (item in value) {
            if (!RecentTaskPackages.shouldHide(item, hidden)) out += item
        }
        return out
    }
}
