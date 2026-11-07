package com.tacz.legacy.common.entity

import io.netty.buffer.ByteBuf
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityLivingBase
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.world.World
import net.minecraftforge.fml.common.registry.IEntityAdditionalSpawnData

/**
 * 自杀式无人机实体。
 *
 * 设计：
 * - 由 [com.tacz.legacy.common.item.DroneItem] 发射，携带锁定目标 ID
 * - 每 tick 自动追踪目标，撞击后爆炸
 * - 不受重力影响（悬停飞行）
 * - lifespan 200 tick 兜底，防止目标丢失后无限飞行
 *
 * 民用无人机：爆炸半径 2 格
 * 军用无人机：爆炸半径 3 格
 */
internal class EntityDrone : Entity, IEntityAdditionalSpawnData {
    private var targetId: Int = -1
    internal var isMilitary: Boolean = false
    private var lifespan: Int = 200

    constructor(world: World) : super(world) {
        setSize(0.5f, 0.5f)
    }

    constructor(world: World, shooter: EntityLivingBase, target: EntityLivingBase, isMilitary: Boolean) : super(world) {
        this.targetId = target.entityId
        this.isMilitary = isMilitary
        setSize(0.5f, 0.5f)
        setPosition(shooter.posX, shooter.posY + 1.5, shooter.posZ)
    }

    override fun entityInit() {
        // 无需同步的数据字段
    }

    override fun onUpdate() {
        super.onUpdate()

        // lifespan 兜底
        lifespan--
        if (lifespan <= 0) {
            setDead()
            return
        }

        // 查找目标
        val target = world.getEntityByID(targetId)
        if (target == null || !target.isEntityAlive) {
            // 目标丢失/死亡，无人机自毁（不爆炸，直接消失）
            setDead()
            return
        }

        val dx = target.posX - posX
        val dy = (target.posY + target.eyeHeight * 0.5) - posY
        val dz = target.posZ - posZ
        val distance = Math.sqrt(dx * dx + dy * dy + dz * dz)

        if (distance < 1.2) {
            // 撞击目标，爆炸
            explode()
            setDead()
            return
        }

        // 朝向目标匀速飞行
        val speed = if (isMilitary) MILITARY_SPEED else CIVILIAN_SPEED
        // 平滑转向（避免瞬间急转弯）
        val targetMotionX = dx / distance * speed
        val targetMotionY = dy / distance * speed
        val targetMotionZ = dz / distance * speed
        motionX += (targetMotionX - motionX) * 0.3
        motionY += (targetMotionY - motionY) * 0.3
        motionZ += (targetMotionZ - motionZ) * 0.3

        posX += motionX
        posY += motionY
        posZ += motionZ
    }

    private fun explode() {
        if (world.isRemote) return
        val radius = if (isMilitary) MILITARY_RADIUS else CIVILIAN_RADIUS
        // 沿用原版爆炸：破坏方块 + 范围伤害，伤害由爆炸半径自然决定
        world.createExplosion(this, posX, posY, posZ, radius, true)
    }

    override fun writeEntityToNBT(compound: NBTTagCompound) {
        compound.setInteger("TargetId", targetId)
        compound.setBoolean("IsMilitary", isMilitary)
        compound.setInteger("Lifespan", lifespan)
    }

    override fun readEntityFromNBT(compound: NBTTagCompound) {
        targetId = compound.getInteger("TargetId")
        isMilitary = compound.getBoolean("IsMilitary")
        lifespan = compound.getInteger("Lifespan")
    }

    override fun writeSpawnData(buffer: ByteBuf) {
        buffer.writeInt(targetId)
        buffer.writeBoolean(isMilitary)
    }

    override fun readSpawnData(buffer: ByteBuf) {
        targetId = buffer.readInt()
        isMilitary = buffer.readBoolean()
    }

    internal companion object {
        private const val CIVILIAN_SPEED = 0.6
        private const val MILITARY_SPEED = 0.85
        private const val CIVILIAN_RADIUS = 2.0f
        private const val MILITARY_RADIUS = 3.0f
    }
}
