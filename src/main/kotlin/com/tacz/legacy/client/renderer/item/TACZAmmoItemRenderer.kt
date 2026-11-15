package com.tacz.legacy.client.renderer.item

import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.api.item.IAmmo
import com.tacz.legacy.api.item.IAmmoBox
import com.tacz.legacy.client.model.SlotModel
import com.tacz.legacy.client.model.TACZPerspectiveAwareBakedModel
import com.tacz.legacy.client.model.bedrock.BedrockModel
import com.tacz.legacy.client.resource.TACZClientAssetManager
import com.tacz.legacy.client.resource.pojo.TransformScale
import com.tacz.legacy.client.resource.pojo.display.ammo.AmmoDisplay
import com.tacz.legacy.client.resource.pojo.display.ammo.AmmoTransform
import com.tacz.legacy.common.config.LegacyConfigManager
import com.tacz.legacy.common.resource.TACZGunPackPresentation
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.block.model.ItemCameraTransforms.TransformType
import net.minecraft.client.renderer.tileentity.TileEntityItemStackRenderer
import net.minecraft.item.ItemStack
import net.minecraft.util.ResourceLocation
import net.minecraftforge.fml.relauncher.Side
import net.minecraftforge.fml.relauncher.SideOnly
import org.joml.Vector3f

/**
 * TEISR for ammo items.
 *
 * Context-aware via [TACZPerspectiveAwareBakedModel]:
 * - **Item presentation contexts** (GUI, dropped, fixed, head) → flat slot texture
 * - **Hand / 3D contexts** → bedrock model with positioning and scale transforms
 */
@SideOnly(Side.CLIENT)
internal object TACZAmmoItemRenderer : TileEntityItemStackRenderer() {
    private val SLOT_MODEL = SlotModel()

    private val EMPTY_TEX = ResourceLocation(TACZLegacy.MOD_ID, "textures/item/ammo_box_empty.png")
    private val NORMAL_TEX = ResourceLocation(TACZLegacy.MOD_ID, "textures/item/ammo_box_normal.png")
    private val FILLED_TEX = ResourceLocation(TACZLegacy.MOD_ID, "textures/item/ammo_box_filled.png")

    override fun renderByItem(stack: ItemStack) {
        renderByItem(stack, 1.0f)
    }

    override fun renderByItem(stack: ItemStack, partialTicks: Float) {
        val item = stack.item

        // 弹药箱：走内置三态 sprite，不套枪包弹药 display。
        if (item is IAmmoBox) {
            renderAmmoBox(stack, item)
            return
        }
        if (item !is IAmmo) return

        val ammoId: ResourceLocation = item.getAmmoId(stack)
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()

        val displayId = TACZGunPackPresentation.resolveAmmoDisplayId(snapshot, ammoId) ?: return
        val display: AmmoDisplay = TACZClientAssetManager.getAmmoDisplay(displayId) ?: return

        val transformType = TACZPerspectiveAwareBakedModel.getCurrentTransformType()

        // Item presentation contexts → always use flat slot texture
        if (TACZPerspectiveAwareBakedModel.isItemPresentationContext(transformType)) {
            renderSlotTexture(display)
            return
        }

        // Hand / 3D contexts → try 3D model, else fall back to slot
        val modelLoc: ResourceLocation? = display.modelLocation
        val textureLoc: ResourceLocation? = display.modelTexture
        val modelData = if (modelLoc != null) TACZClientAssetManager.getModel(modelLoc) else null
        val model: BedrockModel? = if (modelData != null) BedrockModel(modelData.pojo, modelData.version) else null
        val registeredTexture: ResourceLocation? = if (textureLoc != null) TACZClientAssetManager.getTextureLocation(textureLoc) else null

        if (model == null || registeredTexture == null) {
            renderSlotTexture(display)
            return
        }

        GlStateManager.pushMatrix()
        Minecraft.getMinecraft().textureManager.bindTexture(registeredTexture)

        GlStateManager.translate(0.5f, 2.0f, 0.5f)
        GlStateManager.scale(-1f, -1f, 1f)

        val transform: AmmoTransform = display.transform ?: AmmoTransform.getDefault()
        val scale: TransformScale? = transform.scale
        applyScaleTransform(transformType, scale)

        GlStateManager.enableLighting()
        GlStateManager.enableRescaleNormal()
        GlStateManager.enableBlend()
        GlStateManager.tryBlendFuncSeparate(
            GlStateManager.SourceFactor.SRC_ALPHA,
            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
            GlStateManager.SourceFactor.ONE,
            GlStateManager.DestFactor.ZERO,
        )

        model.render()

        GlStateManager.disableBlend()
        GlStateManager.disableRescaleNormal()
        GlStateManager.popMatrix()
    }

    private fun renderSlotTexture(display: AmmoDisplay) {
        val slotTexLoc = display.slotTextureLocation ?: return
        val registeredSlot = TACZClientAssetManager.getTextureLocation(slotTexLoc) ?: return
        renderFlatTexture(registeredSlot)
    }

    /** 弹药箱三态内置 sprite，只表存量，不标口径。 */
    private fun renderAmmoBox(stack: ItemStack, box: IAmmoBox) {
        renderFlatTexture(resolveAmmoBoxTexture(stack, box))
    }

    private fun resolveAmmoBoxTexture(stack: ItemStack, box: IAmmoBox): ResourceLocation {
        // 创意 / 全类型创意：按满箱显示。
        if (box.isCreative(stack) || box.isAllTypeCreative(stack)) return FILLED_TEX
        val count = box.getAmmoCount(stack)
        if (count <= 0) return EMPTY_TEX
        val ammoId = box.getAmmoId(stack)
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val stackSize = snapshot.ammos[ammoId]?.stackSize?.coerceAtLeast(1) ?: 1
        val max = (stackSize * LegacyConfigManager.server.ammoBoxStackSize).coerceAtLeast(1)
        return if (count >= max) FILLED_TEX else NORMAL_TEX
    }

    private fun renderFlatTexture(texLoc: ResourceLocation) {
        Minecraft.getMinecraft().textureManager.bindTexture(texLoc)

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

        SLOT_MODEL.render()

        GlStateManager.disableBlend()
        GlStateManager.disableRescaleNormal()
        GlStateManager.popMatrix()
    }

    private fun applyScaleTransform(transformType: TransformType, scale: TransformScale?) {
        if (scale == null) return
        val v: Vector3f = when (transformType) {
            TransformType.FIXED -> scale.fixed
            TransformType.GROUND -> scale.ground
            TransformType.THIRD_PERSON_RIGHT_HAND,
            TransformType.THIRD_PERSON_LEFT_HAND -> scale.thirdPerson
            else -> scale.thirdPerson
        } ?: return
        GlStateManager.translate(0f, 1.5f, 0f)
        GlStateManager.scale(v.x(), v.y(), v.z())
        GlStateManager.translate(0f, -1.5f, 0f)
    }
}
