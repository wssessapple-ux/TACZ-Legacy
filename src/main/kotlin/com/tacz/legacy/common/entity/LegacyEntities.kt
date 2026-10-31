package com.tacz.legacy.common.entity

import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.api.event.EntityHurtByGunEvent
import com.tacz.legacy.api.event.EntityKillByGunEvent
import com.tacz.legacy.api.modifier.Modifier
import com.tacz.legacy.common.resource.BulletCombatData
import com.tacz.legacy.common.resource.DistanceDamagePoint
import com.tacz.legacy.common.resource.TACZAttachmentModifierRegistry
import com.tacz.legacy.common.resource.TACZExplosionModifierValue
import com.tacz.legacy.common.resource.TACZGunPropertyResolver
import com.tacz.legacy.common.resource.TACZIgniteValue
import com.tacz.legacy.common.config.HeadShotAabbConfigRead
import com.tacz.legacy.common.config.LegacyConfigManager
import com.tacz.legacy.common.network.TACZNetworkHandler
import com.tacz.legacy.common.network.message.event.ServerMessageGunHurt
import com.tacz.legacy.common.network.message.event.ServerMessageGunKill
import io.netty.buffer.ByteBuf
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityList
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.item.EntityMinecartEmpty
import net.minecraft.entity.projectile.EntityThrowable
import net.minecraft.entity.monster.EntityEnderman
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.nbt.NBTTagList
import net.minecraft.init.Blocks
import net.minecraft.util.DamageSource
import net.minecraft.util.ResourceLocation
import net.minecraft.util.math.AxisAlignedBB
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.RayTraceResult
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.common.registry.EntityEntry
import net.minecraftforge.fml.common.registry.EntityEntryBuilder
import net.minecraftforge.fml.common.registry.IEntityAdditionalSpawnData
import net.minecraftforge.fml.common.network.ByteBufUtils
import net.minecraftforge.fml.relauncher.Side
import net.minecraftforge.fml.relauncher.SideOnly
import net.minecraftforge.registries.IForgeRegistry
import org.joml.Vector3d
import org.joml.Vector3f
import kotlin.math.max
import kotlin.math.sqrt

internal object LegacyEntities {
    internal val BULLET: EntityEntry = EntityEntryBuilder.create<EntityKineticBullet>()
        .entity(EntityKineticBullet::class.java)
        .id(ResourceLocation(TACZLegacy.MOD_ID, "bullet"), 0)
        .name("bullet")
        .tracker(64, 1, false)
        .build()

    internal val TARGET_MINECART: EntityEntry = EntityEntryBuilder.create<TargetMinecartEntity>()
        .entity(TargetMinecartEntity::class.java)
        .id(ResourceLocation(TACZLegacy.MOD_ID, "target_minecart"), 1)
        .name("target_minecart")
        .tracker(64, 1, true)
        .build()

    internal fun registerAll(registry: IForgeRegistry<EntityEntry>): Unit {
        registry.register(BULLET)
        registry.register(TARGET_MINECART)
    }
}

internal class EntityKineticBullet : EntityThrowable, IEntityAdditionalSpawnData {
    private data class HitFeedbackResult(
        val appliedDamage: Boolean,
        val baseDamage: Float,
        val headShotMultiplier: Float,
        val headShot: Boolean,
    )

    internal companion object {
        private const val DEFAULT_FORWARD_COMPONENT = 8.0
        private const val DEFAULT_INACCURACY_SCALE = 0.007499999832361937
        private const val FOCUSED_SMOKE_BULLET_SPEED_MULTIPLIER_PROPERTY = "tacz.focusedSmoke.bulletSpeedMultiplier"

        internal fun computeShotDirection(pitch: Double, yaw: Double, spreadX: Double, spreadY: Double): Vec3d {
            val direction = Vector3d(spreadX, spreadY, DEFAULT_FORWARD_COMPONENT)
            direction.rotateX(Math.toRadians(pitch))
            direction.rotateY(Math.toRadians(-yaw))
            val length = direction.length()
            if (length <= 1.0E-8) {
                return Vec3d.ZERO
            }
            return Vec3d(direction.x / length, direction.y / length, direction.z / length)
        }

        private fun resolveFocusedSmokeBulletSpeedMultiplier(): Double {
            val rawValue = System.getProperty(FOCUSED_SMOKE_BULLET_SPEED_MULTIPLIER_PROPERTY)?.toDoubleOrNull()
                ?: return 1.0
            if (!rawValue.isFinite() || rawValue <= 0.0) {
                return 1.0
            }
            return rawValue
        }
    }

    private var damage: Float = 5.0f
    private var bulletSpeed: Float = 5.0f
    private var bulletGravity: Float = 0.0f
    private var friction: Float = 0.01f
    private var pierce: Int = 1
    private var lifespan: Int = 200
    private var knockback: Float = 0.0f
    private var igniteEntity: Boolean = false
    private var igniteEntityTime: Int = 2
    private var igniteBlock: Boolean = false
    private var isTracerAmmo: Boolean = false
    private var damageModifier: Float = 1.0f
    private var armorIgnore: Float = 0.0f
    private var headShotMultiplier: Float = 1.0f
    private val damageAdjust: MutableList<DistanceDamagePoint> = mutableListOf()
    private var startPosX: Double = 0.0
    private var startPosY: Double = 0.0
    private var startPosZ: Double = 0.0
    private var shooterEntityId: Int = -1

