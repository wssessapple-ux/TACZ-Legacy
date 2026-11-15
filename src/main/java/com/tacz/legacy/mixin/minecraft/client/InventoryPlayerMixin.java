package com.tacz.legacy.mixin.minecraft.client;

import com.tacz.legacy.client.fpv.FpvController;
import net.minecraft.entity.player.InventoryPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FPV 遥控期间拦截滚轮切栏。
 *
 * 数字键/背包/丢物/副手交换走 {@code Minecraft.processKeyBinds}，已由 MinecraftMixin 整体 HEAD 取消；
 * 但滚轮换槽走的是独立链路 {@code Minecraft.runTickMouse} → {@code InventoryPlayer.changeCurrentItem}，
 * 不经过 processKeyBinds，这里在切栏方法本身收口：视角内滚轮无法切换手持槽位。
 */
@Mixin(InventoryPlayer.class)
public abstract class InventoryPlayerMixin {

    @Inject(method = "changeCurrentItem", at = @At("HEAD"), cancellable = true)
    private void tacz$cancelFpvHotbarScroll(int direction, CallbackInfo ci) {
        if (FpvController.isFpvActive()) {
            ci.cancel();
        }
    }
}
