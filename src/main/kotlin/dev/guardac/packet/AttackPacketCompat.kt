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

package dev.guardac.packet

import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon
import java.lang.reflect.Constructor
import java.lang.reflect.Method

object AttackPacketCompat {

    private const val WRAPPER = "com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack"

    private val type: PacketTypeCommon? = runCatching {
        PacketType.Play.Client::class.java.getField("ATTACK").get(null) as? PacketTypeCommon
    }.getOrNull()

    private val wrapperCtor: Constructor<*>? = runCatching {
        Class.forName(WRAPPER).getConstructor(PacketReceiveEvent::class.java)
    }.getOrNull()

    private val entityIdGetter: Method? = runCatching {
        Class.forName(WRAPPER).getMethod("getEntityId")
    }.getOrNull()

    val supported: Boolean = type != null && wrapperCtor != null && entityIdGetter != null

    fun isAttack(packetType: PacketTypeCommon?): Boolean = type != null && packetType == type

    fun entityIdOf(event: PacketReceiveEvent): Int? = runCatching {
        entityIdGetter?.invoke(wrapperCtor?.newInstance(event)) as? Int
    }.getOrNull()
}