    // Explosion properties
    private var hasExplosionData: Boolean = false
    private var hasExplosion: Boolean = false
    private var explosionRadius: Float = 0f
    private var explosionDamage: Float = 0f
    private var explosionKnockback: Boolean = false
    private var explosionDestroyBlock: Boolean = false
    private var explosionDelay: Float = 0f
    /** 配置伤害倍率是否已烘进 damage / damageAdjust（见 bakeDamageBaseMultiplier） */
    private var damageBaseBaked: Boolean = false

    /** 发射的枪械 ID，供下游渲染/音效使用 */
    internal var gunId: ResourceLocation = ResourceLocation(TACZLegacy.MOD_ID, "unknown")
        private set
    /** 枪械 display ID，供下游渲染使用 */
    internal var gunDisplayId: ResourceLocation = ResourceLocation(TACZLegacy.MOD_ID, "default")
        private set
    /** 弹药 ID，供下游渲染/特效使用  */
    internal var ammoId: ResourceLocation = ResourceLocation(TACZLegacy.MOD_ID, "empty")
        private set
    internal var firstPersonRenderOffset: Vector3f? = null
    internal var firstPersonCameraPitch: Float? = null
    internal var firstPersonCameraYaw: Float? = null

    /** NBT / network deserialization constructor */
    constructor(worldIn: World) : super(worldIn) {
        setSize(0.1f, 0.1f)
    }

    /** 数据驱动构造器：从 BulletCombatData 获取所有弹道参数 */
    constructor(
        worldIn: World,
        shooter: EntityLivingBase,
        bulletData: BulletCombatData,
        gunId: ResourceLocation,
        gunDisplayId: ResourceLocation,
        ammoId: ResourceLocation,
        isTracer: Boolean,
    ) : super(worldIn, shooter) {
        setSize(0.1f, 0.1f)
        this.shooterEntityId = shooter.entityId
        this.damage = bulletData.damage
        this.bulletSpeed = bulletData.speed
        this.bulletGravity = bulletData.gravity
        this.friction = bulletData.friction
        this.pierce = bulletData.pierce
        this.lifespan = bulletData.getLifeTicks()
        this.knockback = bulletData.knockback
        this.igniteEntity = bulletData.igniteEntity
        this.igniteEntityTime = bulletData.igniteEntityTime
        this.igniteBlock = bulletData.igniteBlock
        this.isTracerAmmo = isTracer
        this.gunId = gunId
        this.gunDisplayId = gunDisplayId
        this.ammoId = ammoId
        resetInitialPosition(shooter)
        this.startPosX = posX
        this.startPosY = posY
        this.startPosZ = posZ

        val extraDamage = bulletData.extraDamageData
        this.armorIgnore = ((extraDamage?.armorIgnore ?: 0.0f) * LegacyConfigManager.server.armorIgnoreBaseMultiplier.toFloat())
            .coerceIn(0.0f, 1.0f)
        this.headShotMultiplier = max((extraDamage?.headShotMultiplier ?: 1.0f) * LegacyConfigManager.server.headShotBaseMultiplier.toFloat(), 0.0f)
        this.damageAdjust.clear()
        this.damageAdjust.addAll(extraDamage?.damageAdjust.orEmpty())
        
        val exp = bulletData.explosionData
        // 兼容层 ③ C2-exp：hasExplosionData = 是否写了 explosion 段（与是否点亮无关），
        // 供配件爆炸聚合判断默认基线（上游 ExplosionModifier.initCache 的 hasData 语义）。
        this.hasExplosionData = exp != null
        if (exp != null && exp.explode) {
            this.hasExplosion = true
            this.explosionRadius = exp.radius
            this.explosionDamage = exp.damage
            this.explosionKnockback = exp.knockback
            this.explosionDestroyBlock = exp.destroyBlock
            this.explosionDelay = if (exp.delay < 0.0f) Float.POSITIVE_INFINITY else exp.delay
        }
    }

    /** 霰弹伤害分摊。与上游 applyShotgunDamageSpread 一致。 */
    internal fun applyShotgunDamageSpread(bulletCount: Int) {
        if (bulletCount > 1) {
            damageModifier = 1.0f / bulletCount
        }
    }

    /** 应用配件点燃 modifier（配件 ignite 覆盖枪械基础点燃属性）。 */
    internal fun applyAttachmentIgnite(ignite: TACZIgniteValue) {
        this.igniteEntity = ignite.igniteEntity
        this.igniteBlock = ignite.igniteBlock
    }

