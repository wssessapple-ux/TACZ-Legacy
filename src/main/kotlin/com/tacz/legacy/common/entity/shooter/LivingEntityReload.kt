package com.tacz.legacy.common.entity.shooter

import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.api.entity.ReloadState
import com.tacz.legacy.api.entity.ShootResult
import com.tacz.legacy.api.event.GunReloadEvent
import com.tacz.legacy.api.item.IGun
import com.tacz.legacy.common.application.refit.LegacyGunRefitRuntime
import com.tacz.legacy.common.network.TACZNetworkHandler
import com.tacz.legacy.common.network.message.event.ServerMessageReload
import com.tacz.legacy.common.network.message.event.ServerMessageShootState
import com.tacz.legacy.common.resource.BoltType
import com.tacz.legacy.common.resource.GunCombatData
import com.tacz.legacy.common.resource.GunDataAccessor
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.relauncher.Side
import org.luaj.vm2.lib.jse.CoerceJavaToLua

/**
 * 服务端换弹逻辑。与上游 TACZ LivingEntityReload 行为一致。
 * 支持脚本 hook：start_reload / tick_reload / interrupt_reload。
 */
public class LivingEntityReload(
    private val shooter: EntityLivingBase,
    private val data: ShooterDataHolder,
    private val draw: LivingEntityDrawGun,
) {
    /**
     * 发起换弹操作。
     */
    public fun reload() {
        val supplier = data.currentGunItem ?: run {
            TACZLegacy.logger.info("[ReloadDebug] reload() abort: no currentGunItem"); return
        }
        val currentGunItem = supplier.get()
        val iGun = currentGunItem.item as? IGun ?: run {
            TACZLegacy.logger.info("[ReloadDebug] reload() abort: held item not IGun"); return
        }
        val gunId = iGun.getGunId(currentGunItem)
        val gunData = GunDataAccessor.getGunData(gunId) ?: run {
            TACZLegacy.logger.info("[ReloadDebug] reload() abort: no gunData gun={}", gunId); return
        }

        val currentAmmo = iGun.getCurrentAmmoCount(currentGunItem)
        val hasBulletInBarrel = iGun.hasBulletInBarrel(currentGunItem)
        val maxAmmo = LegacyGunRefitRuntime.computeAmmoCapacity(currentGunItem).coerceAtLeast(0)

        // 已经在换弹中
        if (data.reloadStateType.isReloading()) {
            TACZLegacy.logger.info("[ReloadDebug] reload() abort gun={} already reloading", gunId); return
        }
        // 还没有完成切枪
        if (draw.getDrawCoolDown() != 0L) {
            TACZLegacy.logger.info("[ReloadDebug] reload() abort gun={} drawCooldown={}", gunId, draw.getDrawCoolDown()); return
        }
        // 在拉栓
        if (data.isBolting) {
            TACZLegacy.logger.info("[ReloadDebug] reload() abort gun={} bolting", gunId); return
        }

        // 满弹判定（考虑扩容弹夹容量）
        val isBarrelFull = hasBulletInBarrel || gunData.boltType == BoltType.OPEN_BOLT
        if (currentAmmo >= maxAmmo && isBarrelFull) {
            TACZLegacy.logger.info("[ReloadDebug] reload() abort gun={} full ammo={} max={} barrel={}", gunId, currentAmmo, maxAmmo, hasBulletInBarrel); return
        }

        val logicalSide = if (shooter.world.isRemote) Side.CLIENT else Side.SERVER
        val reloadEvent = GunReloadEvent(shooter, currentGunItem, logicalSide)
        if (MinecraftForge.EVENT_BUS.post(reloadEvent)) {
            TACZLegacy.logger.info("[ReloadDebug] reload() abort gun={} event cancelled", gunId); return
        }

        // 弹药来源检查
        val needCheck = !gunData.isReloadInfinite
        if (needCheck) {
            val useInventoryAmmo = iGun.useInventoryAmmo(currentGunItem)
            if (useInventoryAmmo) {
                if (!iGun.hasInventoryAmmo(shooter, currentGunItem, needCheck)) {
                    TACZLegacy.logger.info("[ReloadDebug] reload() abort gun={} no inventory ammo", gunId); return
                }
            } else if (iGun.useDummyAmmo(currentGunItem)) {
                if (iGun.getDummyAmmoAmount(currentGunItem) <= 0) {
                    TACZLegacy.logger.info("[ReloadDebug] reload() abort gun={} dummy ammo 0", gunId); return
                }
            }
        }

        TACZLegacy.logger.info("[ReloadDebug] {} reload() enter gun={} ammo={} barrel={} max={}", logicalSide, gunId, currentAmmo, hasBulletInBarrel, maxAmmo)

        if (!shooter.world.isRemote) {
            TACZNetworkHandler.sendToTrackingEntity(ServerMessageReload(shooter.entityId, currentGunItem), shooter)
        }

        // 判定空仓换弹 vs 战术换弹
        val ammoCount = currentAmmo + if (hasBulletInBarrel && gunData.boltType != BoltType.OPEN_BOLT) 1 else 0
        if (ammoCount <= 0) {
            data.reloadStateType = ReloadState.StateType.EMPTY_RELOAD_FEEDING
        } else {
            data.reloadStateType = ReloadState.StateType.TACTICAL_RELOAD_FEEDING
        }
        data.reloadTimestamp = System.currentTimeMillis()

        // 脚本 hook: start_reload
        val script = TACZGunScriptAPI.resolveScript(gunData)
        val startFunc = script?.let { TACZGunScriptAPI.checkFunction(it, "start_reload") }
        TACZLegacy.logger.info(
            "[ReloadDebug] {} reload start gun={} scriptId={} scriptResolved={} startFunc={} ammo={}",
            logicalSide, gunId, gunData.scriptId, script != null, startFunc != null,
            iGun.getCurrentAmmoCount(currentGunItem),
        )
        if (startFunc != null) {
            val api = TACZGunScriptAPI.create(shooter, data, currentGunItem)
            val shouldProceed = startFunc.call(CoerceJavaToLua.coerce(api)).checkboolean()
            if (!shouldProceed) {
                data.reloadStateType = ReloadState.StateType.NOT_RELOADING
                data.reloadTimestamp = -1L
            }
        }
        // 无脚本时，默认允许换弹（startReload 等价 return true）
    }

    /**
     * 服务端 tick：检查换弹阶段转移和完成。
     * 如果脚本定义了 tick_reload，用脚本返回的 (stateType, countDown) 推进状态机。
     * 否则用默认 timer 逻辑。
     */
    public fun tickReload() {
        if (data.reloadTimestamp == -1L) return
        val supplier = data.currentGunItem ?: return
        val currentGunItem = supplier.get()
        val iGun = currentGunItem.item as? IGun ?: return
        val gunId = iGun.getGunId(currentGunItem)
        val gunData = GunDataAccessor.getGunData(gunId) ?: return

        val api = TACZGunScriptAPI.create(shooter, data, currentGunItem)
        val side = if (shooter.world.isRemote) Side.CLIENT else Side.SERVER
        val script = TACZGunScriptAPI.resolveScript(gunData)
        val tickFunc = try {
            script?.let { TACZGunScriptAPI.checkFunction(it, "tick_reload") }
        } catch (e: Exception) {
            TACZLegacy.logger.error("[ReloadDebug] {} checkFunction tick_reload failed gun={}", side, gunId, e)
            null
        }
        val result: ReloadState = if (tickFunc != null) {
            try {
                val varargs = tickFunc.invoke(CoerceJavaToLua.coerce(api))
                val typeOrdinal = varargs.arg(1).checkint()
                val countDown = varargs.arg(2).checklong()
                val stateType = ReloadState.StateType.values().getOrElse(typeOrdinal) { ReloadState.StateType.NOT_RELOADING }
                TACZLegacy.logger.info(
                    "[ReloadDebug] {} script tick gun={} state={} cd={} ammo={}",
                    side, gunId, stateType, countDown, api.getAmmoAmount(),
                )
                ReloadState(stateType, countDown)
            } catch (e: Exception) {
                TACZLegacy.logger.error("[ReloadDebug] {} script tick_reload threw, fallback default gun={}", side, gunId, e)
                defaultTickReload(api, gunData)
            }
        } else {
            TACZLegacy.logger.info(
                "[ReloadDebug] {} default tick gun={} scriptId={} scriptResolved={} ammo={}",
                side, gunId, gunData.scriptId, script != null, api.getAmmoAmount(),
            )
            defaultTickReload(api, gunData)
        }

        data.reloadStateType = result.stateType
        if (!result.stateType.isReloading()) {
            data.reloadTimestamp = -1L
        }
    }

    /**
     * 取消换弹。
     */
    public fun cancelReload(): Boolean {
        if (!data.reloadStateType.isReloading()) return false
        val supplier = data.currentGunItem ?: return false
        val currentGunItem = supplier.get()
        val gunId = (currentGunItem.item as? IGun)?.getGunId(currentGunItem)
        val gunData = gunId?.let { GunDataAccessor.getGunData(it) }

        // 脚本 hook: interrupt_reload
        if (gunData != null) {
            val script = TACZGunScriptAPI.resolveScript(gunData)
            val interruptFunc = script?.let { TACZGunScriptAPI.checkFunction(it, "interrupt_reload") }
            if (interruptFunc != null) {
                val api = TACZGunScriptAPI.create(shooter, data, currentGunItem)
                interruptFunc.call(CoerceJavaToLua.coerce(api))
            }
        }

        data.reloadStateType = ReloadState.StateType.NOT_RELOADING
        data.reloadTimestamp = -1L
        return true
    }

    public fun getReloadState(): ReloadState {
        val countDown = if (data.reloadTimestamp > 0) {
            data.reloadTimestamp + getExpectedReloadLength() - System.currentTimeMillis()
        } else {
            ReloadState.NOT_RELOADING_COUNTDOWN
        }
        return ReloadState(data.reloadStateType, countDown.coerceAtLeast(ReloadState.NOT_RELOADING_COUNTDOWN))
    }

    private fun getExpectedReloadLength(): Long {
        val supplier = data.currentGunItem ?: return 0L
        val currentGunItem = supplier.get()
        val iGun = currentGunItem.item as? IGun ?: return 0L
        val gunId = iGun.getGunId(currentGunItem)
        val gunData = GunDataAccessor.getGunData(gunId) ?: return 0L
        return when (data.reloadStateType) {
            ReloadState.StateType.EMPTY_RELOAD_FEEDING,
            ReloadState.StateType.EMPTY_RELOAD_FINISHING ->
                ((gunData.emptyReloadFeedingTimeS + gunData.emptyReloadFinishingTimeS) * 1000).toLong()
            ReloadState.StateType.TACTICAL_RELOAD_FEEDING,
            ReloadState.StateType.TACTICAL_RELOAD_FINISHING ->
                ((gunData.reloadFeedingTimeS + gunData.reloadFinishingTimeS) * 1000).toLong()
            else -> 0L
        }
    }

    // =====================================================================
    // 默认换弹逻辑（无脚本时使用）— 与上游 defaultTickReload 对齐
    // =====================================================================

    private fun defaultTickReload(api: TACZGunScriptAPI, gunData: GunCombatData): ReloadState {
        val oldStateType = ReloadState.StateType.values()[api.getReloadStateType()]
        val progressTime = api.getReloadTime()
        var stateType: ReloadState.StateType
        var countDown: Long

        if (oldStateType.isReloadingEmpty()) {
            val feedTime = (gunData.emptyReloadFeedingTimeS * 1000).toLong()
            val finishingTime = (gunData.emptyReloadFinishingTimeS * 1000).toLong()
            if (progressTime < feedTime) {
                stateType = ReloadState.StateType.EMPTY_RELOAD_FEEDING
                countDown = feedTime - progressTime
            } else if (progressTime < finishingTime) {
                stateType = ReloadState.StateType.EMPTY_RELOAD_FINISHING
                countDown = finishingTime - progressTime
            } else {
                stateType = ReloadState.StateType.NOT_RELOADING
                countDown = ReloadState.NOT_RELOADING_COUNTDOWN
            }
        } else if (oldStateType.isReloadingTactical()) {
            val feedTime = (gunData.reloadFeedingTimeS * 1000).toLong()
            val finishingTime = (gunData.reloadFinishingTimeS * 1000).toLong()
            if (progressTime < feedTime) {
                stateType = ReloadState.StateType.TACTICAL_RELOAD_FEEDING
                countDown = feedTime - progressTime
            } else if (progressTime < finishingTime) {
                stateType = ReloadState.StateType.TACTICAL_RELOAD_FINISHING
                countDown = finishingTime - progressTime
            } else {
                stateType = ReloadState.StateType.NOT_RELOADING
                countDown = ReloadState.NOT_RELOADING_COUNTDOWN
            }
        } else {
            stateType = ReloadState.StateType.NOT_RELOADING
            countDown = ReloadState.NOT_RELOADING_COUNTDOWN
        }

        // feeding -> finishing 阶段转换时执行补弹
        if (oldStateType == ReloadState.StateType.EMPTY_RELOAD_FEEDING && oldStateType != stateType) {
            defaultReloadFinishing(api, gunData, isTactical = false)
        }
        if (oldStateType == ReloadState.StateType.TACTICAL_RELOAD_FEEDING && oldStateType != stateType) {
            defaultReloadFinishing(api, gunData, isTactical = true)
        }

        return ReloadState(stateType, countDown)
    }

    /**
     * 默认补弹逻辑 — 与上游 defaultReloadFinishing 对齐。
     * 在 feeding → finishing 转换时调用：消耗背包弹药并注入弹匣。
     */
    private fun defaultReloadFinishing(api: TACZGunScriptAPI, gunData: GunCombatData, isTactical: Boolean) {
        val needAmmoCount = api.getNeededAmmoAmount()
        val needConsumeAmmo = api.isReloadingNeedConsumeAmmo() || gunData.isReloadInfinite

        // 根据弹药类型补弹（上游 MAGAZINE / FUEL 两种路径）
        if (gunData.reloadType.equals("fuel", ignoreCase = true)) {
            // FUEL：消耗一个弹药物品把弹匣一次性填满（不是"一个物品换一发"）。
            // 客户端拿不到真实库存，consumeAmmoFromPlayer 在客户端直接返回请求量，走预测写满。
            val consumed = if (needConsumeAmmo) api.consumeAmmoFromPlayer(1) else 1
            if (consumed > 0) {
                api.putAmmoInMagazine(needAmmoCount)
            }
        } else if (needConsumeAmmo) {
            val consumed = api.consumeAmmoFromPlayer(needAmmoCount)
            // 客户端也预测写弹匣，避免补弹后弹匣冻结在 0（纯等服务端回传会滞后/丢失，
            // QA 实测"换弹后子弹几乎还是0"即此副作用）。
            // 服务端在函数末尾以 ServerMessageShootState(actualAmmo) 回传实际弹匣数纠正客户端，
            // 解决"客户端显示满、服务端扣不足"的假弹/空枪（以服务端权威覆盖预测值）。
            api.putAmmoInMagazine(consumed)
        } else {
            api.putAmmoInMagazine(needAmmoCount)
        }

        // 非战术换弹：从弹匣取 1 颗放入枪膛
        val boltType = gunData.boltType
        if (!isTactical && (boltType == BoltType.MANUAL_ACTION || boltType == BoltType.CLOSED_BOLT)) {
            val removed = api.removeAmmoFromMagazine(1)
            if (removed != 0) {
                api.setAmmoInBarrel(true)
            }
        }

        // 服务端补弹完成后，回传实际弹匣数给玩家客户端（复用 ServerMessageShootState，SUCCESS 不触发取消连发副作用）。
        // 根因：客户端 tickReload 也在跑 defaultReloadFinishing，用预测值（consumeAmmoFromPlayer 客户端直接返回 neededAmount）
        // 把弹匣写满；服务端从背包实际提取可能不足（弹药不够/类型不匹配），两端不一致 →
        // 客户端显示满弹匣、服务端实际为空，开枪即空枪。以服务端权威覆盖即可消除。
        if (!shooter.world.isRemote && shooter is EntityPlayerMP) {
            val gunItem = shooter.heldItemMainhand
            val iGunNow = gunItem.item as? IGun
            val gunIdNow = iGunNow?.getGunId(gunItem)?.toString()
            val actualAmmo = api.getAmmoAmount()
            val heatNow = iGunNow?.getHeatAmount(gunItem) ?: 0f
            val lockedNow = iGunNow?.isOverheatLocked(gunItem) ?: false
            TACZNetworkHandler.sendToPlayer(
                ServerMessageShootState(ShootResult.SUCCESS, gunIdNow, actualAmmo, heatNow, lockedNow),
                shooter,
            )
        }
    }
}
