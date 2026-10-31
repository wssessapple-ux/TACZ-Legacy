package com.tacz.legacy.common.resource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 兼容层 ③ 治理项：**注册表 ⊆ 消费面**的守门测试（对齐 Next 分支）。
 *
 * 为什么需要：modifier 消费层曾经脱钩过一次 —— 16 个 modifier 注册在案，但只有少数真正进了玩法
 * （其余只影响 GUI 显示、或者完全没人读）。这种缺陷单测和启动都不会报错，只能靠人肉核对。
 *
 * 这个测试的作用是：以后任何人往 `TACZAttachmentModifierRegistry.readers` 里新增一个 id
 * 却没有在玩法里消费它，[consumers] 与注册表就不再相等，测试立刻变红。
 *
 * 维护方式：**接入一个 modifier，就在这里登记它的玩法消费点**。这不是重复劳动 ——
 * 它是"注册了但没人读"这个缺陷唯一能被机器发现的入口。
 */
class ModifierConsumptionCoverageTest {

    /**
     * 每个已注册 modifier 的**玩法侧**消费点（GUI 预览不算玩法消费）。
     *
     * 只写"谁在读这个值"，不写具体行号 —— 行号会漂移，类名与方法名相对稳定。
     *
     * 当前登记已接入战斗的 15 个。剩余 1 个（knockback）为豁免项。
     * 每接入一个，就在此补一行登记。
     * 注：knockback 属豁免项 —— 命中击退已完全移除（applyImpactKnockback 已删），配件 knockback
     * modifier 没有实际消费点，保留注册但无需玩法消费（后续要重新启用击退时再补登记）。
     */
    private val consumers: Map<String, String> = mapOf(
        "ignite" to "LivingEntityShoot.performSingleFire / applyAttachmentIgnite（配件点燃覆盖）",
        "inaccuracy" to "TACZGunPropertyResolver.resolveInaccuracyProfile（战斗精度）",
        "recoil" to "TACZGunPropertyResolver.resolveCameraRecoil（后坐力）",
        "silence" to "TACZGunSoundRouting.resolveNearbyFireSoundProfile（枪声距离/静音）",
        // 第一批（子弹层）：armor_ignore/head_shot/pierce 经 EntityKineticBullet.applyAttachmentModifiers，
        // damage 经 applyAttachmentDamageModifier（逐距离段），effective_range 经 applyAttachmentEffectiveRange，
        // ammo_speed 在 LivingEntityShoot.performSingleFire 发射时 eval。
        "armor_ignore" to "EntityKineticBullet.applyAttachmentModifiers（护甲穿透）",
        "head_shot" to "EntityKineticBullet.applyAttachmentModifiers（爆头倍率）",
        "pierce" to "EntityKineticBullet.applyAttachmentModifiers（穿透）",
        "damage" to "EntityKineticBullet.applyAttachmentDamageModifier（逐距离段伤害）",
        "effective_range" to "EntityKineticBullet.applyAttachmentEffectiveRange（距离衰减第一段）",
        "ammo_speed" to "LivingEntityShoot.performSingleFire（弹速）",
        // 第二批：explosion 经 TACZExplosionAggregation 聚合写回子弹（typed，热路径不碰 Gson）；
        // rpm 经 GunDataAccessor.getShootIntervalMs 接入射速间隔（LivingEntityShoot/TACZGunScriptAPI/LegacyClientShootCoordinator 三处）。
        "explosion" to "EntityKineticBullet.applyAttachmentExplosion / TACZExplosionAggregation（爆炸聚合）",
        "rpm" to "GunDataAccessor.getShootIntervalMs（射速间隔）",
        // 第二批（ads/movement_speed/weight_modifier）：
        // ads 经 LivingEntityAim.tickAimingProgress eval 修正开镜时长；
        // movement_speed / weight_modifier 经 LivingEntitySpeedModifier.updateSpeedModifier 挂到
        // MOVEMENT_SPEED 的 AttributeModifier（weight→MULTIPLY_BASE 惩罚、movement_speed→MULTIPLY_TOTAL），
        // 由 ShooterTickHandler.onLivingTick 每 tick 驱动（客户端+服务端）。
        "ads" to "LivingEntityAim.tickAimingProgress（开镜时长）",
        "movement_speed" to "LivingEntitySpeedModifier.updateSpeedModifier（移速修正 MULTIPLY_TOTAL）",
        "weight_modifier" to "LivingEntitySpeedModifier.updateSpeedModifier（重量惩罚 MULTIPLY_BASE）",
    )

    /** 豁免项：注册但明确无需玩法消费（无实际生效点）。当前仅 knockback —— 命中击退已完全移除。 */
    private val exempted: Set<String> = setOf("knockback")

    @Test
    fun `every registered modifier id has a gameplay consumer`() {
        val registered = TACZAttachmentModifierRegistry.registeredModifierIds()
        val unclaimed = registered - consumers.keys - exempted
        assertTrue(
            "这些 modifier 只注册了、没有任何玩法消费点：$unclaimed。" +
                "要么接进玩法，要么从读者注册表里删掉 —— 不要留下\"注册了但没人读\"的假象。",
            unclaimed.isEmpty(),
        )
    }

    @Test
    fun `consumer list has no stale entries`() {
        val registered = TACZAttachmentModifierRegistry.registeredModifierIds()
        val stale = consumers.keys - registered
        assertTrue(
            "消费清单里出现了未注册的 modifier id：$stale（注册表或消费方被改过，二者要同步）。",
            stale.isEmpty(),
        )
    }

    @Test
    fun `registry keeps the upstream modifier count`() {
        // 上游 @SerializedName 路径对应的 16 个 modifier。
        assertEquals(
            "注册的 modifier 数量变了：如果是有意新增，请同步上面 consumers 里的消费点登记。",
            16,
            TACZAttachmentModifierRegistry.registeredModifierIds().size,
        )
    }
}
