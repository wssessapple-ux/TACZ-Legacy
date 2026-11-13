package com.tacz.legacy.client.compat.playeranimator.animation;

import dev.kosmx.playerAnim.api.layered.modifier.AdjustmentModifier;
import net.minecraft.entity.player.EntityPlayer;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 常驻的第三人称旋转修正。
 *
 * <p>移植自 Next 同名单类。修正挂在独立的 rotation 层上，不依赖 clip 存活；
 * 淡入淡出固定为 1，避免 empty clip 每 tick 死掉一帧导致全身抽动。
 */
public class RotationAdjustmentModifier extends AdjustmentModifier {

    /** 每个玩家一个实例：工厂建一次，状态机开关也改这一个。 */
    private static final Map<EntityPlayer, RotationAdjustmentModifier> INSTANCES = new WeakHashMap<>();

    public RotationAdjustmentModifier(AdjustmentYRotModifier source) {
        super(source);
    }

    public static RotationAdjustmentModifier get(EntityPlayer player) {
        RotationAdjustmentModifier modifier = INSTANCES.get(player);
        if (modifier == null) {
            modifier = new RotationAdjustmentModifier(new AdjustmentYRotModifier(player));
            INSTANCES.put(player, modifier);
        }
        return modifier;
    }

    /**
     * 只有当前确实在持枪时才启用；空手 / 无动画的枪仍然沿用原版第三人称朝向。
     */
    public static void setEnabled(EntityPlayer player, boolean enabled) {
        setEnabled(player, enabled, "");
    }

    /** 带原因的版本：状态翻转时打印一行，方便定位是谁把修正关掉的。 */
    public static void setEnabled(EntityPlayer player, boolean enabled, String reason) {
        RotationAdjustmentModifier modifier = get(player);
        if (modifier.enabled != enabled && Boolean.getBoolean("tacz.projDebug")) {
            org.apache.logging.log4j.LogManager.getLogger("tacz").info(
                    "[PlayerAnimator] rotation adjust {} -> {} ({})", modifier.enabled, enabled, reason);
        }
        modifier.enabled = enabled;
    }

    @Override
    public boolean isActive() {
        return enabled;
    }

    @Override
    protected float getFadeIn(float delta) {
        return 1f;
    }

    @Override
    protected float getFadeOut(float delta) {
        return 1f;
    }
}
