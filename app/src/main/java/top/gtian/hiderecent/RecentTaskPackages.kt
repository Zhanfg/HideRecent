package top.gtian.hiderecent

import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Reflection-only package extraction for the several task container generations used by
 * AOSP Launcher3 / ColorOS Quickstep. Kept free of Android classes so the compatibility
 * matcher can be unit-tested on the host JVM.
 */
internal object RecentTaskPackages {
    private const val MAX_DEPTH = 5

    fun collect(root: Any?): Set<String> {
        if (root == null) return emptySet()
        val out = LinkedHashSet<String>()
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        visit(root, out, seen, 0)
        return out
    }

    fun shouldHide(root: Any?, hidden: Set<String>): Boolean {
        if (hidden.isEmpty()) return false
        return collect(root).any(hidden::contains)
    }

    private fun visit(value: Any?, out: MutableSet<String>, seen: MutableSet<Any>, depth: Int) {
        if (value == null || depth > MAX_DEPTH) return
        if (value is CharSequence) {
            val s = value.toString()
            if (looksLikePackage(s)) out += s
            return
        }
        if (value is Iterable<*>) {
            value.forEach { visit(it, out, seen, depth + 1) }
            return
        }
        if (value.javaClass.isArray) {
            val n = java.lang.reflect.Array.getLength(value)
            for (i in 0 until n) visit(java.lang.reflect.Array.get(value, i), out, seen, depth + 1)
            return
        }
        if (!seen.add(value)) return

        // Launcher3 Task exposes getPackageName().
        invokeNoArg(value, "getPackageName")?.toString()?.takeIf(::looksLikePackage)?.let(out::add)

        // ComponentName-like objects expose getPackageName().
        if (value.javaClass.simpleName.contains("ComponentName", ignoreCase = true)) {
            invokeNoArg(value, "getPackageName")?.toString()?.takeIf(::looksLikePackage)?.let(out::add)
        }

        // Intent-like objects: component first, explicit package second.
        if (value.javaClass.simpleName.contains("Intent", ignoreCase = true)) {
            visit(invokeNoArg(value, "getComponent"), out, seen, depth + 1)
            invokeNoArg(value, "getPackage")?.toString()?.takeIf(::looksLikePackage)?.let(out::add)
        }

        // GroupedTaskInfo generations and Launcher GroupTask containers.
        val getters = arrayOf(
            "getBaseGroupedTask", "getTaskInfo1", "getTaskInfo2", "getTaskInfoList",
            "getTasks", "getTask1", "getTask2", "getBaseIntent", "getBaseActivity",
            "getTopActivity", "getOrigActivity", "getRealActivity", "getTopComponent",
            "getComponent", "getSourceComponent", "getTaskInfo"
        )
        for (name in getters) visit(invokeNoArg(value, name), out, seen, depth + 1)

        // Public/private fields used across Android/OPlus releases.
        val fields = arrayOf(
            "task1", "task2", "mTask1", "mTask2", "tasks", "mTasks",
            "baseIntent", "mBaseIntent", "baseActivity", "topActivity", "origActivity",
            "realActivity", "componentName", "component", "sourceComponent",
            "taskInfo", "mTaskInfo", "key", "mKey"
        )
        for (name in fields) visit(readField(value, name), out, seen, depth + 1)
    }

    private fun invokeNoArg(target: Any, name: String): Any? = try {
        findMethod(target.javaClass, name)?.invoke(target)
    } catch (_: Throwable) {
        null
    }

    private fun readField(target: Any, name: String): Any? = try {
        findField(target.javaClass, name)?.get(target)
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
