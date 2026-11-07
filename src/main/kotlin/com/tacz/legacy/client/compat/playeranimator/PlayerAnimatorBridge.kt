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
import dev.kosmx.playerAnim.minecraftApi.layers.LeftHandedHelperModifier
import net.minecraft.client.Minecraft
import net.minecraft.client.entity.AbstractClientPlayer
import net.minecraft.item.ItemStack
import net.minecraft.util.ResourceLocation
import net.minecraft.world.World
import org.apache.logging.log4j.MarkerManager
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.UUID

/**
 * 真正用到 PlayerAnimator 1.12.2 的那一层。
 *
 * 这里**只能**在 [PlayerAnimatorCompat.isAvailable] 为 true 之后被加载，
 * 否则 JVM 会因为找不到 dev.kosmx.* 而抛 NoClassDefFoundError。
 *
 * 数据流：
 * gun pack 里 `assets/<ns>/player_animator/<file>.player_animation.json`
 * → [GeckoLibSerializer] 逐 clip 解析成 [KeyframeAnimation]
 * → 每个玩家挂两层 [ModifierLayer]（0 = 下半身 lower，1 = 上半身 upper）
 * → 每 tick 按枪械状态（瞄准 / 换弹 / 开火 / 跑 / 走 / 蹲）选 clip 并带淡入切换。
 *
 * 库的 mixin 已经在 `ModelBiped.setRotationAngles` 的 TAIL 处读取这些 layer 并写进
 * biped 各部位，所以本类只负责"该播哪个 clip"，不碰骨骼。
 */
internal class PlayerAnimatorBridge {

    private val marker = MarkerManager.getMarker("TACZPlayerAnimator")

    /** 动画文件 id → (clip 名 → 解析后的动画)。 */
    private val clipsByAnimation = LinkedHashMap<ResourceLocation, Map<String, KeyframeAnimation>>()

    /** gun display id → 引用的动画文件 id。 */
    private val animationByDisplay = HashMap<ResourceLocation, ResourceLocation>()

    /** 玩家 uuid → 挂在它动画栈上的两层。 */
    private val layers = HashMap<UUID, LayerState>()

    private var trackedWorld: World? = null

    private class LayerState(
        val lower: ModifierLayer<KeyframeAnimationPlayer>,
        val upper: ModifierLayer<KeyframeAnimationPlayer>,
    ) {
        var lowerClip: String? = null
        var upperClip: String? = null
    }

    // ------------------------------------------------------------------ 资源

