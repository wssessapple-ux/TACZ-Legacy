package com.tacz.legacy.client.renderer.entity

import com.google.gson.GsonBuilder
import com.google.gson.stream.JsonReader
import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.client.model.bedrock.BedrockModel
import com.tacz.legacy.client.resource.pojo.model.BedrockModelPOJO
import com.tacz.legacy.client.resource.pojo.model.BedrockVersion
import com.tacz.legacy.client.resource.pojo.model.CubesItem
import com.tacz.legacy.common.entity.EntityFpvDrone
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.entity.RenderManager
import net.minecraft.client.renderer.Tessellator
import net.minecraft.client.renderer.entity.Render
import net.minecraft.client.renderer.texture.TextureMap
import net.minecraft.util.ResourceLocation
import net.minecraft.util.math.BlockPos
import net.minecraftforge.fml.relauncher.Side
import net.minecraftforge.fml.relauncher.SideOnly
import org.lwjgl.opengl.GL11
import java.io.StringReader
import java.nio.charset.StandardCharsets

/**
 * FPV 侦察无人机实体渲染器。复用自杀机同款 bedrock 模型与民用贴图，
 * 朝向跟随实体自身的 yaw/pitch（由操控视角驱动），旋翼持续旋转。
 *
 * 探照灯：机头朝前画一束半透明可见光锥（加法混合），第一/第三人称均可见；
 * 纯视觉光效，不参与世界光照、不改变方块亮度。
 */
@SideOnly(Side.CLIENT)
internal class RenderFpvDrone(renderManager: RenderManager) : Render<EntityFpvDrone>(renderManager) {

    companion object {
        private const val MODEL_PATH = "assets/${TACZLegacy.MOD_ID}/models/bedrock/drone.json"
        private val fpvTexture = ResourceLocation(TACZLegacy.MOD_ID, "textures/entity/drone_civilian.png")

        /** 光锥长度（格）。 */
        private const val BEAM_LENGTH = 7.0
        /** 光锥末端展开半径（格）。 */
        private const val BEAM_RADIUS = 1.25

        private val internalModelGson = GsonBuilder()
            .registerTypeAdapter(CubesItem::class.java, CubesItem.Deserializer())
            .create()

        private var model: BedrockModel? = null
        private var modelResolved = false
    }

    override fun doRender(
        entity: EntityFpvDrone,
        x: Double,
        y: Double,
        z: Double,
        entityYaw: Float,
        partialTicks: Float,
    ) {
        val mc = Minecraft.getMinecraft()
        // FPV 第一人称视角挂在本机上时隐藏机壳/旋翼，避免镜头被自己的模型挡住；
        // 但探照灯光锥仍要画出来，所以这里只跳过模型，不整段返回。
        val firstPerson = mc.renderViewEntity === entity

        if (!firstPerson) {
            renderModel(entity, x, y, z, partialTicks)
        }
        renderSpotlight(entity, x, y, z)
    }

    private fun renderModel(
        entity: EntityFpvDrone,
        x: Double,
        y: Double,
        z: Double,
        partialTicks: Float,
    ) {
        val droneModel = resolveModel() ?: return
        Minecraft.getMinecraft().textureManager.bindTexture(fpvTexture)

        GlStateManager.pushMatrix()
        GlStateManager.translate(x, y, z)
        GlStateManager.rotate(-entity.rotationYaw, 0f, 1f, 0f)
        GlStateManager.rotate(entity.rotationPitch, 1f, 0f, 0f)

        GlStateManager.translate(0.0f, 1.5f, 0.0f)
        GlStateManager.scale(-1.0f, -1.0f, 1.0f)
        GlStateManager.enableLighting()
        GlStateManager.enableRescaleNormal()
        GlStateManager.enableBlend()
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0)

        val tickTime = entity.ticksExisted + partialTicks
        val angle = (tickTime * 0.6) % (2.0 * Math.PI)
        droneModel.getNode("rotor_fl")?.let { it.yRot = angle.toFloat() }
        droneModel.getNode("rotor_fr")?.let { it.yRot = -angle.toFloat() }
        droneModel.getNode("rotor_bl")?.let { it.yRot = -angle.toFloat() }
        droneModel.getNode("rotor_br")?.let { it.yRot = angle.toFloat() }

        droneModel.render()

