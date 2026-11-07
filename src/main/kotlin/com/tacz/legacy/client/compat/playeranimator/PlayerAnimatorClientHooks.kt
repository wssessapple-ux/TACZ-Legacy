package com.tacz.legacy.client.compat.playeranimator

import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent

/**
 * 客户端 tick 钩子，把姿态选择驱动起来。
 *
 * 在 ClientProxy 里以 `register(PlayerAnimatorClientHooks::class.java)` 注册；
 * 1.12.2 的 Forge 只扫描静态订阅方法，所以这里必须是 `@JvmStatic`。
 */
internal object PlayerAnimatorClientHooks {

    @JvmStatic
    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) {
            return
        }
        PlayerAnimatorCompat.onClientTick()
    }
}
