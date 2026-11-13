package com.tacz.legacy.client.renderer.item

import com.tacz.legacy.api.item.IGun
import com.tacz.legacy.client.model.BedrockAnimatedModel
import com.tacz.legacy.client.model.BedrockGunModel
import com.tacz.legacy.client.model.SlotModel
import com.tacz.legacy.client.model.TACZPerspectiveAwareBakedModel
import com.tacz.legacy.client.model.bedrock.BedrockModel
import com.tacz.legacy.client.model.bedrock.BedrockPart
import com.tacz.legacy.client.model.functional.BeamRenderer
import com.tacz.legacy.client.renderer.bloom.TACZBloomBridge
import com.tacz.legacy.client.resource.TACZClientAssetManager
import com.tacz.legacy.client.resource.GunDisplayInstance
import com.tacz.legacy.client.resource.pojo.display.gun.GunDisplay
import com.tacz.legacy.client.resource.pojo.display.gun.GunTransform
import com.tacz.legacy.client.resource.pojo.TransformScale
import com.tacz.legacy.common.resource.TACZGunPackPresentation
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.block.model.ItemCameraTransforms
import net.minecraft.client.renderer.tileentity.TileEntityItemStackRenderer
import net.minecraft.item.ItemStack
import net.minecraft.util.ResourceLocation
import net.minecraftforge.fml.relauncher.Side
import net.minecraftforge.fml.relauncher.SideOnly
import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import java.nio.FloatBuffer

/**
 * TEISR (TileEntityItemStackRenderer) for gun items.
 *
 * Context-aware rendering via [TACZPerspectiveAwareBakedModel]:
 * - **Item presentation contexts** (GUI, dropped, fixed, head) → flat slot texture
 * - **First person** → skipped (handled by FirstPersonRenderGunEvent)
 * - **Third person right hand** → 3D bedrock model with gun pack texture
 * - **Third person left hand** → skipped (upstream convention)
 */
@SideOnly(Side.CLIENT)
internal object TACZGunItemRenderer : TileEntityItemStackRenderer() {
    private val SLOT_MODEL = SlotModel()
    private val POSITIONING_BUFFER: FloatBuffer = BufferUtils.createFloatBuffer(16)

    override fun renderByItem(stack: ItemStack) {
        renderByItem(stack, 1.0f)
    }

    override fun renderByItem(stack: ItemStack, partialTicks: Float) {
        val item = stack.item
        if (item !is IGun) return

        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val gunId = item.getGunId(stack)

        val displayId = TACZGunPackPresentation.resolveGunDisplayId(snapshot, gunId) ?: return
        val display: GunDisplay = TACZClientAssetManager.getGunDisplay(displayId) ?: return
        val displayInstance = TACZClientAssetManager.getGunDisplayInstance(displayId)

        val transformType = TACZPerspectiveAwareBakedModel.getCurrentTransformType()

        // First person is usually handled by FirstPersonRenderGunEvent.
        // If that hook bails out early (missing display instance, texture, etc.),
        // allow the TEISR to act as a last-resort visual fallback instead of
        // making the gun disappear entirely.

        // Third person left hand — skip per upstream convention
        if (transformType == ItemCameraTransforms.TransformType.THIRD_PERSON_LEFT_HAND) {
            return
        }

        // Item presentation contexts → flat slot texture
        if (TACZPerspectiveAwareBakedModel.isItemPresentationContext(transformType)) {
            renderSlotTexture(display)
            return
        }

        // Third person right hand → 3D model
        renderGunModel(stack, display, displayInstance)
    }

