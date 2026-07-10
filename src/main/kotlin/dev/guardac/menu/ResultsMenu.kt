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

package dev.guardac.menu

import dev.guardac.GuardAC
import dev.guardac.history.PunishmentHistory
import dev.guardac.util.Message
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Read-only hit-history window: every AI window result is a cell, ten results
 * per glass pane, the pane dyed by the average probability of its bucket.
 * Hovering a pane lists the individual results (probability / model / age).
 */
class ResultsMenu(
    private val plugin: GuardAC,
    private val viewer: Player,
    private val targetName: String,
    private val results: List<PunishmentHistory.AiResult>,
) : Listener {

    private val inventory: Inventory = run {
        val title = plugin.locale.get(Message.RESULTS_MENU_TITLE, "player", targetName)
        Bukkit.createInventory(null, INV_SIZE, title)
    }

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    fun open() {
        build()
        viewer.openInventory(inventory)
    }

    private fun build() {
        val border = buildItem(Material.BLACK_STAINED_GLASS_PANE, " ", emptyList())
        for (i in 0 until GRID_START) inventory.setItem(i, border)
        inventory.setItem(HEAD_SLOT, buildHead())

        // Newest bucket first, reading order: top-left pane = the latest fight.
        val buckets = results.chunked(RESULTS_PER_PANE)
        for ((i, bucket) in buckets.withIndex()) {
            val slot = GRID_START + i
            if (slot >= INV_SIZE) break
            inventory.setItem(slot, buildBucketPane(bucket))
        }
    }

    private fun buildHead(): ItemStack {
        val avg  = results.sumOf { it.probability } / results.size
        val peak = results.maxOf { it.probability }

        val skull = ItemStack(Material.PLAYER_HEAD)
        val meta  = skull.itemMeta as? SkullMeta ?: return skull
        runCatching { meta.owningPlayer = Bukkit.getOfflinePlayer(UUID.fromString(results.first().uuid)) }

        meta.setDisplayName(plugin.locale.get(Message.RESULTS_HEAD_TITLE, "player", targetName))
        meta.lore = listOf(
            "",
            plugin.locale.get(Message.RESULTS_HEAD_TOTAL, "count", results.size.toString()),
            plugin.locale.get(Message.RESULTS_HEAD_AVG, "color", colorFor(avg), "avg", fmt(avg)),
            plugin.locale.get(Message.RESULTS_HEAD_PEAK, "color", colorFor(peak), "peak", fmt(peak)),
            plugin.locale.get(
                Message.RESULTS_HEAD_SPAN,
                "time", formatAge(System.currentTimeMillis() - results.last().epochMillis),
            ),
        )
        skull.itemMeta = meta
        return skull
    }

    private fun buildBucketPane(bucket: List<PunishmentHistory.AiResult>): ItemStack {
        val avg  = bucket.sumOf { it.probability } / bucket.size
        val now  = System.currentTimeMillis()

        val lore = ArrayList<String>(bucket.size + 2)
        lore.add(plugin.locale.get(Message.RESULTS_LORE_HEADER))
        for (r in bucket) {
            lore.add(plugin.locale.get(
                Message.RESULTS_LORE_ROW,
                "color", colorFor(r.probability),
                "prob",  fmt(r.probability),
                "model", r.model,
                "time",  formatAge(now - r.epochMillis),
            ))
        }

        return buildItem(
            materialFor(avg),
            plugin.locale.get(Message.RESULTS_PANE_TITLE, "color", colorFor(avg), "avg", fmt(avg)),
            lore,
        )
    }

    private fun materialFor(p: Double): Material = when {
        p < 0.25 -> Material.LIME_STAINED_GLASS_PANE
        p < 0.50 -> Material.YELLOW_STAINED_GLASS_PANE
        p < 0.75 -> Material.ORANGE_STAINED_GLASS_PANE
        else     -> Material.RED_STAINED_GLASS_PANE
    }

    private fun colorFor(p: Double): String = when {
        p < 0.25 -> "&a"
        p < 0.50 -> "&e"
        p < 0.75 -> "&6"
        else     -> "&c"
    }

    // Dot decimal separator regardless of the server's system locale.
    private fun fmt(p: Double): String = "%.4f".format(Locale.ROOT, p)

    private fun formatAge(ms: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms)
        val hours   = TimeUnit.MILLISECONDS.toHours(ms)
        val days    = TimeUnit.MILLISECONDS.toDays(ms)
        val d = plugin.locale.get(Message.UNIT_DAYS)
        val h = plugin.locale.get(Message.UNIT_HOURS)
        val m = plugin.locale.get(Message.UNIT_MINUTES)
        return when {
            days > 0    -> "$days$d ${hours % 24}$h"
            hours > 0   -> "$hours$h ${minutes % 60}$m"
            minutes > 0 -> "$minutes$m"
            else        -> plugin.locale.get(Message.RESULTS_TIME_NOW)
        }
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        if (event.inventory === inventory) event.isCancelled = true
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        if (event.inventory === inventory) HandlerList.unregisterAll(this)
    }

    private fun buildItem(material: Material, name: String, lore: List<String>): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        meta.setDisplayName(name)
        if (lore.isNotEmpty()) meta.lore = lore
        item.itemMeta = meta
        return item
    }

    companion object {
        private const val INV_SIZE         = 54
        private const val GRID_START       = 9
        private const val HEAD_SLOT        = 4
        private const val RESULTS_PER_PANE = 10

        /** Max results the window can show: 45 panes of 10. */
        const val CAPACITY = (INV_SIZE - GRID_START) * RESULTS_PER_PANE
    }
}
