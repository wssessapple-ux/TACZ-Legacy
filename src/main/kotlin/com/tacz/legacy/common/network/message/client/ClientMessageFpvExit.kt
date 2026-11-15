package com.tacz.legacy.common.network.message.client

import com.tacz.legacy.common.entity.EntityFpvDrone
import com.tacz.legacy.common.item.LegacyItems
import com.tacz.legacy.common.network.TACZNetworkHandler
import com.tacz.legacy.common.network.message.event.ServerMessageFpvControlState
import io.netty.buffer.ByteBuf
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraft.util.text.TextComponentTranslation
import net.minecraftforge.fml.common.network.simpleimpl.IMessage
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext

/**
 * C2S: 请求退出 FPV 遥控视角。
 *
 * - [recall] = false：普通退出，无人机原地悬停保留；
 * - [recall] = true：潜行右键召回，实体回收销毁，物品 NBT 上的实体 ID 一并清除。
 *
 * 退出不再走原版物品右键链路、也不依赖主手是否还拿着无人机：客户端在 FPV 视角里右键直接
 * 发本条报文，服务端校验归属与受控状态后权威退出并回 S2C 通知客户端切回本体视角。
 */
public class ClientMessageFpvExit() : IMessage, IMessageHandler<ClientMessageFpvExit, IMessage?> {
    private var droneId: Int = -1
    private var recall: Boolean = false

    public constructor(droneId: Int, recall: Boolean) : this() {
        this.droneId = droneId
        this.recall = recall
    }

    override fun fromBytes(buf: ByteBuf) {
        droneId = buf.readInt()
        recall = buf.readBoolean()
    }

    override fun toBytes(buf: ByteBuf) {
        buf.writeInt(droneId)
        buf.writeBoolean(recall)
    }

    override fun onMessage(message: ClientMessageFpvExit, ctx: MessageContext): IMessage? {
        val player: EntityPlayerMP = ctx.serverHandler.player
        player.serverWorld.addScheduledTask {
            val drone = player.serverWorld.getEntityByID(message.droneId) as? EntityFpvDrone
            if (drone == null || !drone.isOwner(player) || !drone.isControlled()) {
                // 受控态已不在（看门狗解除/实体失效）：仍回 detach 通知让客户端切回本体，
                // 避免客户端卡在视角里干等退出回包。
                TACZNetworkHandler.sendToPlayer(ServerMessageFpvControlState(message.droneId, controlled = false), player)
                return@addScheduledTask
            }

            drone.stopControl(message.recall)
            if (message.recall) {
                clearBoundDroneId(player)
                player.sendMessage(TextComponentTranslation("message.tacz.fpv.recalled"))
            } else {
                player.sendMessage(TextComponentTranslation("message.tacz.fpv.exited"))
            }
            TACZNetworkHandler.sendToPlayer(ServerMessageFpvControlState(drone.entityId, controlled = false), player)
        }
        return null
    }

    /** 召回后清掉玩家身上所有 FPV 无人机物品上绑定的旧实体 ID。 */
    private fun clearBoundDroneId(player: EntityPlayerMP) {
        val slots = player.inventory.mainInventory + listOfNotNull(player.inventory.offHandInventory.firstOrNull())
        for (stack in slots) {
            if (!stack.isEmpty && stack.item === LegacyItems.FPV_DRONE) {
                stack.tagCompound?.removeTag("DroneId")
            }
        }
    }
}
