package com.tacz.legacy.mixin.minecraft.client;

import com.tacz.legacy.api.item.IGun;
import com.tacz.legacy.client.compat.playeranimator.PlayerAnimatorCompat;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelBiped.class)
public abstract class ModelBipedGunPoseMixin {

    @Inject(method = "setRotationAngles", at = @At("TAIL"))
    private void tacz$forceGunArmPose(float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch, float scaleFactor, Entity entityIn, CallbackInfo ci) {
        // 装了 PlayerAnimator 且这把枪真有第三人称动画时才让路；否则继续用写死姿势，
        // 免得没有动画数据的枪变成“手臂垂着、枪飘在腰上”。
        if (PlayerAnimatorCompat.shouldSkipVanillaArmPose(entityIn instanceof EntityLivingBase ? (EntityLivingBase) entityIn : null)) return;
        if (!(entityIn instanceof EntityLivingBase)) return;
        EntityLivingBase living = (EntityLivingBase) entityIn;
        ItemStack mainHand = living.getHeldItemMainhand();
        
        // Check if holding a TACZ gun
        if (!mainHand.isEmpty() && mainHand.getItem() instanceof IGun) {
            ModelBiped model = (ModelBiped) (Object) this;
            ModelRenderer rightArm = model.bipedRightArm;
            ModelRenderer leftArm = model.bipedLeftArm;

            // Right hand holds the grip
            rightArm.rotateAngleX = (float) Math.toRadians(-75); 
            rightArm.rotateAngleY = (float) Math.toRadians(-15); 
            rightArm.rotateAngleZ = (float) Math.toRadians(0);

            // Left hand supports the handguard
            leftArm.rotateAngleX = (float) Math.toRadians(-85);
            leftArm.rotateAngleY = (float) Math.toRadians(35); 
            leftArm.rotateAngleZ = (float) Math.toRadians(-10);
        }
    }
}