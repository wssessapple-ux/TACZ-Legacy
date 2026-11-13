package com.tacz.legacy.client.renderer.block

import com.tacz.legacy.common.block.entity.ChunkLoaderTileEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.Tessellator
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer
import net.minecraft.client.renderer.vertex.DefaultVertexFormats
import net.minecraftforge.fml.relauncher.Side
import net.minecraftforge.fml.relauncher.SideOnly
import org.lwjgl.opengl.GL11
import kotlin.math.sin

/**
 * 区块加载器世界渲染：在四个侧面中心画一块发光核心透镜。
 *
 * 激活时琥珀色呼吸脉冲；未激活时暗灰低亮。自发光、加法混合，不参与光照。
 */
@SideOnly(Side.CLIENT)
internal class ChunkLoaderTileEntityRenderer : TileEntitySpecialRenderer<ChunkLoaderTileEntity>() {

    override fun render(
        te: ChunkLoaderTileEntity,
        x: Double,
        y: Double,
        z: Double,
        partialTicks: Float,
        destroyStage: Int,
        alpha: Float,
    ) {
        val active = te.active
        val world = Minecraft.getMinecraft().world
        val time = (world?.totalWorldTime ?: 0L).toFloat() + partialTicks

        val red: Float
        val green: Float
        val blue: Float
        val coreAlpha: Float
        if (active) {
            val pulse = 0.72f + 0.28f * sin(time * 0.18f)
            red = 1.0f
            green = 0.70f
            blue = 0.26f
            coreAlpha = 0.85f * pulse
        } else {
            red = 0.34f
            green = 0.38f
            blue = 0.44f
            coreAlpha = 0.22f
        }

        GlStateManager.pushMatrix()
        GlStateManager.translate(x.toFloat() + 0.5f, y.toFloat() + 0.5f, z.toFloat() + 0.5f)
        GlStateManager.disableTexture2D()
        GlStateManager.disableLighting()
        GlStateManager.enableBlend()
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE)
        GlStateManager.depthMask(false)
        GlStateManager.disableCull()

        val h = 0.17f
        val off = 0.502f
        val tessellator = Tessellator.getInstance()
        val buffer = tessellator.buffer
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR)

        // north / south
        lens(buffer, -h, -h, -off, h, -h, -off, h, h, -off, -h, h, -off, red, green, blue, coreAlpha)
        lens(buffer, -h, -h, off, -h, h, off, h, h, off, h, -h, off, red, green, blue, coreAlpha)
        // west / east
        lens(buffer, -off, -h, -h, -off, h, -h, -off, h, h, -off, -h, h, red, green, blue, coreAlpha)
        lens(buffer, off, -h, h, off, h, h, off, h, -h, off, -h, -h, red, green, blue, coreAlpha)

        tessellator.draw()

        GlStateManager.depthMask(true)
        GlStateManager.enableCull()
        GlStateManager.disableBlend()
        GlStateManager.enableLighting()
        GlStateManager.enableTexture2D()
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f)
        GlStateManager.popMatrix()
    }

    private fun lens(
        buffer: net.minecraft.client.renderer.BufferBuilder,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        x4: Float, y4: Float, z4: Float,
        r: Float, g: Float, b: Float, a: Float,
    ) {
        buffer.pos(x1.toDouble(), y1.toDouble(), z1.toDouble()).color(r, g, b, a).endVertex()
        buffer.pos(x2.toDouble(), y2.toDouble(), z2.toDouble()).color(r, g, b, a).endVertex()
        buffer.pos(x3.toDouble(), y3.toDouble(), z3.toDouble()).color(r, g, b, a).endVertex()
        buffer.pos(x4.toDouble(), y4.toDouble(), z4.toDouble()).color(r, g, b, a).endVertex()
    }
}
