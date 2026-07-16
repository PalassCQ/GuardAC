/*
 * This file is part of GuardAC - https://github.com/PalassCQ/GuardAC
 * Copyright (C) 2026 GuardAC
 *
 * GuardAC is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * GuardAC is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package dev.guardac.util

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitRunnable
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

fun interface TaskHandle {
    fun cancel()
}

/**
 * One scheduling API for both server families.
 *
 * On a regular server every method delegates to the classic BukkitScheduler with
 * exactly the call it replaced, so behaviour there is unchanged.
 *
 * Folia has no single main thread: work is owned by the region that holds the
 * chunk it touches. Each method below therefore says WHERE it runs, and callers
 * pick by what the work touches:
 *
 *   global  - plugin state, chat messages, console commands, ban lists
 *   entity  - one entity: teleport, potions, attributes, kick, its own location
 *   region  - a place in a world: particles, entity spawning, dropped items
 *   async   - network and database work, never touches world state
 *
 * Folia is reached purely by reflection, so nothing here links against classes a
 * 1.16 server does not have; the Folia branch is simply never taken there.
 */
class Scheduler(private val plugin: Plugin) {

    val folia: Boolean = runCatching {
        Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
        true
    }.getOrDefault(false)

    private var globalSched: Any? = null
    private var asyncSched: Any? = null
    private var regionSched: Any? = null

    private var mGlobalRun: Method? = null
    private var mGlobalDelayed: Method? = null
    private var mGlobalRate: Method? = null
    private var mAsyncNow: Method? = null
    private var mAsyncRate: Method? = null
    private var mRegionRun: Method? = null
    private var mRegionRate: Method? = null
    private var mEntitySched: Method? = null
    private var mEntityRun: Method? = null
    private var mEntityDelayed: Method? = null
    private var mEntityRate: Method? = null
    private var mCancel: Method? = null
    private var mTeleportAsync: Method? = null

    init {
        mTeleportAsync = runCatching {
            Entity::class.java.getMethod("teleportAsync", Location::class.java)
        }.getOrNull()
        if (folia) resolveFolia()
    }

    private fun resolveFolia() {
        val pl = Plugin::class.java
        val cons = Consumer::class.java
        val run = Runnable::class.java
        val loc = Location::class.java
        val unit = TimeUnit::class.java

        val globalCls = cls("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler")
        val asyncCls  = cls("io.papermc.paper.threadedregions.scheduler.AsyncScheduler")
        val regionCls = cls("io.papermc.paper.threadedregions.scheduler.RegionScheduler")
        val entityCls = cls("io.papermc.paper.threadedregions.scheduler.EntityScheduler")
        val taskCls   = cls("io.papermc.paper.threadedregions.scheduler.ScheduledTask")

        globalSched = Bukkit::class.java.getMethod("getGlobalRegionScheduler").invoke(null)
        asyncSched  = Bukkit::class.java.getMethod("getAsyncScheduler").invoke(null)
        regionSched = Bukkit::class.java.getMethod("getRegionScheduler").invoke(null)

        mGlobalRun     = globalCls.getMethod("run", pl, cons)
        mGlobalDelayed = globalCls.getMethod("runDelayed", pl, cons, Long::class.javaPrimitiveType)
        mGlobalRate    = globalCls.getMethod(
            "runAtFixedRate", pl, cons, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
        )
        mAsyncNow  = asyncCls.getMethod("runNow", pl, cons)
        mAsyncRate = asyncCls.getMethod(
            "runAtFixedRate", pl, cons, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, unit,
        )
        mRegionRun  = regionCls.getMethod("run", pl, loc, cons)
        mRegionRate = regionCls.getMethod(
            "runAtFixedRate", pl, loc, cons, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
        )
        mEntitySched   = Entity::class.java.getMethod("getScheduler")
        mEntityRun     = entityCls.getMethod("run", pl, cons, run)
        mEntityDelayed = entityCls.getMethod("runDelayed", pl, cons, run, Long::class.javaPrimitiveType)
        mEntityRate    = entityCls.getMethod(
            "runAtFixedRate", pl, cons, run, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
        )
        mCancel = taskCls.getMethod("cancel")
    }

    private fun cls(name: String): Class<*> = Class.forName(name)

    /** Folia rejects a delay or period below one tick; Bukkit accepts zero. */
    private fun ticks(v: Long): Long = if (v < 1L) 1L else v

    private fun handleOf(task: Any?): TaskHandle =
        if (task == null) TaskHandle { } else TaskHandle { runCatching { mCancel!!.invoke(task) } }

    private fun consumerOf(body: Runnable): Consumer<Any?> = Consumer { body.run() }

    private fun consumerOf(body: (TaskHandle) -> Unit): Consumer<Any?> =
        Consumer { st -> body(handleOf(st)) }

    // ---- global ----------------------------------------------------------

    fun global(task: Runnable) {
        if (!folia) {
            Bukkit.getScheduler().runTask(plugin, task)
            return
        }
        mGlobalRun!!.invoke(globalSched, plugin, consumerOf(task))
    }

