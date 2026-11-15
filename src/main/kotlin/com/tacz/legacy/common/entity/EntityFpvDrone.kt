package com.tacz.legacy.common.entity

import io.netty.buffer.ByteBuf
import com.tacz.legacy.common.item.LegacyItems
import com.tacz.legacy.common.network.TACZNetworkHandler
import com.tacz.legacy.common.network.message.event.ServerMessageFpvControlState
import net.minecraft.entity.Entity
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraft.block.material.Material
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.util.DamageSource
import net.minecraft.util.math.AxisAlignedBB
import net.minecraft.util.math.BlockPos
import net.minecraft.util.text.TextComponentTranslation
import net.minecraft.world.World
import net.minecraftforge.fml.common.network.ByteBufUtils
import net.minecraftforge.fml.common.registry.IEntityAdditionalSpawnData
import java.util.UUID

/**
 * FPV 第一人称侦察无人机实体（纯侦察，不带武器，可遥控、悬停、回收复用）。
 *
 * 状态机：
 * - [STATE_IDLE]：未被操控，原地悬停。
 * - [STATE_CONTROLLED]：由 [owner] 玩家遥控，按 C2S 上报的视角与 WASD 输入移动。
 *
 * 与自杀式 [EntityDrone] 的区别：自杀机一次性自动追踪自爆；本实体持续遥控、可回收、不攻击。
 */
internal class EntityFpvDrone : Entity, IEntityAdditionalSpawnData {

    internal companion object {
        const val STATE_IDLE = 0
        const val STATE_CONTROLLED = 1

        /** 水平/垂直飞行速度（格/tick）。 */
        const val SPEED = 0.32
        /** 与操控者的最大遥控距离，超出强制解除操控。 */
        const val MAX_RANGE = 128.0
        /** 接管旧机时离主人超过这个距离就先拉回头顶附近，避免一进去就贴超距线。 */
        const val RECALL_ON_ENTER_RANGE = 16.0
        /** 控制报文超时 tick 数：超过这么久没收到新输入就清空输入自动悬停（断连/卡顿兜底）。 */
        const val CONTROL_TIMEOUT = 20
        /** 失联释放 tick 数：超过这么久完全没收到报文（客户端脱挂/掉线/崩溃），自动解除操控，避免卡死受控态。 */
        const val CONTROL_LOST_TIMEOUT = 60
        /** 速度死区：目标归零后，分量绝对值小于该值直接钳零，避免松手后缓慢漂移。 */
        const val VELOCITY_DEADZONE = 0.01

        /** 机体最大血量（HP）。侦察机脆弱，几发子弹即可击落。 */
        const val MAX_HEALTH = 10f

        /** 不扣血的环境类伤害源：火焰/岩浆/挤压/溺水/坠落/撞墙/虚空等，无人机悬浮飞行不吃这类损耗。 */
        private val NON_COMBAT_SOURCES: Set<DamageSource> = setOf(
            DamageSource.IN_FIRE, DamageSource.ON_FIRE, DamageSource.LAVA, DamageSource.HOT_FLOOR,
            DamageSource.IN_WALL, DamageSource.CRAMMING, DamageSource.DROWN, DamageSource.STARVE,
            DamageSource.CACTUS, DamageSource.FALL, DamageSource.FLY_INTO_WALL, DamageSource.OUT_OF_WORLD,
        )

        /** 正在遥控某架 FPV 的玩家 UUID（服务端权威，兜底拦截开火/交互）。 */
        private val pilotingPlayers: MutableSet<UUID> = mutableSetOf()

        internal fun isPiloting(uuid: UUID?): Boolean = uuid != null && pilotingPlayers.contains(uuid)

        internal fun addPiloting(uuid: UUID) { pilotingPlayers.add(uuid) }

        internal fun removePiloting(uuid: UUID) { pilotingPlayers.remove(uuid) }
    }

    private var ownerUuid: UUID? = null
    private var state: Int = STATE_IDLE

    /** 最近一次 C2S 上报的操控输入（仅服务端使用）。 */
    private var inputForward: Float = 0f
    private var inputStrafe: Float = 0f
    private var inputUp: Float = 0f

    /** 最近一次收到控制报文的世界 tick（看门狗用，仅服务端）。 */
    private var lastControlTick: Long = 0

    /** 当前机体血量（仅服务端权威；客户端不参与扣血）。 */
    private var health: Float = MAX_HEALTH

    constructor(world: World) : super(world) {
        setSize(0.5f, 0.35f)
    }

