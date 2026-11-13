package com.tacz.legacy.client.compat.playeranimator.animation;

import com.tacz.legacy.api.entity.IGunOperator;
import com.tacz.legacy.api.item.IGun;
import com.tacz.legacy.client.resource.GunDisplayInstance;
import com.tacz.legacy.client.resource.TACZClientAssetManager;
import com.tacz.legacy.common.entity.shooter.ShooterDataHolder;
import com.tacz.legacy.common.resource.TACZGunPackPresentation;
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry;
import dev.kosmx.playerAnim.api.layered.modifier.AdjustmentModifier;
import dev.kosmx.playerAnim.core.util.Vec3f;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;

import java.util.Optional;
import java.util.function.Function;

/**
 * 旋转层修正：把玩家头部/身体/手臂的相对朝向叠加到动画上。
 * 移植自 Next AdjustmentYRotModifier 的 1.12.2 等价实现。
 */
public class AdjustmentYRotModifier implements Function<String, Optional<AdjustmentModifier.PartModifier>> {
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    private final EntityPlayer player;

    AdjustmentYRotModifier(EntityPlayer player) {
        this.player = player;
    }

    @Override
    public Optional<AdjustmentModifier.PartModifier> apply(String partName) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (player.equals(minecraft.player) && minecraft.currentScreen != null) {
            return Optional.empty();
        }

        if (player.getRidingEntity() != null && "body".equals(partName)) {
            return Optional.empty();
        }

        float partialTick = minecraft.getRenderPartialTicks();
        float yBodyRot = rotLerp(partialTick, player.prevRenderYawOffset, player.renderYawOffset);
        float yHeadRot = rotLerp(partialTick, player.prevRotationYawHead, player.rotationYawHead);
        float xRot = lerp(partialTick, player.prevRotationPitch, player.rotationPitch);

        float yaw = MathHelper.wrapDegrees(yHeadRot - yBodyRot);
        yaw = MathHelper.clamp(yaw, -85f, 85f);

        float pitch = MathHelper.wrapDegrees(xRot);

        if ("body".equals(partName)) {
            if (isPlayerLie(player)) {
                return Optional.of(new AdjustmentModifier.PartModifier(new Vec3f(0, 0, -yaw * DEG_TO_RAD), Vec3f.ZERO));
            }
            return Optional.of(new AdjustmentModifier.PartModifier(new Vec3f(0, -yaw * DEG_TO_RAD, 0), Vec3f.ZERO));
        }
        if ("head".equals(partName)) {
            return Optional.of(new AdjustmentModifier.PartModifier(new Vec3f(pitch * DEG_TO_RAD, 0, 0), Vec3f.ZERO));
        }
        if ("leftArm".equals(partName) || "rightArm".equals(partName)) {
            GunDisplayInstance display = resolveDisplay(player.getHeldItemMainhand());
            if (display != null && display.is3rdFixedHand()) {
                return Optional.empty();
            }
            return Optional.of(new AdjustmentModifier.PartModifier(new Vec3f(pitch * DEG_TO_RAD, 0, 0), Vec3f.ZERO));
        }
        return Optional.empty();
    }

    public static AdjustmentModifier getModifier(EntityPlayer player) {
        return RotationAdjustmentModifier.get(player);
    }

    private static boolean isPlayerLie(EntityPlayer player) {
        ShooterDataHolder dataHolder = IGunOperator.fromLivingEntity(player).getDataHolder();
        return dataHolder != null && dataHolder.isCrawling;
    }

    private static GunDisplayInstance resolveDisplay(ItemStack stack) {
        IGun iGun = IGun.getIGunOrNull(stack);
        if (iGun == null) {
            return null;
        }
        ResourceLocation gunId = iGun.getGunId(stack);
        if (gunId == null) {
            return null;
        }
        ResourceLocation displayId = TACZGunPackPresentation.INSTANCE.resolveGunDisplayId(
                TACZGunPackRuntimeRegistry.currentSnapshotForJava(), gunId);
        if (displayId == null) {
            return null;
        }
        return TACZClientAssetManager.INSTANCE.getGunDisplayInstance(displayId);
    }

    private static float rotLerp(float delta, float start, float end) {
        return start + delta * MathHelper.wrapDegrees(end - start);
    }

    private static float lerp(float delta, float start, float end) {
        return start + delta * (end - start);
    }
}