    private fun renderSlotTexture(display: GunDisplay) {
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

    private fun renderGunModel(stack: ItemStack, display: GunDisplay, displayInstance: GunDisplayInstance?) {
        val textureLocation: ResourceLocation = displayInstance?.modelTexture ?: display.modelTexture ?: return
        val registeredTexture: ResourceLocation = TACZClientAssetManager.getTextureLocation(textureLocation) ?: return
        val transformType = TACZPerspectiveAwareBakedModel.getCurrentTransformType()
        val beamRenderContext = when (transformType) {
            ItemCameraTransforms.TransformType.THIRD_PERSON_RIGHT_HAND -> BeamRenderer.RenderContext.THIRD_PERSON_RIGHT_HAND
            ItemCameraTransforms.TransformType.FIRST_PERSON_LEFT_HAND,
            ItemCameraTransforms.TransformType.FIRST_PERSON_RIGHT_HAND -> BeamRenderer.RenderContext.FIRST_PERSON
            else -> BeamRenderer.RenderContext.NONE
        }

        val runtimeModel = displayInstance?.gunModel
        val fallbackModel = if (runtimeModel == null) {
            val modelLocation: ResourceLocation = display.modelLocation ?: return
            val modelData = TACZClientAssetManager.getModel(modelLocation) ?: return
            BedrockModel(modelData.pojo, modelData.version)
        } else {
            null
        }

        val transform: GunTransform = displayInstance?.transform ?: display.transform ?: GunTransform.getDefault()
        val scale: TransformScale? = transform.scale

        Minecraft.getMinecraft().textureManager.bindTexture(registeredTexture)

        GlStateManager.pushMatrix()
        GlStateManager.translate(0.5f, 2.0f, 0.5f)
        GlStateManager.scale(-1f, -1f, 1f)

        if (runtimeModel is BedrockGunModel) {
            applyPositioningTransform(transformType, scale, runtimeModel)
        }
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

        displayInstance?.setActiveGunTexture(registeredTexture)
        if (runtimeModel != null) {
            runtimeModel.renderHand = false
            val previousBeamContext = BeamRenderer.pushRenderContext(beamRenderContext)
            try {
                runtimeModel.render(stack)
            } finally {
                BeamRenderer.popRenderContext(previousBeamContext)
            }
            captureBloomIfSupported(transformType, registeredTexture, runtimeModel) {
                runtimeModel.renderHand = false
                runtimeModel.renderBloom(stack)
            }
            runtimeModel.cleanAnimationTransform()
            runtimeModel.cleanCameraAnimationTransform()
        } else {
            val staticModel = fallbackModel ?: return
            staticModel.render()
            captureBloomIfSupported(transformType, registeredTexture, staticModel) {
                staticModel.renderBloom()
            }
        }

        GlStateManager.disableBlend()
        GlStateManager.disableRescaleNormal()
        GlStateManager.popMatrix()
    }

    /**
     * Walks the gun model's own positioning node (fixed/ground/thirdperson_hand)
     * backwards from leaf to root, applying an approximate inverse of each bone's
     * transform so that the node's local origin lands at the current origin.
     */
    private fun applyPositioningTransform(
        transformType: ItemCameraTransforms.TransformType,
        scale: TransformScale?,
        model: BedrockGunModel,
    ) {
        val vectorScale = resolveScaleVector(transformType, scale) ?: Vector3f(1f, 1f, 1f)
        when (transformType) {
            ItemCameraTransforms.TransformType.FIXED ->
                applyPositioningNodeTransform(model.fixedOriginPath, vectorScale)
            ItemCameraTransforms.TransformType.GROUND ->
                applyPositioningNodeTransform(model.groundOriginPath, vectorScale)
            ItemCameraTransforms.TransformType.THIRD_PERSON_RIGHT_HAND ->
                applyPositioningNodeTransform(model.thirdPersonHandOriginPath, vectorScale)
            else -> Unit
        }
    }

    private fun applyScaleTransform(transformType: ItemCameraTransforms.TransformType, scale: TransformScale?) {
        val vector = resolveScaleVector(transformType, scale) ?: return
        GlStateManager.translate(0f, 1.5f, 0f)
        GlStateManager.scale(vector.x, vector.y, vector.z)
        GlStateManager.translate(0f, -1.5f, 0f)
    }

    private fun resolveScaleVector(
        transformType: ItemCameraTransforms.TransformType,
        scale: TransformScale?,
    ): Vector3f? {
        if (scale == null) return null
        return when (transformType) {
            ItemCameraTransforms.TransformType.FIXED -> scale.fixed?.let { Vector3f(it) }
            ItemCameraTransforms.TransformType.GROUND -> scale.ground?.let { Vector3f(it) }
            ItemCameraTransforms.TransformType.THIRD_PERSON_RIGHT_HAND,
            ItemCameraTransforms.TransformType.THIRD_PERSON_LEFT_HAND -> scale.thirdPerson?.let { Vector3f(it) }
            else -> null
        }
    }

    private fun applyPositioningNodeTransform(nodePath: List<BedrockPart>?, scale: Vector3f) {
        if (nodePath.isNullOrEmpty()) return
        if (scale.x == 0f || scale.y == 0f || scale.z == 0f) return

        val inverse = Matrix4f()
        for (index in nodePath.size - 1 downTo 0) {
            val part = nodePath[index]
            if (part.xRot != 0f) inverse.rotate(-part.xRot, 1f, 0f, 0f)
            if (part.yRot != 0f) inverse.rotate(-part.yRot, 0f, 1f, 0f)
            if (part.zRot != 0f) inverse.rotate(-part.zRot, 0f, 0f, 1f)
            inverse.translate(-part.x / 16f, -part.y / 16f, -part.z / 16f)
        }
        inverse.translate(0f, 1.5f, 0f)

        val scaleMatrix = Matrix4f().scale(scale)
        val inverseScale = Matrix4f().scale(1f / scale.x, 1f / scale.y, 1f / scale.z)
        val positioned = Matrix4f(scaleMatrix).mul(inverse).mul(inverseScale)

        GlStateManager.translate(0f, 1.5f, 0f)
        POSITIONING_BUFFER.clear()
        positioned.get(POSITIONING_BUFFER)
        POSITIONING_BUFFER.rewind()
        GL11.glMultMatrix(POSITIONING_BUFFER)
        GlStateManager.translate(0f, -1.5f, 0f)
    }

    private fun captureBloomIfSupported(
        transformType: ItemCameraTransforms.TransformType,
        texture: ResourceLocation,
        model: BedrockModel?,
        renderBloom: () -> Unit,
    ) {
        if (model == null) return
        if (transformType == ItemCameraTransforms.TransformType.FIRST_PERSON_LEFT_HAND ||
            transformType == ItemCameraTransforms.TransformType.FIRST_PERSON_RIGHT_HAND
        ) return

        when (model) {
            is BedrockAnimatedModel -> {
                val snapshot = model.captureRenderState()
                TACZBloomBridge.captureCurrentModelBloom(texture) {
                    model.restoreRenderState(snapshot)
                    renderBloom()
                    model.cleanAnimationTransform()
                    model.cleanCameraAnimationTransform()
                }
            }
            else -> {
                TACZBloomBridge.captureCurrentModelBloom(texture, renderBloom)
            }
        }
    }
}