    // 兼容层 ③：配件 modifier 接入子弹属性（armor_ignore/head_shot/pierce）。
    // 用子弹当前值作为 default，eval 配件 modifier 后覆盖（对齐上游 EntityKineticBullet 读缓存）。
    // 注意这里的下限/区间不是"额外 clamp"：上游子弹构造器同样在 eval 之后做
    // armorIgnore.clamp(0,1)、headShot.max(0)、pierce.clamp(1,MAX)。
    // knockback 已豁免：命中击退已完全移除（applyImpactKnockback 已删），
    // 接入配件 knockback modifier 没有实际消费点，见守门测试登记注释。
    internal fun applyAttachmentModifiers(mods: TACZGunPropertyResolver.AttachmentModifierLists) {
        val registry = TACZAttachmentModifierRegistry
        if (mods.armorIgnore.isNotEmpty()) {
            armorIgnore = registry.evalNumeric(mods.armorIgnore, armorIgnore.toDouble()).toFloat().coerceIn(0f, 1f)
        }
        if (mods.headShot.isNotEmpty()) {
            headShotMultiplier = registry.evalNumeric(mods.headShot, headShotMultiplier.toDouble()).toFloat().coerceAtLeast(0f)
        }
        if (mods.pierce.isNotEmpty()) {
            // 上游 PierceModifier：eval 后 clamp(1, MAX)。0 或负数会被上游抬到 1，
            // 这里也必须抬到 1，否则 onUpdate 会在第一只目标后直接销毁子弹。
            pierce = Math.round(registry.evalNumeric(mods.pierce, pierce.toDouble())).toInt().coerceAtLeast(1)
        }
    }

    // 兼容层 ③ C1-2：配件 damage modifier 逐距离段 eval（对齐上游 DamageModifier.java:77-86，
    // 对 damageAdjust 列表的每个距离点分别套用 eval 公式），无 modifier 时不覆盖
    internal fun applyAttachmentDamageModifier(modifiers: List<Modifier>) {
        if (modifiers.isEmpty()) return
        val registry = TACZAttachmentModifierRegistry
        // 审计 B1-9：配置倍率必须先烘进基准值再 eval（上游 DamageModifier.initCache 先
        // (base + fireAdjust) * DAMAGE_BASE_MULTIPLIER，之后才 eval 配件），
        // 否则配件的**绝对值** addend 会被一起乘倍率，语义就偏了。
        bakeDamageBaseMultiplier()
        damage = registry.evalNumeric(modifiers, damage.toDouble()).toFloat()
        for (i in damageAdjust.indices) {
            val point = damageAdjust[i]
            damageAdjust[i] = point.copy(
                damage = registry.evalNumeric(modifiers, point.damage.toDouble()).toFloat(),
            )
        }
    }

    // 兼容层 ③ C2-ext：effective_range 修正距离衰减曲线第一段的距离（对齐上游
    // EffectiveRangeModifier.java:39-51 + EntityKineticBullet）
    internal fun applyAttachmentEffectiveRange(modifiers: List<Modifier>) {
        if (modifiers.isEmpty() || damageAdjust.isEmpty()) return
        val registry = TACZAttachmentModifierRegistry
        val firstPoint = damageAdjust[0]
        val newDistance = registry.evalNumeric(modifiers, firstPoint.distance.toDouble()).toFloat()
        damageAdjust[0] = firstPoint.copy(distance = newDistance)
    }

    // 兼容层 ③ C2-exp：explosion 聚合（对齐上游 ExplosionModifier.eval:45-79）
    // 计算本体在 TACZExplosionAggregation（纯函数，可单测）；这里只负责把结果写回子弹字段。
    // 入参是解析期成型的 typed 值（TACZExplosionModifierReader），不在每发子弹路径上碰 Gson。
    internal fun applyAttachmentExplosion(explosions: List<TACZExplosionModifierValue>) {
        val aggregated = TACZExplosionAggregation.aggregate(
            TACZExplosionAggregation.Base(
                hasData = hasExplosionData,
                explode = hasExplosion,
                radius = explosionRadius,
                damage = explosionDamage,
                knockback = explosionKnockback,
                destroyBlock = explosionDestroyBlock,
                delay = explosionDelay,
            ),
            explosions,
        ) ?: return

        hasExplosion = true
        explosionRadius = aggregated.radius
        explosionDamage = aggregated.damage
        explosionKnockback = aggregated.knockback
        explosionDestroyBlock = aggregated.destroyBlock
        explosionDelay = aggregated.delay
    }

    /**
     * 把配置项 `damage_base_multiplier` 烘进基准伤害与距离曲线各档。
     *
     * 审计 B1-9：旧实现在结算处事后相乘，配件的**绝对值** modifier 会被一起乘 ——
     * `damage:{"addend":5}` 在上游是 `base*m + 5`，在移植版是 `(base + 5) * m`。
     * 默认包只用 multiplier/percent（比例不变）且 m 默认 1.0，现网不可见，但语义确实偏了。
     * 幂等：只烘一次；无配件的枪在首次结算处补烘，保证任何路径都恰好乘一遍。
     */
    private fun bakeDamageBaseMultiplier() {
        if (damageBaseBaked) return
        damageBaseBaked = true
        val multiplier = LegacyConfigManager.server.damageBaseMultiplier.toFloat()
        if (multiplier == 1.0f) return
        damage *= multiplier
        for (i in damageAdjust.indices) {
            damageAdjust[i] = damageAdjust[i].let { point -> point.copy(damage = point.damage * multiplier) }
        }
    }

