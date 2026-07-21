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

package dev.guardac.ai

import dev.guardac.sample.AimSample
import java.util.concurrent.CompletableFuture

interface AiTransport {
    val isEnabled: Boolean

    /**
     * True when the connection to the backend is currently in a failed state and
     * requests are being short-circuited (circuit breaker open). Transports with
     * no breaker report false. Used only for operator diagnostics (/guard health).
     */
    val circuitOpen: Boolean get() = false

    fun infer(ticks: Array<AimSample>, priority: Boolean = false): CompletableFuture<InferenceResult>

    fun reload()
    fun shutdown()
}
