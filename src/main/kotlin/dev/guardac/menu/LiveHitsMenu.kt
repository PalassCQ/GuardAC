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
 * This file contains code derived from:
 *   - Shard (© 2026 KaelusAI, https://github.com/KaelusAI/Shard)
 *   - Grim (© 2025 GrimAnticheat, https://github.com/GrimAnticheat/Grim)
 * All derived code is licensed under GPL-3.0.
 */

package dev.guardac.menu

import dev.guardac.GuardAC
import dev.guardac.player.GuardPlayer
import dev.guardac.player.HitFeed
import dev.guardac.util.Colors
import dev.guardac.util.Message
import dev.guardac.util.SafeName
import dev.guardac.util.TaskHandle
import org.bukkit.Bukkit
import org.bukkit.GameMode
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

class LiveHitsMenu(
    private val plugin: GuardAC,
    private val admin: Player,
) : Listener {

    private var page = 0
    private var task: TaskHandle? = null
    private val slotToPlayer = HashMap<Int, UUID>()

    private val inventory: Inventory = run {
        val title = Colors.translate(plugin.locale.get(Message.LIVE_MENU_TITLE))
        Bukkit.createInventory(null, INV_SIZE, title)
    }

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    fun open() {
        refresh()
        admin.openInventory(inventory)
        task = plugin.scheduler.entityTimer(
            admin, REFRESH_TICKS, REFRESH_TICKS,
            retired = Runnable { close() },
        ) { handle ->
            if (!admin.isOnline || admin.openInventory.topInventory !== inventory) {
                handle.cancel()
                task = null
                return@entityTimer
            }
            refresh()
        }
    }

    private fun close() {
        task?.cancel()
        task = null
        HandlerList.unregisterAll(this)
    }

    private fun activePlayers(): List<Pair<GuardPlayer, List<HitFeed.Sample>>> =
        plugin.playerDataManager.getAll()
            .asSequence()
            .filter { it.player.isOnline }
            .map { it to it.getHitFeed() }
            .filter { (_, feed) -> feed.isNotEmpty() }
            .sortedWith(
                compareByDescending<Pair<GuardPlayer, List<HitFeed.Sample>>> { (_, feed) ->
                    feed.maxOf { it.probability }
                }.thenByDescending { (_, feed) -> feed.last().epochMillis }
            )
            .toList()

    private fun refresh() {
        inventory.clear()
        slotToPlayer.clear()

        val active = activePlayers()
        val totalPages = ((active.size - 1) / ITEMS_PER_PAGE).coerceAtLeast(0)
        page = page.coerceIn(0, totalPages)
        val pageItems = active.drop(page * ITEMS_PER_PAGE).take(ITEMS_PER_PAGE)

        if (pageItems.isEmpty()) {
            inventory.setItem(EMPTY_SLOT, buildItem(
                Material.SUNFLOWER,
                plugin.locale.get(Message.MENU_EMPTY_TITLE),
                listOf(plugin.locale.get(Message.LIVE_MENU_EMPTY)),
            ))
        } else {
            pageItems.forEachIndexed { i, (gp, feed) ->
                inventory.setItem(i, buildHead(gp, feed))
                slotToPlayer[i] = gp.uuid
            }
        }

        buildControls(hasPrev = page > 0, hasNext = page < totalPages, total = active.size)
    }

    private fun buildHead(gp: GuardPlayer, feed: List<HitFeed.Sample>): ItemStack {
        val skull = ItemStack(Material.PLAYER_HEAD)
        val meta  = skull.itemMeta as? SkullMeta ?: return skull
        runCatching { meta.owningPlayer = Bukkit.getOfflinePlayer(gp.uuid) }

        val peak = feed.maxOf { it.probability }
        val headColor = plugin.monitorConfig.colorForProbability(peak * 100.0)
        meta.setDisplayName(Colors.translate("$headColor&l${gp.player.name}"))

        val lore = ArrayList<String>(feed.size + 6)
        lore.add("")
        lore.add(plugin.locale.get(Message.LIVE_MENU_HITS_HEADER, "count", feed.size.toString()))

        for (hit in feed.asReversed()) {
            val pct = hit.probability * 100.0
            lore.add(plugin.locale.get(
                Message.LIVE_MENU_HIT,
                "color", plugin.monitorConfig.colorForProbability(pct),
                "value", "%.4f".format(Locale.ROOT, hit.probability),
                "pct",   "%.0f".format(pct),
            ))
        }

        val avgPct = gp.avgProbability * 100.0
        lore.add("")
        lore.add(plugin.locale.get(
            Message.LIVE_MENU_AVG,
            "color", plugin.monitorConfig.colorForProbability(avgPct),
            "value", "%.0f".format(avgPct),
        ))
        lore.add(plugin.locale.get(Message.LIVE_MENU_VL, "value", gp.aiViolationLevel.toString()))
        lore.add(plugin.locale.get(
            Message.LIVE_MENU_LAST_HIT,
            "seconds", secondsSince(feed.last().epochMillis),
        ))
        lore.add("")
        lore.add(plugin.locale.get(Message.LIVE_MENU_CLICK))

        meta.lore = lore
        skull.itemMeta = meta
        return skull
    }

    private fun secondsSince(epochMillis: Long): String {
        val secs = ((System.currentTimeMillis() - epochMillis) / 1000L).coerceAtLeast(0L)
        return secs.toString()
    }

    private fun buildControls(hasPrev: Boolean, hasNext: Boolean, total: Int) {
        val border = buildItem(Material.BLACK_STAINED_GLASS_PANE, " ", emptyList())
        for (i in ITEMS_PER_PAGE until INV_SIZE) inventory.setItem(i, border)

        if (hasPrev) {
            inventory.setItem(PREV_SLOT, buildItem(
                Material.ARROW,
                plugin.locale.get(Message.SUSPECTS_MENU_PREV),
                listOf(plugin.locale.get(Message.MENU_PAGE, "page", page.toString())),
            ))
        }

        inventory.setItem(INFO_SLOT, buildItem(
            Material.NETHER_STAR,
            plugin.locale.get(Message.LIVE_MENU_INFO_TITLE),
            listOf(
                plugin.locale.get(Message.LIVE_MENU_INFO_COUNT, "count", total.toString()),
                plugin.locale.get(Message.LIVE_MENU_INFO_WINDOW,
                    "seconds", plugin.configManager.combatResetAfterSeconds.toString()),
            ),
        ))

        inventory.setItem(CLOSE_SLOT, buildItem(
            Material.BARRIER, plugin.locale.get(Message.SUSPECTS_MENU_CLOSE), emptyList(),
        ))

        if (hasNext) {
            inventory.setItem(NEXT_SLOT, buildItem(
                Material.ARROW,
                plugin.locale.get(Message.SUSPECTS_MENU_NEXT),
                listOf(plugin.locale.get(Message.MENU_PAGE, "page", (page + 2).toString())),
            ))
        }
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        if (event.inventory !== inventory) return
        event.isCancelled = true

        val viewer = event.whoClicked as? Player ?: return
        if (viewer.uniqueId != admin.uniqueId) return

        when (val slot = event.rawSlot) {
            PREV_SLOT  -> { page--; refresh() }
            NEXT_SLOT  -> { page++; refresh() }
            CLOSE_SLOT -> viewer.closeInventory()
            INFO_SLOT  -> refresh()
            else -> {
                if (slot < 0 || slot >= ITEMS_PER_PAGE) return
                slotToPlayer[slot]?.let { spectate(viewer, it) }
            }
        }
    }

    private fun spectate(viewer: Player, targetId: UUID) {
        val target = Bukkit.getPlayer(targetId)
        if (target == null || !target.isOnline) {
            viewer.sendMessage(plugin.locale.get(Message.MENU_PLAYER_OFFLINE, "player", "?"))
            return
        }
        viewer.closeInventory()

        val cmds = plugin.configManager.menuClickCommands
        if (cmds.isEmpty()) {
            plugin.scheduler.entity(viewer, Runnable {
                if (!viewer.isOnline) return@Runnable
                viewer.gameMode = GameMode.SPECTATOR
                plugin.scheduler.teleport(viewer, target.location)
            })
            return
        }

        if (!SafeName.isSafe(target.name) || !SafeName.isSafe(viewer.name)) {
            viewer.sendMessage(plugin.locale.get(Message.MENU_UNSAFE_NAME, "player", target.name))
            plugin.logger.warning("[Menu] Name '${target.name}' is not safe for menu console commands - click skipped.")
            return
        }

        cmds.forEach { raw ->
            val cmd = raw.replace("<admin>", viewer.name)
                .replace("<player>", target.name)
                .replace("<target>", target.name)
                .removePrefix("/")
            plugin.scheduler.global(Runnable {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)
            })
        }
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        if (event.inventory === inventory && event.player.uniqueId == admin.uniqueId) close()
    }

    private fun buildItem(material: Material, name: String, lore: List<String>): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        meta.setDisplayName(name)
        if (lore.isNotEmpty()) meta.lore = lore
        item.itemMeta = meta
        return item
    }

    private companion object {
        const val INV_SIZE       = 54
        const val ITEMS_PER_PAGE = 45
        const val PREV_SLOT      = INV_SIZE - 9
        const val INFO_SLOT      = INV_SIZE - 5
        const val CLOSE_SLOT     = INV_SIZE - 4
        const val NEXT_SLOT      = INV_SIZE - 1
        const val EMPTY_SLOT     = 22
        const val REFRESH_TICKS  = 20L
    }
}
