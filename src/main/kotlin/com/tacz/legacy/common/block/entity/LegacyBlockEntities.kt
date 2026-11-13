package com.tacz.legacy.common.block.entity

import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.api.DefaultAssets
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.tileentity.TileEntity
import net.minecraft.util.ITickable
import net.minecraft.util.ResourceLocation
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraftforge.common.ForgeChunkManager
import net.minecraftforge.fml.common.registry.GameRegistry

internal object LegacyBlockEntities {
    private var registered: Boolean = false

    internal fun registerAll(): Unit {
        if (registered) {
            return
        }
        GameRegistry.registerTileEntity(GunSmithTableTileEntity::class.java, ResourceLocation(TACZLegacy.MOD_ID, "gun_smith_table"))
        GameRegistry.registerTileEntity(TargetTileEntity::class.java, ResourceLocation(TACZLegacy.MOD_ID, "target"))
        GameRegistry.registerTileEntity(StatueTileEntity::class.java, ResourceLocation(TACZLegacy.MOD_ID, "statue"))
        GameRegistry.registerTileEntity(ChunkLoaderTileEntity::class.java, ResourceLocation(TACZLegacy.MOD_ID, "chunk_loader"))
        registered = true
    }
}

internal class GunSmithTableTileEntity : TileEntity() {
    internal var blockId: ResourceLocation = DefaultAssets.DEFAULT_BLOCK_ID

    override fun readFromNBT(compound: NBTTagCompound): Unit {
        super.readFromNBT(compound)
        if (compound.hasKey("BlockId")) {
            blockId = ResourceLocation(compound.getString("BlockId"))
        }
    }

    override fun writeToNBT(compound: NBTTagCompound): NBTTagCompound {
        val tag = super.writeToNBT(compound)
        tag.setString("BlockId", blockId.toString())
        return tag
    }

    override fun getUpdateTag(): NBTTagCompound = writeToNBT(NBTTagCompound())

    override fun getUpdatePacket(): net.minecraft.network.play.server.SPacketUpdateTileEntity =
        net.minecraft.network.play.server.SPacketUpdateTileEntity(pos, 0, updateTag)

    override fun onDataPacket(net: net.minecraft.network.NetworkManager, pkt: net.minecraft.network.play.server.SPacketUpdateTileEntity) {
        readFromNBT(pkt.nbtCompound)
    }
}

internal class TargetTileEntity : TileEntity() {
    internal var triggered: Boolean = false

    internal fun trigger(): Unit {
        triggered = true
        markDirty()
    }

    internal fun reset(): Unit {
        triggered = false
        markDirty()
    }

    override fun readFromNBT(compound: NBTTagCompound): Unit {
        super.readFromNBT(compound)
        triggered = compound.getBoolean("Triggered")
    }

    override fun writeToNBT(compound: NBTTagCompound): NBTTagCompound {
        val tag = super.writeToNBT(compound)
        tag.setBoolean("Triggered", triggered)
        return tag
    }
}

internal class StatueTileEntity : TileEntity() {
    internal var storedItem: ItemStack = ItemStack.EMPTY

    internal fun store(stack: ItemStack): Unit {
        storedItem = stack.copy()
        markDirty()
    }

    internal fun clear(): Unit {
        storedItem = ItemStack.EMPTY
        markDirty()
    }

    override fun readFromNBT(compound: NBTTagCompound): Unit {
        super.readFromNBT(compound)
        storedItem = if (compound.hasKey("StoredItem")) {
            ItemStack(compound.getCompoundTag("StoredItem"))
        } else {
            ItemStack.EMPTY
        }
    }

    override fun writeToNBT(compound: NBTTagCompound): NBTTagCompound {
        val tag = super.writeToNBT(compound)
        if (!storedItem.isEmpty) {
            tag.setTag("StoredItem", storedItem.writeToNBT(NBTTagCompound()))
        }
        return tag
    }
}

/**
 * 区块加载器方块实体。
 *
 * 走 ForgeChunkManager NORMAL 票：红石有信号时强制加载以自身为中心的一片区块，
 * 信号断开释放。票引用不序列化，重启后由 ChunkLoaderTicketCallback 从票的
 * modData 里记录的坐标挂回本 TE。
 */
internal class ChunkLoaderTileEntity : TileEntity(), ITickable {
    internal var owner: String? = null
    internal var range: Int = DEFAULT_RANGE
    internal var active: Boolean = false

    /** 运行时票引用，不写 NBT。 */
    private var ticket: ForgeChunkManager.Ticket? = null

