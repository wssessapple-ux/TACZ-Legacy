package com.tacz.legacy.common.event

import com.tacz.legacy.common.block.entity.ChunkLoaderTileEntity
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World
import net.minecraftforge.common.ForgeChunkManager

/**
 * 世界加载时把持久化的区块票挂回对应 TE。
 *
 * 票的 modData 里记录了方块坐标：
 * - 方块还在且 TE 激活：挂回 TE，由 TE 重新 force 区块；
 * - 方块已不存在（残留票）：直接释放，防止永久强加载泄漏；
 * - TE 存在但当前未激活：释放票，等红石信号到来时再申请。
 */
internal object ChunkLoaderTicketCallback : ForgeChunkManager.LoadingCallback {
    override fun ticketsLoaded(tickets: List<ForgeChunkManager.Ticket>, world: World) {
        for (ticket in tickets) {
            val data = ticket.modDataSafe
            val pos = BlockPos(
                data.getLong("X").toInt(),
                data.getLong("Y").toInt(),
                data.getLong("Z").toInt(),
            )
            val te = world.getTileEntity(pos) as? ChunkLoaderTileEntity
            if (te == null) {
                ForgeChunkManager.releaseTicket(ticket)
                continue
            }
            if (!te.active) {
                ForgeChunkManager.releaseTicket(ticket)
                continue
            }
            te.attachTicket(ticket)
        }
    }

    private val ForgeChunkManager.Ticket.modDataSafe: NBTTagCompound
        get() = getModData()
}