    constructor(world: World, owner: EntityPlayer) : super(world) {
        this.ownerUuid = owner.uniqueID
        setSize(0.5f, 0.35f)
        setPosition(owner.posX, owner.posY + 1.8, owner.posZ)
        rotationYaw = owner.rotationYaw
        rotationPitch = 0f
    }

    override fun entityInit() {
        // 无可同步数据字段
    }

    internal fun ownerPlayer(): EntityPlayer? {
        val uuid = ownerUuid ?: return null
        return world.getPlayerEntityByUUID(uuid)
    }

    internal fun isOwner(player: EntityPlayer): Boolean =
        ownerUuid != null && player.uniqueID == ownerUuid

    internal fun isControlled(): Boolean = state == STATE_CONTROLLED

    internal fun startControl(player: EntityPlayer): Boolean {
        if (!isOwner(player)) return false
        // 自愈：当前这架并未受控，玩家却还残留在被控名单（上次异常退出没走 stopControl），
        // 先清掉脏状态，避免残留把后续进入一直拦下。
        if (isPiloting(player.uniqueID) && state != STATE_CONTROLLED) {
            removePiloting(player.uniqueID)
        }
        if (state == STATE_CONTROLLED) return false
        // 同一玩家同时只能控一架 FPV，避免一人多机双开视角。
        if (isPiloting(player.uniqueID)) return false

        // 复用旧机时机体可能停在上次飞远的位置；离主人过远就先拉回头顶附近再切视角，
        // 避免进入即贴超距线被服务端立刻踢回。
        val owner = ownerPlayer()
        if (owner != null && owner.getDistance(this) > RECALL_ON_ENTER_RANGE) {
            setPosition(player.posX, player.posY + 1.8, player.posZ)
            rotationYaw = player.rotationYaw
            rotationPitch = 0f
        }

        state = STATE_CONTROLLED
        lastControlTick = world.totalWorldTime
        ownerUuid?.let { addPiloting(it) }
        return true
    }

    internal fun stopControl(recall: Boolean) {
        releaseState()
        if (recall) {
            setDead()
        }
    }

    private fun releaseState() {
        state = STATE_IDLE
        inputForward = 0f
        inputStrafe = 0f
        inputUp = 0f
        ownerUuid?.let { removePiloting(it) }
    }

    override fun onRemovedFromWorld() {
        super.onRemovedFromWorld()
        // 实体被区块卸载/移除世界时兜底释放受控态与被控名单，避免静态残留卡死下次进入。
        if (!world.isRemote && state == STATE_CONTROLLED) {
            releaseState()
        }
    }

    /** 通知主人客户端进入/退出无人机视角（仅服务端调用）。 */
    private fun notifyControlState(controlled: Boolean) {
        val mp = ownerPlayer() as? EntityPlayerMP ?: return
        TACZNetworkHandler.sendToPlayer(ServerMessageFpvControlState(entityId, controlled), mp)
    }

    internal fun applyControl(yaw: Float, pitch: Float, forward: Float, strafe: Float, up: Float) {
        rotationYaw = yaw
        rotationPitch = pitch
        inputForward = forward.coerceIn(-1f, 1f)
        inputStrafe = strafe.coerceIn(-1f, 1f)
        inputUp = up.coerceIn(-1f, 1f)
        lastControlTick = world.totalWorldTime
    }

