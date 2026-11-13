package com.tacz.legacy.api.client.other;

import com.google.common.collect.Maps;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.entity.EntityLivingBase;

import java.util.Map;

/**
 * 第三人称持枪动画注册表。
 *
 * <p>移植自上游 {@code com.tacz.guns.api.client.other.ThirdPersonManager}。内置两个实现：
 * <ul>
 *     <li>{@code default} —— 通用持枪/瞄准姿势，所有没写 {@code third_person_animation}
 *         的枪（字段缺省即 {@code "empty"}）都会回落到这里；</li>
 *     <li>{@code minigun} —— 双手前伸、躯干随头部转向的机枪姿势。</li>
 * </ul>
 *
 * <p>第三方枪包可以调用 {@link #register(String, IThirdPersonAnimation)} 注册自己的实现，
 * 然后在 {@code display} 里写同名的 {@code third_person_animation}。
 */
public final class ThirdPersonManager {

    private static final Map<String, IThirdPersonAnimation> CACHE = Maps.newHashMap();

    private static final String RESERVED_DEFAULT_NAME = "default";

    private static final IThirdPersonAnimation DEFAULT = new IThirdPersonAnimation() {
        @Override
        public void animateGunHold(EntityLivingBase entity, ModelRenderer rightArm, ModelRenderer leftArm,
                                   ModelRenderer body, ModelRenderer head) {
            rightArm.rotateAngleY = -0.3F + head.rotateAngleY;
            leftArm.rotateAngleY = 0.8F + head.rotateAngleY;
            rightArm.rotateAngleX = -1.4F + head.rotateAngleX;
            leftArm.rotateAngleX = -1.4F + head.rotateAngleX;
        }

        @Override
        public void animateGunAim(EntityLivingBase entity, ModelRenderer rightArm, ModelRenderer leftArm,
                                  ModelRenderer body, ModelRenderer head, float aimProgress) {
            // 上游用 Mth.lerp；1.12.2 的 MathHelper 没有同名三参版本，这里直接展开。
            float lerp1 = 0.3F + (0.35F - 0.3F) * aimProgress;
            float lerp2 = 1.4F + (1.6F - 1.4F) * aimProgress;
            rightArm.rotateAngleY = -lerp1 + head.rotateAngleY;
            leftArm.rotateAngleY = 0.8F + head.rotateAngleY;
            rightArm.rotateAngleX = -lerp2 + head.rotateAngleX;
            leftArm.rotateAngleX = -lerp2 + head.rotateAngleX;
        }
    };

    private static final String MINI_GUN_NAME = "minigun";

    private static final IThirdPersonAnimation MINI_GUN = new IThirdPersonAnimation() {
        @Override
        public void animateGunHold(EntityLivingBase entity, ModelRenderer rightArm, ModelRenderer leftArm,
                                   ModelRenderer body, ModelRenderer head) {
            body.rotateAngleY = head.rotateAngleY + 0.8F;

            double cosTheta = Math.cos(-body.rotateAngleY);
            double sinTheta = Math.sin(-body.rotateAngleY);

            // 上游读的是 ModelPart 的枢轴 x；1.12.2 对应 rotationPointX。
            // 注意 setRotationAngles 每帧都会把这些旋转点重置成标准值，所以这里直接改写是安全的。
            float x = rightArm.rotationPointX;
            rightArm.rotationPointX = (float) (x * cosTheta);
            rightArm.rotationPointZ = (float) (x * sinTheta);
            rightArm.rotateAngleY = -1.0F + body.rotateAngleY;
            rightArm.rotateAngleX = -0.1F + body.rotateAngleX;

            float x2 = leftArm.rotationPointX;
            leftArm.rotationPointX = (float) (x2 * cosTheta);
            leftArm.rotationPointZ = (float) (x2 * sinTheta);
            leftArm.rotateAngleY = -0.1F + body.rotateAngleY;
            leftArm.rotateAngleX = -1.0F + body.rotateAngleX;
        }

        @Override
        public void animateGunAim(EntityLivingBase entity, ModelRenderer rightArm, ModelRenderer leftArm,
                                  ModelRenderer body, ModelRenderer head, float aimProgress) {
            animateGunHold(entity, rightArm, leftArm, body, head);
        }
    };

    static {
        // 上游是初始化时显式调 registerDefault()；这里放进静态块，免得漏调之后
        // 所有枪都静默回落到 default —— 那种情况从画面上看不出来，很难查。
        registerDefault();
    }

    private ThirdPersonManager() {
    }

    /**
     * 注册内置的两个实现。由客户端初始化调用一次。
     */
    public static void registerDefault() {
        CACHE.put(RESERVED_DEFAULT_NAME, DEFAULT);
        CACHE.put(MINI_GUN_NAME, MINI_GUN);
    }

    /**
     * 注册一个第三方实现。{@code default} 是保留名，注册会被忽略（与上游一致）。
     */
    public static void register(String name, IThirdPersonAnimation animation) {
        if (name == null || RESERVED_DEFAULT_NAME.equals(name)) {
            return;
        }
        CACHE.put(name, animation);
    }

    /**
     * 取实现。名字为 null 或未注册时回落到 {@code default} —— 上游的 {@code getOrDefault} 语义。
     * 这也是 {@code display} 里 {@code third_person_animation} 缺省值写成 {@code "empty"} 的原因：
     * 它本来就不该命中任何具名实现。
     */
    public static IThirdPersonAnimation getAnimation(String name) {
        if (name == null) {
            return DEFAULT;
        }
        return CACHE.getOrDefault(name, DEFAULT);
    }
}
