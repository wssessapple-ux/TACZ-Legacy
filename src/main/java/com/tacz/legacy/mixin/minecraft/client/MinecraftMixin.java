package com.tacz.legacy.mixin.minecraft.client;

import com.tacz.legacy.api.item.IGun;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "clickMouse", at = @At("HEAD"), cancellable = true)
    private void tacz$cancelGunClickMouse(CallbackInfo ci) {
        if (tacz$shouldSuppressGunLeftClick()) {
            ci.cancel();
        }
    }

    @Inject(method = "sendClickBlockToController", at = @At("HEAD"), cancellable = true)
    private void tacz$cancelGunBlockDamage(boolean leftClick, CallbackInfo ci) {
        if (!leftClick || !tacz$shouldSuppressGunLeftClick()) {
            return;
        }
        Minecraft minecraft = (Minecraft) (Object) this;
        if (minecraft.playerController != null) {
            minecraft.playerController.resetBlockRemoving();
        }
        ci.cancel();
    }

    // [0.6.4-REV] 持枪右键拦截：原版 rightClickMouse 会触发 use/放方块/用物品，持枪时右键应归 TACZ 瞄准
    // (AIM 键绑定，不经过 rightClickMouse)。QA 反馈'拿着枪右键能使用 Minecraft 基础键位功能'，补齐此拦截。
    @Inject(method = "rightClickMouse", at = @At("HEAD"), cancellable = true)
    private void tacz$cancelGunRightClickMouse(CallbackInfo ci) {
        if (tacz$shouldSuppressGunLeftClick()) {
            ci.cancel();
        }
    }

    @Unique
    private boolean tacz$shouldSuppressGunLeftClick() {
        Minecraft minecraft = (Minecraft) (Object) this;
        EntityPlayerSP player = minecraft.player;
        if (player == null || minecraft.currentScreen != null || !minecraft.inGameHasFocus) {
            return false;
        }
        ItemStack stack = player.getHeldItemMainhand();
        return !stack.isEmpty() && stack.getItem() instanceof IGun;
    }
}