    internal fun shootFromRotation(shooter: Entity, pitch: Float, yaw: Float, velocity: Float, inaccuracy: Float) {
        val spreadX = rand.nextGaussian() * DEFAULT_INACCURACY_SCALE * inaccuracy.toDouble()
        val spreadY = rand.nextGaussian() * DEFAULT_INACCURACY_SCALE * inaccuracy.toDouble()
        shootFromRotation(shooter, pitch, yaw, velocity, spreadX, spreadY)
    }

    internal fun shootFromRotation(shooter: Entity, pitch: Float, yaw: Float, velocity: Float, spreadX: Double, spreadY: Double) {
        val actualVelocity = velocity.toDouble() * resolveFocusedSmokeBulletSpeedMultiplier()
        val direction = computeShotDirection(pitch.toDouble(), yaw.toDouble(), spreadX, spreadY).scale(actualVelocity)
        motionX = direction.x
        motionY = direction.y
        motionZ = direction.z

        val horizontalDistance = sqrt(motionX * motionX + motionZ * motionZ)
        rotationYaw = Math.toDegrees(Math.atan2(motionX, motionZ)).toFloat()
        rotationPitch = Math.toDegrees(Math.atan2(motionY, horizontalDistance)).toFloat()
        prevRotationYaw = rotationYaw
        prevRotationPitch = rotationPitch

        motionX += shooter.motionX
        motionZ += shooter.motionZ
        if (!shooter.onGround) {
            motionY += shooter.motionY
        }
    }

    private fun resetInitialPosition(shooter: EntityLivingBase) {
        val interpolatedX = shooter.lastTickPosX + (shooter.posX - shooter.lastTickPosX) / 2.0
        val interpolatedY = shooter.lastTickPosY + (shooter.posY - shooter.lastTickPosY) / 2.0 + shooter.eyeHeight.toDouble()
        val interpolatedZ = shooter.lastTickPosZ + (shooter.posZ - shooter.lastTickPosZ) / 2.0
        setPosition(interpolatedX, interpolatedY, interpolatedZ)
    }

    /** 获取实际伤害（含霰弹分摊） */
    internal fun getEffectiveDamage(): Float = resolveEffectiveDamageAt(Vec3d(posX, posY, posZ))

    internal fun isTracerAmmo(): Boolean = isTracerAmmo

    internal fun getShooterForRender(): EntityLivingBase? {
        val cached = thrower
        if (cached != null && !cached.isDead) {
            return cached
        }
        if (shooterEntityId < 0) {
            return null
        }
        val resolved = world.getEntityByID(shooterEntityId) as? EntityLivingBase ?: return null
        thrower = resolved
        return resolved
    }

    override fun writeSpawnData(buffer: ByteBuf) {
        buffer.writeFloat(rotationPitch)
        buffer.writeFloat(rotationYaw)
        buffer.writeDouble(motionX)
        buffer.writeDouble(motionY)
        buffer.writeDouble(motionZ)
        ByteBufUtils.writeUTF8String(buffer, gunId.toString())
        ByteBufUtils.writeUTF8String(buffer, gunDisplayId.toString())
        ByteBufUtils.writeUTF8String(buffer, ammoId.toString())
        buffer.writeBoolean(isTracerAmmo)
        buffer.writeInt(shooterEntityId)
        buffer.writeFloat(bulletGravity)
        buffer.writeFloat(friction)
        buffer.writeInt(lifespan)
    }

    override fun readSpawnData(additionalData: ByteBuf) {
        rotationPitch = additionalData.readFloat()
        rotationYaw = additionalData.readFloat()
        prevRotationPitch = rotationPitch
        prevRotationYaw = rotationYaw
        motionX = additionalData.readDouble()
        motionY = additionalData.readDouble()
        motionZ = additionalData.readDouble()
        gunId = ResourceLocation(ByteBufUtils.readUTF8String(additionalData))
        gunDisplayId = ResourceLocation(ByteBufUtils.readUTF8String(additionalData))
        ammoId = ResourceLocation(ByteBufUtils.readUTF8String(additionalData))
        isTracerAmmo = additionalData.readBoolean()
        shooterEntityId = additionalData.readInt()
        bulletGravity = additionalData.readFloat()
        friction = additionalData.readFloat()
        lifespan = additionalData.readInt()
        getShooterForRender()
        if (world.isRemote && java.lang.Boolean.getBoolean("tacz.focusedSmoke")) {
            TACZLegacy.logger.info(
                "[FocusedSmoke] BULLET_CLIENT_SPAWN entityId={} gun={} display={} ammo={} tracer={} shooterId={} shooterResolved={}",
                entityId,
                gunId,
                gunDisplayId,
                ammoId,
                isTracerAmmo,
                shooterEntityId,
                getShooterForRender() != null,
            )
        }
    }