    fun globalDelayed(delayTicks: Long, task: Runnable): TaskHandle {
        if (!folia) {
            val t = Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks)
            return TaskHandle { runCatching { t.cancel() } }
        }
        return handleOf(
            mGlobalDelayed!!.invoke(globalSched, plugin, consumerOf(task), ticks(delayTicks))
        )
    }

    fun globalTimer(delayTicks: Long, periodTicks: Long, task: (TaskHandle) -> Unit): TaskHandle {
        if (!folia) return paperTimer(delayTicks, periodTicks, async = false, body = task)
        return handleOf(
            mGlobalRate!!.invoke(
                globalSched, plugin, consumerOf(task), ticks(delayTicks), ticks(periodTicks),
            )
        )
    }

    // ---- async -----------------------------------------------------------

    fun async(task: Runnable) {
        if (!folia) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task)
            return
        }
        mAsyncNow!!.invoke(asyncSched, plugin, consumerOf(task))
    }

    fun asyncTimer(delayTicks: Long, periodTicks: Long, task: (TaskHandle) -> Unit): TaskHandle {
        if (!folia) return paperTimer(delayTicks, periodTicks, async = true, body = task)
        // The async scheduler takes real time, not ticks.
        return handleOf(
            mAsyncRate!!.invoke(
                asyncSched, plugin, consumerOf(task),
                ticks(delayTicks) * 50L, ticks(periodTicks) * 50L, TimeUnit.MILLISECONDS,
            )
        )
    }

    // ---- entity ----------------------------------------------------------

    /** [retired] runs instead of [task] when the entity is gone by execution time. */
    fun entity(entity: Entity, task: Runnable, retired: Runnable? = null) {
        if (!folia) {
            Bukkit.getScheduler().runTask(plugin, task)
            return
        }
        val sched = mEntitySched!!.invoke(entity)
        val res = mEntityRun!!.invoke(
            sched, plugin, consumerOf(task), Runnable { retired?.run() },
        )
        // null => the entity was already removed, so Folia never scheduled it and
        // never calls the retired hook itself.
        if (res == null) retired?.run()
    }

    fun entityDelayed(
        entity: Entity, delayTicks: Long, task: Runnable, retired: Runnable? = null,
    ): TaskHandle {
        if (!folia) {
            val t = Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks)
            return TaskHandle { runCatching { t.cancel() } }
        }
        val sched = mEntitySched!!.invoke(entity)
        val res = mEntityDelayed!!.invoke(
            sched, plugin, consumerOf(task), Runnable { retired?.run() }, ticks(delayTicks),
        )
        if (res == null) retired?.run()
        return handleOf(res)
    }

    fun entityTimer(
        entity: Entity, delayTicks: Long, periodTicks: Long,
        retired: Runnable? = null, task: (TaskHandle) -> Unit,
    ): TaskHandle {
        if (!folia) return paperTimer(delayTicks, periodTicks, async = false, body = task)
        val sched = mEntitySched!!.invoke(entity)
        val res = mEntityRate!!.invoke(
            sched, plugin, consumerOf(task), Runnable { retired?.run() },
            ticks(delayTicks), ticks(periodTicks),
        )
        if (res == null) retired?.run()
        return handleOf(res)
    }

    // ---- region ----------------------------------------------------------

    fun region(location: Location, task: Runnable) {
        if (!folia) {
            Bukkit.getScheduler().runTask(plugin, task)
            return
        }
        mRegionRun!!.invoke(regionSched, plugin, location, consumerOf(task))
    }

    fun regionTimer(
        location: Location, delayTicks: Long, periodTicks: Long, task: (TaskHandle) -> Unit,
    ): TaskHandle {
        if (!folia) return paperTimer(delayTicks, periodTicks, async = false, body = task)
        return handleOf(
            mRegionRate!!.invoke(
                regionSched, plugin, location, consumerOf(task),
                ticks(delayTicks), ticks(periodTicks),
            )
        )
    }

    // ---- misc ------------------------------------------------------------

    /** Folia only moves entities asynchronously; elsewhere this is a plain teleport. */
    fun teleport(entity: Entity, to: Location) {
        val m = mTeleportAsync
        if (folia && m != null) {
            runCatching { m.invoke(entity, to) }
            return
        }
        runCatching { entity.teleport(to) }
    }

    private class PaperTimer(private val body: (TaskHandle) -> Unit) : BukkitRunnable() {
        // Handed to the body so a repeating task can stop itself, exactly like
        // calling cancel() inside the BukkitRunnable this replaced.
        private val self = TaskHandle { runCatching { this@PaperTimer.cancel() } }
        override fun run() = body(self)
    }

    private fun paperTimer(
        delayTicks: Long, periodTicks: Long, async: Boolean, body: (TaskHandle) -> Unit,
    ): TaskHandle {
        val runnable = PaperTimer(body)
        val task = if (async) runnable.runTaskTimerAsynchronously(plugin, delayTicks, periodTicks)
                   else runnable.runTaskTimer(plugin, delayTicks, periodTicks)
        return TaskHandle { runCatching { task.cancel() } }
    }
}
