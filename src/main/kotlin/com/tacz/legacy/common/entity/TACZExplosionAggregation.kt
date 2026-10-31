package com.tacz.legacy.common.entity

import com.tacz.legacy.api.modifier.ModifierEvaluator
import com.tacz.legacy.common.resource.TACZExplosionModifierValue

/**
 * 兼容层 ③ C2-exp：爆炸聚合的**纯计算**部分。
 *
 * 从 [EntityKineticBullet] 里抽出来是为了能被单测覆盖 —— 审计 B1-1 的数值错误
 * （默认包 M107 装 HE 弹匣后爆炸伤害从 100 变成 2）正发生在这一步，而子弹实体
 * 需要 World 才能构造，游戏内验证成本高。
 *
 * 对齐上游两处：
 * - `ExplosionModifier.initCache:36-42` —— 只有 `explosionData == null` 才用默认值，
 *   有数据时原样保留（**含 `explode:false` 的那些数值**）；
 * - `ExplosionModifier.eval:45-79` —— radius/damage/delay 走 Modifier 四元组，
 *   default 取 cacheValue 的值且**不看 isExplode**；explode/knockback/destroy_block 是布尔 OR，
 *   cacheValue 的三个布尔无条件进列表。
 * - `EntityKineticBullet:204` 半径下限 0；`:207-213` 的 `delay < 0 → 永不定时爆炸` 映射
 *   发生在聚合**之后**，所以配件能把负 delay 拉回有限值。
 */
internal object TACZExplosionAggregation {

    /** 上游 initCache 里 `new ExplosionData(false, 0.5f, 2, false, 30, false)` 的三个数值 */
    internal const val DEFAULT_RADIUS: Float = 0.5f
    internal const val DEFAULT_DAMAGE: Float = 2f
    internal const val DEFAULT_DELAY: Float = 30f

    /** 子弹侧的爆炸基线（来自弹药/枪械数据，未经配件修改） */
    internal data class Base(
        /** 枪是否写了 `explosion` 段（对应上游 `explosionData != null`），与是否已点亮无关 */
        val hasData: Boolean,
        val explode: Boolean,
        val radius: Float,
        val damage: Float,
        val knockback: Boolean,
        val destroyBlock: Boolean,
        val delay: Float,
    )

    internal data class Aggregated(
        val radius: Float,
        val damage: Float,
        val knockback: Boolean,
        val destroyBlock: Boolean,
        val delay: Float,
    )

    /**
     * @return 聚合结果；`explosions` 为空或聚合后仍不会爆炸时返回 null（调用方保持原状态）。
     */
    internal fun aggregate(
        base: Base,
        explosions: List<TACZExplosionModifierValue>,
    ): Aggregated? {
        if (explosions.isEmpty()) return null
        // 上游 :67 —— 子弹自身已点亮时不提前返回，配件仍然可以改数值
        val explode = base.explode || explosions.any { it.explode }
        if (!explode) return null

        val radius = ModifierEvaluator.eval(
            explosions.map { it.radius },
            (if (base.hasData) base.radius else DEFAULT_RADIUS).toDouble(),
        ).toFloat().coerceAtLeast(0.0f)
        val damage = ModifierEvaluator.eval(
            explosions.map { it.damage },
            (if (base.hasData) base.damage else DEFAULT_DAMAGE).toDouble(),
        ).toFloat()
        val delay = ModifierEvaluator.eval(
            explosions.map { it.delay },
            (if (base.hasData) base.delay else DEFAULT_DELAY).toDouble(),
        ).toFloat()
        // 上游 :53/:55 —— cacheValue 的两个布尔**不以 isExplode 为条件**进 OR 列表
        val knockback = (base.hasData && base.knockback) || explosions.any { it.knockback }
        val destroyBlock = (base.hasData && base.destroyBlock) || explosions.any { it.destroyBlock }

        return Aggregated(
            radius = radius,
            damage = damage,
            knockback = knockback,
            destroyBlock = destroyBlock,
            delay = normalizeDelay(delay),
        )
    }

    /** 上游 EntityKineticBullet:207-210：负 delay 表示"不做定时爆炸"（映射为无限延时）。 */
    internal fun normalizeDelay(delay: Float): Float =
        if (delay < 0.0f) Float.POSITIVE_INFINITY else delay
}
