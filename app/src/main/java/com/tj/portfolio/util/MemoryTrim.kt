package com.tj.portfolio.util

/**
 * One place the Activity tells the rest of the app that Android wants memory back.
 *
 * WHY IT EXISTS. TJ asked that the app not hold on to RAM while it is not being used.
 * `Activity.onTrimMemory` is the callback Android provides for exactly that and the app
 * implemented none of it, so every cache built during a session stayed resident for as long
 * as the process did. The cost is not only the memory: `onTrimMemory` is the system saying
 * the app is a candidate to be killed, and the process that gives nothing back is the one
 * chosen first - which for this app means a cold start and a full reload the next time it is
 * opened, so being a good citizen here is also what keeps the app fast.
 *
 * WHY A REGISTRY RATHER THAN DIRECT CALLS. The things worth releasing live in a ViewModel
 * and in an object in `net/`, neither of which the Activity has a reference to at the moment
 * the callback arrives - `onTrimMemory` can fire before composition has produced a ViewModel,
 * and after it has been torn down. Listeners register themselves and are held WEAKLY, so a
 * ViewModel that has been cleared is collected normally and this can never be the thing that
 * keeps it alive.
 *
 * ONLY RECOMPUTABLE STATE MAY BE DROPPED FROM A LISTENER. Nothing the user typed or owns is
 * in play here: every transaction is a synchronous SQLite write, so there is no unsaved state
 * in the process to lose. A listener that dropped something unrecoverable would turn a
 * routine memory trim into data loss.
 */
object MemoryTrim {

    /** Mirrors `ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN` without importing the framework. */
    const val UI_HIDDEN = 20

    /**
     * The most severe level, for the legacy `onLowMemory` callback. Numerically equal to
     * `TRIM_MEMORY_COMPLETE`, which is deprecated - naming it here rather than at the call
     * site keeps the deprecation out of the build without suppressing it everywhere, and
     * listeners only ever compare levels numerically.
     */
    const val SEVERE = 80

    fun interface Listener {
        /** @param level Android's own trim level; higher means more urgent. */
        fun onTrim(level: Int)
    }

    private val listeners = ArrayList<java.lang.ref.WeakReference<Listener>>()

    private val installed = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Receive trim callbacks for the whole PROCESS, not just while an Activity exists.
     *
     * The Activity's own `onTrimMemory` is only delivered while an Activity instance is
     * alive. A backgrounded app whose Activity has been destroyed but whose process Android
     * has kept around - which is the normal state, not an edge case - got no trims at all and
     * held every cache until it was killed. Registering on the application context fixes
     * that: `ComponentCallbacks2` on a Context is delivered for as long as the process runs.
     *
     * Idempotent, and deliberately never unregistered - the registration's lifetime IS the
     * process, and unregistering would put back the gap this closes. Registered against the
     * application context so it cannot hold an Activity.
     */
    fun installProcessWide(appContext: android.content.Context) {
        if (installed.get()) return
        // Latched only AFTER a successful registration. Setting it first means a single
        // failed `registerComponentCallbacks` silently disables trims for the whole process,
        // including on every later Activity recreation.
        runCatching {
            appContext.registerComponentCallbacks(object : android.content.ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) = trim(level)
                override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
                @Deprecated("Superseded by onTrimMemory; still delivered on older devices")
                override fun onLowMemory() = trim(SEVERE)
            })
        }.onSuccess { installed.set(true) }
    }

    @Synchronized
    fun register(l: Listener) {
        listeners.removeAll { it.get() == null }
        if (listeners.none { it.get() === l }) listeners.add(java.lang.ref.WeakReference(l))
    }

    @Synchronized
    fun unregister(l: Listener) {
        listeners.removeAll { it.get() == null || it.get() === l }
    }

    /**
     * Fan the callback out. A listener that throws must not stop the others from releasing
     * their memory - the whole point of this path is that it runs when the system is short.
     */
    fun trim(level: Int) {
        // Snapshot under the lock, then call OUTSIDE it. A listener is free to do anything -
        // including registering another one - and holding the monitor across that call is how
        // a memory-pressure callback turns into a deadlock. Dead references are pruned here
        // as well as on register, so a listener that is never re-registered cannot leave an
        // empty WeakReference in the list forever.
        val snapshot = synchronized(this) {
            listeners.removeAll { it.get() == null }
            listeners.mapNotNull { it.get() }
        }
        snapshot.forEach { runCatching { it.onTrim(level) } }
    }

    /** For tests. */
    @Synchronized
    fun clear() = listeners.clear()
}