    override fun getGravityVelocity(): Float = bulletGravity

    override fun onImpact(result: RayTraceResult) {
        if (world.isRemote) return

        when (result.typeOfHit) {
            RayTraceResult.Type.ENTITY -> {
                val target = result.entityHit ?: return
                val hitPos = result.hitVec ?: Vec3d(target.posX, target.posY + target.height * 0.5, target.posZ)
                val headShot = target is EntityLivingBase && isHeadShot(target, hitPos)
                if (target != thrower) {
                    val feedback = applyDirectHitDamage(target, hitPos, headShot)
                    if (hasExplosion) {
                        triggerExplosion(hitPos)
                        emitHitFeedback(target, feedback)
                        setDead()
                        return
                    }
                    emitHitFeedback(target, feedback)
                }
                pierce--
                if (pierce <= 0) setDead()
            }
            RayTraceResult.Type.BLOCK -> {
                val hitPos = result.hitVec ?: Vec3d(posX, posY, posZ)
                if (hasExplosion) {
                    triggerExplosion(hitPos)
                    setDead()
                    return
                }
                if (igniteBlock && thrower != null) {
                    val pos = result.blockPos.offset(result.sideHit)
                    if (world.isAirBlock(pos) && LegacyConfigManager.common.igniteBlock) {
                        world.setBlockState(pos, Blocks.FIRE.defaultState)
                    }
                }
                setDead()
            }
            else -> {}
        }
    }

    override fun writeEntityToNBT(compound: NBTTagCompound) {
        super.writeEntityToNBT(compound)
        compound.setFloat("Damage", damage)
        compound.setFloat("Speed", bulletSpeed)
        compound.setFloat("Gravity", bulletGravity)
        compound.setFloat("Friction", friction)
        compound.setInteger("Pierce", pierce)
        compound.setFloat("Knockback", knockback)
        compound.setBoolean("IgniteEntity", igniteEntity)
        compound.setInteger("IgniteEntityTime", igniteEntityTime)
        compound.setBoolean("IgniteBlock", igniteBlock)
        compound.setBoolean("IsTracer", isTracerAmmo)
        compound.setFloat("ArmorIgnore", armorIgnore)
        compound.setFloat("HeadShotMultiplier", headShotMultiplier)
        compound.setDouble("StartPosX", startPosX)
        compound.setDouble("StartPosY", startPosY)
        compound.setDouble("StartPosZ", startPosZ)
        compound.setInteger("ShooterEntityId", shooterEntityId)
        val damageAdjustList = NBTTagList()
        damageAdjust.forEach { pair ->
            val entry = NBTTagCompound()
            entry.setFloat("Distance", pair.distance)
            entry.setFloat("Damage", pair.damage)
            damageAdjustList.appendTag(entry)
        }
        compound.setTag("DamageAdjust", damageAdjustList)
        
        compound.setBoolean("HasExplosion", hasExplosion)
        if (hasExplosion) {
            compound.setFloat("ExplosionRadius", explosionRadius)
            compound.setFloat("ExplosionDamage", explosionDamage)
            compound.setBoolean("ExplosionKnockback", explosionKnockback)
            compound.setBoolean("ExplosionDestroyBlock", explosionDestroyBlock)
            compound.setBoolean("ExplosionDelayInfinite", explosionDelay.isInfinite())
            if (!explosionDelay.isInfinite()) {
                compound.setFloat("ExplosionDelay", explosionDelay)
            }
        }
        compound.setString("GunId", gunId.toString())
        compound.setString("GunDisplayId", gunDisplayId.toString())
        compound.setString("AmmoId", ammoId.toString())
        compound.setFloat("DamageModifier", damageModifier)
    }

    private fun triggerExplosion(position: Vec3d = Vec3d(posX, posY, posZ)) {
        LegacyProjectileExplosionHelper.createExplosion(
            owner = thrower,
            exploder = this,
            x = position.x,
            y = position.y,
            z = position.z,
            damage = explosionDamage,
            radius = explosionRadius,
            knockback = explosionKnockback,
            destroyBlock = explosionDestroyBlock,
        )
    }

