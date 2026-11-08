package com.tacz.legacy.client.compat.playeranimator

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.api.entity.IGunOperator
import com.tacz.legacy.api.item.IGun
import com.tacz.legacy.client.resource.TACZClientAssetManager
import com.tacz.legacy.common.resource.TACZGunPackPresentation
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry
import com.tacz.legacy.common.resource.TACZRuntimeSnapshot
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer
import dev.kosmx.playerAnim.api.layered.ModifierLayer
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier
import dev.kosmx.playerAnim.core.data.KeyframeAnimation
import dev.kosmx.playerAnim.core.data.gson.GeckoLibSerializer
import dev.kosmx.playerAnim.core.util.Ease
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationFactory
import dev.kosmx.playerAnim.minecraftApi.layers.LeftHandedHelperModifier
import net.minecraft.client.Minecraft
import net.minecraft.client.entity.AbstractClientPlayer
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.item.ItemStack
import net.minecraft.util.ResourceLocation
import net.minecraft.world.World
import org.apache.logging.log4j.MarkerManager
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/**
 * 真正用到 PlayerAnimator 1.12.2 的那一层。
 *
 * 这里**只能**在 [PlayerAnimatorCompat.isAvailable] 为 true 之后被加载，
 * 否则 JVM 会因为找不到 dev.kosmx.* 而抛 NoClassDefFoundError。
 *
 * 分层照上游 TACZ（`MCModderAnchor/TACZ` 的 `compat/playeranimator`）的做法：
 *
 * - `lower`（priority 0）：走路/跑步/蹲/骑乘，只动腿
 * - `loopUpper`（priority 1）：持枪/走/跑/瞄准，持续循环
 * - `onceUpper`（priority 2）：开火/换弹/近战，一次性，播完自动失效
 *
 * 关键差别在于**一次性动作不再靠每 tick 猜状态**：开火和近战由枪械逻辑主动通知
 * （[onShoot] / [onMelee]），换弹用同步状态的上升沿触发。这样 once 层和 loop 层
 * 互不干扰，动作切换的“跳”明显减少。
 */
internal class PlayerAnimatorBridge {

    private val marker = MarkerManager.getMarker("TACZPlayerAnimator")

    /** 动画文件 id → (clip 名 → 解析后的动画)。 */
    private val clipsByAnimation = LinkedHashMap<ResourceLocation, Map<String, KeyframeAnimation>>()

    /** gun display id → 引用的动画文件 id。 */
    private val animationByDisplay = HashMap<ResourceLocation, ResourceLocation>()

    /** 玩家 uuid → 挂在它动画栈上的三层。 */
    private val layers = HashMap<UUID, LayerState>()

    private var trackedWorld: World? = null

    private var factoriesRegistered = false

    private class LayerState(
        val lower: ModifierLayer<KeyframeAnimationPlayer>,
        val loopUpper: ModifierLayer<KeyframeAnimationPlayer>,
        val onceUpper: ModifierLayer<KeyframeAnimationPlayer>,
    ) {
        var lowerClip: String? = null
        var loopUpperClip: String? = null
        var onceClip: String? = null
        var wasReloading: Boolean = false
        var hadAnimation: Boolean = false
    }

    // ------------------------------------------------------------------ 资源

