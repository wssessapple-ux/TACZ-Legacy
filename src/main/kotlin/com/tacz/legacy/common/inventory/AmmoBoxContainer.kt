package com.tacz.legacy.common.inventory

import com.tacz.legacy.api.DefaultAssets
import com.tacz.legacy.api.item.IAmmo
import com.tacz.legacy.api.item.IAmmoBox
import com.tacz.legacy.common.config.LegacyConfigManager
import com.tacz.legacy.common.item.LegacyItems
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.entity.player.InventoryPlayer
import net.minecraft.inventory.Container
import net.minecraft.inventory.IInventory
import net.minecraft.inventory.Slot
import net.minecraft.item.ItemStack
import net.minecraft.util.ResourceLocation
import net.minecraft.util.text.ITextComponent
import net.minecraft.util.text.TextComponentString

/**
 * 弹药箱的内部虚拟库存：单个槽位映射到弹药箱 ItemStack 的 NBT（AmmoId + AmmoCount）。
 * GUI 中的"弹药槽"读/写都直接落到弹箱 NBT 上，保证服务端/客户端一致，且不产生独立物品实体。
 */
internal class AmmoBoxInventory(private val box: ItemStack) : IInventory {
    private val boxAmmo: IAmmoBox get() = box.item as IAmmoBox

    /** 箱内弹药口径；空箱返回 EMPTY_AMMO_ID */
    internal val ammoId: ResourceLocation get() = boxAmmo.getAmmoId(box)

    /** 当前口径弹药的最大堆叠上限（来自枪包数据 × ammoBoxStackSize 配置） */
    internal val maxStackLimit: Int
        get() {
            val id = ammoId
            if (id == DefaultAssets.EMPTY_AMMO_ID) return 1
            val stackSize = TACZGunPackRuntimeRegistry.getSnapshot().ammos[id]?.stackSize?.coerceAtLeast(1) ?: 1
            // 兼容层 ③ C7：弹箱容量 = 弹药 stackSize × ammoBoxStackSize 配置（对齐 Next/上游，
            // 玩家可改配置扩容；默认 3 → 308 弹箱 48×3=144）。此前只用 ammo.stackSize（48），
            // 玩家改 AmmoBoxStackSize 配置不生效。
            return (stackSize * LegacyConfigManager.server.ammoBoxStackSize).coerceAtLeast(1)
        }

    private fun readAmmoStack(): ItemStack {
        val id = boxAmmo.getAmmoId(box)
        if (id == DefaultAssets.EMPTY_AMMO_ID) return ItemStack.EMPTY
        val count = boxAmmo.getAmmoCount(box)
        if (count <= 0) return ItemStack.EMPTY
        val stack = ItemStack(LegacyItems.AMMO)
        (LegacyItems.AMMO as IAmmo).setAmmoId(stack, id)
        stack.count = count.coerceAtMost(maxStackLimit)
        return stack
    }

    private fun writeAmmoStack(stack: ItemStack) {
        val iAmmo = stack.item as? IAmmo
        if (stack.isEmpty || iAmmo == null || iAmmo.getAmmoId(stack) == DefaultAssets.EMPTY_AMMO_ID || stack.count <= 0) {
            boxAmmo.setAmmoId(box, DefaultAssets.EMPTY_AMMO_ID)
            boxAmmo.setAmmoCount(box, 0)
            return
        }
        boxAmmo.setAmmoId(box, iAmmo.getAmmoId(stack))
        boxAmmo.setAmmoCount(box, stack.count)
    }

    override fun getSizeInventory(): Int = 1

    override fun isEmpty(): Boolean = readAmmoStack().isEmpty

    override fun getStackInSlot(index: Int): ItemStack = readAmmoStack()

    override fun decrStackSize(index: Int, count: Int): ItemStack {
        val current = readAmmoStack()
        if (current.isEmpty) return ItemStack.EMPTY
        val taken = minOf(current.count, count)
        val left = current.count - taken
        val result = current.copy()
        result.count = taken
        if (left <= 0) {
            writeAmmoStack(ItemStack.EMPTY)
        } else {
            current.count = left
            writeAmmoStack(current)
        }
        return result
    }

    override fun removeStackFromSlot(index: Int): ItemStack {
        val current = readAmmoStack()
        writeAmmoStack(ItemStack.EMPTY)
        return current
    }

    override fun setInventorySlotContents(index: Int, stack: ItemStack) {
        writeAmmoStack(stack)
        markDirty()
    }

    override fun getInventoryStackLimit(): Int = 64

    override fun isItemValidForSlot(index: Int, stack: ItemStack): Boolean = true

    override fun markDirty() {}

    override fun isUsableByPlayer(player: EntityPlayer): Boolean = true

    override fun openInventory(player: EntityPlayer) {}
    override fun closeInventory(player: EntityPlayer) {}

    override fun getField(id: Int): Int = 0
    override fun setField(id: Int, value: Int) {}
    override fun getFieldCount(): Int = 0