    override fun readEntityFromNBT(compound: NBTTagCompound) {
        super.readEntityFromNBT(compound)
        damage = compound.getFloat("Damage")
        bulletSpeed = compound.getFloat("Speed")
        bulletGravity = compound.getFloat("Gravity")
        friction = if (compound.hasKey("Friction")) compound.getFloat("Friction") else 0.01f
        pierce = compound.getInteger("Pierce")
        knockback = if (compound.hasKey("Knockback")) compound.getFloat("Knockback") else 0.0f
        igniteEntity = compound.getBoolean("IgniteEntity")
        igniteEntityTime = if (compound.hasKey("IgniteEntityTime")) compound.getInteger("IgniteEntityTime") else 2
        igniteBlock = compound.getBoolean("IgniteBlock")
        isTracerAmmo = compound.getBoolean("IsTracer")
        armorIgnore = if (compound.hasKey("ArmorIgnore")) compound.getFloat("ArmorIgnore") else 0.0f
        headShotMultiplier = if (compound.hasKey("HeadShotMultiplier")) compound.getFloat("HeadShotMultiplier") else 1.0f
        startPosX = if (compound.hasKey("StartPosX")) compound.getDouble("StartPosX") else posX
        startPosY = if (compound.hasKey("StartPosY")) compound.getDouble("StartPosY") else posY
        startPosZ = if (compound.hasKey("StartPosZ")) compound.getDouble("StartPosZ") else posZ
        shooterEntityId = if (compound.hasKey("ShooterEntityId")) compound.getInteger("ShooterEntityId") else -1
        getShooterForRender()
        damageAdjust.clear()
        if (compound.hasKey("DamageAdjust")) {
            val list = compound.getTagList("DamageAdjust", 10)
            for (index in 0 until list.tagCount()) {
                val entry = list.getCompoundTagAt(index)
                damageAdjust += DistanceDamagePoint(
                    distance = entry.getFloat("Distance"),
                    damage = entry.getFloat("Damage"),
                )
            }
        }
        
        hasExplosion = compound.getBoolean("HasExplosion")
        if (hasExplosion) {
            explosionRadius = compound.getFloat("ExplosionRadius")
            explosionDamage = compound.getFloat("ExplosionDamage")
            explosionKnockback = compound.getBoolean("ExplosionKnockback")
            explosionDestroyBlock = compound.getBoolean("ExplosionDestroyBlock")
            explosionDelay = if (compound.getBoolean("ExplosionDelayInfinite")) {
                Float.POSITIVE_INFINITY
            } else {
                compound.getFloat("ExplosionDelay")
            }
        }
        if (compound.hasKey("GunId")) gunId = ResourceLocation(compound.getString("GunId"))
        if (compound.hasKey("GunDisplayId")) gunDisplayId = ResourceLocation(compound.getString("GunDisplayId"))
        if (compound.hasKey("AmmoId")) ammoId = ResourceLocation(compound.getString("AmmoId"))
        damageModifier = if (compound.hasKey("DamageModifier")) compound.getFloat("DamageModifier") else 1.0f
    }

    /**
     * Override to skip rotation sync from server packets.
     * The server sends byte-compressed (256-step) rotation values every tick,
     * which contaminate [rotationYaw]/[rotationPitch] precision. Our [onUpdate]
     * computes precise rotation from motion locally, so we only accept position.
     */
    @SideOnly(Side.CLIENT)
    override fun setPositionAndRotationDirect(
        x: Double, y: Double, z: Double,
        yaw: Float, pitch: Float,
        posRotationIncrements: Int, teleport: Boolean,
    ) {
        setPosition(x, y, z)
    }

    /**
     * Block velocity sync packets from overwriting client motion vectors.
     * Upstream uses [setShouldReceiveVelocityUpdates(false)] so the client
     * computes position/motion locally from spawn data + physics. In 1.12
     * we set tracker to not send velocity, but override this as extra safety.
     */
    @SideOnly(Side.CLIENT)
    override fun setVelocity(x: Double, y: Double, z: Double) {
        // no-op: client computes motion locally
    }