    override fun onUpdate() {
        super.onUpdate()

        if (world.isRemote) {
            // 客户端只做插值/渲染，移动由服务端权威计算。
            return
        }

        val owner = ownerPlayer()

        // 操控状态校验：主人离开世界、死亡或飞出遥控距离 → 解除操控，原地悬停。
        if (state == STATE_CONTROLLED) {
            if (owner == null || !owner.isEntityAlive) {
                stopControl(false)
                notifyControlState(false)
                return
            }
            if (owner.getDistance(this) > MAX_RANGE) {
                stopControl(false)
                notifyControlState(false)
                return
            }
            // 看门狗：超过超时阈值没收到新控制报文（卡顿/掉线/切后台），清空输入原地悬停，
            // 保持受控等报文恢复或玩家主动退出，避免按最后方向一直飞出去。
            val sinceLast = world.totalWorldTime - lastControlTick
            if (sinceLast > CONTROL_LOST_TIMEOUT) {
                // 长时间完全失联（客户端脱挂/掉线/崩溃），自动解除操控并通知客户端切回本体，
                // 避免服务端一直挂着受控态、地面右键被 PASS 卡死。
                stopControl(false)
                notifyControlState(false)
                return
            }
            if (sinceLast > CONTROL_TIMEOUT) {
                inputForward = 0f
                inputStrafe = 0f
                inputUp = 0f
            }
        }

        // 目标速度：受控时按输入方向，空闲时归零悬停。
        var targetX = 0.0
        var targetY = 0.0
        var targetZ = 0.0
        if (state == STATE_CONTROLLED) {
            val yawRad = Math.toRadians(rotationYaw.toDouble())
            val pitchRad = Math.toRadians(rotationPitch.toDouble())
            val cosPitch = Math.cos(pitchRad)
            // MC 视线方向：x=-sin(yaw)*cos(pitch), y=-sin(pitch), z=cos(yaw)*cos(pitch)
            val fwdX = -Math.sin(yawRad) * cosPitch
            val fwdY = -Math.sin(pitchRad)
            val fwdZ = Math.cos(yawRad) * cosPitch
            // 右方向（水平垂直于 yaw）
            val rightX = Math.cos(yawRad)
            val rightZ = Math.sin(yawRad)

            targetX = (fwdX * inputForward + rightX * inputStrafe) * SPEED
            targetY = (fwdY * inputForward + inputUp) * SPEED
            targetZ = (fwdZ * inputForward + rightZ * inputStrafe) * SPEED
        }

        // 平滑加减速，避免瞬间急刹/急加速；入水时响应更迟钝（水阻）。
        val inWater = isInsideWater()
        val damp = if (inWater) 0.12 else 0.25
        motionX += (targetX - motionX) * damp
        motionY += (targetY - motionY) * damp
        motionZ += (targetZ - motionZ) * damp

        // 速度死区：目标已归零且剩余分量很小，直接钳零，避免松手后缓慢漂移。
        if (targetX == 0.0 && Math.abs(motionX) < VELOCITY_DEADZONE) motionX = 0.0
        if (targetY == 0.0 && Math.abs(motionY) < VELOCITY_DEADZONE) motionY = 0.0
        if (targetZ == 0.0 && Math.abs(motionZ) < VELOCITY_DEADZONE) motionZ = 0.0

        // 入水额外水阻：水平速度打折扣，并缓慢下沉（无人机无浮力设计）。
        if (inWater) {
            motionX *= 0.7
            motionZ *= 0.7
            motionY -= 0.02
        }

        // 分轴碰撞：逐轴探测真实碰撞盒，撞哪轴清哪轴分量，
        // 不再一撞墙就把水平/垂直速度一起清零（否则贴墙后会被锁死在原地）。
        if (collidesBox(entityBoundingBox.offset(motionX, 0.0, 0.0))) motionX = 0.0
        if (collidesBox(entityBoundingBox.offset(0.0, 0.0, motionZ))) motionZ = 0.0
        if (collidesBox(entityBoundingBox.offset(0.0, motionY, 0.0))) motionY = 0.0

        // 防卡死：自身包围盒陷进实心/碰撞方块时先水平推离，推不动再向上托举。
        freeFromSolid()

        // 高度兜底：底部挡世界底面，顶部挡世界高度上限。
        if (posY + motionY < 0.0) {
            motionY = 0.0
        }
        if (posY + motionY > world.getHeight() - 1) {
            motionY = 0.0
        }

        posX += motionX
        posY += motionY
        posZ += motionZ
        setPosition(posX, posY, posZ)
        prevRotationYaw = rotationYaw
        prevRotationPitch = rotationPitch
    }

    /** 是否处于水中：手写位移不走 handleWaterMovement，[Entity.isInWater] 不会置位，直接查方块材质。 */
    private fun isInsideWater(): Boolean =
        world.getBlockState(BlockPos(posX, posY, posZ)).material === Material.WATER

    /** 探测指定包围盒是否与世界方块碰撞盒相交（不含实体碰撞）。 */
    private fun collidesBox(bb: AxisAlignedBB): Boolean =
        world.getCollisionBoxes(null, bb).isNotEmpty()