    override fun clear() {
        writeAmmoStack(ItemStack.EMPTY)
    }

    override fun getName(): String = "ammo_box"
    override fun hasCustomName(): Boolean = false
    override fun getDisplayName(): ITextComponent = TextComponentString(getName())
}

/**
 * 弹药槽。核心安全点：isItemValid 强校验口径 —— 只有弹药 ID 与箱内 AmmoId 一致才允许放入，
 * 防止 5.56 塞进 7.62 的箱子导致 NBT 数据冲突 / 刷物品。
 */
internal class AmmoBoxSlot(inv: AmmoBoxInventory, x: Int, y: Int) : Slot(inv, 0, x, y) {
    override fun isItemValid(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        val iAmmo = stack.item as? IAmmo ?: return false
        val id = iAmmo.getAmmoId(stack)
        if (id == DefaultAssets.EMPTY_AMMO_ID) return false
        val boxId = (inventory as AmmoBoxInventory).ammoId
        // 空箱（AmmoId 为空）允许装入任意口径，放入时自动设定该口径；非空箱仅允许同口径
        return boxId == DefaultAssets.EMPTY_AMMO_ID || boxId == id
    }

    override fun getSlotStackLimit(): Int = 64
}

internal class AmmoBoxContainer(
    playerInventory: InventoryPlayer,
    private val player: EntityPlayer,
) : Container() {
    /** 弹药箱本体：玩家当前手持的物品（右键打开时） */
    internal val ammoBox: ItemStack = player.heldItemMainhand

    internal val ammoInventory = AmmoBoxInventory(ammoBox)

    init {
        // 弹药槽 (index 0)
        addSlotToContainer(AmmoBoxSlot(ammoInventory, AMMO_SLOT_X, AMMO_SLOT_Y))
        // 玩家主背包 27 格
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlotToContainer(Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18))
            }
        }
        // 玩家快捷栏 9 格
        for (col in 0 until 9) {
            addSlotToContainer(Slot(playerInventory, col, 8 + col * 18, 142))
        }
    }

    override fun canInteractWith(playerIn: EntityPlayer): Boolean =
        playerIn == player && !playerIn.heldItemMainhand.isEmpty && playerIn.heldItemMainhand.item === ammoBox.item

    override fun transferStackInSlot(playerIn: EntityPlayer, index: Int): ItemStack {
        val slot = inventorySlots.getOrNull(index) ?: return ItemStack.EMPTY
        val stack = slot.getStack()
        if (stack.isEmpty) return ItemStack.EMPTY

        if (index == 0) {
            // 弹箱 -> 背包：尽量转移
            val toMove = stack.copy()
            var remaining = toMove.count
            for (i in 1 until inventorySlots.size) {
                if (remaining <= 0) break
                val target = inventorySlots[i]
                val existing = target.getStack()
                if (existing.isEmpty) {
                    val putCount = minOf(remaining, target.getSlotStackLimit())
                    val put = toMove.copy()
                    put.count = putCount
                    target.putStack(put)
                    remaining -= putCount
                } else if (existing.isItemEqual(toMove) && ItemStack.areItemStackTagsEqual(existing, toMove)) {
                    val room = target.getSlotStackLimit() - existing.count
                    if (room > 0) {
                        val putCount = minOf(remaining, room)
                        existing.count += putCount
                        target.onSlotChanged()
                        remaining -= putCount
                    }
                }
            }
            val moved = toMove.count - remaining
            val newCount = stack.count - moved
            ammoInventory.setInventorySlotContents(0, if (newCount <= 0) ItemStack.EMPTY else stack.copy().apply { count = newCount })
            return stack
        }

        // 背包 -> 弹箱：非空箱强校验口径；空箱允许装入任意口径（放入时设定口径）
        val iAmmo = stack.item as? IAmmo ?: return ItemStack.EMPTY
        val putId = iAmmo.getAmmoId(stack)
        if (putId == DefaultAssets.EMPTY_AMMO_ID) return ItemStack.EMPTY
        val boxId = ammoInventory.ammoId
        if (boxId != DefaultAssets.EMPTY_AMMO_ID && boxId != putId) return ItemStack.EMPTY
        val boxSlot = inventorySlots[0]
        if (!boxSlot.isItemValid(stack)) return ItemStack.EMPTY
        val boxLimit = ammoInventory.maxStackLimit
        val existing = boxSlot.getStack()
        if (existing.isEmpty) {
            val putCount = minOf(stack.count, boxLimit)
            val put = stack.copy()
            put.count = putCount
            boxSlot.putStack(put)
            stack.count -= putCount
        } else {
            val room = boxLimit - existing.count
            if (room <= 0) return ItemStack.EMPTY
            val putCount = minOf(stack.count, room)
            boxSlot.putStack(existing.copy().apply { count = existing.count + putCount })
            stack.count -= putCount
        }
        slot.onSlotChanged()
        return stack
    }

    private companion object {
        private const val AMMO_SLOT_X: Int = 26
        private const val AMMO_SLOT_Y: Int = 34
    }
}