    /**
     * gun pack 客户端资源重载后调用：重新收集 display 引用的 player_animator 文件并解析。
     * 只清数据，**不动**已经挂在玩家动画栈上的 layer（重载不该让动作层重建）。
     */
    fun reload(snapshot: TACZRuntimeSnapshot) {
        clipsByAnimation.clear()
        animationByDisplay.clear()

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
            val clips = loadClips(animationId)
            if (clips.isNullOrEmpty()) {
                missingFiles++
                TACZLegacy.logger.warn(marker, "player_animator file not found or empty: {}", animationId)
                continue
            }
            clipsByAnimation[animationId] = clips
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

    /** 先按 gun pack 的资产约定找，再退到内置资源（jar 内的默认枪包）。 */
    private fun readAnimationFile(animationId: ResourceLocation): String? {
        val assetId = ResourceLocation(animationId.namespace, "player_animator/${animationId.path}.json")
        TACZClientAssetManager.openPackAsset(assetId)?.use { stream ->
            return stream.bufferedReader(StandardCharsets.UTF_8).readText()
        }
        for (candidate in CLASS_RESOURCE_CANDIDATES(animationId)) {
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

    private fun CLASS_RESOURCE_CANDIDATES(animationId: ResourceLocation): List<String> = listOf(
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
        for (entity in world.playerEntities) {
            if (entity !is AbstractClientPlayer) continue
            updatePlayer(entity)
        }
    }

    private fun updatePlayer(player: AbstractClientPlayer) {
        val layer = layerFor(player) ?: return
        val clips = resolveClips(player.heldItemMainhand)
        if (clips == null) {
            fadeOut(layer)
            return
        }
        val upperName = resolveUpperClipName(player, clips)
        val lowerName = resolveLowerClipName(player, clips)
        layer.upperClip = switchClip(layer.upper, clips, upperName, layer.upperClip)
        layer.lowerClip = switchClip(layer.lower, clips, lowerName, layer.lowerClip)
    }

    private fun layerFor(player: AbstractClientPlayer): LayerState? {
        layers[player.uniqueID]?.let { return it }
        val stack = try {
            PlayerAnimationAccess.getPlayerAnimLayer(player)
        } catch (_: Throwable) {
            return null
        }
        val lower = ModifierLayer<KeyframeAnimationPlayer>()
        val upper = ModifierLayer<KeyframeAnimationPlayer>()
        // 优先级别低的是下半身，上半身覆盖它
        stack.addAnimLayer(0, lower)
        stack.addAnimLayer(1, upper)
        // 左手玩家需要镜像，库不会自动挂
        lower.addModifierLast(LeftHandedHelperModifier(player))
        upper.addModifierLast(LeftHandedHelperModifier(player))
        val created = LayerState(lower, upper)
        layers[player.uniqueID] = created
        return created
    }

    private fun resolveClips(stack: ItemStack): Map<String, KeyframeAnimation>? {
        val gun = stack.item as? IGun ?: return null
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val displayId = TACZGunPackPresentation.resolveGunDisplayId(snapshot, gun.getGunId(stack)) ?: return null
        val animationId = animationByDisplay[displayId] ?: return null
        return clipsByAnimation[animationId]
    }

    // ------------------------------------------------------------------ clip 选择

    private fun resolveUpperClipName(player: AbstractClientPlayer, clips: Map<String, KeyframeAnimation>): String? {
        val operator = player as? IGunOperator
        val reloading = operator?.getSynReloadState()?.isReloading() == true
        if (reloading && clips.containsKey(CLIP_RELOAD_UPPER)) {
            return CLIP_RELOAD_UPPER
        }
        val aiming = operator?.getSynIsAiming() == true
        val firing = (operator?.getSynShootCoolDown() ?: 0L) > 0L
        if (aiming && firing && clips.containsKey(CLIP_AIM_FIRE_UPPER)) {
            return CLIP_AIM_FIRE_UPPER
        }
        if (firing && clips.containsKey(CLIP_NORMAL_FIRE_UPPER)) {
            return CLIP_NORMAL_FIRE_UPPER
        }
        if (aiming && clips.containsKey(CLIP_AIM_UPPER)) {
            return CLIP_AIM_UPPER
        }
        return pick(player, clips, upperCandidates(player), CLIP_HOLD_UPPER)
    }

    private fun resolveLowerClipName(player: AbstractClientPlayer, clips: Map<String, KeyframeAnimation>): String? =
        pick(player, clips, lowerCandidates(player), CLIP_HOLD_LOWER)

    private fun upperCandidates(player: AbstractClientPlayer): List<String> = when {
        isRunning(player) -> listOf(CLIP_RUN_UPPER, CLIP_WALK_UPPER, CLIP_HOLD_UPPER)
        isMoving(player) && isCrouching(player) -> listOf(CLIP_CROUCH_WALK_UPPER, CLIP_WALK_UPPER, CLIP_HOLD_UPPER)
        isMoving(player) -> listOf(CLIP_WALK_UPPER, CLIP_HOLD_UPPER)
        else -> listOf(CLIP_HOLD_UPPER)
    }

    private fun lowerCandidates(player: AbstractClientPlayer): List<String> = when {
        isRunning(player) -> listOf(CLIP_RUN_LOWER, CLIP_WALK_LOWER, CLIP_HOLD_LOWER)
        isMoving(player) && isCrouching(player) -> listOf(CLIP_CROUCH_WALK_LOWER, CLIP_WALK_LOWER, CLIP_HOLD_LOWER)
        isMoving(player) -> listOf(CLIP_WALK_LOWER, CLIP_HOLD_LOWER)
        isCrouching(player) -> listOf(CLIP_CROUCH_LOWER, CLIP_HOLD_LOWER)
        else -> listOf(CLIP_HOLD_LOWER)
    }

    private fun pick(
        player: AbstractClientPlayer,
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

    /** 返回切换后的当前 clip 名；null 表示这一层已经清空。 */
    private fun switchClip(
        layer: ModifierLayer<KeyframeAnimationPlayer>,
        clips: Map<String, KeyframeAnimation>,
        clipName: String?,
        currentClip: String?,
    ): String? {
        if (clipName == null) {
            if (currentClip != null) {
                layer.replaceAnimationWithFade(fadeIn(), null)
            }
            return null
        }
        val clip = clips[clipName] ?: return currentClip
        val current = layer.getAnimation()
        if (clipName == currentClip && current != null && current.isActive) {
            return currentClip
        }
        // 同一 clip 播完（一次性动作，例如 reload/fire）→ 直接重起，不带淡入
        layer.replaceAnimationWithFade(fadeIn(), KeyframeAnimationPlayer(clip))
        return clipName
    }

    private fun fadeOut(layer: LayerState) {
        if (layer.upperClip != null) {
            layer.upper.replaceAnimationWithFade(fadeIn(), null)
            layer.upperClip = null
        }
        if (layer.lowerClip != null) {
            layer.lower.replaceAnimationWithFade(fadeIn(), null)
            layer.lowerClip = null
        }
    }

    private fun fadeIn(): AbstractFadeModifier = AbstractFadeModifier.standardFadeIn(FADE_TICKS, Ease.INOUTSINE)

    private companion object {
        private const val FADE_TICKS = 6

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
    }
}
