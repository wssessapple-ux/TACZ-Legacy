package com.tacz.legacy.common.entity

import com.google.common.collect.Sets
import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.common.config.LegacyConfigManager
import net.minecraft.block.material.Material
import net.minecraft.block.state.IBlockState
import net.minecraft.enchantment.EnchantmentProtection
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraft.network.play.server.SPacketExplosion
import net.minecraft.util.DamageSource
import net.minecraft.util.math.AxisAlignedBB
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.RayTraceResult
import net.minecraft.util.math.Vec3d
import net.minecraft.world.Explosion
import net.minecraft.world.WorldServer
import net.minecraftforge.event.ForgeEventFactory
import kotlin.math.sqrt

internal object LegacyProjectileExplosionHelper {
    internal fun createExplosion(
        owner: Entity?,
        exploder: Entity,
        x: Double,
        y: Double,
        z: Double,
        damage: Float,
        radius: Float,
        knockback: Boolean,
        destroyBlock: Boolean,
    ) {
        val worldServer = exploder.world as? WorldServer ?: return
        val actualRadius = radius.coerceAtLeast(0.0f)
        val actualDamage = damage.coerceAtLeast(0.0f)
        val damagesTerrain = destroyBlock && LegacyConfigManager.common.explosiveAmmoDestroysBlock
        val allowKnockback = knockback && LegacyConfigManager.common.explosiveAmmoKnockBack
        TACZLegacy.logger.debug(
            "[KnockbackTrace] EXPLOSION created allowKnockback={} (nbtKnockback={}, cfgExplosiveAmmoKnockBack={}) radius={} dmg={} at {},{},{}",
            allowKnockback, knockback, LegacyConfigManager.common.explosiveAmmoKnockBack, actualRadius, actualDamage, x, y, z,
        )
        val explosion = LegacyProjectileExplosion(
            world = worldServer,
            owner = owner,
            exploder = exploder,
            centerX = x,
            centerY = y,
            centerZ = z,
            damagePower = actualDamage,
            radius = actualRadius,
            applyKnockback = allowKnockback,
            damagesTerrain = damagesTerrain,
        )
        if (ForgeEventFactory.onExplosionStart(worldServer, explosion)) {
            return
        }
        explosion.doExplosionA()
        explosion.doExplosionB(false)
        if (!damagesTerrain) {
            explosion.clearAffectedBlockPositions()
        }
        for (player in worldServer.playerEntities) {
            if (player.getDistanceSq(x, y, z) < 4096.0) {
                (player as EntityPlayerMP).connection.sendPacket(
                    SPacketExplosion(
                        x,
                        y,
                        z,
                        actualRadius,
                        explosion.getAffectedBlockPositions(),
                        explosion.getPlayerKnockbackMap()[player],
                    )
                )
            }
        }
    }
}

