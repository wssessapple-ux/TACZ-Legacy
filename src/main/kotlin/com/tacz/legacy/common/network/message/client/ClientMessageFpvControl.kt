package com.tacz.legacy.common.network.message.client

import com.tacz.legacy.common.entity.EntityFpvDrone
import io.netty.buffer.ByteBuf
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraftforge.fml.common.network.simpleimpl.IMessage
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext

/**
 * C2S: FPV 无人机操控输入（视角 + 前后/平移/升降）。
 * 仅在玩家已进入某架 FPV 视角时由客户端每 tick 上报，服务端做权威移动并校验归属。
 */
public class ClientMessageFpvControl() : IMessage, IMessageHandler<ClientMessageFpvControl, IMessage?> {
    private var droneId: Int = -1
    private var yaw: Float = 0f
    private var pitch: Float = 0f
    private var forward: Float = 0f
    private var strafe: Float = 0f
    private var up: Float = 0f

    public constructor(droneId: Int, yaw: Float, pitch: Float, forward: Float, strafe: Float, up: Float) : this() {
        this.droneId = droneId
        this.yaw = yaw
        this.pitch = pitch
        this.forward = forward
        this.strafe = strafe
        this.up = up
    }

    override fun fromBytes(buf: ByteBuf) {
        droneId = buf.readInt()
        yaw = buf.readFloat()
        pitch = buf.readFloat()
        forward = buf.readFloat()
        strafe = buf.readFloat()
        up = buf.readFloat()
    }

    override fun toBytes(buf: ByteBuf) {
        buf.writeInt(droneId)
        buf.writeFloat(yaw)
        buf.writeFloat(pitch)
        buf.writeFloat(forward)
        buf.writeFloat(strafe)
        buf.writeFloat(up)
    }

    override fun onMessage(message: ClientMessageFpvControl, ctx: MessageContext): IMessage? {
        val player: EntityPlayerMP = ctx.serverHandler.player
        player.serverWorld.addScheduledTask {
            val drone = player.serverWorld.getEntityByID(message.droneId) as? EntityFpvDrone ?: return@addScheduledTask
            if (!drone.isOwner(player) || !drone.isControlled()) return@addScheduledTask
            drone.applyControl(message.yaw, message.pitch, message.forward, message.strafe, message.up)
        }
        return null
    }
}
