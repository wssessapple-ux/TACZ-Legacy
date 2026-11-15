package com.tacz.legacy.common.event

import com.tacz.legacy.common.entity.EntityFpvDrone
import net.minecraft.entity.player.EntityPlayer
import net.minecraftforge.event.entity.player.AttackEntityEvent
import net.minecraftforge.event.entity.player.EntityItemPickupEvent
import net.minecraftforge.event.entity.player.PlayerInteractEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.PlayerEvent

/**
 * FPV 遥控期间的服务端兜底拦截：宿主玩家本体锁定，不能开火、不能交互/使用物品、
 * 不能攻击实体。遥控输入只映射给无人机。
 *
 * 退出 / 召回走独立 C2S 报文（[com.tacz.legacy.common.network.message.client.ClientMessageFpvExit]），
 * 不经过原版物品右键事件，因此这里不再为主手拿着无人机本体开放行口子。
 */
internal object FpvPilotGuardHandler {

    private fun shouldBlock(player: EntityPlayer): Boolean =
        EntityFpvDrone.isPiloting(player.uniqueID)

    @SubscribeEvent
    fun onRightClickBlock(event: PlayerInteractEvent.RightClickBlock) {
        if (shouldBlock(event.entityPlayer)) event.isCanceled = true
    }

    @SubscribeEvent
    fun onRightClickItem(event: PlayerInteractEvent.RightClickItem) {
        if (shouldBlock(event.entityPlayer)) event.isCanceled = true
    }

    @SubscribeEvent
    fun onEntityInteract(event: PlayerInteractEvent.EntityInteract) {
        if (shouldBlock(event.entityPlayer)) event.isCanceled = true
    }

    @SubscribeEvent
    fun onAttackEntity(event: AttackEntityEvent) {
        if (shouldBlock(event.entityPlayer)) event.isCanceled = true
    }

    @SubscribeEvent
    fun onPickupItem(event: EntityItemPickupEvent) {
        if (EntityFpvDrone.isPiloting(event.entityPlayer.uniqueID)) event.isCanceled = true
    }

    /** 玩家登出/掉线：清掉被控名单残留，避免下次进同一存档被脏状态一直拦下。 */
    @SubscribeEvent
    fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        EntityFpvDrone.removePiloting(event.player.uniqueID)
    }
}
