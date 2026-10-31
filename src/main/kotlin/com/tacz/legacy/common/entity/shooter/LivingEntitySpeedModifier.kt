package com.tacz.legacy.common.entity.shooter

import com.tacz.legacy.api.entity.IGunOperator
import com.tacz.legacy.api.item.IGun
import com.tacz.legacy.common.config.LegacyConfigManager
import com.tacz.legacy.common.resource.TACZAttachmentModifierRegistry
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry
import com.tacz.legacy.common.resource.TACZGunPropertyResolver
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.SharedMonsterAttributes
import net.minecraft.entity.ai.attributes.AttributeModifier
import net.minecraft.entity.ai.attributes.IAttributeInstance
import java.util.UUID

/**
 * 持枪移动速度修正（对齐上游 LivingEntitySpeedModifier）。
 *
 * 两套 AttributeModifier：
 * - 重量修正（weight_modifier → MULTIPLY_BASE(operation=1)）：枪基础重量 + 配件 weight_modifier eval
 *   → -weight × weightSpeedMultiplier（amount 为负 = 减速），对齐上游 LivingEntitySpeedModifier.java
 * - 移动速度修正（movement_speed → MULTIPLY_TOTAL(operation=2)）：配件 movement_speed raw json 的
 *   base/aim/reload 逐分量相加（对齐上游 ExtraMovementModifier.java），对齐 1.12.2 MoveSpeed 语义。
 *
 * 注：我方当前未解析"枪自身 movement_speed 基础值"（上游 gunData.moveSpeedBase/Aim/Reload），
 * 此实现只叠加配件 movement_speed（从 0 起）；枪自身若后续补解析可在此处加入。
 */
internal object LivingEntitySpeedModifier {
    private val SPEED_MODIFIER_UUID = UUID.fromString("4D5696AE-A7C5-C59C-80E9-2A2DC8373C46")
    private val WEIGHT_SPEED_MODIFIER_UUID = UUID.fromString("2CB6F5AD-C6D2-9D29-4E84-0856ACD47CDB")
    private const val MODIFIER_NAME = "tacz:gun_move_speed"
    private const val WEIGHT_MODIFIER_NAME = "tacz:gun_weight_speed"

    /**
     * 每 tick 调用（客户端 + 服务端），根据持枪状态更新 MOVEMENT_SPEED 的 AttributeModifier。
     */
    fun updateSpeedModifier(shooter: EntityLivingBase) {
        if (!shooter.isEntityAlive) return
        val speedAttr: IAttributeInstance? = shooter.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED)
        if (speedAttr == null) return

        val mainHand = shooter.heldItemMainhand
        if (mainHand.isEmpty || mainHand.item !is IGun) {
            speedAttr.removeModifier(SPEED_MODIFIER_UUID)
            speedAttr.removeModifier(WEIGHT_SPEED_MODIFIER_UUID)
            return
        }

        val iGun = mainHand.item as IGun
        val gunId = iGun.getGunId(mainHand)
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val attachIds = TACZGunPropertyResolver.collectAttachmentIds(mainHand, iGun)

        // ① weight_modifier：枪基础重量 + 配件 modifier → eval → -weight × weightSpeedMultiplier → MULTIPLY_BASE
        val baseWeight = snapshot.guns[gunId]?.data?.weight?.toDouble() ?: 0.0
        val weightMods = TACZGunPropertyResolver.collectNumericModifiers(snapshot, attachIds, "weight_modifier")
        val totalWeight = TACZAttachmentModifierRegistry.evalNumeric(weightMods, baseWeight)
        val weightAmount = -totalWeight * LegacyConfigManager.server.weightSpeedMultiplier
        if (weightAmount != 0.0) {
            val currentWeight = speedAttr.getModifier(WEIGHT_SPEED_MODIFIER_UUID)
            if (currentWeight == null || currentWeight.amount != weightAmount) {
                speedAttr.removeModifier(WEIGHT_SPEED_MODIFIER_UUID)
                // 1.12.2 operation: 1 = MULTIPLY_BASE（base × amount，amount 为负 = 减速）
                speedAttr.applyModifier(AttributeModifier(WEIGHT_SPEED_MODIFIER_UUID, WEIGHT_MODIFIER_NAME, weightAmount, 1))
            }
        } else {
            speedAttr.removeModifier(WEIGHT_SPEED_MODIFIER_UUID)
        }

        // ② movement_speed：配件 raw json 的 base/aim/reload 逐分量相加 → MULTIPLY_TOTAL
        var dBase = 0f
        var dAim = 0f
        var dReload = 0f
        for (id in attachIds) {
            val msObj = snapshot.attachments[id]?.data?.raw?.get("movement_speed")
                ?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            dBase += msObj.get("base")?.takeIf { it.isJsonPrimitive }?.asFloat ?: 0f
            dAim += msObj.get("aim")?.takeIf { it.isJsonPrimitive }?.asFloat ?: 0f
            dReload += msObj.get("reload")?.takeIf { it.isJsonPrimitive }?.asFloat ?: 0f
        }
        val operator = IGunOperator.fromLivingEntity(shooter)
        val holder = operator.getDataHolder()
        val targetSpeed = when {
            holder.reloadStateType.isReloading() -> dReload
            holder.isAiming -> dAim
            else -> dBase
        }.coerceAtLeast(0f)
        // 无修正时不添加 modifier
        if (targetSpeed == 0f) {
            speedAttr.removeModifier(SPEED_MODIFIER_UUID)
            return
        }
        val current = speedAttr.getModifier(SPEED_MODIFIER_UUID)
        if (current == null || current.amount != targetSpeed.toDouble()) {
            speedAttr.removeModifier(SPEED_MODIFIER_UUID)
            // operation 2 = MULTIPLY_TOTAL
            speedAttr.applyModifier(AttributeModifier(SPEED_MODIFIER_UUID, MODIFIER_NAME, targetSpeed.toDouble(), 2))
        }
    }
}
