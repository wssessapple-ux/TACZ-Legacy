package com.tacz.legacy.common.inventory

import com.tacz.legacy.api.DefaultAssets
import com.tacz.legacy.api.item.IAmmo
import com.tacz.legacy.api.item.IAmmoBox
import com.tacz.legacy.common.config.LegacyConfigManager
import com.tacz.legacy.common.item.LegacyItems
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.entity.player.InventoryPlayer
import net.minecraft.inventory.ClickType
import net.minecraft.inventory.Container
import net.minecraft.inventory.IInventory
import net.minecraft.inventory.Slot
import net.minecraft.item.ItemStack
import net.minecraft.util.ResourceLocation
import net.minecraft.util.text.ITextComponent
import net.minecraft.util.text.TextComponentString
import kotlin.math.ceil

/**
 * 弹药箱的内部虚拟库存：单个槽位映射到弹药箱 ItemStack 的 NBT（AmmoId + AmmoCount）。
 * GUI 中的"弹药槽"读/写都直接落到弹箱 NBT 上，保证服务端/客户端一致，且不产生独立物品实体。
 */
internal class AmmoBoxInventory(
    private val player: EntityPlayer,
    private val box: ItemStack,
) : IInventory {
    private val boxAmmo: IAmmoBox get() = box.item as IAmmoBox

    /** 主手仍是打开时那把弹药箱才允许读写；主手被换/拿起后立即视为空箱，避免引用悬空（QA 实测：GUI 里把弹药箱拿了就卡死）。 */
    private fun isStillHeld(): Boolean {
        val held = player.heldItemMainhand
        return !held.isEmpty && held.item === box.item
    }

    /** 箱内弹药口径；空箱返回 EMPTY_AMMO_ID */
    internal val ammoId: ResourceLocation get() = if (isStillHeld()) boxAmmo.getAmmoId(box) else DefaultAssets.EMPTY_AMMO_ID

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
        if (!isStillHeld()) return ItemStack.EMPTY
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
        if (!isStillHeld()) return
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

    override fun getSlotStackLimit(): Int = (inventory as AmmoBoxInventory).maxStackLimit

    override fun getItemStackLimit(stack: ItemStack): Int = (inventory as AmmoBoxInventory).maxStackLimit
}

