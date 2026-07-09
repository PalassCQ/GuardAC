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

package dev.guardac.alert

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Envelope for cross-server alerts over the proxy's plugin-messaging bridge.
 *
 * Custom channels are NOT routed between servers by BungeeCord/Velocity - they
 * only travel server<->client. The one transport a standard proxy does route is
 * the "BungeeCord" channel with the Forward subcommand: the proxy consumes the
 * envelope and re-delivers `subchannel + payload` to every other server.
 * Works on BungeeCord out of the box and on Velocity with its default
 * bungee-plugin-message-channel compatibility.
 */
object CrossServerCodec {

    /** Channel both sides register. The proxy owns and routes it. */
    const val PROXY_CHANNEL = "BungeeCord"

    /** Our subchannel inside Forward - filters out other plugins' traffic. */
    const val SUBCHANNEL = "guardac:alert"

    /** Wraps a payload into a Forward-to-ALL envelope for sending. */
    fun encode(payload: String): ByteArray {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val buf = ByteArrayOutputStream()
        DataOutputStream(buf).use { out ->
            out.writeUTF("Forward")
            out.writeUTF("ALL")
            out.writeUTF(SUBCHANNEL)
            out.writeShort(bytes.size)
            out.write(bytes)
        }
        return buf.toByteArray()
    }

    /**
     * Extracts the payload from a message delivered by the proxy
     * (`subchannel + length + data` - Forward/ALL are consumed proxy-side).
     * Returns null for other plugins' subchannels or malformed data.
     */
    fun decode(message: ByteArray): String? {
        return try {
            DataInputStream(ByteArrayInputStream(message)).use { input ->
                if (input.readUTF() != SUBCHANNEL) return null
                val len = input.readUnsignedShort()
                if (len <= 0 || len > MAX_PAYLOAD_BYTES || len > input.available()) return null
                val data = ByteArray(len)
                input.readFully(data)
                String(data, Charsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        }
    }

    const val MAX_PAYLOAD_BYTES = 512
}
