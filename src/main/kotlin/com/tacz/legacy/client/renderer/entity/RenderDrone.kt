package com.tacz.legacy.client.renderer.entity

import com.google.gson.GsonBuilder
import com.google.gson.stream.JsonReader
import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.client.model.bedrock.BedrockModel
import com.tacz.legacy.client.resource.pojo.model.BedrockModelPOJO
import com.tacz.legacy.client.resource.pojo.model.BedrockVersion
import com.tacz.legacy.client.resource.pojo.model.CubesItem
import com.tacz.legacy.common.entity.EntityDrone
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.entity.Render
import net.minecraft.client.renderer.entity.RenderManager
import net.minecraft.client.renderer.texture.TextureMap
import net.minecraft.util.ResourceLocation
import net.minecraftforge.fml.relauncher.Side
import net.minecraftforge.fml.relauncher.SideOnly
import java.io.StringReader
import java.nio.charset.StandardCharsets
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 无人机实体渲染器（路线 B：内置 bedrock 模型 + 实体 UV 贴图）。
 *
 * 模型随 mod classloader 打包在 assets/tacz/models/bedrock/drone.json，
 * 民用/军用各绑一张实体贴图；四旋翼为独立骨，按 tick 绕 Y 轴旋转。
 * 整体仍按飞行方向（motion）朝向目标。
 */
@SideOnly(Side.CLIENT)
internal class RenderDrone(renderManager: RenderManager) : Render<EntityDrone>(renderManager) {

    companion object {
        private const val MODEL_PATH = "assets/${TACZLegacy.MOD_ID}/models/bedrock/drone.json"

        private val civilianTexture = ResourceLocation(TACZLegacy.MOD_ID, "textures/entity/drone_civilian.png")
        private val militaryTexture = ResourceLocation(TACZLegacy.MOD_ID, "textures/entity/drone_military.png")

        private val internalModelGson = GsonBuilder()
            .registerTypeAdapter(CubesItem::class.java, CubesItem.Deserializer())
            .create()

        private var model: BedrockModel? = null
        private var modelResolved = false
    }

    override fun doRender(
        entity: EntityDrone,
        x: Double,
        y: Double,
        z: Double,
        entityYaw: Float,
        partialTicks: Float,
    ) {
        val droneModel = resolveModel() ?: return
        val military = entity.isMilitary
        val texture = if (military) militaryTexture else civilianTexture
        Minecraft.getMinecraft().textureManager.bindTexture(texture)

        val mx = entity.motionX.toFloat()
        val my = entity.motionY.toFloat()
        val mz = entity.motionZ.toFloat()
        val hDist = sqrt(mx * mx + mz * mz)

        GlStateManager.pushMatrix()
        GlStateManager.translate(x, y, z)

        // 按飞行方向朝向目标
        if (hDist > 1.0E-4f) {
            val yaw = Math.toDegrees(atan2(mx, mz).toDouble()).toFloat()
            val pitch = -Math.toDegrees(atan2(my, hDist).toDouble()).toFloat()
            GlStateManager.rotate(yaw, 0f, 1f, 0f)
            GlStateManager.rotate(pitch, 1f, 0f, 0f)
        }

        // 与子弹模型一致的 bedrock 模型变换三明治
        GlStateManager.translate(0.0f, 1.5f, 0.0f)
        GlStateManager.scale(-1.0f, -1.0f, 1.0f)
        GlStateManager.enableLighting()
        GlStateManager.enableRescaleNormal()
        GlStateManager.enableBlend()
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0)

        // 旋翼旋转（对侧反向）
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

    private fun resolveModel(): BedrockModel? {
        if (modelResolved) {
            return model
        }
        modelResolved = true
        val json = RenderDrone::class.java.classLoader
            .getResourceAsStream(MODEL_PATH)
            ?.bufferedReader(StandardCharsets.UTF_8)
            ?.use { it.readText() }
        if (json == null) {
            TACZLegacy.logger.warn("[RenderDrone] Failed to open bundled drone model: {}", MODEL_PATH)
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
            TACZLegacy.logger.warn("[RenderDrone] Failed to parse bundled drone model: {}", error.message)
        }.getOrNull().also { model = it }
    }

    override fun getEntityTexture(entity: EntityDrone): ResourceLocation = TextureMap.LOCATION_MISSING_TEXTURE
}
