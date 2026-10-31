package com.tacz.legacy.common.network.message.event

import com.tacz.legacy.api.entity.ShootResult
import com.tacz.legacy.client.gameplay.LegacyClientShootCoordinator
import io.netty.buffer.ByteBuf
import net.minecraft.client.Minecraft
import net.minecraftforge.fml.common.network.simpleimpl.IMessage
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext

/**
 * S2C: 服务端回传一次射击请求的处理结果与最新枪械状态。
 *
 * 用途（修复高射速空枪）：
 * 1. 客户端每次射击后用它对齐服务端权威状态（弹药数/发热/锁枪），
 *    消除"客户端显示剩弹、服务端实际已空/已锁"导致的盲射空枪。
 * 2. result != SUCCESS 时，客户端立即停止连发并套用冷却，
 *    不再"开了枪但服务端没生成子弹实体"。
 */
public class ServerMessageShootState() : IMessage {
    private var resultOrdinal: Int = 0
    private var gunId: String? = null
    private var ammoCount: Int = 0
    private var heatAmount: Float = 0f
    private var overheatLocked: Boolean = false

    public constructor(
        result: ShootResult,
        gunId: String?,
        ammoCount: Int,
        heatAmount: Float,
        overheatLocked: Boolean,
    ) : this() {
        this.resultOrdinal = result.ordinal
        this.gunId = gunId
        this.ammoCount = ammoCount
        this.heatAmount = heatAmount
        this.overheatLocked = overheatLocked
    }

    override fun fromBytes(buf: ByteBuf) {
        resultOrdinal = buf.readInt()
        gunId = if (buf.readBoolean()) {
            val len = buf.readInt()
            val bytes = ByteArray(len)
            buf.readBytes(bytes)
            String(bytes, Charsets.UTF_8)
        } else {
            null
        }
        ammoCount = buf.readInt()
        heatAmount = buf.readFloat()
        overheatLocked = buf.readBoolean()
    }

    override fun toBytes(buf: ByteBuf) {
        buf.writeInt(resultOrdinal)
        val g = gunId
        buf.writeBoolean(g != null)
        if (g != null) {
            val bytes = g.toByteArray(Charsets.UTF_8)
            buf.writeInt(bytes.size)
            buf.writeBytes(bytes)
        }
        buf.writeInt(ammoCount)
        buf.writeFloat(heatAmount)
        buf.writeBoolean(overheatLocked)
    }

    public class Handler : IMessageHandler<ServerMessageShootState, IMessage?> {
        override fun onMessage(message: ServerMessageShootState, ctx: MessageContext): IMessage? {
            val mc = Minecraft.getMinecraft()
            mc.addScheduledTask {
                val max = ShootResult.values().lastIndex
                LegacyClientShootCoordinator.applyServerShootState(
                    result = ShootResult.values()[message.resultOrdinal.coerceIn(0, max)],
                    gunId = message.gunId,
                    ammoCount = message.ammoCount,
                    heatAmount = message.heatAmount,
                    overheatLocked = message.overheatLocked,
                )
            }
            return null
        }
    }
}
