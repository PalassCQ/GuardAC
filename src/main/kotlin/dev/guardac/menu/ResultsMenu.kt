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
import dev.guardac.util.Colors
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
import kotlin.math.roundToInt

/**
 * Read-only hit-history window: every AI window result is a cell, ten results
 * per glass pane, the pane dyed by the average probability of its bucket.
 * Hovering a pane lists the individual results (probability / model / age,
 * plus the source server when the history is network-wide).
 */
class ResultsMenu(
    private val plugin: GuardAC,
    private val viewer: Player,
    private val targetName: String,
    private val rows: List<Row>,
    private val showServer: Boolean = false,
) : Listener {

    /** One AI window result, from the local database or the key's network. */
    data class Row(
        val uuid: String,
        val name: String,
        val model: String,
        val server: String?,
        val probability: Double,
        val epochMillis: Long,
    )

    private val inventory: Inventory = run {
        val title = plugin.locale.get(Message.RESULTS_MENU_TITLE, "player", targetName)
        Bukkit.createInventory(null, INV_SIZE, title)
    }

    // Newest bucket first, reading order: top-left pane = the latest fight.
    private val buckets = rows.chunked(RESULTS_PER_PANE)
    private var page = 0

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    fun open() {
        build()
        viewer.openInventory(inventory)
    }

    private fun build() {
        inventory.clear()
        val border = buildItem(Material.GRAY_STAINED_GLASS_PANE, " ", emptyList())
        for (i in 0 until GRID_START) inventory.setItem(i, border)
        for (i in INV_SIZE - 9 until INV_SIZE) inventory.setItem(i, border)
        inventory.setItem(HEAD_SLOT, buildHead())

        val totalPages = ((buckets.size - 1) / PANES_PER_PAGE).coerceAtLeast(0)
        page = page.coerceIn(0, totalPages)
        val pageBuckets = buckets.drop(page * PANES_PER_PAGE).take(PANES_PER_PAGE)
        for ((i, bucket) in pageBuckets.withIndex()) {
            inventory.setItem(GRID_START + i, buildBucketPane(bucket))
        }

        if (page > 0) {
            inventory.setItem(PREV_SLOT, buildItem(
                Material.ARROW,
                plugin.locale.get(Message.SUSPECTS_MENU_PREV),
                listOf(plugin.locale.get(Message.MENU_PAGE, "page", page.toString())),
            ))
        }
        if (page < totalPages) {
            inventory.setItem(NEXT_SLOT, buildItem(
                Material.ARROW,
                plugin.locale.get(Message.SUSPECTS_MENU_NEXT),
                listOf(plugin.locale.get(Message.MENU_PAGE, "page", (page + 2).toString())),
            ))
        }
    }

    private fun buildHead(): ItemStack {
        val avg  = rows.sumOf { it.probability } / rows.size
        val peak = rows.maxOf { it.probability }

        val skull = ItemStack(Material.PLAYER_HEAD)
        val meta  = skull.itemMeta as? SkullMeta ?: return skull
        runCatching { meta.owningPlayer = Bukkit.getOfflinePlayer(UUID.fromString(rows.first().uuid)) }

        meta.setDisplayName(plugin.locale.get(Message.RESULTS_HEAD_TITLE, "player", targetName))
        meta.lore = listOf(
            "",
            plugin.locale.get(Message.RESULTS_HEAD_TOTAL, "count", rows.size.toString()),
            plugin.locale.get(Message.RESULTS_HEAD_AVG, "color", colorFor(avg), "avg", fmt(avg)),
            plugin.locale.get(Message.RESULTS_HEAD_PEAK, "color", colorFor(peak), "peak", fmt(peak)),
            plugin.locale.get(
                Message.RESULTS_HEAD_SPAN,
                "time", formatAge(System.currentTimeMillis() - rows.last().epochMillis),
            ),
        )
        skull.itemMeta = meta
        return skull
    }

    private fun buildBucketPane(bucket: List<Row>): ItemStack {
        val avg = bucket.sumOf { it.probability } / bucket.size
        val now = System.currentTimeMillis()

        val probs   = bucket.map { fmt(it.probability) }
        val models  = bucket.map { it.model.ifBlank { "Def" }.take(12) }
        val servers = if (showServer) bucket.map { (it.server ?: "-").take(12) } else null
        val times   = bucket.map { formatAge(now - it.epochMillis) }

        val hProb   = plugin.locale.get(Message.RESULTS_COL_PROB)
        val hModel  = plugin.locale.get(Message.RESULTS_COL_MODEL)
        val hServer = plugin.locale.get(Message.RESULTS_COL_SERVER)
        val hTime   = plugin.locale.get(Message.RESULTS_COL_TIME)

        val wProb   = colWidth(hProb, probs)
        val wModel  = colWidth(hModel, models)
        val wServer = servers?.let { colWidth(hServer, it) }

        val lore = ArrayList<String>(bucket.size + 2)

        val header = StringBuilder("&7").append(padPx(hProb, wProb)).append(SEP)
            .append("&7").append(padPx(hModel, wModel))
        if (wServer != null) header.append(SEP).append("&7").append(padPx(hServer, wServer))
        header.append(SEP).append("&7").append(hTime)
        lore.add(Colors.translate(header.toString()))

        // Thin strikethrough divider sized to the table (spaces are 4px each).
        val totalPx = wProb + wModel + (wServer ?: 0) + pxWidth(hTime) + SEP_PX * (if (wServer != null) 3 else 2)
        lore.add(Colors.translate("&8&m" + " ".repeat((totalPx / 4).coerceIn(8, 44))))

        for (i in bucket.indices) {
            val row = StringBuilder()
            row.append(colorFor(bucket[i].probability)).append(padPx(probs[i], wProb)).append(SEP)
            row.append("&7").append(padPx(models[i], wModel))
            if (wServer != null) row.append(SEP).append("&7").append(padPx(servers!![i], wServer))
            row.append(SEP).append("&7").append(times[i])
            lore.add(Colors.translate(row.toString()))
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

    // ------------------------------------------------------------------
    // Column alignment. Minecraft's font is NOT monospace: most glyphs advance
    // 6px, but '.'/':'/'i' are 2px and a space is 4px - so columns are padded
    // to pixel targets, never to character counts.
    // ------------------------------------------------------------------
    private fun pxWidth(s: String): Int = s.sumOf { c ->
        when (c) {
            '.', ',', ':', ';', 'i', '!', '|', '\'' -> 2
            'l'                                     -> 3
            ' ', 't', 'I', '[', ']'                 -> 4
            'f', 'k', '(', ')', '<', '>', '{', '}'  -> 5
            else                                    -> 6
        }.toInt()
    }

    private fun padPx(s: String, targetPx: Int): String {
        val need = targetPx - pxWidth(s)
        if (need <= 0) return s
        return s + " ".repeat((need / 4.0).roundToInt().coerceAtLeast(1))
    }

    private fun colWidth(header: String, values: List<String>): Int =
        (values.maxOfOrNull { pxWidth(it) } ?: 0).coerceAtLeast(pxWidth(header)) + COLUMN_GAP_PX

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
        if (event.inventory !== inventory) return
        event.isCancelled = true
        val clicker = event.whoClicked as? Player ?: return
        if (clicker.uniqueId != viewer.uniqueId) return
        when (event.rawSlot) {
            PREV_SLOT -> { page--; build() }
            NEXT_SLOT -> { page++; build() }
        }
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
        // Four middle rows hold panes; the top row (head) and the bottom row
        // (frame + page arrows) are chrome.
        private const val PANES_PER_PAGE   = 36
        private const val PREV_SLOT        = INV_SIZE - 9
        private const val NEXT_SLOT        = INV_SIZE - 1
        // Breathing room between columns; the "| " separator itself is 6px
        // ('|' 2px + space 4px, color codes are zero-width).
        private const val COLUMN_GAP_PX    = 12
        private const val SEP_PX           = 6

        private const val SEP = "&8| "

        /** Max results the window can show: 45 panes of 10 across pages. */
        const val CAPACITY = 450
    }
}
