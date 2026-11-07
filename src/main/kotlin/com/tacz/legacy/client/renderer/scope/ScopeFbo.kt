package com.tacz.legacy.client.renderer.scope

import net.minecraft.client.Minecraft
import net.minecraft.client.shader.Framebuffer
import org.lwjgl.opengl.GL11

/**
 * 高倍镜专用离屏帧缓冲（FBO）：自带 depth + stencil。
 *
 * 1.12.2 主 framebuffer 默认不带 stencil buffer，运行时硬开（RenderHelper.enableItemEntityStencilTest
 * 走 framebuffer.enableStencil()）在很多显卡/驱动/优化 mod 环境会失败或污染主显示，导致瞄准时纯黑。
 * 这里把镜框 + 分划板（绿色引线）的 stencil 渲染隔离进独立 FBO，再 blit 合成回主屏幕。
 *
 * 开关：LegacyConfigManager.client.scopeFboEnabled，关闭时走纯 FOV 缩放兜底（FirstPersonFovHooks）。
 */
public object ScopeFbo {
    private var fbo: Framebuffer? = null
    private var lastWidth = -1
    private var lastHeight = -1

    /** FBO 是否可用（已建或可建）。 */
    public fun isAvailable(): Boolean = ensure() != null

    private fun ensure(): Framebuffer? {
        val mc = Minecraft.getMinecraft() ?: return null
        val w = mc.displayWidth
        val h = mc.displayHeight
        if (w <= 0 || h <= 0) return null
        if (fbo != null && lastWidth == w && lastHeight == h) return fbo
        val fb = Framebuffer(w, h, true)
        fb.enableStencil()
        fb.setFramebufferColor(0f, 0f, 0f, 0f)
        fbo = fb
        lastWidth = w
        lastHeight = h
        return fb
    }

    /** 绑定 FBO，准备渲染（含 stencil 缓冲）。 */
    public fun beginRender() {
        val fb = ensure() ?: return
        fb.bindFramebuffer(true)
    }

    /** 结束 FBO 渲染，切回主 framebuffer。 */
    public fun endRender() {
        Minecraft.getMinecraft().framebuffer.bindFramebuffer(true)
    }

    /** 把 FBO 内容全屏 blit 到主屏幕（2D 正交投影画全屏 quad）。 */
    public fun drawToScreen() {
        val fb = ensure() ?: return
        val mc = Minecraft.getMinecraft()
        // 切回主 framebuffer
        mc.framebuffer.bindFramebuffer(true)
        // 保存矩阵
        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glPushMatrix()
        GL11.glLoadIdentity()
        GL11.glOrtho(0.0, mc.displayWidth.toDouble(), mc.displayHeight.toDouble(), 0.0, 1000.0, 3000.0)
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glPushMatrix()
        GL11.glLoadIdentity()
        // 画全屏 quad
        GL11.glDisable(GL11.GL_DEPTH_TEST)
        GL11.glDisable(GL11.GL_ALPHA_TEST)
        GL11.glEnable(GL11.GL_TEXTURE_2D)
        GL11.glColor4f(1f, 1f, 1f, 1f)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, fb.framebufferTexture)
        GL11.glBegin(GL11.GL_QUADS)
        GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(0f, 0f)
        GL11.glTexCoord2f(1f, 0f); GL11.glVertex2f(mc.displayWidth.toFloat(), 0f)
        GL11.glTexCoord2f(1f, 1f); GL11.glVertex2f(mc.displayWidth.toFloat(), mc.displayHeight.toFloat())
        GL11.glTexCoord2f(0f, 1f); GL11.glVertex2f(0f, mc.displayHeight.toFloat())
        GL11.glEnd()
        // 恢复矩阵
        GL11.glPopMatrix()
        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glPopMatrix()
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
    }
}