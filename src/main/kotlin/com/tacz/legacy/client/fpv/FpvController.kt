package com.tacz.legacy.client.fpv

import com.tacz.legacy.common.entity.EntityFpvDrone
import com.tacz.legacy.common.network.TACZNetworkHandler
import com.tacz.legacy.common.network.message.client.ClientMessageFpvControl
import com.tacz.legacy.common.network.message.client.ClientMessageFpvExit
import net.minecraft.client.Minecraft
import net.minecraftforge.fml.relauncher.Side
import net.minecraftforge.fml.relauncher.SideOnly
import org.lwjgl.input.Mouse

/**
 * FPV 第一人称视角客户端控制器。
 *
 * - 进入视角：服务端授权后 [attach]，把渲染视角切到无人机实体（[Minecraft.setRenderViewEntity]）。
 * - 退出视角：[detach]，视角切回玩家本体。
 * - 每 tick [onClientTick] 把玩家当前视角与 WASD/空格/Shift 输入打包成 C2S 报文上报服务端。
 *
 * 服务端是权威移动方，客户端只负责采集输入与切换相机。
 */
@SideOnly(Side.CLIENT)
public object FpvController {

    private val mc: Minecraft get() = Minecraft.getMinecraft()

    internal var droneId: Int = -1
        private set
    private var drone: EntityFpvDrone? = null

    /** 进入视角后的退出响应冷却 tick：挡住进入那一下右键的残留点击沿，避免刚进就被误退出。 */
    private var enterCooldown: Int = 0
    /** 实体暂时还没同步到客户端的计数；超过宽限才判定实体丢失切回本体。 */
    private var missingTicks: Int = 0
    /** 已向服务端发出退出请求，等待 S2C detach 回包期间不再重复发退出报文。 */
    private var exitRequested: Boolean = false
    /** 退出请求已等待的 tick 数；回包丢失超时后允许重发。 */
    private var exitWaitTicks: Int = 0
    /** 上一帧右键物理按住态，用于自算下降沿兜底（绕开 KeyBinding 沿被同绑键抢占）。 */
    private var wasRightDown: Boolean = false

    private const val ENTER_COOLDOWN_TICKS = 5
    private const val MISSING_TIMEOUT_TICKS = 15
    private const val EXIT_WAIT_TIMEOUT_TICKS = 20

    /** 当前是否正处于 FPV 遥控视角。 */
    internal val isActive: Boolean get() = droneId >= 0

    /** Java/Mixin 友好的静态判断入口。 */
    @JvmStatic
    public fun isFpvActive(): Boolean = isActive

    internal fun attach(id: Int) {
        droneId = id
        exitRequested = false
        exitWaitTicks = 0
        enterCooldown = ENTER_COOLDOWN_TICKS
        missingTicks = 0
        wasRightDown = false
        val entity = mc.world?.getEntityByID(id) as? EntityFpvDrone
        drone = entity
        if (entity != null) {
            mc.setRenderViewEntity(entity)
        }
        // 实体暂未同步到客户端时不立刻 detach，交由 onClientTick 宽限补挂视角。
    }

    internal fun detach() {
        droneId = -1
        drone = null
        exitRequested = false
        exitWaitTicks = 0
        enterCooldown = 0
        missingTicks = 0
        wasRightDown = false
        if (mc.world != null) {
            mc.setRenderViewEntity(mc.player)
        }
    }

    /**
     * 请求服务端退出遥控视角。[recall] true=召回销毁实体，false=原地悬停保留。
     * 由客户端右键直接发起，不依赖主手是否仍拿着无人机物品；服务端校验归属与受控状态后回 S2C。
     */
    @JvmStatic
    public fun requestExit(recall: Boolean) {
        if (droneId < 0) return
        TACZNetworkHandler.sendToServer(ClientMessageFpvExit(droneId, recall))
    }

    internal fun onClientTick() {
        if (droneId < 0) return
        val player = mc.player ?: run { detach(); return }
        val entity = mc.world?.getEntityByID(droneId) as? EntityFpvDrone
        // 注意：不能用本地 isControlled() 守门——state 只在生成时同步过一次，
        // 服务端 startControl 后客户端本地仍是旧值。是否退出视角以服务端 S2C 通知为准。
        if (entity == null || !entity.isEntityAlive) {
            // 实体可能只是还没同步到客户端，给一段宽限再判定丢失，避免刚进入就被弹回本体。
            missingTicks++
            if (missingTicks > MISSING_TIMEOUT_TICKS) {
                detach()
            }
            return
        }
        missingTicks = 0
        drone = entity
        // 实体到位后确保渲染视角挂在本机上（宽限期内视角可能还停在本体）。
        if (mc.renderViewEntity !== entity) {
            mc.setRenderViewEntity(entity)
        }

        // 退出/召回：processKeyBinds 被 Mixin 整体 HEAD 取消，内部的 rightClickMouse 不会执行，
        // 这里在 tick 里轮询使用键的点击沿触发退出；潜行键物理按住即按召回处理。
        val gameSettings = mc.gameSettings
        if (gameSettings != null) {
            // 物理右键下降沿兜底：原版使用键与模组瞄准键同绑右键，KeyBinding 的点击沿可能被
            // 同绑键抢占，这里直接读 LWJGL 鼠标物理态自算下降沿，两路任一触发退出。
            val rightNow = Mouse.isButtonDown(1)
            val rightEdge = rightNow && !wasRightDown
            wasRightDown = rightNow
            if (enterCooldown > 0) {
                enterCooldown--
                // 冷却期清空积压的使用键沿，但不触发退出，防止进入那一下右键残留被误判。
                while (gameSettings.keyBindUseItem.isPressed()) {
                }
            } else if (exitRequested) {
                // 已发退出请求，等 S2C detach 回包；超时未回则允许重发，避免卡死在退出态。
                exitWaitTicks++
                if (exitWaitTicks > EXIT_WAIT_TIMEOUT_TICKS) {
                    exitRequested = false
                }
            } else if (gameSettings.keyBindUseItem.isPressed() || rightEdge) {
                exitRequested = true
                exitWaitTicks = 0
                requestExit(gameSettings.keyBindSneak.isKeyDown())
            }
        }

        val input = player.movementInput
        val up = when {
            input.jump -> 1f
            input.sneak -> -1f
            else -> 0f
        }
        TACZNetworkHandler.sendToServer(
            ClientMessageFpvControl(
                droneId = droneId,
                yaw = player.rotationYaw,
                pitch = player.rotationPitch,
                forward = input.moveForward,
                strafe = input.moveStrafe,
                up = up,
            ),
        )

        // 锁定宿主玩家本体：清零移动输入与水平速度，人在地上不跑、不跳、不滑。
        // 输入已经先打包上报给无人机，这里再把本体输入清空。
        input.moveForward = 0f
        input.moveStrafe = 0f
        input.jump = false
        input.sneak = false
        player.motionX = 0.0
        player.motionZ = 0.0
    }
}
