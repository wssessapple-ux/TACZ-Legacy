package com.tacz.legacy.client.fpv

import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import net.minecraftforge.fml.relauncher.Side
import net.minecraftforge.fml.relauncher.SideOnly

/**
 * 客户端 tick 驱动：FPV 视角中每帧上报操控输入。
 */
@SideOnly(Side.CLIENT)
internal object FpvClientHandler {

    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        FpvController.onClientTick()
    }
}