internal class LegacyProjectileExplosion(
    private val world: WorldServer,
    private val owner: Entity?,
    private val exploder: Entity,
    private val centerX: Double,
    private val centerY: Double,
    private val centerZ: Double,
    private val damagePower: Float,
    private val radius: Float,
    private val applyKnockback: Boolean,
    damagesTerrain: Boolean,
) : Explosion(world, exploder, centerX, centerY, centerZ, radius, false, damagesTerrain) {
    private val center: Vec3d = Vec3d(centerX, centerY, centerZ)

    override fun getExplosivePlacedBy(): EntityLivingBase? {
        return owner as? EntityLivingBase ?: super.getExplosivePlacedBy()
    }

    override fun doExplosionA() {
        if (radius > 0.0f) {
            collectAffectedBlocks()
        }
        if (radius <= 0.0f && damagePower <= 0.0f) {
            return
        }
        val doubledRadius = radius * 2.0f
        val minX = MathHelper.floor(centerX - doubledRadius - 1.0)
        val maxX = MathHelper.floor(centerX + doubledRadius + 1.0)
        val minY = MathHelper.floor(centerY - doubledRadius - 1.0)
        val maxY = MathHelper.floor(centerY + doubledRadius + 1.0)
        val minZ = MathHelper.floor(centerZ - doubledRadius - 1.0)
        val maxZ = MathHelper.floor(centerZ + doubledRadius + 1.0)
        val nearbyEntities = world.getEntitiesWithinAABBExcludingEntity(
            exploder,
            AxisAlignedBB(minX.toDouble(), minY.toDouble(), minZ.toDouble(), maxX.toDouble(), maxY.toDouble(), maxZ.toDouble())
        )
        ForgeEventFactory.onExplosionDetonate(world, this, nearbyEntities, doubledRadius.toDouble())

        val knockbackScale = (damagePower * radius / 500.0f).coerceAtLeast(0.0f).toDouble()
        for (entity in nearbyEntities) {
            if (entity.isImmuneToExplosions) {
                continue
            }
            // 官方 TACZ 语义：取生物 15 个角点/面中心里"最暴露"（未被完整方块挡）的
            // 直达距离算伤害；生物只要有一个方向暴露就能造成伤害（对齐科技枪穿透手感），
            // 全部被完整方块包住时 strength>1 → 无伤，保持"隔墙不炸穿"。
            val minDist = computeMinExposedDistance(center, entity.entityBoundingBox)
            val strength = if (radius <= 0.0f) 2.0 else minDist / radius.toDouble()
            // [0.6.4-REV] AOE 方向诊断日志：记录每只怪相对爆炸中心的偏移(dx,dz)与遮挡判定，
            // 用于定位"正方向(+X 东 / +Z 南)其他怪不受伤"的方向性异常。
            val offX = entity.posX - centerX
            val offZ = entity.posZ - centerZ
            TACZLegacy.logger.debug(
                "[AoeTrace] {} posX={} posZ={} off=({}, {}) minDist={} radius={} strength={} blocked={}",
                entity.name,
                "%.1f".format(entity.posX), "%.1f".format(entity.posZ),
                "%.2f".format(offX), "%.2f".format(offZ),
                "%.2f".format(minDist),
                radius,
                "%.2f".format(strength),
                strength > 1.0,
            )
            if (strength > 1.0) {
                continue
            }
            val impact = (1.0 - strength).coerceAtLeast(0.0)
            val damageAmount = (impact * damagePower).toFloat().coerceAtLeast(0.0f)
            if (damageAmount > 0.0f) {
                // [0.6.4-REV] 伤害施加诊断：区分 attackEntityFrom 返回 false 的具体原因。
                // applied=false 时只有几种可能：isEntityInvulnerable / 已死 / world.isRemote / ForgeHooks 取消 / 无敌帧半血递减。
                // 日志额外带出 immune/dead/remote 三字段，定位到底是哪一层吞了伤害。
                val damageSource = DamageSource.causeExplosionDamage(this)
                val immune = entity.isEntityInvulnerable(damageSource)
                val dead = entity.isDead
                val remote = entity.world.isRemote
                val applied = entity.attackEntityFrom(damageSource, damageAmount)
                TACZLegacy.logger.debug(
                    "[AoeTraceDmg] {} off=({}, {}) strength={} dmg={} damagePower={} applied={} hurtResistant={} immune={} dead={} remote={}",
                    entity.name,
                    "%.2f".format(entity.posX - centerX), "%.2f".format(entity.posZ - centerZ),
                    "%.2f".format(strength),
                    "%.2f".format(damageAmount),
                    damagePower,
                    applied,
                    if (entity is EntityLivingBase) entity.hurtResistantTime else -1,
                    immune,
                    dead,
                    remote,
                )
            }

            val dx = entity.posX - centerX
            val dy = entity.posY + entity.eyeHeight.toDouble() - centerY
            val dz = entity.posZ - centerZ
            val length = sqrt(dx * dx + dy * dy + dz * dz)
            if (length == 0.0) {
                continue
            }

            if (!applyKnockback || knockbackScale <= 0.0) {
                continue
            }

            val normX = dx / length
            val normY = dy / length
            val normZ = dz / length
            var knockbackAmount = impact
            if (entity is EntityLivingBase) {
                knockbackAmount = EnchantmentProtection.getBlastDamageReduction(entity, impact)
            }
            TACZLegacy.logger.debug(
                "[KnockbackTrace] EXPLOSION applyKnockback to {} motion+=({}, {}, {}) scale={} cfg={}",
                entity.name,
                normX * knockbackAmount * knockbackScale,
                normY * knockbackAmount * knockbackScale,
                normZ * knockbackAmount * knockbackScale,
                knockbackScale,
                LegacyConfigManager.common.explosiveAmmoKnockBack,
            )
            entity.motionX += normX * knockbackAmount * knockbackScale
            entity.motionY += normY * knockbackAmount * knockbackScale
            entity.motionZ += normZ * knockbackAmount * knockbackScale
            entity.velocityChanged = true

            if (entity is EntityPlayer && !entity.isSpectator && (!entity.isCreative || !entity.capabilities.isFlying)) {
                getPlayerKnockbackMap()[entity] = Vec3d(normX * impact * knockbackScale, normY * impact * knockbackScale, normZ * impact * knockbackScale)
            }
        }
    }

    /**
     * 官方 TACZ 语义：从爆炸中心向生物 AABB 的 15 个角点/面中心发射线，
     * 取"最近一个未被完整方块阻挡（穿透不完整方块）"的直达距离算伤害。
     * 生物只要有一个方向暴露即可按该方向造成伤害（对齐科技枪穿透手感）；
     * 全部方向被完整方块挡住时返回半径（strength>1 → 无伤），保持隔墙不炸穿。
     */
    private fun computeMinExposedDistance(center: Vec3d, bb: AxisAlignedBB): Double {
        val cx = (bb.minX + bb.maxX) * 0.5
        val cy = (bb.minY + bb.maxY) * 0.5
        val cz = (bb.minZ + bb.maxZ) * 0.5
        // [0.6.4-REV] 隔墙不炸穿：先扫一条"爆炸中心→怪物中心"的直达射线做前置判定。
        // 若中心直达怪中心被完整方块(非中心格、非怪占位格)挡住，说明中间有完整墙/黑曜石墙，
        // 直接判定隔墙无伤——防止怪 bbox 上部角点越墙顶导致整只怪被判定暴露
        // (QA:"墙后面也死了""黑曜石也能波及到")。
        if (rayTraceIgnorePartial(center, Vec3d(cx, cy, cz)) != null) {
            return radius.toDouble()
        }
        val points = arrayOf(
            Vec3d(bb.minX, bb.minY, bb.minZ), Vec3d(bb.minX, bb.minY, bb.maxZ),
            Vec3d(bb.minX, bb.maxY, bb.minZ), Vec3d(bb.maxX, bb.minY, bb.minZ),
            Vec3d(bb.minX, bb.maxY, bb.maxZ), Vec3d(bb.maxX, bb.minY, bb.maxZ),
            Vec3d(bb.maxX, bb.maxY, bb.minZ), Vec3d(bb.maxX, bb.maxY, bb.maxZ),
            Vec3d(bb.minX, cy, cz), Vec3d(bb.maxX, cy, cz),
            Vec3d(cx, bb.minY, cz), Vec3d(cx, bb.maxY, cz),
            Vec3d(cx, cy, bb.minZ), Vec3d(cx, cy, bb.maxZ),
            Vec3d(cx, cy, cz),
        )
        var minDist = radius.toDouble()
        for (p in points) {
            // 爆炸中心 → 该角点：未被完整方块挡 = 直达
            if (rayTraceIgnorePartial(center, p) == null) {
                val d = center.distanceTo(p)
                if (d < minDist) minDist = d
            }
        }
        return minDist
    }

    /**
     * 只被完整方块阻挡的射线检测，穿透不完整方块（草/雪/半砖/栅栏/花）。
     */
    private fun rayTraceIgnorePartial(from: Vec3d, to: Vec3d): RayTraceResult? {
        var scanStart = from
        // [0.6.4-REV] 爆炸中心自身所在方块不挡自己的射线：中心落进地面/雪堆等完整方块时，
        // rayTraceBlocks(center, p) 第一步就命中它 → isFullBlock → 15 条角点射线全被挡 → AOE 全无伤。
        val fromBlockPos = BlockPos(from.x, from.y, from.z)
        // [0.6.4-REV] 终点(怪所在占位格)方块同样不挡：射线终点在怪 bbox 内，可能落在完整方块里，
        // 若终点格也算遮挡会误伤"怪站在地面/墙边"的命中。
        val toBlockPos = BlockPos(to.x, to.y, to.z)
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        val ux = if (length > 1.0E-6) dx / length else 0.0
        val uy = if (length > 1.0E-6) dy / length else 0.0
        val uz = if (length > 1.0E-6) dz / length else 0.0
        var iteration = 0
        while (iteration < 64) {
            val result = world.rayTraceBlocks(scanStart, to, false, false, false)
            if (result == null || result.typeOfHit != RayTraceResult.Type.BLOCK) {
                return null
            }
            val state = world.getBlockState(result.blockPos)
            // 完整方块墙且不是爆炸中心所在方块、也不是终点怪所在方块 → 遮挡（隔墙不炸穿）
            if (state.isFullBlock() && result.blockPos != fromBlockPos && result.blockPos != toBlockPos) {
                return result
            }
            // 不完整方块或起点(中心)方块：沿方向微推跳过，继续扫描（步长 0.05 确保越出表面）
            scanStart = Vec3d(
                result.hitVec.x + ux * 0.05,
                result.hitVec.y + uy * 0.05,
                result.hitVec.z + uz * 0.05,
            )
            iteration++
        }
        return null
    }

    private fun collectAffectedBlocks() {
        val affectedBlocks = Sets.newHashSet<BlockPos>()
        for (xIndex in 0 until 16) {
            for (yIndex in 0 until 16) {
                for (zIndex in 0 until 16) {
                    if (xIndex != 0 && xIndex != 15 && yIndex != 0 && yIndex != 15 && zIndex != 0 && zIndex != 15) {
                        continue
                    }
                    var dx = (xIndex.toFloat() / 15.0f * 2.0f - 1.0f).toDouble()
                    var dy = (yIndex.toFloat() / 15.0f * 2.0f - 1.0f).toDouble()
                    var dz = (zIndex.toFloat() / 15.0f * 2.0f - 1.0f).toDouble()
                    val directionLength = sqrt(dx * dx + dy * dy + dz * dz)
                    dx /= directionLength
                    dy /= directionLength
                    dz /= directionLength
                    var remaining = radius * (0.7f + world.rand.nextFloat() * 0.6f)
                    var currentX = centerX
                    var currentY = centerY
                    var currentZ = centerZ

                    while (remaining > 0.0f) {
                        val blockPos = BlockPos(currentX, currentY, currentZ)
                        val blockState = world.getBlockState(blockPos)
                        if (blockState.material != Material.AIR) {
                            val resistance = exploder.getExplosionResistance(this, world, blockPos, blockState)
                            remaining -= (resistance + 0.3f) * 0.3f
                        }
                        if (remaining > 0.0f && exploder.canExplosionDestroyBlock(this, world, blockPos, blockState, remaining)) {
                            affectedBlocks.add(blockPos)
                        }
                        currentX += dx * 0.30000001192092896
                        currentY += dy * 0.30000001192092896
                        currentZ += dz * 0.30000001192092896
                        remaining -= 0.22500001f
                    }
                }
            }
        }
        getAffectedBlockPositions().addAll(affectedBlocks)
    }
}