    /**
     * gun pack 客户端资源重载后调用：重新收集 display 引用的 player_animator 文件并解析。
     * 只清数据，**不动**已经挂在玩家动画栈上的 layer（重载不该让动作层重建）。
     */
    fun reload(snapshot: TACZRuntimeSnapshot) {
        clipsByAnimation.clear()
        animationByDisplay.clear()
        ensureFactories()

        val referenced = LinkedHashMap<ResourceLocation, ResourceLocation>()
        for ((displayId, definition) in snapshot.gunDisplays) {
            val animationId = readPlayerAnimator3rd(definition.raw) ?: continue
            referenced[displayId] = animationId
        }
        animationByDisplay.putAll(referenced)

        var loadedFiles = 0
        var loadedClips = 0
        var missingFiles = 0
        for (animationId in referenced.values.toSet()) {
            val clips = loadClips(animationId) ?: loadClipsFromDefaultNamespace(animationId)
            if (clips.isNullOrEmpty()) {
                missingFiles++
                TACZLegacy.logger.warn(marker, "player_animator file not found or empty: {}", animationId)
                continue
            }
            clipsByAnimation[animationId] = clips
            loadedFiles++
            loadedClips += clips.size
        }

        // 内置的三个通用动画，无论有没有 display 引用都先加载好：
        // 没写 player_animator_3rd 的枪会按枪型借用它们，这样不用改任何枪包 json。
        for (fallbackId in FALLBACK_ANIMATIONS) {
            if (clipsByAnimation.containsKey(fallbackId)) continue
            val clips = loadClips(fallbackId) ?: continue
            if (clips.isEmpty()) continue
            clipsByAnimation[fallbackId] = clips
            loadedFiles++
            loadedClips += clips.size
        }

        TACZLegacy.logger.info(
            marker,
            "Third-person player animation: {} displays reference {}, loaded {}/{} files ({} clips, {} missing)",
            referenced.size, referenced.values.toSet().size, loadedFiles,
            referenced.values.toSet().size + missingFiles, loadedClips, missingFiles,
        )
    }

    fun clear() {
        clipsByAnimation.clear()
        animationByDisplay.clear()
    }

