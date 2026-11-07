package com.tacz.legacy.client.renderer.entity

import com.tacz.legacy.common.entity.EntityDrone
import net.minecraft.client.renderer.BufferBuilder
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.Tessellator
import net.minecraft.client.renderer.entity.Render
import net.minecraft.client.renderer.entity.RenderManager
import net.minecraft.client.renderer.texture.TextureMap
import net.minecraft.client.renderer.vertex.DefaultVertexFormats
import net.minecraft.util.ResourceLocation
import net.minecraftforge.fml.relauncher.Side
import net.minecraftforge.fml.relauncher.SideOnly

/**
 * 无人机实体渲染器。
 *
 * 简单的彩色小立方体：民用灰色、军用红色，
 * 顶部加一个旋转的薄片表示螺旋桨。
 */
@SideOnly(Side.CLIENT)
internal class RenderDrone(renderManager: RenderManager) : Render<EntityDrone>(renderManager) {

    override fun doRender(entity: EntityDrone, x: Double, y: Double, z: Double, entityYaw: Float, partialTicks: Float) {
        val military = entity.isMilitary
        val r: Float
        val g: Float
        val b: Float
        if (military) {
            r = 0.75f; g = 0.18f; b = 0.15f
        } else {
            r = 0.45f; g = 0.48f; b = 0.52f
        }

        GlStateManager.pushMatrix()
        GlStateManager.translate(x.toDouble(), y.toDouble(), z.toDouble())
        GlStateManager.disableTexture2D()
        GlStateManager.enableBlend()
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0)
        GlStateManager.disableLighting()

        // 机身：0.4 边长的立方体
        val half = 0.2f
        drawCube(half, r, g, b, 1.0f)

        // 螺旋桨：旋转的水平薄片
        val spin = (entity.ticksExisted + partialTicks) * 0.8f
        GlStateManager.rotate(spin, 0.0f, 1.0f, 0.0f)
        drawPropeller(0.28f)

        GlStateManager.enableLighting()
        GlStateManager.disableBlend()
        GlStateManager.enableTexture2D()
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f)
        GlStateManager.popMatrix()
    }

    private fun drawCube(half: Float, r: Float, g: Float, b: Float, a: Float) {
        val tessellator = Tessellator.getInstance()
        val buffer = tessellator.buffer
        buffer.begin(7, DefaultVertexFormats.POSITION_COLOR)

        // 上下
        buffer.pos(-half.toDouble(), half.toDouble(), -half.toDouble()).color(r, g, b, a).endVertex()
        buffer.pos(half.toDouble(), half.toDouble(), -half.toDouble()).color(r, g, b, a).endVertex()
        buffer.pos(half.toDouble(), half.toDouble(), half.toDouble()).color(r, g, b, a).endVertex()
        buffer.pos(-half.toDouble(), half.toDouble(), half.toDouble()).color(r, g, b, a).endVertex()

        buffer.pos(-half.toDouble(), -half.toDouble(), half.toDouble()).color(r, g, b, a).endVertex()
        buffer.pos(half.toDouble(), -half.toDouble(), half.toDouble()).color(r, g, b, a).endVertex()
        buffer.pos(half.toDouble(), -half.toDouble(), -half.toDouble()).color(r, g, b, a).endVertex()
        buffer.pos(-half.toDouble(), -half.toDouble(), -half.toDouble()).color(r, g, b, a).endVertex()

        // 北南
        buffer.pos(-half.toDouble(), -half.toDouble(), half.toDouble()).color(r, g, b, a).endVertex()
        buffer.pos(half.toDouble(), -half.toDouble(), half.toDouble()).color(r, g, b, a).endVertex()
        buffer.pos(half.toDouble(), half.toDouble(), half.toDouble()).color(r, g, b, a).endVertex()
        buffer.pos(-half.toDouble(), half.toDouble(), half.toDouble()).color(r, g, b, a).endVertex()

        buffer.pos(-half.toDouble(), half.toDouble(), -half.toDouble()).color(r, g, b, a).endVertex()
        buffer.pos(half.toDouble(), half.toDouble(), -half.toDouble()).color(r, g, b, a).endVertex()
        buffer.pos(half.toDouble(), -half.toDouble(), -half.toDouble()).color(r, g, b, a).endVertex()
        buffer.pos(-half.toDouble(), -half.toDouble(), -half.toDouble()).color(r, g, b, a).endVertex()

        // 西东
        buffer.pos(-half.toDouble(), -half.toDouble(), half.toDouble()).color(r, g, b, a).endVertex()
        buffer.pos(-half.toDouble(), half.toDouble(), half.toDouble()).color(r, g, b, a).endVertex()
        buffer.pos(-half.toDouble(), half.toDouble(), -half.toDouble()).color(r, g, b, a).endVertex()
        buffer.pos(-half.toDouble(), -half.toDouble(), -half.toDouble()).color(r, g, b, a).endVertex()

        buffer.pos(half.toDouble(), -half.toDouble(), -half.toDouble()).color(r, g, b, a).endVertex()
        buffer.pos(half.toDouble(), half.toDouble(), -half.toDouble()).color(r, g, b, a).endVertex()
        buffer.pos(half.toDouble(), half.toDouble(), half.toDouble()).color(r, g, b, a).endVertex()
        buffer.pos(half.toDouble(), -half.toDouble(), half.toDouble()).color(r, g, b, a).endVertex()

        tessellator.draw()
    }

    private fun drawPropeller(blade: Float) {
        val tessellator = Tessellator.getInstance()
        val buffer = tessellator.buffer
        buffer.begin(7, DefaultVertexFormats.POSITION_COLOR)
        val y = 0.24
        val dark = 0.2f
        // 十字薄片
        buffer.pos(-blade.toDouble(), y.toDouble(), 0.0).color(dark, dark, dark, 0.85f).endVertex()
        buffer.pos(blade.toDouble(), y.toDouble(), 0.0).color(dark, dark, dark, 0.85f).endVertex()
        buffer.pos(0.0, y.toDouble(), blade.toDouble()).color(dark, dark, dark, 0.85f).endVertex()
        buffer.pos(0.0, y.toDouble(), -blade.toDouble()).color(dark, dark, dark, 0.85f).endVertex()
        tessellator.draw()
    }

    override fun getEntityTexture(entity: EntityDrone): ResourceLocation = TextureMap.LOCATION_MISSING_TEXTURE
}
