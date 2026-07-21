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
import dev.guardac.util.Colors
import dev.guardac.util.Message
import dev.guardac.util.SafeName
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

class SuspectsMenu(
    private val plugin: GuardAC,
    private val admin: Player,
) : Listener {

    private var page = 0
    private val inventory: Inventory = run {
        val title = color(plugin.locale.get(Message.SUSPECTS_MENU_TITLE))
        Bukkit.createInventory(null, INV_SIZE, title)
    }

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    fun open() {
        refresh()
        admin.openInventory(inventory)
    }

    private fun getSuspects() = plugin.playerDataManager.getAll()
        .filter { it.player.isOnline && it.aiBuffer > SUSPICIOUS_THRESHOLD }
        .sortedByDescending { it.aiBuffer }

    private fun refresh() {
        inventory.clear()

        val suspects   = getSuspects()
        val totalPages = ((suspects.size - 1) / ITEMS_PER_PAGE).coerceAtLeast(0)
        page           = page.coerceIn(0, totalPages)
        val pageItems  = suspects.drop(page * ITEMS_PER_PAGE).take(ITEMS_PER_PAGE)

        if (pageItems.isEmpty()) {
            inventory.setItem(22, buildItem(
                Material.SUNFLOWER,
                plugin.locale.get(Message.MENU_EMPTY_TITLE),
                listOf(plugin.locale.get(Message.SUSPECTS_MENU_EMPTY))
            ))
        } else {
            pageItems.forEachIndexed { i, gp -> inventory.setItem(i, buildSkullItem(gp)) }
        }

        val hasNext = page.compareTo(totalPages) < 0
        val hasPrev = page.compareTo(0) > 0
        buildBorderAndControls(hasNext, hasPrev)
    }

    private fun buildSkullItem(gp: GuardPlayer): ItemStack {
        val skull = ItemStack(Material.PLAYER_HEAD)
        val meta  = skull.itemMeta as? SkullMeta ?: return skull
        meta.owningPlayer = Bukkit.getOfflinePlayer(gp.uuid)

        val color = when {
            gp.aiBuffer > 40.0 -> "&c"
            gp.aiBuffer > 20.0 -> "&6"
            else               -> "&e"
        }
        val confPct = gp.lastAiProbability * 100.0
        val avgPct  = gp.avgProbability * 100.0

        meta.setDisplayName(color("$color&l${gp.player.name}"))
        meta.lore = listOf(
            "",
            plugin.locale.get(Message.MENU_SKULL_CONF, "color", color, "value", "%.0f".format(confPct)),
            plugin.locale.get(Message.MENU_SKULL_AVG, "color", color, "value", "%.0f".format(avgPct)),
            plugin.locale.get(Message.MENU_SKULL_VL, "value", gp.aiViolationLevel.toString()),
            plugin.locale.get(Message.MENU_SKULL_PING, "value", gp.player.ping.toString()),
            "",
            plugin.locale.get(Message.MENU_SKULL_CLICK),
        )
        skull.itemMeta = meta
        return skull
    }

    private fun buildBorderAndControls(hasNext: Boolean, hasPrev: Boolean) {
        val border = buildItem(Material.BLACK_STAINED_GLASS_PANE, " ", emptyList())
        for (i in ITEMS_PER_PAGE until INV_SIZE) inventory.setItem(i, border)

        if (hasPrev) {
            inventory.setItem(INV_SIZE - 9, buildItem(
                Material.ARROW,
                plugin.locale.get(Message.SUSPECTS_MENU_PREV),
                listOf(plugin.locale.get(Message.MENU_PAGE, "page", page.toString()))
            ))
        }

        inventory.setItem(INV_SIZE - 5, buildItem(
            Material.NETHER_STAR,
            plugin.locale.get(Message.SUSPECTS_MENU_REFRESH),
            listOf(plugin.locale.get(Message.MENU_REFRESH_LORE))
        ))

        inventory.setItem(INV_SIZE - 4, buildItem(
            Material.BARRIER,
            plugin.locale.get(Message.SUSPECTS_MENU_CLOSE),
            emptyList()
        ))

        if (hasNext) {
            inventory.setItem(INV_SIZE - 1, buildItem(
                Material.ARROW,
                plugin.locale.get(Message.SUSPECTS_MENU_NEXT),
                listOf(plugin.locale.get(Message.MENU_PAGE, "page", (page + 2).toString()))
            ))
        }
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        if (event.inventory !== inventory) return
        event.isCancelled = true

        val viewer = event.whoClicked as? Player ?: return
        if (viewer.uniqueId != admin.uniqueId) return

        val slot = event.rawSlot
        if (slot >= INV_SIZE) return

        when (slot) {
            INV_SIZE - 9 -> { page--; refresh() }
            INV_SIZE - 5 -> refresh()
            INV_SIZE - 4 -> viewer.closeInventory()
            INV_SIZE - 1 -> { page++; refresh() }
            else -> handleSkullClick(event, viewer)
        }
    }

    private fun handleSkullClick(event: InventoryClickEvent, viewer: Player) {
        val item = event.currentItem?.takeIf { it.type == Material.PLAYER_HEAD } ?: return
        val name = (item.itemMeta as? SkullMeta)?.owningPlayer?.name ?: return
        viewer.closeInventory()

        val cmds = plugin.configManager.menuClickCommands
        if (cmds.isEmpty()) {

            val target = Bukkit.getPlayerExact(name)
            if (target != null && target.isOnline) {
                viewer.gameMode = GameMode.SPECTATOR

                plugin.scheduler.teleport(viewer, target.location)
            } else {
                viewer.sendMessage(plugin.locale.get(Message.MENU_PLAYER_OFFLINE, "player", name))
            }
            return
        }

        if (!SafeName.isSafe(name) || !SafeName.isSafe(viewer.name)) {
            viewer.sendMessage(plugin.locale.get(Message.MENU_UNSAFE_NAME, "player", name))
            plugin.logger.warning("[Menu] Name '$name' is not safe for menu console commands - click skipped.")
            return
        }

        cmds.forEach { raw ->
            val cmd = raw.replace("<admin>", viewer.name)
                .replace("<player>", name)
                .replace("<target>", name)
                .removePrefix("/")
            plugin.scheduler.global(Runnable {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)
            })
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

    private fun color(s: String): String =
        Colors.translate(s)

    companion object {
        private const val INV_SIZE             = 54
        private const val ITEMS_PER_PAGE       = 45
        private const val SUSPICIOUS_THRESHOLD = 5.0
    }
}
