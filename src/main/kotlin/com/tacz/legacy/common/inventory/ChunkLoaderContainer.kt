package com.tacz.legacy.common.inventory

import com.tacz.legacy.common.block.entity.ChunkLoaderTileEntity
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.entity.player.InventoryPlayer
import net.minecraft.inventory.Container
import net.minecraft.inventory.Slot
import net.minecraft.item.ItemStack
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World

/**
 * 区块加载器面板容器。无物品槽，仅承载 TE 状态展示与换档按钮。
 */
internal class ChunkLoaderContainer(
    private val playerInventory: InventoryPlayer,
    val world: World,
    val blockPos: BlockPos,
) : Container() {
    val tile: ChunkLoaderTileEntity?
        get() = world.getTileEntity(blockPos) as? ChunkLoaderTileEntity

    init {
        addHiddenPlayerInventory(playerInventory)
    }

    override fun canInteractWith(playerIn: EntityPlayer): Boolean {
        if (world.getTileEntity(blockPos) !is ChunkLoaderTileEntity) {
            return false
        }
        return playerIn.getDistanceSq(blockPos.x + 0.5, blockPos.y + 0.5, blockPos.z + 0.5) <= 64.0
    }

    override fun transferStackInSlot(playerIn: EntityPlayer, index: Int): ItemStack = ItemStack.EMPTY

    private fun addHiddenPlayerInventory(inventory: InventoryPlayer) {
        for (row in 0 until 3) {
            for (column in 0 until 9) {
                addSlotToContainer(Slot(inventory, column + row * 9 + 9, HIDDEN_SLOT_X, HIDDEN_SLOT_Y))
            }
        }
        for (column in 0 until 9) {
            addSlotToContainer(Slot(inventory, column, HIDDEN_SLOT_X, HIDDEN_SLOT_Y))
        }
    }

    private companion object {
        private const val HIDDEN_SLOT_X: Int = -2000
        private const val HIDDEN_SLOT_Y: Int = -2000
    }
}