    /** 循环切换到下一个范围档位，返回切换后的档位。 */
    internal fun cycleRange(): Int {
        val current = RANGES.indexOf(range).let { if (it < 0) RANGES.indexOf(DEFAULT_RANGE) else it }
        range = RANGES[(current + 1) % RANGES.size]
        markDirty()
        val w = world ?: return range
        if (ticket != null) {
            syncChunks()
        }
        val state = w.getBlockState(pos)
        w.notifyBlockUpdate(pos, state, state, 3)
        return range
    }

    /** 每 tick 核对红石信号，通断变化时刷新票状态。 */
    override fun update() {
        if (world == null || world.isRemote) return
        val powered = world.isBlockPowered(pos)
        if (powered != active) {
            refresh()
        }
    }

    /** 按当前红石信号刷新票：有信号申请票并 force 区块，无信号释放。 */
    internal fun refresh() {
        if (world == null || world.isRemote) return
        val powered = world.isBlockPowered(pos)
        if (powered == active && ticket != null) return
        active = powered
        if (active) {
            ensureTicket()
        } else {
            releaseTicket()
        }
        markDirty()
        val state = world.getBlockState(pos)
        world.notifyBlockUpdate(pos, state, state, 3)
    }

    /** 方块被破坏时调用，释放持有票。 */
    internal fun onBroken() {
        releaseTicket()
    }

    /** 世界加载回调里把持久化票挂回本 TE。 */
    internal fun attachTicket(t: ForgeChunkManager.Ticket) {
        ticket = t
        if (active) {
            syncChunks()
        }
    }

    private fun ensureTicket() {
        if (world == null || world.isRemote || ticket != null) return
        val t = ForgeChunkManager.requestTicket(TACZLegacy, world, ForgeChunkManager.Type.NORMAL) ?: return
        val data = t.modData
        data.setLong("X", pos.x.toLong())
        data.setLong("Y", pos.y.toLong())
        data.setLong("Z", pos.z.toLong())
        ticket = t
        syncChunks()
    }

    private fun releaseTicket() {
        val t = ticket ?: return
        ForgeChunkManager.releaseTicket(t)
        ticket = null
    }

    /** 按当前档位重新对齐票持有区块：先解绑旧区块，再 force 新区块。 */
    private fun syncChunks() {
        val t = ticket ?: return
        for (chunk in t.chunkList) {
            ForgeChunkManager.unforceChunk(t, chunk)
        }
        for (chunk in chunkList()) {
            ForgeChunkManager.forceChunk(t, chunk)
        }
    }

    /** 实际生效档位：受 Forge 每票区块深度上限约束，取不超上限的最大档。 */
    private fun effectiveRange(): Int {
        val depth = ForgeChunkManager.getMaxChunkDepthFor(TACZLegacy.MOD_ID)
        return RANGES.lastOrNull { it * it <= depth } ?: MIN_RANGE
    }

    private fun chunkList(): List<ChunkPos> {
        val side = effectiveRange()
        val center = ChunkPos(pos)
        val off = side / 2
        val out = ArrayList<ChunkPos>(side * side)
        for (dx in 0 until side) {
            for (dz in 0 until side) {
                out.add(ChunkPos(center.x - off + dx, center.z - off + dz))
            }
        }
        return out
    }

    override fun readFromNBT(compound: NBTTagCompound): Unit {
        super.readFromNBT(compound)
        owner = compound.getString("Owner").takeIf { it.isNotEmpty() }
        range = compound.getInteger("Range").let { if (it in MIN_RANGE..MAX_RANGE) it else DEFAULT_RANGE }
        active = compound.getBoolean("Active")
    }

    override fun writeToNBT(compound: NBTTagCompound): NBTTagCompound {
        val tag = super.writeToNBT(compound)
        owner?.let { tag.setString("Owner", it) }
        tag.setInteger("Range", range)
        tag.setBoolean("Active", active)
        return tag
    }

    override fun getUpdateTag(): NBTTagCompound = writeToNBT(NBTTagCompound())

    override fun getUpdatePacket(): net.minecraft.network.play.server.SPacketUpdateTileEntity =
        net.minecraft.network.play.server.SPacketUpdateTileEntity(pos, 0, updateTag)

    override fun onDataPacket(net: net.minecraft.network.NetworkManager, pkt: net.minecraft.network.play.server.SPacketUpdateTileEntity) {
        readFromNBT(pkt.nbtCompound)
    }

    private val ForgeChunkManager.Ticket.modData: NBTTagCompound
        get() = getModData()

    internal companion object {
        const val MIN_RANGE = 1
        const val DEFAULT_RANGE = 5
        const val MAX_RANGE = 8
        val RANGES: IntArray = intArrayOf(MIN_RANGE, DEFAULT_RANGE, MAX_RANGE)
    }
}
