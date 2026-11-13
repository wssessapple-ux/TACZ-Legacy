package com.tacz.legacy.mixin.minecraft.client;

import com.tacz.legacy.api.item.IGun;
import com.tacz.legacy.client.animation.third.InnerThirdPersonManager;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHandSide;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelBiped.class)
public abstract class ModelBipedGunPoseMixin {

    @Shadow
    public ModelBiped.ArmPose rightArmPose;

    @Shadow
    public ModelBiped.ArmPose leftArmPose;

    @Shadow
    public ModelRenderer bipedHead;

    @Shadow
    public ModelRenderer bipedHeadwear;

    @Shadow
    public ModelRenderer bipedBody;

    @Shadow
    public ModelRenderer bipedRightArm;

    @Shadow
    public ModelRenderer bipedLeftArm;

    /**
     * HEAD 那个 ArmPose 只是把手臂“扶正”——让别的读 {@code ArmPose} 的逻辑
     * （比如第一人称手部渲染的分支）也能看出这是持枪状态。真正的姿势由
     * {@link InnerThirdPersonManager} 在 TAIL 覆盖。
     */
    @Inject(method = "setRotationAngles", at = @At("HEAD"))
    private void tacz$forceGunArmPose(
            float limbSwing, float limbSwingAmount, float ageInTicks,
            float netHeadYaw, float headPitch, float scaleFactor,
            Entity entityIn, CallbackInfo ci
    ) {
        if (!(entityIn instanceof EntityLivingBase)) {
            return;
        }
        EntityLivingBase living = (EntityLivingBase) entityIn;
        ItemStack mainHand = living.getHeldItemMainhand();
        if (!mainHand.isEmpty() && mainHand.getItem() instanceof IGun) {
            if (living.getPrimaryHand() == EnumHandSide.LEFT) {
                this.leftArmPose = ModelBiped.ArmPose.BOW_AND_ARROW;
            } else {
                this.rightArmPose = ModelBiped.ArmPose.BOW_AND_ARROW;
            }
        }
    }

    /**
     * 原版算完之后再覆盖成实际的持枪姿势。静态姿势由 {@code display} 的
     * {@code third_person_animation} 决定：没写的枪走 {@code default}，写了
     * {@code minigun} 的走机枪姿势；PlayerAnimator 在场且有 clip 时由兼容层自行驱动。
     */
    @Inject(method = "setRotationAngles", at = @At("TAIL"))
    private void tacz$applyThirdPersonGunPose(
            float limbSwing, float limbSwingAmount, float ageInTicks,
            float netHeadYaw, float headPitch, float scaleFactor,
            Entity entityIn, CallbackInfo ci
    ) {
        if (!(entityIn instanceof EntityLivingBase)) {
            return;
        }
        InnerThirdPersonManager.setRotationAnglesHead(
                (EntityLivingBase) entityIn, this.bipedRightArm, this.bipedLeftArm, this.bipedBody, this.bipedHead,
                this.bipedHeadwear, limbSwingAmount);
    }
}
