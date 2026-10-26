package com.tacz.legacy.common.network.message.event

import com.tacz.legacy.api.event.GunShootEvent
import io.netty.buffer.ByteBuf
import net.minecraft.client.Minecraft
import net.minecraft.entity.EntityLivingBase
import net.minecraft.util.ResourceLocation
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.common.network.ByteBufUtils
import net.minecraftforge.fml.common.network.simpleimpl.IMessage
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext
import net.minecraftforge.fml.relauncher.Side

/**
 * S2C: 广播射击事件给其他客户端（用于第三人称动画/音效）。
 * 瘦身版：只传 entityId + gunId，不再携带整把枪的 ItemStack（含附件/弹药/耐久 NBT）。
 */
public class ServerMessageGunShoot() : IMessage {
    private var entityId: Int = 0
    private var gunId: ResourceLocation = ResourceLocation("minecraft:air")

    public constructor(entityId: Int, gunId: ResourceLocation) : this() {
        this.entityId = entityId
        this.gunId = gunId
    }

    override fun fromBytes(buf: ByteBuf) {
        entityId = buf.readInt()
        gunId = ResourceLocation(ByteBufUtils.readUTF8String(buf))
    }

    override fun toBytes(buf: ByteBuf) {
        buf.writeInt(entityId)
        ByteBufUtils.writeUTF8String(buf, gunId.toString())
    }

    public class Handler : IMessageHandler<ServerMessageGunShoot, IMessage?> {
        override fun onMessage(message: ServerMessageGunShoot, ctx: MessageContext): IMessage? {
            val mc = Minecraft.getMinecraft()
            mc.addScheduledTask {
                val world = mc.world ?: return@addScheduledTask
                val entity = world.getEntityByID(message.entityId)
                if (entity is EntityLivingBase) {
                    MinecraftForge.EVENT_BUS.post(GunShootEvent(entity, message.gunId, Side.CLIENT))
                }
            }
            return null
        }
    }
}