        GlStateManager.disableBlend()
        GlStateManager.disableRescaleNormal()
        GlStateManager.popMatrix()
    }

    /** 机头前向可见光锥：四棱锥加法混合，近端亮、远端透明，夜间更明显。 */
    private fun renderSpotlight(entity: EntityFpvDrone, x: Double, y: Double, z: Double) {
        val yawRad = Math.toRadians(entity.rotationYaw.toDouble())
        val pitchRad = Math.toRadians(entity.rotationPitch.toDouble())
        val cosPitch = Math.cos(pitchRad)
        // MC 视线方向：x=-sin(yaw)*cos(pitch), y=-sin(pitch), z=cos(yaw)*cos(pitch)
        val fwdX = -Math.sin(yawRad) * cosPitch
        val fwdY = -Math.sin(pitchRad)
        val fwdZ = Math.cos(yawRad) * cosPitch
        // 水平右方向（垂直于 yaw）
        val rightX = Math.cos(yawRad)
        val rightZ = Math.sin(yawRad)
        // 上方向 = fwd × right（right 无垂直分量）
        val upX = fwdY * rightZ
        val upY = fwdZ * rightX - fwdX * rightZ
        val upZ = -fwdY * rightX

        // 机头高度近似：实体中心略上。
        val baseY = y + 0.2
        val nearX = x + fwdX * 0.3
        val nearY = baseY + fwdY * 0.3
        val nearZ = z + fwdZ * 0.3
        val farX = nearX + fwdX * BEAM_LENGTH
        val farY = nearY + fwdY * BEAM_LENGTH
        val farZ = nearZ + fwdZ * BEAM_LENGTH

        // 按周围亮度压低白天光锥强度：越暗越明显，白天只剩一丝。
        val world = Minecraft.getMinecraft().world
        val light = world?.getLightFromNeighbors(BlockPos(x, baseY, z)) ?: 15
        val darkness = (15 - light).coerceIn(0, 15) / 15.0
        val alphaNear = (0.16 * (0.35 + 0.65 * darkness)).toFloat()

        val r = 1.0f
        val g = 0.95f
        val b = 0.72f

        GlStateManager.pushMatrix()
        GlStateManager.disableTexture2D()
        GlStateManager.disableLighting()
        GlStateManager.enableBlend()
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE)
        GlStateManager.depthMask(false)
        GlStateManager.disableCull()

        val tessellator = Tessellator.getInstance()
        val buffer = tessellator.buffer
        buffer.begin(GL11.GL_TRIANGLES, net.minecraft.client.renderer.vertex.DefaultVertexFormats.POSITION_COLOR)

        // 底面四角：远心 ± right*R ± up*R
        val corners = listOf(
            triple(farX, farY, farZ, rightX, rightZ, upX, upY, upZ, BEAM_RADIUS, 1, 1),
            triple(farX, farY, farZ, rightX, rightZ, upX, upY, upZ, BEAM_RADIUS, -1, 1),
            triple(farX, farY, farZ, rightX, rightZ, upX, upY, upZ, BEAM_RADIUS, -1, -1),
            triple(farX, farY, farZ, rightX, rightZ, upX, upY, upZ, BEAM_RADIUS, 1, -1),
        )
        for (i in corners.indices) {
            val (cx1, cy1, cz1) = corners[i]
            val (cx2, cy2, cz2) = corners[(i + 1) % corners.size]
            buffer.pos(nearX, nearY, nearZ).color(r, g, b, alphaNear).endVertex()
            buffer.pos(cx1, cy1, cz1).color(r, g, b, 0f).endVertex()
            buffer.pos(cx2, cy2, cz2).color(r, g, b, 0f).endVertex()
        }

        tessellator.draw()

        GlStateManager.depthMask(true)
        GlStateManager.enableCull()
        GlStateManager.disableBlend()
        GlStateManager.enableLighting()
        GlStateManager.enableTexture2D()
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f)
        GlStateManager.popMatrix()
    }

    private fun triple(
        cx: Double, cy: Double, cz: Double,
        rightX: Double, rightZ: Double,
        upX: Double, upY: Double, upZ: Double,
        radius: Double, rs: Int, us: Int,
    ): Triple<Double, Double, Double> = Triple(
        cx + rightX * radius * rs + upX * radius * us,
        cy + upY * radius * us,
        cz + rightZ * radius * rs + upZ * radius * us,
    )

    private fun resolveModel(): BedrockModel? {
        if (modelResolved) {
            return model
        }
        modelResolved = true
        val json = RenderFpvDrone::class.java.classLoader
            .getResourceAsStream(MODEL_PATH)
            ?.bufferedReader(StandardCharsets.UTF_8)
            ?.use { it.readText() }
        if (json == null) {
            TACZLegacy.logger.warn("[RenderFpvDrone] Failed to open bundled drone model: {}", MODEL_PATH)
            return null
        }
        return runCatching {
            val reader = JsonReader(StringReader(json))
            reader.isLenient = true
            val pojo = internalModelGson.fromJson<BedrockModelPOJO>(reader, BedrockModelPOJO::class.java)
            val version = BedrockVersion.fromPojo(pojo)
                ?: error("unknown bedrock format version ${pojo.formatVersion}")
            BedrockModel(pojo, version)
        }.onFailure { error ->
            TACZLegacy.logger.warn("[RenderFpvDrone] Failed to parse bundled drone model: {}", error.message)
        }.getOrNull().also { model = it }
    }

    override fun getEntityTexture(entity: EntityFpvDrone): ResourceLocation = TextureMap.LOCATION_MISSING_TEXTURE
}