    override fun onUpdate() {
        // 完全绕过 EntityThrowable.onUpdate() 的碰撞检测、0.99f 拖拽与 0.2 旋转插值。
        // 与上游 TACZ EntityKineticBullet.tick() + onBulletTick() 逻辑对齐。
        lastTickPosX = posX
        lastTickPosY = posY
        lastTickPosZ = posZ
        // Entity.onUpdate() — 只跑计时器和骑乘逻辑
        super.onEntityUpdate()

        // ---- 服务端碰撞检测 (对齐上游 onBulletTick) ----
        if (!world.isRemote) {
            if (hasExplosion && explosionDelay.isFinite()) {
                explosionDelay -= 0.05f
                if (explosionDelay <= 0 && !isDead) {
                    triggerExplosion()
                    setDead()
                    return
                }
            }

            val startVec = Vec3d(posX, posY, posZ)
            var endVec = Vec3d(posX + motionX, posY + motionY, posZ + motionZ)

            // 方块碰撞
            val blockResult = world.rayTraceBlocks(startVec, endVec)
            if (blockResult != null && blockResult.typeOfHit == RayTraceResult.Type.BLOCK) {
                endVec = blockResult.hitVec
            }

            // 实体碰撞
            val entitiesInRange = world.getEntitiesWithinAABBExcludingEntity(
                this,
                entityBoundingBox.expand(motionX, motionY, motionZ).grow(1.0),
            )
            val hitEntities = mutableListOf<Pair<Entity, Vec3d>>()
            for (entity in entitiesInRange) {
                if (!entity.canBeCollidedWith()) continue
                if (entity === thrower && ticksExisted < 5) continue
                val aabb = entity.entityBoundingBox.grow(0.3)
                val intercept = aabb.calculateIntercept(startVec, endVec)
                if (intercept != null) {
                    hitEntities += entity to intercept.hitVec
                }
            }
            // 按距离排序
            hitEntities.sortBy { (_, hitVec) -> startVec.squareDistanceTo(hitVec) }

            // 处理实体命中
            for ((entity, hitVec) in hitEntities) {
                val entityHitResult = RayTraceResult(entity, hitVec)
                if (!net.minecraftforge.event.ForgeEventFactory.onProjectileImpact(this, entityHitResult)) {
                    onImpact(entityHitResult)
                }
                if (isDead) return
                if (pierce < 1) {
                    setDead()
                    return
                }
            }

            // 处理方块命中（在实体命中之后，与上游一致）
            if (blockResult != null && blockResult.typeOfHit == RayTraceResult.Type.BLOCK) {
                if (!net.minecraftforge.event.ForgeEventFactory.onProjectileImpact(this, blockResult)) {
                    onImpact(blockResult)
                }
                if (isDead) return
            }
        }

        // ---- 位置与旋转更新 (对齐上游 tick) ----
        posX += motionX
        posY += motionY
        posZ += motionZ

        val horizontalDist = sqrt(motionX * motionX + motionZ * motionZ)
        val targetYaw = (Math.toDegrees(Math.atan2(motionX, motionZ))).toFloat()
        val targetPitch = (Math.toDegrees(Math.atan2(motionY, horizontalDist))).toFloat()
        if (prevRotationPitch == 0.0f && prevRotationYaw == 0.0f) {
            prevRotationYaw = targetYaw
            prevRotationPitch = targetPitch
        }
        rotationYaw = lerpRotation(prevRotationYaw, targetYaw)
        rotationPitch = lerpRotation(prevRotationPitch, targetPitch)
        // Do NOT set prevRotation = rotation here.
        // Entity.onEntityUpdate() sets prev = current at the START of each tick,
        // matching upstream TACZ where super.tick() → Entity.tick() does the same.
        // Setting it here kills render-frame interpolation (prev == current → no lerp).

        setPosition(posX, posY, posZ)

        // ---- 阻力与重力 (只用 TACZ 的参数，不叠加 vanilla 0.99f) ----
        var frictionFactor = friction
        var gravityValue = bulletGravity
        if (isInWater) {
            frictionFactor = 0.4f
            gravityValue *= 0.6f
        }
        motionX *= (1.0 - frictionFactor)
        motionY *= (1.0 - frictionFactor)
        motionZ *= (1.0 - frictionFactor)
        motionY -= gravityValue * 0.05F

        // ---- 寿命检查 ----
        lifespan--
        if (lifespan <= 0) setDead()
    }

    private fun lerpRotation(previous: Float, current: Float): Float {
        var delta = MathHelper.wrapDegrees(current - previous)
        delta = delta.coerceIn(-180.0f, 180.0f)
        return previous + delta * 0.2f
    }

    private fun applyDirectHitDamage(target: Entity, hitPosition: Vec3d, headShot: Boolean): HitFeedbackResult {
        val totalDamage = resolveEffectiveDamageAt(hitPosition)
        if (totalDamage <= 0.0f) {
            return HitFeedbackResult(false, 0.0f, 1.0f, headShot)
        }
        val headShotDamage = if (headShot) totalDamage * headShotMultiplier else totalDamage
        val damageSplit = splitDamage(headShotDamage)
        if (target is EntityLivingBase) {
            target.hurtResistantTime = 0
        }
        var applied = false
        if (damageSplit.first > 0.0f) {
            applied = target.attackEntityFrom(createBulletDamageSource(target, ignoreArmor = false), damageSplit.first) || applied
        }
        if (damageSplit.second > 0.0f) {
            if (target is EntityLivingBase) {
                target.hurtResistantTime = 0
            }
            applied = target.attackEntityFrom(createBulletDamageSource(target, ignoreArmor = true), damageSplit.second) || applied
        }
        // 原版 TaCZ 子弹无击退，移植对齐：移除命中击退（knockback 字段保留用于存档兼容与爆炸配置）。
        if (igniteEntity && !target.world.isRemote && LegacyConfigManager.common.igniteEntity) {
            target.setFire(igniteEntityTime)
        }
        return HitFeedbackResult(
            appliedDamage = applied,
            baseDamage = totalDamage,
            headShotMultiplier = if (headShot) headShotMultiplier else 1.0f,
            headShot = headShot,
        )
    }

