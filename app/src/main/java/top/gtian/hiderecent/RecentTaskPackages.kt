package top.gtian.hiderecent

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Package extraction for the task container generations used by Launcher3 / ColorOS Quickstep.
 *
 * Reflection metadata is resolved once per runtime class. Recents filtering is a launcher hot path,
 * so repeatedly scanning declaredMethods/fields for every card creates avoidable allocation and GC
 * pressure.
 */
internal object RecentTaskPackages {
    private const val MAX_DEPTH = 5

    private val nestedGetterNames = arrayOf(
        "getBaseGroupedTask", "getTaskInfo1", "getTaskInfo2", "getTaskInfoList",
        "getTasks", "getTask1", "getTask2", "getBaseIntent", "getBaseActivity",
        "getTopActivity", "getOrigActivity", "getRealActivity", "getTopComponent",
        "getComponent", "getSourceComponent", "getTaskInfo"
    )

    private val nestedFieldNames = arrayOf(
        "task1", "task2", "mTask1", "mTask2", "tasks", "mTasks",
        "baseIntent", "mBaseIntent", "baseActivity", "topActivity", "origActivity",
        "realActivity", "componentName", "component", "sourceComponent",
        "taskInfo", "mTaskInfo", "key", "mKey"
    )

    private data class AccessPlan(
        val packageName: Method?,
        val intentPackage: Method?,
        val nestedGetters: Array<Method>,
        val nestedFields: Array<Field>
    )

    private val plans = ConcurrentHashMap<Class<*>, AccessPlan>()

    fun collect(root: Any?): Set<String> {
        if (root == null) return emptySet()
        val out = LinkedHashSet<String>()
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        collectInto(root, out, seen, 0)
        return out
    }

    /**
     * Boolean hot-path matcher. Unlike [collect], this short-circuits on the first hidden package
     * and avoids allocating a package Set for every task card.
     */
    fun shouldHide(root: Any?, hidden: Set<String>): Boolean {
        if (root == null || hidden.isEmpty()) return false
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        return containsHidden(root, hidden, seen, 0)
    }

    private fun containsHidden(
        value: Any?,
        hidden: Set<String>,
        seen: MutableSet<Any>,
        depth: Int
    ): Boolean {
        if (value == null || depth > MAX_DEPTH) return false

        if (value is CharSequence) {
            val s = value.toString()
            return looksLikePackage(s) && s in hidden
        }

        if (value is Iterable<*>) {
            for (item in value) {
                if (containsHidden(item, hidden, seen, depth + 1)) return true
            }
            return false
        }

        if (value.javaClass.isArray) {
            val n = java.lang.reflect.Array.getLength(value)
            for (i in 0 until n) {
                if (containsHidden(java.lang.reflect.Array.get(value, i), hidden, seen, depth + 1)) {
                    return true
                }
            }
            return false
        }

        if (value is Number || value is Boolean || value is Enum<*> || value is Class<*>) {
            return false
        }

        if (!seen.add(value)) return false

        val plan = planFor(value.javaClass)

        invoke(plan.packageName, value)
            ?.toString()
            ?.takeIf(::looksLikePackage)
            ?.let { if (it in hidden) return true }

        if (value.javaClass.simpleName.contains("Intent", ignoreCase = true)) {
            invoke(plan.intentPackage, value)
                ?.toString()
                ?.takeIf(::looksLikePackage)
                ?.let { if (it in hidden) return true }
        }

        for (method in plan.nestedGetters) {
            if (containsHidden(invoke(method, value), hidden, seen, depth + 1)) return true
        }
        for (field in plan.nestedFields) {
            if (containsHidden(read(field, value), hidden, seen, depth + 1)) return true
        }

        return false
    }

    private fun collectInto(
        value: Any?,
        out: MutableSet<String>,
        seen: MutableSet<Any>,
        depth: Int
    ) {
        if (value == null || depth > MAX_DEPTH) return

        if (value is CharSequence) {
            val s = value.toString()
            if (looksLikePackage(s)) out += s
            return
        }

        if (value is Iterable<*>) {
            value.forEach { collectInto(it, out, seen, depth + 1) }
            return
        }

        if (value.javaClass.isArray) {
            val n = java.lang.reflect.Array.getLength(value)
            for (i in 0 until n) {
                collectInto(java.lang.reflect.Array.get(value, i), out, seen, depth + 1)
            }
            return
        }

        if (value is Number || value is Boolean || value is Enum<*> || value is Class<*>) return
        if (!seen.add(value)) return

        val plan = planFor(value.javaClass)

        invoke(plan.packageName, value)
            ?.toString()
            ?.takeIf(::looksLikePackage)
            ?.let(out::add)

        if (value.javaClass.simpleName.contains("Intent", ignoreCase = true)) {
            invoke(plan.intentPackage, value)
                ?.toString()
                ?.takeIf(::looksLikePackage)
                ?.let(out::add)
        }

        for (method in plan.nestedGetters) {
            collectInto(invoke(method, value), out, seen, depth + 1)
        }
        for (field in plan.nestedFields) {
            collectInto(read(field, value), out, seen, depth + 1)
        }
    }

    private fun planFor(cls: Class<*>): AccessPlan =
        plans.computeIfAbsent(cls) { buildPlan(it) }

    private fun buildPlan(start: Class<*>): AccessPlan {
        val packageName = findMethod(start, "getPackageName")
        val intentPackage = if (start.simpleName.contains("Intent", ignoreCase = true)) {
            findMethod(start, "getPackage")
        } else {
            null
        }

        val methods = nestedGetterNames.mapNotNull { findMethod(start, it) }.toTypedArray()
        val fields = nestedFieldNames.mapNotNull { findField(start, it) }.toTypedArray()

        return AccessPlan(packageName, intentPackage, methods, fields)
    }

    private fun invoke(method: Method?, target: Any): Any? {
        if (method == null) return null
        return try {
            method.invoke(target)
        } catch (_: Throwable) {
            null
        }
    }

    private fun read(field: Field, target: Any): Any? = try {
        field.get(target)
    } catch (_: Throwable) {
        null
    }

    private fun findMethod(start: Class<*>, name: String): Method? {
        var cls: Class<*>? = start
        while (cls != null) {
            val method = cls.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }
            if (method != null) return method.apply { isAccessible = true }
            cls = cls.superclass
        }
        return null
    }

    private fun findField(start: Class<*>, name: String): Field? {
        var cls: Class<*>? = start
        while (cls != null) {
            try {
                return cls.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        return null
    }

    private fun looksLikePackage(value: String): Boolean =
        value.length in 3..255 && value.contains('.') && !value.contains('/') && !value.contains(' ')
}
