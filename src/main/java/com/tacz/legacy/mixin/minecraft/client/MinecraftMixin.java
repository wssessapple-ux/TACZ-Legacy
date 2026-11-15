package com.tacz.legacy.mixin.minecraft.client;

import com.tacz.legacy.api.item.IGun;
import com.tacz.legacy.client.fpv.FpvController;
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

    // FPV 遥控期间：整段原版按键绑定不处理（背包 / 副手交换 / 丢物 / 数字键切栏 / 聊天 / 视角切换）。
    @Inject(method = "processKeyBinds", at = @At("HEAD"), cancellable = true)
    private void tacz$cancelFpvKeyBinds(CallbackInfo ci) {
        if (tacz$isFpvActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "clickMouse", at = @At("HEAD"), cancellable = true)
    private void tacz$cancelGunClickMouse(CallbackInfo ci) {
        if (tacz$shouldSuppressGunLeftClick() || tacz$isFpvActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "sendClickBlockToController", at = @At("HEAD"), cancellable = true)
    private void tacz$cancelGunBlockDamage(boolean leftClick, CallbackInfo ci) {
        Minecraft minecraft = (Minecraft) (Object) this;
        if (tacz$isFpvActive()) {
            if (minecraft.playerController != null) {
                minecraft.playerController.resetBlockRemoving();
            }
            ci.cancel();
            return;
        }
        if (!leftClick || !tacz$shouldSuppressGunLeftClick()) {
            return;
        }
        if (minecraft.playerController != null) {
            minecraft.playerController.resetBlockRemoving();
        }
        ci.cancel();
    }

    // [0.6.4-REV] 持枪右键拦截：原版 rightClickMouse 会触发 use/放方块/用物品，持枪时右键应归 TACZ 瞄准
    // (AIM 键绑定，不经过 rightClickMouse)。QA 反馈'拿着枪右键能使用 Minecraft 基础键位功能'，补齐此拦截。
    // FPV 遥控期间：右键直接作为退出/召回指令发给服务端（普通右键=悬停退出，潜行右键=召回），
    // 不再依赖主手是否还拿着无人机本体，随后拦掉原版物品使用。
    @Inject(method = "rightClickMouse", at = @At("HEAD"), cancellable = true)
    private void tacz$cancelGunRightClickMouse(CallbackInfo ci) {
        if (tacz$shouldSuppressGunLeftClick()) {
            ci.cancel();
            return;
        }
        if (tacz$isFpvActive()) {
            Minecraft minecraft = (Minecraft) (Object) this;
            // 召回判定读按键绑定的物理按下态，不读 player.isSneaking()：
            // FpvController.onClientTick 每 tick 会把 movementInput.sneak 清零，实体潜行标志同步不上。
            boolean recall = minecraft.gameSettings != null
                && minecraft.gameSettings.keyBindSneak.isKeyDown();
            FpvController.requestExit(recall);
            ci.cancel();
        }
    }

    @Unique
    private boolean tacz$isFpvActive() {
        return FpvController.isFpvActive();
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