    private fun emitHitFeedback(target: Entity, feedback: HitFeedbackResult) {
        if (world.isRemote) {
            return
        }
        if (!feedback.appliedDamage) {
            return
        }
        val attacker = thrower as? EntityLivingBase
        if (target is EntityLivingBase && !target.isEntityAlive) {
            MinecraftForge.EVENT_BUS.post(
                EntityKillByGunEvent(
                    bullet = this,
                    killedEntity = target,
                    attacker = attacker,
                    gunId = gunId,
                    gunDisplayId = gunDisplayId,
                    baseDamage = feedback.baseDamage,
                    isHeadShot = feedback.headShot,
                    headShotMultiplier = feedback.headShotMultiplier,
                    logicalSide = Side.SERVER,
                ),
            )
            TACZNetworkHandler.sendToDimension(
                ServerMessageGunKill(
                    bulletId = entityId,
                    killedEntityId = target.entityId,
                    attackerId = attacker?.entityId ?: -1,
                    gunId = gunId,
                    gunDisplayId = gunDisplayId,
                    baseDamage = feedback.baseDamage,
                    isHeadShot = feedback.headShot,
                    headShotMultiplier = feedback.headShotMultiplier,
                ),
                world.provider.dimension,
            )
            return
        }

        MinecraftForge.EVENT_BUS.post(
            EntityHurtByGunEvent.Post(
                bullet = this,
                hurtEntity = target,
                attacker = attacker,
                gunId = gunId,
                gunDisplayId = gunDisplayId,
                baseAmount = feedback.baseDamage,
                isHeadShot = feedback.headShot,
                headShotMultiplier = feedback.headShotMultiplier,
                logicalSide = Side.SERVER,
            ),
        )
        TACZNetworkHandler.sendToDimension(
            ServerMessageGunHurt(
                bulletId = entityId,
                hurtEntityId = target.entityId,
                attackerId = attacker?.entityId ?: -1,
                gunId = gunId,
                gunDisplayId = gunDisplayId,
                baseAmount = feedback.baseDamage,
                isHeadShot = feedback.headShot,
                headShotMultiplier = feedback.headShotMultiplier,
            ),
            world.provider.dimension,
        )
    }

    private fun createBulletDamageSource(target: Entity?, ignoreArmor: Boolean): DamageSource {
        val damageSource = if (target is EntityEnderman && thrower is EntityLivingBase) {
            // 末影人对 EntityThrowable 会瞬移躲子弹（1.12.2 原版分支）。
            // 对末影人改用非抛射伤害源（immediateSource=射手，非 EntityThrowable），
            // 使其走原版 else 正常伤害、不瞬移；对齐官方高版本"末影人几乎躲不了子弹"。
            DamageSource.causeMobDamage(thrower)
        } else {
            DamageSource.causeThrownDamage(this, thrower)
        }
        val src = if (ignoreArmor) damageSource.setDamageBypassesArmor() else damageSource
        return src.setProjectile()
    }

    private fun resolveEffectiveDamageAt(hitPosition: Vec3d): Float {
        bakeDamageBaseMultiplier()
        val origin = Vec3d(startPosX, startPosY, startPosZ)
        val travelDistance = origin.distanceTo(hitPosition)
        val adjustedDamage = if (damageAdjust.isEmpty()) {
            damage
        } else {
            // 审计 B1-10：超出最后一个**有限**距离档 → 0（对齐上游 getDamage，
            // "如果忘记写最大值，那我就直接认为你伤害为 0"），不是回落末档伤害。
            // 末档写成 infinity 的枪两条路径一致。
            damageAdjust.firstOrNull { travelDistance < it.distance || it.distance.isInfinite() }?.damage ?: 0f
        }
        // 顺序对齐上游：距离曲线伤害（已含配置倍率，见 bakeDamageBaseMultiplier）* 霰弹分摊（damageModifier），
        // 最后统一抬到 0。注意：配置倍率已烘进 damage/damageAdjust，这里不再乘 damage_base_multiplier。
        return (adjustedDamage * damageModifier).coerceAtLeast(0.0f)
    }

    private fun splitDamage(totalDamage: Float): Pair<Float, Float> {
        val armorPiercingDamage = (totalDamage * armorIgnore.coerceIn(0.0f, 1.0f)).coerceAtLeast(0.0f)
        val normalDamage = (totalDamage - armorPiercingDamage).coerceAtLeast(0.0f)
        return normalDamage to armorPiercingDamage
    }

    private fun isHeadShot(target: EntityLivingBase, hitPosition: Vec3d): Boolean {
        return resolveHeadShotBox(target).contains(hitPosition)
    }

    private fun resolveHeadShotBox(target: EntityLivingBase): AxisAlignedBB {
        val configured = EntityList.getKey(target)?.let(HeadShotAabbConfigRead::getAabb)
        if (configured != null) {
            return configured.offset(target.posX, target.posY, target.posZ).grow(0.01)
        }
        val halfWidth = target.width / 2.0
        return AxisAlignedBB(
            target.posX - halfWidth,
            target.posY + target.eyeHeight - 0.25,
            target.posZ - halfWidth,
            target.posX + halfWidth,
            target.posY + target.eyeHeight + 0.25,
            target.posZ + halfWidth,
        ).grow(0.01)
    }
}

internal class TargetMinecartEntity : EntityMinecartEmpty {
    constructor(worldIn: World) : super(worldIn)

    constructor(worldIn: World, x: Double, y: Double, z: Double) : super(worldIn, x, y, z)
}
