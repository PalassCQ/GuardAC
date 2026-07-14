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

package dev.guardac.player

import dev.guardac.GuardAC
import dev.guardac.combat.SuppressionStage
import dev.guardac.data.TickData
import dev.guardac.player.state.CombatState
import dev.guardac.player.state.RotationState
import dev.guardac.util.GeyserUtil
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max

class GuardPlayer(
    val uuid: UUID,
    val player: Player,
    private val plugin: GuardAC,
) {
    val rotation   = RotationState()
    val combat     = CombatState()

    val joinTime: Long = System.currentTimeMillis()

    // What the client reports itself as (minecraft:brand); null until it arrives.
    @Volatile var clientBrand: String? = null

    private val sequenceSize get() = plugin.configManager.aiSequence
    private val tickBuffer   = ArrayDeque<TickData>(plugin.configManager.aiSequence * 2)
    private var ticksSinceLastSend = 0

    var idleTickCount: Int = 0
        private set
    var totalTickCount: Int = 0
        private set

    val isRiding: Boolean
        get() = player.isInsideVehicle


    private enum class TeleportPhase { NONE, WAITING, JUST_CONFIRMED }
    @Volatile private var teleportPhase: TeleportPhase = TeleportPhase.NONE
    @Volatile private var pendingTeleportId: Int? = null

    fun markTeleportSent(id: Int) {
        pendingTeleportId = id
        teleportPhase = TeleportPhase.WAITING
    }

    fun confirmTeleport(id: Int) {
        if (pendingTeleportId == id) {
            pendingTeleportId = null
            teleportPhase = TeleportPhase.JUST_CONFIRMED
        }
    }

    fun consumeTeleportGate(): Boolean = when (teleportPhase) {
        TeleportPhase.NONE -> false
        TeleportPhase.WAITING -> true
        TeleportPhase.JUST_CONFIRMED -> { teleportPhase = TeleportPhase.NONE; true }
    }

    // Rolling record of aim movement over the last AIM_ACTIVITY_WINDOW rotation
    // ticks. Written on every processed movement packet (zeros included), so an
    // attack can be gated on "was the camera actually moving just now" - a hit
    // thrown with a near-static crosshair carries no useful signal.
    private val recentAim = FloatArray(AIM_ACTIVITY_WINDOW)
    private var recentAimIdx = 0

    fun noteAimActivity(deltaYaw: Float, deltaPitch: Float) {
        recentAim[recentAimIdx] = abs(deltaYaw) + abs(deltaPitch)
        recentAimIdx = (recentAimIdx + 1) % recentAim.size
    }

    fun recentAimSum(): Double {
        var sum = 0.0
        for (v in recentAim) sum += v
        return sum
    }

    // Last time the client sent a position-changing packet. Lets the attack gate
    // distinguish a standing player (barely turns while clicking) from a moving
    // one (real strafing PvP always comes with camera work).
    @Volatile private var lastMoveMs: Long = 0L

    fun noteMovement() {
        lastMoveMs = System.currentTimeMillis()
    }

    val isMovingRecently: Boolean
        get() = System.currentTimeMillis() - lastMoveMs < MOVE_RECENT_MS

    // Network-jitter detector. A healthy client sends rotation packets every
    // ~50ms; a lagging one delivers them in bursts (a stall, then a catch-up
    // clump). Those bursts distort the per-tick movement deltas and read as
    // inhuman aim - the number-one source of false flags on laggy players.
    // The signal only DAMPENS the verdict (see addAiProbability), it never
    // skips analysis: the client controls its own jitter and ping, so a hard
    // skip would let a fake-lag cheat stay invisible forever.
    @Volatile private var lastRotationNanos: Long = 0L
    @Volatile private var unstableTicks: Int = 0

    fun recordRotationTiming(nowNanos: Long) {
        val last = lastRotationNanos
        lastRotationNanos = nowNanos
        if (last == 0L) return
        // Only BURSTS count. A long gap is just a player not moving the camera
        // (idle, straight-line walk) - counting stalls would wrongly skip the
        // first combat window of every calm player. A lagging client always
        // betrays itself with catch-up clumps of packets.
        val gapMs = (nowNanos - last) / 1_000_000
        if (gapMs < UNSTABLE_GAP_MIN_MS) unstableTicks++
    }

    /** Unstable packet gaps accumulated since the last read; resets on read. */
    fun consumeUnstableTicks(): Int {
        val v = unstableTicks
        unstableTicks = 0
        return v
    }

    val lastMonitorHitMs: AtomicLong = AtomicLong(0L)
    val lastAlertMs: AtomicLong      = AtomicLong(0L)
    val lastSuspiciousMs: AtomicLong = AtomicLong(0L)

    private val probHistory    = ArrayDeque<Double>(AVG_WINDOW)
    private var probHistorySum = 0.0

    val avgProbability: Double
        get() = if (probHistory.isEmpty()) 0.0 else probHistorySum / probHistory.size

    var peakProbability: Double = 0.0
        private set

    var highProbCount: Int = 0
        private set

    private val hitProbHistory = ArrayDeque<Double>(HIT_HISTORY_SIZE)

    @Synchronized
    fun getHitProbHistory(): List<Double> = hitProbHistory.toList()

    fun onTick(tick: TickData, recordForAi: Boolean) {
        totalTickCount++
        if (abs(tick.deltaYaw) < IDLE_DELTA_THRESHOLD && abs(tick.deltaPitch) < IDLE_DELTA_THRESHOLD) {
            idleTickCount++
        }

        if (!plugin.configManager.aiContinuous && !recordForAi) {
            if (tickBuffer.isNotEmpty()) tickBuffer.clear()
            ticksSinceLastSend = 0
            return
        }

        tickBuffer.addLast(tick)
        while (tickBuffer.size > sequenceSize * 2) tickBuffer.removeFirst()
        ticksSinceLastSend++
    }

    fun pollSequence(): Array<TickData>? {
        if (ticksSinceLastSend < plugin.configManager.aiStep) return null
        if (tickBuffer.size < sequenceSize) return null
        ticksSinceLastSend = 0
        return tickBuffer.takeLast(sequenceSize).toTypedArray()
    }

    var aiBuffer: Double = 0.0
        private set
    var aiViolationLevel: Int = 0
        private set
    var lastAiProbability: Double = 0.0
        private set
    val totalAiFlags: AtomicInteger = AtomicInteger(0)

    private var fpBaseline = 0.0
    private var fpRecent   = 0.0
    private var fpHits     = 0
    private val lastDriftMs = AtomicLong(0L)
    val fingerprintBaseline: Double get() = fpBaseline
    val fingerprintRecent: Double   get() = fpRecent

    private fun updateFingerprint(p: Double) {
        fpHits++
        if (fpHits == 1) { fpRecent = p; fpBaseline = p; return }
        fpRecent   += FP_FAST * (p - fpRecent)
        fpBaseline += FP_SLOW * (p - fpBaseline)
    }

    fun checkFingerprintDrift(): Boolean {
        val cfg = plugin.configManager
        if (!cfg.fingerprintEnabled) return false
        if (fpHits < cfg.fingerprintWarmup) return false
        if (fpRecent < cfg.fingerprintMinRecent) return false
        if (fpRecent - fpBaseline < cfg.fingerprintDriftMargin) return false
        val now = System.currentTimeMillis()
        val last = lastDriftMs.get()
        if (now - last < FP_THROTTLE_MS) return false
        return lastDriftMs.compareAndSet(last, now)
    }

    val isBedrock: Boolean by lazy { GeyserUtil.isBedrock(uuid) }

    private fun updateProbStats(probability: Double) {
        probHistory.addLast(probability)
        probHistorySum += probability
        while (probHistory.size > AVG_WINDOW) {
            probHistorySum -= probHistory.removeFirst()
        }
        if (probability > peakProbability) peakProbability = probability
        if (probability >= HIGH_PROB_THRESHOLD) highProbCount++
    }

    @Synchronized
    fun addAiProbability(probability: Double, lagDistorted: Boolean = false) {

        maybeResetStaleCombat()

        lastAiProbability = probability
        updateProbStats(probability)
        // A lag-distorted window must not teach the personal aim fingerprint:
        // its probability reflects the network, not the hand.
        if (!lagDistorted) updateFingerprint(probability)

        hitProbHistory.addLast(probability)
        while (hitProbHistory.size > HIT_HISTORY_SIZE) hitProbHistory.removeFirst()

        val cfg = plugin.configManager
        aiBuffer = when {
            probability > CHEAT_THRESHOLD -> {
                val excess = probability - CHEAT_THRESHOLD
                val gain   = excess * cfg.aiBufferMultiplier * (1.0 + excess * CONFIDENCE_WEIGHT_FACTOR)
                // Under lag the verdict still counts, just slower: honest lag
                // spikes decay away between fights, a real cheater keeps
                // climbing no matter how hard he fakes his connection.
                aiBuffer + gain * (if (lagDistorted) LAG_GAIN_SCALE else 1.0)
            }
            probability < LEGIT_THRESHOLD -> max(0.0, aiBuffer - cfg.aiBufferDecrease)
            else                           -> aiBuffer
        }

        applySuppression(probability)
    }

    private fun maybeResetStaleCombat() {
        val cfg = plugin.configManager
        if (!cfg.combatResetEnabled) return
        val lastMs = combat.lastAttackMs
        if (lastMs == 0L) return
        if (System.currentTimeMillis() - lastMs < cfg.combatResetAfterSeconds * 1000L) return
        if (aiBuffer == 0.0 && probHistory.isEmpty() && hitProbHistory.isEmpty()) return
        aiBuffer = 0.0
        probHistory.clear(); probHistorySum = 0.0
        hitProbHistory.clear()
        peakProbability = 0.0
        highProbCount = 0
        attackSpeedPenalty = 0.0
        isolateUntilMs = 0L
    }

    @Volatile var attackSpeedPenalty: Double = 0.0
        private set
    @Volatile private var suppressionSetMs: Long = 0L
    @Volatile private var lastFlagMs: Long = 0L
    @Volatile private var isolateUntilMs: Long = 0L

    val isIsolated: Boolean get() = System.currentTimeMillis() < isolateUntilMs

    val suppressionStage: SuppressionStage
        get() = when {
            isIsolated                        -> SuppressionStage.ISOLATE
            currentAttackSpeedPenalty() > 0.0 -> SuppressionStage.DAMPEN
            else                              -> SuppressionStage.NONE
        }

    private fun applySuppression(probability: Double) {
        val cfg = plugin.configManager
        if (!cfg.suppressionEnabled) {
            attackSpeedPenalty = 0.0
            return
        }
        val thr = cfg.suppressionStartProbability
        if (probability < thr || thr >= 1.0) return
        val ratio = ((probability - thr) / (1.0 - thr)).coerceIn(0.0, 1.0)
        attackSpeedPenalty = (ratio * cfg.suppressionMaxAttackSpeedPercent / 100.0).coerceIn(0.0, 1.0)
        suppressionSetMs = System.currentTimeMillis()
    }

    fun currentAttackSpeedPenalty(): Double {
        if (attackSpeedPenalty <= 0.0) return 0.0
        val resetMs = plugin.configManager.suppressionResetSeconds * 1000L
        if (resetMs > 0 && System.currentTimeMillis() - suppressionSetMs > resetMs) {
            attackSpeedPenalty = 0.0
            return 0.0
        }
        return attackSpeedPenalty
    }

    private fun maybeEscalateToIsolate() {
        val cfg = plugin.configManager
        if (!cfg.suppressionEnabled || !cfg.suppressionIsolateEnabled) return
        val now = System.currentTimeMillis()
        if (lastFlagMs != 0L && now - lastFlagMs <= cfg.suppressionIsolateWindowMs) {
            isolateUntilMs = now + cfg.suppressionIsolateDurationMs
        }
        lastFlagMs = now
    }

    @Synchronized
    fun restorePersisted(buffer: Double, vl: Int) {
        if (buffer > aiBuffer) aiBuffer = buffer
        if (vl > aiViolationLevel) aiViolationLevel = vl
    }

    @Synchronized
    fun flagAi(): Boolean {
        val cfg = plugin.configManager
        if (aiBuffer < cfg.aiBufferFlag) return false
        maybeEscalateToIsolate()
        aiViolationLevel++
        totalAiFlags.incrementAndGet()
        aiBuffer = cfg.aiBufferResetOnFlag
        return true
    }

    @Synchronized
    fun resetAi() {
        aiBuffer          = 0.0
        aiViolationLevel  = 0
        lastAiProbability = 0.0
        peakProbability   = 0.0
        highProbCount     = 0
        probHistory.clear()
        probHistorySum    = 0.0
        hitProbHistory.clear()
        fpBaseline = 0.0; fpRecent = 0.0; fpHits = 0
        attackSpeedPenalty = 0.0
        isolateUntilMs     = 0L
        lastFlagMs         = 0L
    }

    private val checkVL = ConcurrentHashMap<String, Int>(4)

    fun incrementVL(checkName: String): Int =
        checkVL.merge(checkName, 1, Int::plus)!!

    fun getVL(checkName: String): Int =
        checkVL.getOrDefault(checkName, 0)

    fun resetVL(checkName: String) =
        checkVL.remove(checkName)

    fun resetAllVL() {
        checkVL.clear()
        resetAi()
    }

    @Synchronized
    fun decayVl(amount: Int) {
        checkVL.replaceAll { _, v -> (v - amount).coerceAtLeast(0) }
        checkVL.entries.removeIf { it.value <= 0 }
        if (aiViolationLevel > 0) {
            aiViolationLevel = (aiViolationLevel - amount).coerceAtLeast(0)
        }
    }

    val isExempt: Boolean
        get() = !player.isOnline
            || player.hasPermission("guardac.bypass")
            || plugin.exemptManager.isExempt(uuid)

    private companion object {
        const val AIM_ACTIVITY_WINDOW      = 10
        const val MOVE_RECENT_MS           = 500L
        // Vanilla rotation cadence is ~50ms; way under = a catch-up burst.
        // Internal constant on purpose - not a public knob.
        const val UNSTABLE_GAP_MIN_MS      = 15L
        const val CHEAT_THRESHOLD          = 0.90
        const val LEGIT_THRESHOLD          = 0.10
        // Buffer-gain multiplier for windows that arrived visibly lag-distorted
        // (packet bursts + high ping). 0.5 = suspicion builds at half speed.
        const val LAG_GAIN_SCALE           = 0.5
        const val IDLE_DELTA_THRESHOLD     = 0.05f
        const val AVG_WINDOW               = 10
        const val HIT_HISTORY_SIZE         = 5
        const val HIGH_PROB_THRESHOLD      = 0.70
        const val CONFIDENCE_WEIGHT_FACTOR = 2.0

        const val FP_FAST                  = 0.25
        const val FP_SLOW                  = 0.03
        const val FP_THROTTLE_MS           = 30_000L
    }
}