internal class AmmoBoxContainer(
    playerInventory: InventoryPlayer,
    private val player: EntityPlayer,
) : Container() {
    /** 弹药箱本体：玩家当前手持的物品（右键打开时） */
    internal val ammoBox: ItemStack = player.heldItemMainhand

    internal val ammoInventory = AmmoBoxInventory(player, ammoBox)

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

    /**
     * 弹药槽是映射 NBT 的虚拟库存：原版 slotClick 的同口径合并分支会直接对槽位返回的
     * 临时副本做 grow/shrink 而不落盘，导致光标与箱子计数脱节（刷/丢子弹）。这里把弹药槽
     * 的 PICKUP / THROW 摘出来走"读副本 → 口径/容量校验 → 显式 putStack/decrStackSize 提交"，
     * 背包槽仍交回原版流程。SWAP（数字键换栏）禁止进弹药槽，避免绕过口径校验。
     */
    override fun slotClick(slotId: Int, dragType: Int, clickType: ClickType, player: EntityPlayer): ItemStack {
        if (slotId == 0) {
            when (clickType) {
                ClickType.PICKUP -> return clickAmmoSlotPickup(player, dragType)
                ClickType.THROW -> return clickAmmoSlotThrow(player, dragType)
                ClickType.SWAP -> return ItemStack.EMPTY
                else -> {}
            }
        }
        return super.slotClick(slotId, dragType, clickType, player)
    }

    /** PICKUP：光标空→从箱取（左键全取/右键取半）；光标有弹→校验口径后放入或合并。 */
    private fun clickAmmoSlotPickup(player: EntityPlayer, dragType: Int): ItemStack {
        val boxSlot = inventorySlots[0]
        val cursor = player.inventory.itemStack
        val boxStack = boxSlot.getStack()

        // 光标为空：从箱子取出
        if (cursor.isEmpty) {
            if (boxStack.isEmpty) return ItemStack.EMPTY
            val takeCount = if (dragType == 0) boxStack.count else ceil(boxStack.count / 2.0).toInt()
            val taken = boxSlot.decrStackSize(takeCount)
            if (taken.isEmpty) return ItemStack.EMPTY
            player.inventory.itemStack = taken
            boxSlot.onTake(player, taken)
            return taken
        }

        // 光标有物：弹药槽只收弹药
        val cursorAmmo = cursor.item as? IAmmo ?: return ItemStack.EMPTY
        val cursorId = cursorAmmo.getAmmoId(cursor)
        if (cursorId == DefaultAssets.EMPTY_AMMO_ID) return ItemStack.EMPTY
        val boxId = ammoInventory.ammoId
        if (boxId != DefaultAssets.EMPTY_AMMO_ID && boxId != cursorId) return ItemStack.EMPTY

        val boxLimit = ammoInventory.maxStackLimit
        val putOne = dragType == 1

        if (boxStack.isEmpty) {
            val putCount = minOf(cursor.count, boxLimit).let { if (putOne) 1 else it }
            if (putCount <= 0) return ItemStack.EMPTY
            val put = cursor.copy()
            put.count = putCount
            boxSlot.putStack(put)
            cursor.shrink(putCount)
            if (cursor.isEmpty) player.inventory.itemStack = ItemStack.EMPTY
        } else {
            val room = boxLimit - boxStack.count
            if (room <= 0) return ItemStack.EMPTY
            val putCount = minOf(cursor.count, room).let { if (putOne) 1 else it }
            if (putCount <= 0) return ItemStack.EMPTY
            val merged = boxStack.copy()
            merged.count = boxStack.count + putCount
            boxSlot.putStack(merged)
            cursor.shrink(putCount)
            if (cursor.isEmpty) player.inventory.itemStack = ItemStack.EMPTY
        }
        boxSlot.onSlotChanged()
        return ItemStack.EMPTY
    }

    /** THROW（Q 键丢出）：从箱子按实际取出量写回 NBT，再丢到世界。 */
    private fun clickAmmoSlotThrow(player: EntityPlayer, dragType: Int): ItemStack {
        val boxSlot = inventorySlots[0]
        val boxStack = boxSlot.getStack()
        if (boxStack.isEmpty || !boxSlot.canTakeStack(player)) return ItemStack.EMPTY
        val amount = if (dragType == 0) 1 else boxStack.count
        val taken = boxSlot.decrStackSize(amount)
        if (taken.isEmpty) return ItemStack.EMPTY
        player.dropItem(taken, true)
        return ItemStack.EMPTY
    }

    override fun transferStackInSlot(playerIn: EntityPlayer, index: Int): ItemStack {
        val slot = inventorySlots.getOrNull(index) ?: return ItemStack.EMPTY
        val stack = slot.getStack()
        if (stack.isEmpty) return ItemStack.EMPTY

        if (index == 0) {
            // 弹箱 -> 背包：先合并同类，再放空槽，最后按实际移动量写回 NBT。
            val toMove = stack.copy()
            var remaining = toMove.count

            for (i in 1 until inventorySlots.size) {
                if (remaining <= 0) break
                val target = inventorySlots[i]
                val existing = target.getStack()
                if (!existing.isEmpty && existing.isItemEqual(toMove) && ItemStack.areItemStackTagsEqual(existing, toMove)) {
                    val room = target.getSlotStackLimit() - existing.count
                    if (room > 0) {
                        val n = minOf(remaining, room)
                        existing.count += n
                        target.onSlotChanged()
                        remaining -= n
                    }
                }
            }
            for (i in 1 until inventorySlots.size) {
                if (remaining <= 0) break
                val target = inventorySlots[i]
                if (target.getStack().isEmpty) {
                    val n = minOf(remaining, target.getSlotStackLimit())
                    val put = toMove.copy()
                    put.count = n
                    target.putStack(put)
                    remaining -= n
                }
            }

            val moved = toMove.count - remaining
            if (moved > 0) {
                val newCount = stack.count - moved
                ammoInventory.setInventorySlotContents(
                    0,
                    if (newCount <= 0) ItemStack.EMPTY else stack.copy().apply { count = newCount },
                )
            }
            return ItemStack.EMPTY
        }

        // 背包 -> 弹箱：非空箱强校验口径；空箱允许装入任意口径（放入时设定口径）。
        val iAmmo = stack.item as? IAmmo ?: return ItemStack.EMPTY
        val putId = iAmmo.getAmmoId(stack)
        if (putId == DefaultAssets.EMPTY_AMMO_ID) return ItemStack.EMPTY
        val boxId = ammoInventory.ammoId
        if (boxId != DefaultAssets.EMPTY_AMMO_ID && boxId != putId) return ItemStack.EMPTY
        val boxSlot = inventorySlots[0]
        if (!boxSlot.isItemValid(stack)) return ItemStack.EMPTY

        val boxLimit = ammoInventory.maxStackLimit
        val existing = boxSlot.getStack()
        val putCount = if (existing.isEmpty) {
            minOf(stack.count, boxLimit)
        } else {
            val room = boxLimit - existing.count
            if (room <= 0) return ItemStack.EMPTY
            minOf(stack.count, room)
        }
        if (putCount <= 0) return ItemStack.EMPTY

        if (existing.isEmpty) {
            val put = stack.copy()
            put.count = putCount
            boxSlot.putStack(put)
        } else {
            boxSlot.putStack(existing.copy().apply { count = existing.count + putCount })
        }
        stack.count -= putCount
        if (stack.count <= 0) {
            slot.putStack(ItemStack.EMPTY)
        } else {
            slot.onSlotChanged()
        }
        return stack
    }

    private companion object {
        private const val AMMO_SLOT_X: Int = 26
        private const val AMMO_SLOT_Y: Int = 34
    }
}
