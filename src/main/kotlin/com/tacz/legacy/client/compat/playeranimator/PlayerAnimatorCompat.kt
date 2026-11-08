package com.tacz.legacy.client.compat.playeranimator

import com.tacz.legacy.common.resource.TACZRuntimeSnapshot
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.player.EntityPlayer
import net.minecraftforge.fml.common.Loader

/**
 * 第三人称玩家动画的软依赖入口。
 *
 * 本类刻意 **不引用** `dev.kosmx.*` 下的任何类型，也不在字段/方法签名里出现它们：
 * 玩家没装 PlayerAnimator 时，[PlayerAnimatorBridge] 永远不会被类加载，
 * 因此不会出现 NoClassDefFoundError。真正的实现全部集中在 [PlayerAnimatorBridge] 里。
 *
 * 装了库 → 第三人称手臂/腿部/躯干由 playeranimator 的 ModelBiped mixin 驱动；
 * 没装 → 一切照旧，退回 [com.tacz.legacy.mixin.minecraft.client.ModelBipedGunPoseMixin]
 * 的写死手臂角度。
 */
internal object PlayerAnimatorCompat {

    const val MOD_ID: String = "playeranimator"

    private var availabilityCache: Boolean? = null

    /** 库是否在场。结果缓存，避免每帧调 Loader。 */
    @JvmStatic
    fun isAvailable(): Boolean {
        availabilityCache?.let { return it }
        val available: Boolean = try {
            Loader.isModLoaded(MOD_ID)
        } catch (_: Throwable) {
            false
        }
        availabilityCache = available
        return available
    }

    /**
     * 桥接实例。这里用 Any 存放，避免 [PlayerAnimatorCompat] 的字段签名引用桥接类。
     */
    private var bridgeRef: Any? = null

    private fun bridge(): PlayerAnimatorBridge? {
        if (!isAvailable()) {
            return null
        }
        (bridgeRef as? PlayerAnimatorBridge)?.let { return it }
        val created = try {
            PlayerAnimatorBridge()
        } catch (_: Throwable) {
            return null
        }
        bridgeRef = created
        return created
    }

    /**
     * 原生手臂姿势是否要让路给 PlayerAnimator。
     *
     * **按枪判断**，不是全局判断：只有当前这把枪真的解析出动画、且层里确实有东西在播时
     * 才让路；枪包里没写 `player_animator_3rd` 的枪继续用 [ModelBipedGunPoseMixin]
     * 的写死姿势，不至于变成“手臂垂着、枪飘在腰上”。
     */
    @JvmStatic
    fun shouldSkipVanillaArmPose(entity: EntityLivingBase?): Boolean {
        if (!isAvailable()) return false
        if (entity == null) return false
        val bridge = bridge() ?: return false
        return try {
            bridge.isAnimated(entity)
        } catch (_: Throwable) {
            false
        }
    }

    /** gun pack 客户端资源重载完成后调用，重新收集 player_animator 动画文件。 */
    fun reload(snapshot: TACZRuntimeSnapshot) {
        bridge()?.reload(snapshot)
    }

    /** 客户端每 tick 驱动一次姿态选择。 */
    fun onClientTick() {
        bridge()?.onClientTick()
    }

    /** 成功打出一发子弹时调用（不是每 tick 调，只在真开火时调）。 */
    fun onShoot(player: EntityPlayer?) {
        bridge()?.onShoot(player)
    }

    /** 近战挥砍时调用。 */
    fun onMelee(player: EntityPlayer?) {
        bridge()?.onMelee(player)
    }

    /** 枪包列表清空时释放缓存的 clip 数据（不动已挂在玩家身上的 layer）。 */
    fun clear() {
        (bridgeRef as? PlayerAnimatorBridge)?.clear()
    }
}
