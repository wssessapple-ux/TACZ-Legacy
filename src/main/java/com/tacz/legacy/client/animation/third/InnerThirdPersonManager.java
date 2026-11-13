package com.tacz.legacy.client.animation.third;

import com.tacz.legacy.api.client.other.IThirdPersonAnimation;
import com.tacz.legacy.api.client.other.ThirdPersonManager;
import com.tacz.legacy.api.entity.IGunOperator;
import com.tacz.legacy.api.item.IGun;
import com.tacz.legacy.client.compat.playeranimator.PlayerAnimatorCompat;
import com.tacz.legacy.client.resource.TACZClientAssetManager;
import com.tacz.legacy.client.resource.pojo.display.gun.GunDisplay;
import com.tacz.legacy.common.resource.TACZGunPackPresentation;
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/**
 * 第三人称持枪姿势的分发点。
 *
 * <p>移植自上游 {@code com.tacz.guns.client.animation.third.InnerThirdPersonManager}。
 * 装了 PlayerAnimator 且这把枪真有第三人称动画时，兼容层自己驱动骨骼，这里直接让路；
 * 否则按 {@code display} 的 {@code third_person_animation} 选内置静态姿势（default / minigun）。
 *
 * <p>调用方是 {@code ModelBipedGunPoseMixin}，注入在 {@code ModelBiped.setRotationAngles} 的 TAIL：
 * 原版先把手臂摆成正常姿势，这里再覆盖成持枪姿势。
 *
 * <p>与 Next 的差异：clip 播放层由本仓库精简桥接（{@link PlayerAnimatorCompat}）按 tick 驱动；
 * 模型根跟随视线的旋转修正层已移植，持枪即开，静态姿势路径同样生效。
 */
public final class InnerThirdPersonManager {

    private InnerThirdPersonManager() {
    }

    /**
     * 在主手持有枪械时改写双臂（以及机枪姿势下的躯干）的旋转角。
     *
     * @param entityIn 正在被渲染的实体
     * @param rightArm 右手模型
     * @param leftArm  左手模型
     * @param body     躯干模型
     * @param head     头部模型
     * @param headwear 头部外层模型（帽子层）
     */
    public static void setRotationAnglesHead(EntityLivingBase entityIn, ModelRenderer rightArm,
                                             ModelRenderer leftArm, ModelRenderer body, ModelRenderer head,
                                             ModelRenderer headwear, float limbSwingAmount) {
        // 暂停时不算，否则标题界面的预览实体会一直重算（上游同款保护）
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || minecraft.isGamePaused()) {
            return;
        }

        ItemStack mainHandItem = entityIn.getHeldItemMainhand();
        IGun iGun = IGun.getIGunOrNull(mainHandItem);
        if (iGun == null) {
            PlayerAnimatorCompat.stopAllAnimation(entityIn);
            return;
        }

        // 睡觉、爬梯、游泳、鞘翅飞行时不摆枪姿（与上游一致）
        if (entityIn.isPlayerSleeping() || entityIn.isOnLadder() || entityIn.isInWater()
                || entityIn.isElytraFlying()) {
            PlayerAnimatorCompat.stopAllAnimation(entityIn);
            return;
        }

        GunDisplay display = resolveDisplay(iGun, mainHandItem);
        if (display == null) {
            PlayerAnimatorCompat.stopRotationAdjustment(entityIn);
            return;
        }

        // 持枪时（无论有没有 player_animator clip）都把模型根转到视线方向。
        // 原版 renderYawOffset 在站立转身时会滞后并被 ±75° 夹住，不补偿的话
        // 身体和手里的枪都会卡在旧朝向（枪挂在身体根上，枪管也就跟着不转）。
        boolean lookAligned = PlayerAnimatorCompat.enableRotationAdjustment(entityIn);

        // PlayerAnimator 兼容层判定这把枪正在播动画：手臂/躯干交给动画层，这里不动。
        if (PlayerAnimatorCompat.shouldSkipVanillaArmPose(entityIn)) {
            return;
        }

        // 静态姿势的骨骼角度是按「原版身体朝向」写的；root 已经改成视线方向后，
        // 骨骼上的相对偏航（原版 netHeadYaw）要清零，否则头/手臂会多转一份。
        if (lookAligned) {
            head.rotateAngleY = 0.0F;
        }

        IThirdPersonAnimation animation = ThirdPersonManager.getAnimation(display.getThirdPersonAnimation());
        float aimingProgress = IGunOperator.fromLivingEntity(entityIn).getSynAimingProgress();
        if (aimingProgress <= 0) {
            animation.animateGunHold(entityIn, rightArm, leftArm, body, head);
        } else {
            animation.animateGunAim(entityIn, rightArm, leftArm, body, head, aimingProgress);
        }

        if (lookAligned) {
            // ModelBiped 在 TAIL 注入之前就把 head 的旋转抄给了 headwear，这里补一次。
            headwear.rotateAngleX = head.rotateAngleX;
            headwear.rotateAngleY = head.rotateAngleY;
            headwear.rotateAngleZ = head.rotateAngleZ;
            headwear.rotationPointX = head.rotationPointX;
            headwear.rotationPointY = head.rotationPointY;
            headwear.rotationPointZ = head.rotationPointZ;
        }
    }

    /**
     * 枪械 id → display id → display 实例。display 没加载出来时（枪包被换成不认识的、
     * 或者资源还在建）直接放弃，不要抛异常 —— 这里是渲染热路径，每帧都会走。
     */
    private static GunDisplay resolveDisplay(IGun iGun, ItemStack stack) {
        ResourceLocation gunId = iGun.getGunId(stack);
        if (gunId == null) {
            return null;
        }
        ResourceLocation displayId = TACZGunPackPresentation.INSTANCE.resolveGunDisplayId(
                TACZGunPackRuntimeRegistry.INSTANCE.currentSnapshotForJava(), gunId);
        if (displayId == null) {
            return null;
        }
        return TACZClientAssetManager.INSTANCE.getGunDisplay(displayId);
    }
}
