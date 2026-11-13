package com.tacz.legacy.common.network.message.client

import com.tacz.legacy.common.inventory.ChunkLoaderContainer
import io.netty.buffer.ByteBuf
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraftforge.fml.common.network.simpleimpl.IMessage
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext

/**
 * C2S: 区块加载器面板切换范围档位。
 */
public class ClientMessageChunkLoaderCycle() : IMessage, IMessageHandler<ClientMessageChunkLoaderCycle, IMessage?> {
    private var windowId: Int = -1

    public constructor(windowId: Int) : this() {
        this.windowId = windowId
    }

    override fun fromBytes(buf: ByteBuf) {
        windowId = buf.readInt()
    }

    override fun toBytes(buf: ByteBuf) {
        buf.writeInt(windowId)
    }

    override fun onMessage(message: ClientMessageChunkLoaderCycle, ctx: MessageContext): IMessage? {
        val player: EntityPlayerMP = ctx.serverHandler.player
        player.serverWorld.addScheduledTask {
            val container = player.openContainer as? ChunkLoaderContainer ?: return@addScheduledTask
            if (container.windowId != message.windowId || !container.canInteractWith(player)) {
                return@addScheduledTask
            }
            container.tile?.cycleRange()
        }
        return null
    }
}
