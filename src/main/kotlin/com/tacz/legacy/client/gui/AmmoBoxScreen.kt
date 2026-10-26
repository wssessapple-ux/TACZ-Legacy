package com.tacz.legacy.client.gui

import com.tacz.legacy.api.DefaultAssets
import com.tacz.legacy.common.inventory.AmmoBoxContainer
import com.tacz.legacy.common.resource.TACZGunPackPresentation
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry
import net.minecraft.client.gui.inventory.GuiContainer
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.entity.player.InventoryPlayer
import net.minecraftforge.fml.relauncher.Side
import net.minecraftforge.fml.relauncher.SideOnly

@SideOnly(Side.CLIENT)
internal class AmmoBoxScreen(
    private val menu: AmmoBoxContainer,
    playerInventory: InventoryPlayer,
) : GuiContainer(menu) {

    init {
        xSize = 176
        ySize = 166
    }

    override fun drawGuiContainerBackgroundLayer(partialTicks: Float, mouseX: Int, mouseY: Int) {
        GlStateManager.color(1f, 1f, 1f, 1f)
        drawRect(guiLeft, guiTop, guiLeft + xSize, guiTop + ySize, 0xC01E1E1E.toInt())
        drawRect(guiLeft, guiTop, guiLeft + xSize, guiTop + ySize - 56, 0xC0202020.toInt())
        drawSlotFrame(guiLeft + 26, guiTop + 34)
        // 玩家背包 27 + 快捷栏 9
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                drawSlotFrame(guiLeft + 8 + col * 18, guiTop + 84 + row * 18)
            }
        }
        for (col in 0 until 9) {
            drawSlotFrame(guiLeft + 8 + col * 18, guiTop + 142)
        }
    }

    override fun drawGuiContainerForegroundLayer(mouseX: Int, mouseY: Int) {
        fontRenderer.drawStringWithShadow(ammoDisplayName, 8f, 6f, 0xFFFFFF)
        fontRenderer.drawStringWithShadow("背包", 8f, 72f, 0xFFFFFF)
        fontRenderer.drawStringWithShadow("左键取/放整组 · Shift 转移同口径", 8f, 154f, 0xCCCCCC)
    }

    private val ammoDisplayName: String
        get() {
            val id = menu.ammoInventory.ammoId
            if (id == DefaultAssets.EMPTY_AMMO_ID) return "空弹药箱"
            val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
            return TACZGunPackPresentation.localizedAmmoName(snapshot, id, id.toString())
        }

    private fun drawSlotFrame(x: Int, y: Int) {
        drawRect(x, y, x + 16, y + 16, 0xFF141414.toInt())
        val border = 0xFF9A9A9A.toInt()
        drawHorizontalLine(x, x + 15, y, border)
        drawHorizontalLine(x, x + 15, y + 15, border)
        drawVerticalLine(x, y, y + 15, border)
        drawVerticalLine(x + 15, y, y + 15, border)
    }
}
