package com.tacz.legacy.client.renderer.item

import com.tacz.legacy.api.item.IAmmo
import com.tacz.legacy.api.item.IAmmoBox
import com.tacz.legacy.client.model.SlotModel
import com.tacz.legacy.client.model.TACZPerspectiveAwareBakedModel
import com.tacz.legacy.client.model.bedrock.BedrockModel
import com.tacz.legacy.client.resource.TACZClientAssetManager
import com.tacz.legacy.client.resource.pojo.TransformScale
import com.tacz.legacy.client.resource.pojo.display.ammo.AmmoDisplay
import com.tacz.legacy.client.resource.pojo.display.ammo.AmmoTransform
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

    override fun renderByItem(stack: ItemStack) {
        renderByItem(stack, 1.0f)
    }

    override fun renderByItem(stack: ItemStack, partialTicks: Float) {
        val item = stack.item
        if (item !is IAmmo && item !is IAmmoBox) return

        val ammoId: ResourceLocation = when (item) {
            is IAmmo -> item.getAmmoId(stack)
            is IAmmoBox -> item.getAmmoId(stack)
            else -> return
        }
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
        Minecraft.getMinecraft().textureManager.bindTexture(registeredSlot)

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