    /** 陷入碰撞方块时脱困：先四个水平方向各推一段，推不动再向上逐格托举；脱困后清零速度防压回。 */
    private fun freeFromSolid() {
        if (!collidesBox(entityBoundingBox)) return

        val horizontal = listOf(
            0.35 to 0.0, -0.35 to 0.0, 0.0 to 0.35, 0.0 to -0.35,
        )
        for ((dx, dz) in horizontal) {
            val probe = entityBoundingBox.offset(dx, 0.0, dz)
            if (!collidesBox(probe)) {
                posX += dx
                posZ += dz
                setPosition(posX, posY, posZ)
                motionX = 0.0
                motionY = 0.0
                motionZ = 0.0
                return
            }
        }

        // 水平推不动（横卡墙缝/夹层），向上托举最多 4 格。
        var guard = 0
        while (collidesBox(entityBoundingBox) && guard < 4) {
            posY += 1.0
            setPosition(posX, posY, posZ)
            guard++
        }
        motionX = 0.0
        motionY = 0.0
        motionZ = 0.0
    }

    override fun writeEntityToNBT(compound: NBTTagCompound) {
        ownerUuid?.let { compound.setString("Owner", it.toString()) }
        compound.setInteger("State", state)
        compound.setFloat("Yaw", rotationYaw)
        compound.setFloat("Pitch", rotationPitch)
        compound.setFloat("Health", health)
    }

    override fun readEntityFromNBT(compound: NBTTagCompound) {
        ownerUuid = if (compound.hasKey("Owner")) runCatching { UUID.fromString(compound.getString("Owner")) }.getOrNull() else null
        // 受控是临时会话态，不持久化：重登/区块重载后一律回到空闲，
        // 避免静态 pilotingPlayers 不恢复、state 却读回 CONTROLLED 的脏帧。
        state = STATE_IDLE
        inputForward = 0f
        inputStrafe = 0f
        inputUp = 0f
        rotationYaw = compound.getFloat("Yaw")
        rotationPitch = compound.getFloat("Pitch")
        prevRotationYaw = rotationYaw
        prevRotationPitch = rotationPitch
        health = if (compound.hasKey("Health")) compound.getFloat("Health").coerceIn(0f, MAX_HEALTH) else MAX_HEALTH
    }

    override fun writeSpawnData(buffer: ByteBuf) {
        buffer.writeInt(state)
        buffer.writeFloat(rotationYaw)
        buffer.writeFloat(rotationPitch)
        buffer.writeBoolean(ownerUuid != null)
        ownerUuid?.let { ByteBufUtils.writeUTF8String(buffer, it.toString()) }
    }

    override fun readSpawnData(additionalData: ByteBuf) {
        state = additionalData.readInt()
        rotationYaw = additionalData.readFloat()
        rotationPitch = additionalData.readFloat()
        prevRotationYaw = rotationYaw
        prevRotationPitch = rotationPitch
        ownerUuid = if (additionalData.readBoolean()) {
            runCatching { UUID.fromString(ByteBufUtils.readUTF8String(additionalData)) }.getOrNull()
        } else null
    }

    override fun canBeCollidedWith(): Boolean = true

    /**
     * 受击入口：FPV 继承 [Entity]（基类无血量字段），这里自管血量。
     * 只接受战斗类伤害（玩家子弹/近战/爆炸），环境损耗一律不扣血。
     */
    override fun attackEntityFrom(source: DamageSource, amount: Float): Boolean {
        if (world.isRemote) return false
        if (isEntityInvulnerable(source)) return false
        if (source in NON_COMBAT_SOURCES) return false

        health -= amount
        if (health <= 0f) {
            destroyed()
        }
        return true
    }

    /** 机体被击落：先把操控者视角弹回本体，移除背包内绑定的无人机物品，再销毁实体。 */
    private fun destroyed() {
        if (world.isRemote) return
        val owner = ownerPlayer() as? EntityPlayerMP
        if (state == STATE_CONTROLLED) {
            releaseState()
            notifyControlState(false)
        }
        if (owner != null) {
            removeBoundItems(owner)
            owner.sendMessage(TextComponentTranslation("message.tacz.fpv.destroyed"))
        }
        setDead()
    }

    /** 移除主人背包内所有 FPV 无人机物品：坠毁后物品不保留，需重新部署。 */
    private fun removeBoundItems(owner: EntityPlayerMP) {
        val inv = owner.inventory
        fun isDrone(stack: ItemStack): Boolean = !stack.isEmpty && stack.item === LegacyItems.FPV_DRONE
        for (i in inv.mainInventory.indices) {
            if (isDrone(inv.mainInventory[i])) inv.mainInventory[i] = ItemStack.EMPTY
        }
        if (inv.offHandInventory.isNotEmpty() && isDrone(inv.offHandInventory[0])) {
            inv.offHandInventory[0] = ItemStack.EMPTY
        }
    }
}
