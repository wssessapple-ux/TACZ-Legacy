package com.tacz.legacy.client.gui

import com.tacz.legacy.common.block.entity.ChunkLoaderTileEntity
import com.tacz.legacy.common.inventory.ChunkLoaderContainer
import com.tacz.legacy.common.network.TACZNetworkHandler
import com.tacz.legacy.common.network.message.client.ClientMessageChunkLoaderCycle
import net.minecraft.client.gui.GuiButton
import net.minecraft.client.gui.inventory.GuiContainer
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.resources.I18n
import net.minecraft.entity.player.InventoryPlayer
import net.minecraftforge.fml.relauncher.Side
import net.minecraftforge.fml.relauncher.SideOnly

@SideOnly(Side.CLIENT)
internal class ChunkLoaderScreen(
    private val menu: ChunkLoaderContainer,
    playerInventory: InventoryPlayer,
) : GuiContainer(menu) {

    init {
        xSize = 176
        ySize = 120
    }

    private lateinit var cycleButton: GuiButton

    override fun initGui() {
        super.initGui()
        cycleButton = GuiButton(
            0,
            guiLeft + 58,
            guiTop + 84,
            60,
            20,
            I18n.format("gui.tacz.chunk_loader.cycle"),
        )
        buttonList.add(cycleButton)
    }

    override fun actionPerformed(button: GuiButton) {
        if (button.id == 0) {
            TACZNetworkHandler.sendToServer(ClientMessageChunkLoaderCycle(menu.windowId))
        }
    }

    override fun drawGuiContainerBackgroundLayer(partialTicks: Float, mouseX: Int, mouseY: Int) {
        GlStateManager.color(1f, 1f, 1f, 1f)
        drawRect(guiLeft, guiTop, guiLeft + xSize, guiTop + ySize, 0xC01E1E1E.toInt())
    }

    override fun drawGuiContainerForegroundLayer(mouseX: Int, mouseY: Int) {
        val te = menu.tile
        fontRenderer.drawStringWithShadow(
            I18n.format("block.tacz.chunk_loader"),
            8f,
            8f,
            0xFFFFFF,
        )

        val range = te?.range ?: ChunkLoaderTileEntity.DEFAULT_RANGE
        fontRenderer.drawStringWithShadow(
            I18n.format("gui.tacz.chunk_loader.range", range, range),
            8f,
            28f,
            0xFFFFFF,
        )

        val active = te?.active == true
        val status = I18n.format(
            if (active) "gui.tacz.chunk_loader.active" else "gui.tacz.chunk_loader.inactive",
        )
        val statusColor = if (active) 0xFF55FF55.toInt() else 0xFFAAAAAA.toInt()
        fontRenderer.drawStringWithShadow(status, 8f, 42f, statusColor)

        val owner = te?.owner ?: "—"
        fontRenderer.drawStringWithShadow(
            I18n.format("gui.tacz.chunk_loader.owner", owner),
            8f,
            56f,
            0xCCCCCC,
        )
    }
}
