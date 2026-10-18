package com.tacz.legacy.common.entity.shooter

import com.tacz.legacy.api.DefaultAssets
import com.tacz.legacy.api.event.GunMeleeEvent
import com.tacz.legacy.api.item.IGun
import com.tacz.legacy.api.item.attachment.AttachmentType
import com.tacz.legacy.common.network.TACZNetworkHandler
import com.tacz.legacy.common.network.message.event.ServerMessageMelee
import com.tacz.legacy.common.resource.AttachmentMeleeCombatData
import com.tacz.legacy.common.resource.GunDataAccessor
import com.tacz.legacy.common.resource.GunDefaultMeleeCombatData
import com.tacz.legacy.common.resource.GunMeleeCombatData
import net.minecraft.entity.EntityLivingBase
import net.minecraft.item.ItemStack
import net.minecraft.util.DamageSource
import net.minecraft.util.math.AxisAlignedBB
import net.minecraft.util.math.Vec3d
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.relauncher.Side
import kotlin.math.sqrt

/**
 * 服务端近战逻辑。对齐 0.17.0：瞬发结算 + 配件叠加 + 清无敌帧 + 精确判定。
 */
public class LivingEntityMelee(
    private val shooter: EntityLivingBase,
    private val data: ShooterDataHolder,
    private val draw: LivingEntityDrawGun,
) {
    /**
     * 移植版基础近战距离。
     * 原版 TACZ 的近战总距离 = default.distance + melee.distance，默认只有 2.0~2.25 格，
     * 明显短于原版空手攻击(约 3 格)，玩家会觉得必须贴脸才中。
     * 这里统一加一个基础值让所有枪都更舒服；设为 0 即完全还原原版手感。
     */
    private companion object {
        const val BASE_MELEE_REACH = 0.75f
    }

    public fun melee() {
        val supplier = data.currentGunItem ?: return
        val currentGunItem = supplier.get()
        val iGun = currentGunItem.item as? IGun ?: return
        val gunId = iGun.getGunId(currentGunItem)
        val gunData = GunDataAccessor.getGunData(gunId) ?: return

        val meleeData = gunData.meleeData ?: return

        // 冷却检查
        val meleeCoolDown = getMeleeCoolDown()
        if (meleeCoolDown > 0) return
        // 切枪中不可近战
        if (draw.getDrawCoolDown() != 0L) return
        // 换弹中不可近战
        if (data.reloadStateType.isReloading()) return

        // 发布服务端 Forge 事件
        val meleeEvent = GunMeleeEvent(shooter, currentGunItem, Side.SERVER)
        if (MinecraftForge.EVENT_BUS.post(meleeEvent)) return

        data.meleeTimestamp = System.currentTimeMillis()
        data.meleePrepTickCount = -1

        // 广播近战开始
        TACZNetworkHandler.sendToTrackingEntity(ServerMessageMelee(shooter.entityId, currentGunItem), shooter)

        // 伤害立即结算，不再等待枪包的 prep 前摇。
        // 原来"按下 → 服务端排主线程 → 等 prep(默认0.1秒=2tick) → 结算"，
        // 手感 100~200ms，表现为"砍了没反应、过一下才掉血"。
        val effective = resolveEffectiveMeleeData(currentGunItem, iGun, meleeData) ?: return
        executeDefaultMeleeDamage(effective)
    }

    /**
     * 把枪上已安装配件的近战数据(刺刀/枪托)叠加到枪械默认近战上。
     * 伤害/距离/角度/击退取"枪与配件中的更优值"，避免多配件叠乘出异常数值。
     */
    private fun resolveEffectiveMeleeData(
        gunItem: ItemStack,
        iGun: IGun,
        meleeData: GunMeleeCombatData,
    ): GunDefaultMeleeCombatData? {
        val base = meleeData.defaultMeleeData ?: return null
        var damage = base.damage
        // 枪械的 distance 是"延伸"量，要与 default/配件的 distance 加和。
        var distance = base.distance + meleeData.distance
        var rangeAngle = base.rangeAngle
        var knockback = base.knockback

        for (type in AttachmentType.values()) {
            if (type == AttachmentType.NONE) continue
            val attachmentId = iGun.getAttachmentId(gunItem, type)
            val builtInId = iGun.getBuiltInAttachmentId(gunItem, type)
            val melee = resolveAttachmentMelee(attachmentId) ?: resolveAttachmentMelee(builtInId) ?: continue
            if (melee.damage > 0f) damage = maxOf(damage, melee.damage)
            // 配件的 distance 替换 default 的一截，再与枪械的延升值相加
            distance = (distance - base.distance) + maxOf(base.distance, melee.distance)
            if (melee.rangeAngle > 0f) rangeAngle = maxOf(rangeAngle, melee.rangeAngle)
            if (melee.knockback > 0f) knockback = maxOf(knockback, melee.knockback)
        }

        // 移植版基础距离：在配件叠加完成后一次性加上，
        // 避免被上方配件替换公式 (distance - base.distance) 抵消。
        distance += BASE_MELEE_REACH

        val changed = damage != base.damage ||
            distance != base.distance ||
            rangeAngle != base.rangeAngle ||
            knockback != base.knockback
        if (!changed) {
            return base
        }
        return GunDefaultMeleeCombatData(
            animationType = base.animationType,
            prepTime = base.prepTime,
            cooldown = base.cooldown,
            damage = damage,
            distance = distance,
            rangeAngle = rangeAngle,
            knockback = knockback,
        )
    }

    private fun resolveAttachmentMelee(attachmentId: net.minecraft.util.ResourceLocation?): AttachmentMeleeCombatData? {
        if (attachmentId == null || attachmentId == DefaultAssets.EMPTY_ATTACHMENT_ID) {
            return null
        }
        return GunDataAccessor.getAttachmentMeleeData(attachmentId)
    }

    /**
     * 每 tick 检查近战 prep 阶段。伤害已在 melee() 里立即结算，
     * 此处空实现以不动 mixin 侧调用点；meleePrepTickCount 恒为 -1，前摇分支永不进入。
     */
    public fun tickMelee() {
    }

    public fun getMeleeCoolDown(): Long {
        val supplier = data.currentGunItem ?: return 0
        val currentGunItem = supplier.get()
        val iGun = currentGunItem.item as? IGun ?: return 0
        val gunId = iGun.getGunId(currentGunItem)
        val gunData = GunDataAccessor.getGunData(gunId) ?: return -1

        val meleeData = gunData.meleeData ?: return 0
        if (data.meleeTimestamp < 0) return 0

        val elapsed = System.currentTimeMillis() - data.meleeTimestamp
        // 枪包语义：枪械 cooldown 与 default 的 cooldown 做加和
        val totalCooldown = meleeData.cooldown + (meleeData.defaultMeleeData?.cooldown ?: 0f)
        val coolDown = (totalCooldown * 1000).toLong() - elapsed
        return if (coolDown < 0) 0 else coolDown
    }

    private fun executeDefaultMeleeDamage(mData: GunDefaultMeleeCombatData) {
        val look = shooter.lookVec
        val eyePos = Vec3d(shooter.posX, shooter.posY + shooter.eyeHeight, shooter.posZ)

        // 以眼睛为中心的立方体搜索盒，替代 eyePos→end 线段盒。
        // 线段盒让"搜索范围"随视线漂移，垂直上下看时甚至退化成一个点。
        val reach = mData.distance.toDouble().coerceAtLeast(1.0)
        val searchBox = AxisAlignedBB(
            eyePos.x - reach, eyePos.y - reach, eyePos.z - reach,
            eyePos.x + reach, eyePos.y + reach, eyePos.z + reach,
        )
        val candidates = shooter.world.getEntitiesWithinAABBExcludingEntity(shooter, searchBox)
        // rangeAngle 作为半角参与判定；保底下限提到 45°，
        // 防止某些枪包把角度写得太窄导致正对都打不中。
        val halfAngleRad = Math.toRadians(mData.rangeAngle.toDouble().coerceIn(45.0, 180.0))
        // 视线水平分量，用于水平夹角判定
        val lookHorizontal = sqrt(look.x * look.x + look.z * look.z)

        for (entity in candidates) {
            if (entity !is EntityLivingBase) continue
            if (!entity.isEntityAlive) continue

            // 用包围盒上离眼睛最近的点，而不是实体中心。
            val closest = closestPointOnBox(entity.entityBoundingBox, eyePos)
            val dx = closest.x - eyePos.x
            val dy = closest.y - eyePos.y
            val dz = closest.z - eyePos.z
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            if (dist > mData.distance) continue

            // 只判水平夹角，垂直方向交给距离限制。
            val horizontal = sqrt(dx * dx + dz * dz)
            if (horizontal > 1.0E-6 && lookHorizontal > 1.0E-6) {
                val cos = ((dx * look.x + dz * look.z) / (horizontal * lookHorizontal)).coerceIn(-1.0, 1.0)
                if (Math.acos(cos) > halfAngleRad) continue
            }

            // 清空目标无敌帧，否则刚被子弹命中过的目标会吞掉近战伤害。
            entity.hurtResistantTime = 0
            val applied = entity.attackEntityFrom(createMeleeDamageSource(), mData.damage)
            if (applied) {
                // 击退取水平方向，避免把目标垂直弹飞
                val knockDir = if (horizontal > 1.0E-6) {
                    Vec3d(dx / horizontal, 0.0, dz / horizontal)
                } else {
                    Vec3d(look.x, 0.0, look.z).normalize()
                }
                applyMeleeKnockback(entity, mData.knockback, knockDir)
            }
        }
    }

    /** 包围盒上距离给定点最近的坐标（点在盒内时返回该点本身）。 */
    private fun closestPointOnBox(box: AxisAlignedBB, point: Vec3d): Vec3d {
        return Vec3d(
            point.x.coerceIn(box.minX, box.maxX),
            point.y.coerceIn(box.minY, box.maxY),
            point.z.coerceIn(box.minZ, box.maxZ),
        )
    }

    /** 近战伤害源。玩家用 player 类型；非玩家退回 mob 类型，不再作废整次近战。 */
    private fun createMeleeDamageSource(): DamageSource {
        return if (shooter is net.minecraft.entity.player.EntityPlayer) {
            DamageSource.causePlayerDamage(shooter)
        } else {
            net.minecraft.util.EntityDamageSource("mob", shooter)
        }
    }

    private fun applyMeleeKnockback(target: EntityLivingBase, knockback: Float, direction: Vec3d) {
        if (knockback <= 0.0f) return
        target.addVelocity(direction.x * knockback * 0.6, 0.1, direction.z * knockback * 0.6)
        target.velocityChanged = true
    }
}
