package com.tacz.legacy.client.model;

import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraftforge.client.model.BakedModelWrapper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nonnull;
import javax.vecmath.Matrix4f;

/**
 * Wraps a baked item model to capture the current {@link ItemCameraTransforms.TransformType}
 * before the TEISR is invoked. In 1.12.2, {@code TileEntityItemStackRenderer.renderByItem}
 * does not receive the transform type, so TEISRs read it from
 * {@link #getCurrentTransformType()} instead.
 * <p>
 * Install via {@code ModelBakeEvent} for any item that uses {@code "parent": "builtin/entity"}.
 */
@SideOnly(Side.CLIENT)
public class TACZPerspectiveAwareBakedModel extends BakedModelWrapper<IBakedModel> {
    private static ItemCameraTransforms.TransformType currentTransformType = ItemCameraTransforms.TransformType.NONE;

    public TACZPerspectiveAwareBakedModel(IBakedModel original) {
        super(original);
    }

    @Override
    @Nonnull
    public Pair<? extends IBakedModel, Matrix4f> handlePerspective(@Nonnull ItemCameraTransforms.TransformType cameraTransformType) {
        currentTransformType = cameraTransformType;
        // 把「采用三维 TEISR」与「中和原始 JSON 透视矩阵」分开判断：
        // GUI/FIXED/HEAD/NONE 以及 GROUND 都应从干净矩阵开始（TEISR 自行应用枪包的 ground/slot 变换），
        // 若 GROUND 仍返回原始 JSON 地面矩阵，会与枪包的 ground 缩放叠加成双重变换。
        // 手部上下文（FIRST_PERSON / THIRD_PERSON）保持原始矩阵不变，避免第一人称副作用。
        if (shouldNeutralizeOriginalMatrix(cameraTransformType)) {
            return Pair.of(this, null);
        }
        Pair<? extends IBakedModel, Matrix4f> result = originalModel.handlePerspective(cameraTransformType);
        return Pair.of(this, result.getRight());
    }

    /**
     * Whether the TEISR should start from a neutralized (identity) matrix for this context.
     * Includes {@link ItemCameraTransforms.TransformType#GROUND}, which still uses the 3D TEISR
     * but must not additionally apply the original JSON ground transform.
     */
    public static boolean shouldNeutralizeOriginalMatrix(@Nonnull ItemCameraTransforms.TransformType type) {
        return isItemPresentationContext(type) || type == ItemCameraTransforms.TransformType.GROUND;
    }

    /**
     * Whether the given transform type is an "item presentation" context
     * (GUI, item frame, head) as opposed to a hand-held or dropped 3D context.
     */
    public static boolean isItemPresentationContext(@Nonnull ItemCameraTransforms.TransformType type) {
        switch (type) {
            case GUI:
            case FIXED:
            case HEAD:
            case NONE:
                return true;
            default:
                return false;
        }
    }

    /**
     * Returns the transform type that was last set during {@code handlePerspective}.
     * Called by TACZ TEISRs on the render thread to determine the current display context.
     */
    @Nonnull
    public static ItemCameraTransforms.TransformType getCurrentTransformType() {
        return currentTransformType;
    }
}
