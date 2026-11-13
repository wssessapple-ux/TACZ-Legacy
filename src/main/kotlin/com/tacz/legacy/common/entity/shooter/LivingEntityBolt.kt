package com.tacz.legacy.common.entity.shooter

import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.api.item.IGun
import com.tacz.legacy.common.resource.BoltType
import com.tacz.legacy.common.resource.GunCombatData
import com.tacz.legacy.common.resource.GunDataAccessor
import net.minecraft.entity.EntityLivingBase
import org.luaj.vm2.lib.jse.CoerceJavaToLua

/**
 * 服务端拉栓逻辑。与上游 TACZ LivingEntityBolt 行为一致。
 * 支持脚本 hook：start_bolt / tick_bolt。
 */
public class LivingEntityBolt(
    private val shooter: EntityLivingBase,
    private val data: ShooterDataHolder,
    private val draw: LivingEntityDrawGun,
) {
    /**
     * 执行拉栓操作。
     */
    public fun bolt() {
        val supplier = data.currentGunItem ?: return
        val currentGunItem = supplier.get()
        val iGun = currentGunItem.item as? IGun ?: return
        val gunId = iGun.getGunId(currentGunItem)
        val gunData = GunDataAccessor.getGunData(gunId) ?: return

        // 过滤：开膛/闭膛枪不需要手动拉栓
        if (gunData.boltType != BoltType.MANUAL_ACTION) return
        // 已有膛内弹
        if (iGun.hasBulletInBarrel(currentGunItem)) {
            TACZLegacy.logger.info("[ReloadDebug] bolt() abort gun={} barrel has ammo", gunId); return
        }
        // 没弹药可上
        if (iGun.getCurrentAmmoCount(currentGunItem) <= 0) {
            TACZLegacy.logger.info("[ReloadDebug] bolt() abort gun={} magazine empty", gunId); return
        }
        // 正在换弹
        if (data.reloadStateType.isReloading()) {
            TACZLegacy.logger.info("[ReloadDebug] bolt() abort gun={} reloading", gunId); return
        }
        // 正在切枪
        if (draw.getDrawCoolDown() != 0L) {
            TACZLegacy.logger.info("[ReloadDebug] bolt() abort gun={} drawCooldown={}", gunId, draw.getDrawCoolDown()); return
        }
        // 已在拉栓
        if (data.isBolting) return

        data.boltTimestamp = System.currentTimeMillis()
        TACZLegacy.logger.info("[ReloadDebug] bolt() start gun={} ammo={}", gunId, iGun.getCurrentAmmoCount(currentGunItem))

        // 脚本 hook: start_bolt → 返回 boolean（是否开始拉栓）
        val script = TACZGunScriptAPI.resolveScript(gunData)
        val startFunc = script?.let { TACZGunScriptAPI.checkFunction(it, "start_bolt") }
        if (startFunc != null) {
            val api = TACZGunScriptAPI.create(shooter, data, currentGunItem)
            data.isBolting = startFunc.call(CoerceJavaToLua.coerce(api)).checkboolean()
            TACZLegacy.logger.info("[ReloadDebug] bolt() start_bolt gun={} isBolting={}", gunId, data.isBolting)
        } else {
            data.isBolting = true
            TACZLegacy.logger.info("[ReloadDebug] bolt() no script gun={} isBolting=true", gunId)
        }
    }

    /**
     * 每 tick 检查拉栓是否完成。
     */
    public fun tickBolt() {
        if (!data.isBolting) return
        val supplier = data.currentGunItem ?: run { data.isBolting = false; return }
        val currentGunItem = supplier.get()
        val iGun = currentGunItem.item as? IGun ?: run { data.isBolting = false; return }
        val gunId = iGun.getGunId(currentGunItem)
        val gunData = GunDataAccessor.getGunData(gunId) ?: run { data.isBolting = false; return }

        val api = TACZGunScriptAPI.create(shooter, data, currentGunItem)

        val script = TACZGunScriptAPI.resolveScript(gunData)
        val tickFunc = try {
            script?.let { TACZGunScriptAPI.checkFunction(it, "tick_bolt") }
        } catch (e: Exception) {
            TACZLegacy.logger.error("[ReloadDebug] checkFunction tick_bolt failed gun={}", gunId, e)
            null
        }
        data.isBolting = if (tickFunc != null) {
            try {
                val bolting = tickFunc.call(CoerceJavaToLua.coerce(api)).checkboolean()
                TACZLegacy.logger.info("[ReloadDebug] script tick_bolt gun={} bolting={} ammo={} barrel={}", gunId, bolting, api.getAmmoAmount(), api.hasAmmoInBarrel())
                bolting
            } catch (e: Exception) {
                TACZLegacy.logger.error("[ReloadDebug] tick_bolt threw, fallback default gun={}", gunId, e)
                defaultTickBolt(api, gunData)
            }
        } else {
            TACZLegacy.logger.info("[ReloadDebug] default tick_bolt gun={} ammo={} barrel={}", gunId, api.getAmmoAmount(), api.hasAmmoInBarrel())
            defaultTickBolt(api, gunData)
        }
    }

    /**
     * 默认拉栓逻辑 — 与上游 defaultTickBolt 对齐。
     */
    private fun defaultTickBolt(api: TACZGunScriptAPI, gunData: GunCombatData): Boolean {
        val boltActionTime = (gunData.boltTimeS * 1000).toLong()
        val rawFeedTime = gunData.boltFeedTimeS
        val boltFeedTime = if (rawFeedTime < 0) boltActionTime else (rawFeedTime * 1000).toLong()

        if (api.getBoltTime() < boltFeedTime) {
            return true
        }

        // feed 时间已到：如果膛内无弹，从弹匣/背包取 1 颗上膛
        if (!api.hasAmmoInBarrel()) {
            if (api.useInventoryAmmo()) {
                if (api.consumeAmmoFromPlayer(1) == 1) {
                    api.setAmmoInBarrel(true)
                }
            } else if (api.removeAmmoFromMagazine(1) != 0) {
                api.setAmmoInBarrel(true)
            }
        }

        return api.getBoltTime() < boltActionTime
    }
}