    /** 从 gun display 的原始 JSON 里取 `player_animator_3rd`。 */
    private fun readPlayerAnimator3rd(display: JsonObject): ResourceLocation? {
        val element = try {
            display.get("player_animator_3rd")
        } catch (_: Throwable) {
            null
        } ?: return null
        if (!element.isJsonPrimitive) return null
        val raw = try {
            element.asString.trim()
        } catch (_: Throwable) {
            return null
        }
        if (raw.isEmpty()) return null
        return try {
            ResourceLocation(raw)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 读取并解析一个 `.player_animation.json`。
     *
     * 库的 [GeckoLibSerializer] 一次吃掉整个 `animations` 节点，而 [KeyframeAnimation]
     * 没有公开的 name 字段，所以这里逐 clip 包一层单条目 JSON 再解析，
     * 用外层的 key 当 clip 名（和上游 `hold_upper` / `aim_upper` 的用法一致）。
     */
    private fun loadClips(animationId: ResourceLocation): Map<String, KeyframeAnimation>? {
        val text = readAnimationFile(animationId) ?: return null
        val root = try {
            JsonParser().parse(text)
        } catch (_: Throwable) {
            return null
        }
        if (!root.isJsonObject) return null
        val animations = root.asJsonObject.get("animations") ?: return null
        if (!animations.isJsonObject) return null

        val result = LinkedHashMap<String, KeyframeAnimation>()
        for ((clipName, clipElement) in animations.asJsonObject.entrySet()) {
            if (!clipElement.isJsonObject) continue
            val single = JsonObject()
            single.add("animations", JsonObject().apply { add(clipName, clipElement) })
            try {
                val parsed = GeckoLibSerializer.serialize(single)
                if (parsed.isNotEmpty()) {
                    result[clipName] = parsed[0]
                }
            } catch (throwable: Throwable) {
                TACZLegacy.logger.warn(marker, "Skip clip {}.{}: {}", animationId, clipName, throwable.toString())
            }
        }
        return result
    }

    /**
     * 第三方枪包常常只写 `xxx:pistol_default.player_animation` 却不带这个动画文件，
     * 而默认包里正好有同名的一份。按同名去 [DEFAULT_NAMESPACE] 里再找一次。
     */
    private fun loadClipsFromDefaultNamespace(animationId: ResourceLocation): Map<String, KeyframeAnimation>? {
        if (animationId.namespace == DEFAULT_NAMESPACE) return null
        val fallbackId = ResourceLocation(DEFAULT_NAMESPACE, animationId.path)
        return loadClips(fallbackId)
    }

    /** 先按 gun pack 的资产约定找，再退到内置资源（jar 内的默认枪包）。 */
    private fun readAnimationFile(animationId: ResourceLocation): String? {
        val assetId = ResourceLocation(animationId.namespace, "player_animator/${animationId.path}.json")
        TACZClientAssetManager.openPackAsset(assetId)?.use { stream ->
            return stream.bufferedReader(StandardCharsets.UTF_8).readText()
        }
        for (candidate in classResourceCandidates(animationId)) {
            val stream = try {
                javaClass.classLoader.getResourceAsStream(candidate)
            } catch (_: Throwable) {
                null
            } ?: continue
            stream.use {
                return it.bufferedReader(StandardCharsets.UTF_8).readText()
            }
        }
        return null
    }

    private fun classResourceCandidates(animationId: ResourceLocation): List<String> = listOf(
        "assets/${animationId.namespace}/player_animator/${animationId.path}.json",
        "assets/tacz/custom/tacz_default_gun/assets/${animationId.namespace}/player_animator/${animationId.path}.json",
    )

    // ------------------------------------------------------------------ 每 tick

    fun onClientTick() {
        val minecraft = Minecraft.getMinecraft()
        val world = minecraft.world ?: return
        if (minecraft.isGamePaused) return
        if (world !== trackedWorld) {
            trackedWorld = world
            layers.clear()
        }
        ensureFactories()
        for (entity in world.playerEntities) {
            if (entity !is AbstractClientPlayer) continue
            updatePlayer(entity)
        }
    }

    private fun updatePlayer(player: AbstractClientPlayer) {
        val layer = layerFor(player) ?: return
        val clips = resolveClips(player.heldItemMainhand)
        if (clips == null) {
            if (layer.hadAnimation) {
                fadeOut(layer)
                layer.hadAnimation = false
            }
            layer.wasReloading = false
            return
        }
        layer.hadAnimation = true

        val operator = player as? IGunOperator
        val aiming = isAiming(operator)
        val reloading = operator?.getSynReloadState()?.isReloading() == true

        // 换弹用同步状态的上升沿触发（远程玩家也吃得到）
        if (reloading && !layer.wasReloading) {
            triggerOnce(layer, clips, CLIP_RELOAD_UPPER)
        }
        layer.wasReloading = reloading

        layer.lowerClip = switchLoop(
            layer.lower, clips, resolveLowerClipName(player, clips), layer.lowerClip, fadeIn(FADE_LOOP),
        )
        layer.loopUpperClip = switchLoop(
            layer.loopUpper, clips, resolveLoopUpperClipName(player, aiming, clips), layer.loopUpperClip, fadeIn(FADE_LOOP),
        )

        // once 层播完就收掉，避免它把 loop 层的姿势永久压住
        val once = layer.onceUpper.getAnimation()
        if (layer.onceClip != null && (once == null || !once.isActive)) {
            layer.onceUpper.replaceAnimationWithFade(fadeIn(FADE_OUT), null)
            layer.onceClip = null
        }
    }

    // ------------------------------------------------------------------ 事件驱动

    /** 该实体当前是否真的由本兼容层驱动（有枪、能解析到动画文件、层里在播）。 */
    fun isAnimated(entity: net.minecraft.entity.EntityLivingBase): Boolean {
        val player = entity as? AbstractClientPlayer ?: return false
        val state = layers[player.uniqueID] ?: return false
        return isPlaying(state.lower) || isPlaying(state.loopUpper)
    }

    private fun isPlaying(layer: ModifierLayer<KeyframeAnimationPlayer>): Boolean {
        val animation = layer.getAnimation() ?: return false
        return animation.isActive
    }

    fun onShoot(player: EntityPlayer?) {
        val clientPlayer = player as? AbstractClientPlayer ?: return
        val layer = layerFor(clientPlayer) ?: return
        val clips = resolveClips(clientPlayer.heldItemMainhand) ?: return
        val clipName = if (isAiming(clientPlayer as? IGunOperator) && clips.containsKey(CLIP_AIM_FIRE_UPPER)) {
            CLIP_AIM_FIRE_UPPER
        } else {
            CLIP_NORMAL_FIRE_UPPER
        }
        triggerOnce(layer, clips, clipName)
    }

    /** 近战：上游是随机取三段之一，这里照抄。 */
    fun onMelee(player: EntityPlayer?) {
        val clientPlayer = player as? AbstractClientPlayer ?: return
        val layer = layerFor(clientPlayer) ?: return
        val clips = resolveClips(clientPlayer.heldItemMainhand) ?: return
        val available = MELEE_CLIPS.filter { clips.containsKey(it) }
        val clipName = if (available.isEmpty()) {
            CLIP_NORMAL_FIRE_UPPER
        } else {
            available[ThreadLocalRandom.current().nextInt(available.size)]
        }
        triggerOnce(layer, clips, clipName)
    }

    // ------------------------------------------------------------------ 层与注册

    /**
     * 上游的推荐姿势：用 [PlayerAnimationFactory] 在玩家构造时就把层建好，
     * 库会自动 addAnimLayer 并写进 associated data。
     */
    private fun ensureFactories() {
        if (factoriesRegistered) return
        factoriesRegistered = true
        try {
            PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(ID_LOWER, 0) { player ->
                createState(player).lower
            }
            PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(ID_LOOP_UPPER, 1) { player ->
                createState(player).loopUpper
            }
            PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(ID_ONCE_UPPER, 2) { player ->
                createState(player).onceUpper
            }
        } catch (throwable: Throwable) {
            TACZLegacy.logger.warn(marker, "Failed to register animation factories: {}", throwable.toString())
        }
    }

    private fun createState(player: AbstractClientPlayer): LayerState {
        layers[player.uniqueID]?.let { return it }
        val state = LayerState(
            ModifierLayer(),
            ModifierLayer(),
            ModifierLayer(),
        )
        // 左手玩家需要镜像，库不会自动挂
        state.lower.addModifierLast(LeftHandedHelperModifier(player))
        state.loopUpper.addModifierLast(LeftHandedHelperModifier(player))
        state.onceUpper.addModifierLast(LeftHandedHelperModifier(player))
        layers[player.uniqueID] = state
        return state
    }

    /**
     * 取玩家状态；万一 factory 没赶上（例如重载后中途进服的玩家），
     * 就退回手动 addAnimLayer 的兜底路径。
     */
    private fun layerFor(player: AbstractClientPlayer): LayerState? {
        layers[player.uniqueID]?.let { return it }
        val stack = try {
            PlayerAnimationAccess.getPlayerAnimLayer(player)
        } catch (_: Throwable) {
            return null
        }
        val state = createState(player)
        stack.addAnimLayer(0, state.lower)
        stack.addAnimLayer(1, state.loopUpper)
        stack.addAnimLayer(2, state.onceUpper)
        return state
    }

    /**
     * 给玩家的枪找动画。
     *
     * 顺序：display 声明的动画文件 → 文件缺失/没写时按枪型借默认动画 → 都没有就返回 null
     * （返回 null 时 [fadeOut] 会让出控制权，原生手臂姿势接管）。
     */
    private fun resolveClips(stack: ItemStack): Map<String, KeyframeAnimation>? {
        val gun = stack.item as? IGun ?: return null
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val displayId = TACZGunPackPresentation.resolveGunDisplayId(snapshot, gun.getGunId(stack)) ?: return null

        val declaredId = animationByDisplay[displayId]
        if (declaredId != null) {
            clipsByAnimation[declaredId]?.let { return it }
        }
        return borrowDefaultClips(snapshot, displayId)
    }

    /**
     * 没写 `player_animator_3rd`（或写了但文件缺失）的枪，按枪型借用内置通用动画。
     * 判型只看 display 里的 `use_default_animation`（rifle / pistol），认不出来就按步枪处理。
     * 这样第三方枪包一行 json 都不用改，也能有第三人称动作。
     */
    private fun borrowDefaultClips(snapshot: TACZRuntimeSnapshot, displayId: ResourceLocation): Map<String, KeyframeAnimation>? {
        val hint = readString(snapshot.gunDisplays[displayId]?.raw, "use_default_animation")
        val fallbackId = when (hint?.lowercase()) {
            "pistol" -> FALLBACK_PISTOL
            "minigun" -> FALLBACK_MINIGUN
            else -> FALLBACK_RIFLE
        }
        clipsByAnimation[fallbackId]?.let { return it }
        return clipsByAnimation[FALLBACK_RIFLE]
    }

    private fun readString(display: JsonObject?, key: String): String? {
        val element = try {
            display?.get(key)
        } catch (_: Throwable) {
            null
        } ?: return null
        if (!element.isJsonPrimitive) return null
        val value = try {
            element.asString.trim()
        } catch (_: Throwable) {
            return null
        }
        return value.ifEmpty { null }
    }

    // ------------------------------------------------------------------ clip 选择

    private fun isAiming(operator: IGunOperator?): Boolean {
        if (operator == null) return false
        if (operator.getSynIsAiming()) return true
        // 上游用的是渐进瞄准进度，这里退一步：进度过半也算瞄准，切 clip 更跟手
        return operator.getSynAimingProgress() > 0.35f
    }

    private fun resolveLoopUpperClipName(
        player: AbstractClientPlayer,
        aiming: Boolean,
        clips: Map<String, KeyframeAnimation>,
    ): String? {
        val candidates = when {
            aiming -> listOf(CLIP_AIM_UPPER, CLIP_HOLD_UPPER)
            isRunning(player) -> listOf(CLIP_RUN_UPPER, CLIP_WALK_UPPER, CLIP_HOLD_UPPER)
            isMoving(player) && isCrouching(player) -> listOf(CLIP_CROUCH_WALK_UPPER, CLIP_WALK_UPPER, CLIP_HOLD_UPPER)
            isMoving(player) -> listOf(CLIP_WALK_UPPER, CLIP_HOLD_UPPER)
            else -> listOf(CLIP_HOLD_UPPER)
        }
        return pick(clips, candidates, CLIP_HOLD_UPPER)
    }

    private fun resolveLowerClipName(player: AbstractClientPlayer, clips: Map<String, KeyframeAnimation>): String? {
        val candidates = when {
            player.isRiding -> listOf(CLIP_RIDE_LOWER, CLIP_HOLD_LOWER)
            isRunning(player) -> listOf(CLIP_RUN_LOWER, CLIP_WALK_LOWER, CLIP_HOLD_LOWER)
            isMoving(player) && isCrouching(player) -> listOf(CLIP_CROUCH_WALK_LOWER, CLIP_WALK_LOWER, CLIP_HOLD_LOWER)
            isMoving(player) -> listOf(CLIP_WALK_LOWER, CLIP_HOLD_LOWER)
            isCrouching(player) -> listOf(CLIP_CROUCH_LOWER, CLIP_HOLD_LOWER)
            else -> listOf(CLIP_HOLD_LOWER)
        }
        return pick(clips, candidates, CLIP_HOLD_LOWER)
    }

    private fun pick(
        clips: Map<String, KeyframeAnimation>,
        candidates: List<String>,
        fallback: String,
    ): String? {
        for (name in candidates) {
            if (clips.containsKey(name)) return name
        }
        return if (clips.containsKey(fallback)) fallback else null
    }

    private fun isRunning(player: AbstractClientPlayer): Boolean = player.isSprinting

    private fun isCrouching(player: AbstractClientPlayer): Boolean = player.isSneaking

    private fun isMoving(player: AbstractClientPlayer): Boolean = player.limbSwingAmount > 0.05f

    // ------------------------------------------------------------------ 切动画

    /** 循环层：同一个 clip 且还在播就不动，只在不同 clip 之间淡入切换。 */
    private fun switchLoop(
        layer: ModifierLayer<KeyframeAnimationPlayer>,
        clips: Map<String, KeyframeAnimation>,
        clipName: String?,
        currentClip: String?,
        fade: AbstractFadeModifier,
    ): String? {
        if (clipName == null) {
            if (currentClip != null) {
                layer.replaceAnimationWithFade(fade, null)
            }
            return null
        }
        val clip = clips[clipName] ?: return currentClip
        val current = layer.getAnimation()
        if (clipName == currentClip && current != null && current.isActive) {
            return currentClip
        }
        layer.replaceAnimationWithFade(fade, KeyframeAnimationPlayer(clip))
        return clipName
    }

    /** 一次性层：直接顶到最上层播一次，播完由 tick 收掉。 */
    private fun triggerOnce(
        layer: LayerState,
        clips: Map<String, KeyframeAnimation>,
        clipName: String,
    ) {
        val clip = clips[clipName] ?: return
        layer.onceUpper.replaceAnimationWithFade(fadeIn(FADE_ONCE), KeyframeAnimationPlayer(clip))
        layer.onceClip = clipName
    }

    private fun fadeOut(layer: LayerState) {
        val fade = fadeIn(FADE_OUT)
        layer.lower.replaceAnimationWithFade(fade, null)
        layer.loopUpper.replaceAnimationWithFade(fade, null)
        layer.onceUpper.replaceAnimationWithFade(fade, null)
        layer.lowerClip = null
        layer.loopUpperClip = null
        layer.onceClip = null
    }

    private fun fadeIn(ticks: Int): AbstractFadeModifier = AbstractFadeModifier.standardFadeIn(ticks, Ease.INOUTSINE)

    private companion object {
        /** 默认枪包的命名空间，第三方包缺动画文件时按同名兜底到这里 */
        private const val DEFAULT_NAMESPACE = "tacz"

        /** 没写 `player_animator_3rd` 的枪借用这三套内置通用动画 */
        private val FALLBACK_RIFLE = ResourceLocation("tacz", "rifle_default.player_animation")
        private val FALLBACK_PISTOL = ResourceLocation("tacz", "pistol_default.player_animation")
        private val FALLBACK_MINIGUN = ResourceLocation("tacz", "minigun.player_animation")
        private val FALLBACK_ANIMATIONS = listOf(FALLBACK_RIFLE, FALLBACK_PISTOL, FALLBACK_MINIGUN)

        private val ID_LOWER = ResourceLocation("tacz_legacy", "lower_animation")
        private val ID_LOOP_UPPER = ResourceLocation("tacz_legacy", "loop_upper_animation")
        private val ID_ONCE_UPPER = ResourceLocation("tacz_legacy", "once_upper_animation")

        /** 上游用 8 tick，动作之间过渡更柔 */
        private const val FADE_LOOP = 8
        /** 一次性动作要跟手，短一点 */
        private const val FADE_ONCE = 4
        /** 收枪/切空时退出，介于两者之间 */
        private const val FADE_OUT = 6

        private const val CLIP_HOLD_UPPER = "hold_upper"
        private const val CLIP_WALK_UPPER = "walk_upper"
        private const val CLIP_RUN_UPPER = "run_upper"
        private const val CLIP_AIM_UPPER = "aim_upper"
        private const val CLIP_CROUCH_WALK_UPPER = "crouch_walk_upper"
        private const val CLIP_RELOAD_UPPER = "reload_upper"
        private const val CLIP_NORMAL_FIRE_UPPER = "normal_fire_upper"
        private const val CLIP_AIM_FIRE_UPPER = "aim_fire_upper"

        private const val CLIP_HOLD_LOWER = "hold_lower"
        private const val CLIP_WALK_LOWER = "walk_lower"
        private const val CLIP_RUN_LOWER = "run_lower"
        private const val CLIP_CROUCH_LOWER = "crouch_lower"
        private const val CLIP_CROUCH_WALK_LOWER = "crouch_walk_lower"
        private const val CLIP_RIDE_LOWER = "ride_lower"

        private val MELEE_CLIPS = listOf("melee_upper", "melee_2_upper", "melee_3_upper")
    }
}
