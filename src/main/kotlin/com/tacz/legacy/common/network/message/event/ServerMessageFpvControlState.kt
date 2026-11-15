package com.tacz.legacy.common.network.message.event

import com.tacz.legacy.client.fpv.FpvController
import io.netty.buffer.ByteBuf
import net.minecraft.client.Minecraft
import net.minecraftforge.fml.common.network.simpleimpl.IMessage
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext

/**
 * S2C: 通知客户端进入/退出某架 FPV 无人机视角。
 */
public class ServerMessageFpvControlState() : IMessage {
    private var droneId: Int = -1
    private var controlled: Boolean = false

    public constructor(droneId: Int, controlled: Boolean) : this() {
        this.droneId = droneId
        this.controlled = controlled
    }

    override fun fromBytes(buf: ByteBuf) {
        droneId = buf.readInt()
        controlled = buf.readBoolean()
    }

    override fun toBytes(buf: ByteBuf) {
        buf.writeInt(droneId)
        buf.writeBoolean(controlled)
    }

    public class Handler : IMessageHandler<ServerMessageFpvControlState, IMessage?> {
        override fun onMessage(message: ServerMessageFpvControlState, ctx: MessageContext): IMessage? {
            val mc = Minecraft.getMinecraft()
            mc.addScheduledTask {
                if (message.controlled) {
                    FpvController.attach(message.droneId)
                } else {
                    FpvController.detach()
                }
            }
            return null
        }
    }
}
