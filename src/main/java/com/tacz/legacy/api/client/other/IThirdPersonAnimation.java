package com.tacz.legacy.api.client.other;

import net.minecraft.client.model.ModelRenderer;
import net.minecraft.entity.EntityLivingBase;

/**
 * 第三人称持枪动画。由 {@code display} 的 {@code third_person_animation} 字段选实现。
 *
 * <p>移植自上游 {@code com.tacz.guns.api.client.other.IThirdPersonAnimation}。
 * 上游拿到的是 1.16+ 的 {@code ModelPart}，这里是 1.12.2 的 {@link ModelRenderer}，字段对应关系：
 * <ul>
 *     <li>{@code ModelPart.xRot / yRot / zRot} → {@code ModelRenderer.rotateAngleX / Y / Z}</li>
 *     <li>{@code ModelPart.x / y / z}（枢轴点）→ {@code ModelRenderer.rotationPointX / Y / Z}</li>
 * </ul>
 *
 * <p><b>注意</b>：这套东西和 {@code player_animator_3rd} 是两条独立的路径。后者交给
 * PlayerAnimator 兼容层驱动；本接口走的是“直接摆原版双臂骨骼”，不依赖任何外部库。
 */
public interface IThirdPersonAnimation {

    /**
     * 主手持有枪械时的姿势。
     *
     * @param entity   持有枪械的实体
     * @param rightArm 右手模型
     * @param leftArm  左手模型
     * @param body     躯干模型
     * @param head     头部模型
     */
    void animateGunHold(EntityLivingBase entity, ModelRenderer rightArm, ModelRenderer leftArm,
                        ModelRenderer body, ModelRenderer head);

    /**
     * 瞄准时的姿势。
     *
     * @param aimProgress 瞄准进度 0-1
     */
    void animateGunAim(EntityLivingBase entity, ModelRenderer rightArm, ModelRenderer leftArm,
                       ModelRenderer body, ModelRenderer head, float aimProgress);
}
