package com.tacz.legacy.client.renderer.item

import com.google.gson.GsonBuilder
import com.google.gson.stream.JsonReader
import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.client.model.bedrock.BedrockModel
import com.tacz.legacy.client.resource.pojo.model.BedrockModelPOJO
import com.tacz.legacy.client.resource.pojo.model.BedrockVersion
import com.tacz.legacy.client.resource.pojo.model.CubesItem
import com.tacz.legacy.common.item.LegacyItems
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.tileentity.TileEntityItemStackRenderer
import net.minecraft.item.ItemStack
import net.minecraft.util.ResourceLocation
import net.minecraftforge.fml.relauncher.Side
import net.minecraftforge.fml.relauncher.SideOnly
import java.io.StringReader
import java.nio.charset.StandardCharsets

/**
 * 无人机物品 TEISR：背包 / 手里 / 掉落物里直接渲染内置 bedrock 无人机模型。
 *
 * 复用世界实体同一套 [drone.json]，按物品实例区分民用 / 军用贴图。
 */
@SideOnly(Side.CLIENT)
internal object DroneItemRenderer : TileEntityItemStackRenderer() {

    private const val MODEL_PATH = "assets/${TACZLegacy.MOD_ID}/models/bedrock/drone.json"

    private val civilianTexture = ResourceLocation(TACZLegacy.MOD_ID, "textures/entity/drone_civilian.png")
    private val militaryTexture = ResourceLocation(TACZLegacy.MOD_ID, "textures/entity/drone_military.png")

    private val internalModelGson = GsonBuilder()
        .registerTypeAdapter(CubesItem::class.java, CubesItem.Deserializer())
        .create()

    private var model: BedrockModel? = null
    private var modelResolved = false

    override fun renderByItem(stack: ItemStack) {
        renderByItem(stack, 1.0f)
    }

    override fun renderByItem(stack: ItemStack, partialTicks: Float) {
        val droneModel = resolveModel() ?: return
        val military = stack.item === LegacyItems.DRONE_MILITARY
        val texture = if (military) militaryTexture else civilianTexture

        Minecraft.getMinecraft().textureManager.bindTexture(texture)

        GlStateManager.pushMatrix()
        GlStateManager.translate(0.5f, 1.5f, 0.5f)
        GlStateManager.rotate(180f, 0f, 0f, 1f)

        GlStateManager.enableLighting()
        GlStateManager.enableRescaleNormal()
        GlStateManager.enableBlend()
        GlStateManager.tryBlendFuncSeparate(
            GlStateManager.SourceFactor.SRC_ALPHA,
            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
            GlStateManager.SourceFactor.ONE,
            GlStateManager.DestFactor.ZERO,
        )

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
        val json = DroneItemRenderer::class.java.classLoader
            .getResourceAsStream(MODEL_PATH)
            ?.bufferedReader(StandardCharsets.UTF_8)
            ?.use { it.readText() }
        if (json == null) {
            TACZLegacy.logger.warn("[DroneItemRenderer] Failed to open bundled drone model: {}", MODEL_PATH)
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
            TACZLegacy.logger.warn("[DroneItemRenderer] Failed to parse bundled drone model: {}", error.message)
        }.getOrNull().also { model = it }
    }
}
