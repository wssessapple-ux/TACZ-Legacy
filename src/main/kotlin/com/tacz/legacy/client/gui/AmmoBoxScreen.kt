package com.tacz.legacy.client.gui

import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.api.DefaultAssets
import com.tacz.legacy.api.item.IAmmoBox
import com.tacz.legacy.client.foundation.TACZAsciiFontHelper
import com.tacz.legacy.common.inventory.AmmoBoxContainer
import com.tacz.legacy.common.resource.TACZGunPackPresentation
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry
import net.minecraft.client.gui.inventory.GuiContainer
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.resources.I18n
import net.minecraft.entity.player.InventoryPlayer
import net.minecraft.util.ResourceLocation
import net.minecraftforge.fml.relauncher.Side
import net.minecraftforge.fml.relauncher.SideOnly

@SideOnly(Side.CLIENT)
internal class AmmoBoxScreen(
    private val menu: AmmoBoxContainer,
    playerInventory: InventoryPlayer,
) : GuiContainer(menu) {

    init {
        xSize = 176
        ySize = 178
    }

    override fun drawGuiContainerBackgroundLayer(partialTicks: Float, mouseX: Int, mouseY: Int) {
        GlStateManager.color(1f, 1f, 1f, 1f)
        mc.textureManager.bindTexture(BACKGROUND)
        drawTexturedModalRect(guiLeft, guiTop, 0, 0, xSize, ySize)
    }

    override fun drawGuiContainerForegroundLayer(mouseX: Int, mouseY: Int) {
        val box = menu.ammoBox.item as? IAmmoBox
        val ammoId = menu.ammoInventory.ammoId
        val empty = ammoId == DefaultAssets.EMPTY_AMMO_ID
        val slotStack = menu.inventorySlots.getOrNull(0)?.stack
        val count = if (slotStack == null || slotStack.isEmpty) 0 else slotStack.count
        val max = menu.ammoInventory.maxStackLimit
        val creative = box != null && (box.isCreative(menu.ammoBox) || box.isAllTypeCreative(menu.ammoBox))

        val statusColor = when {
            creative -> COLOR_FULL
            empty -> COLOR_EMPTY
            count >= max -> COLOR_FULL
            else -> COLOR_NORMAL
        }

        // 标题
        TACZAsciiFontHelper.drawStringWithShadow(fontRenderer, titleName, 8f, 6f, COLOR_NORMAL)

        // 当前口径
        val ammoName = if (empty) I18n.format("gui.tacz.ammo_box.empty") else localizedAmmoName(ammoId)
        TACZAsciiFontHelper.drawStringWithShadow(fontRenderer, ammoName, 50f, 34f, statusColor)

        // 存量 / 容量
        if (!empty) {
            val countText = if (creative) "∞" else "$count/$max"
            TACZAsciiFontHelper.drawStringWithShadow(fontRenderer, countText, 50f, 46f, statusColor)
        }

        TACZAsciiFontHelper.drawStringWithShadow(
            fontRenderer,
            I18n.format("gui.tacz.ammo_box.inventory"),
            8f,
            72f,
            COLOR_NORMAL,
        )

        // 底部版权署名（居中，暗灰）
        val copyright = I18n.format("gui.tacz.chunk_loader.copyright")
        TACZAsciiFontHelper.drawCenteredStringWithShadow(
            fontRenderer,
            copyright,
            xSize / 2,
            165,
            COLOR_COPYRIGHT,
        )
    }

    private val titleName: String
        get() = menu.ammoBox.displayName

    private fun localizedAmmoName(ammoId: ResourceLocation): String {
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        return TACZGunPackPresentation.localizedAmmoName(snapshot, ammoId, ammoId.toString())
    }

    private companion object {
        val BACKGROUND = ResourceLocation(TACZLegacy.MOD_ID, "textures/gui/ammo_box.png")
        const val COLOR_EMPTY = 0xFFAAAAAA.toInt()
        const val COLOR_NORMAL = 0xFFFFFFFF.toInt()
        const val COLOR_FULL = 0xFF55FF55.toInt()
        const val COLOR_HINT = 0xFFCCCCCC.toInt()
        const val COLOR_COPYRIGHT = 0xFF6A707C.toInt()
    }
}
