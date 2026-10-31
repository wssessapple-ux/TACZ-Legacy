package com.tacz.legacy.common.item

import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.api.DefaultAssets
import com.tacz.legacy.api.item.IAttachment
import com.tacz.legacy.api.item.IAmmo
import com.tacz.legacy.api.item.IAmmoBox
import com.tacz.legacy.api.item.IGun
import com.tacz.legacy.api.item.attachment.AttachmentType
import com.tacz.legacy.api.item.gun.FireMode
import com.tacz.legacy.api.item.gun.GunItemManager
import com.tacz.legacy.common.block.LegacyBlocks
import com.tacz.legacy.common.block.entity.GunSmithTableTileEntity
import com.tacz.legacy.common.config.LegacyConfigManager
import com.tacz.legacy.common.resource.BoltType
import com.tacz.legacy.common.resource.GunDataAccessor
import com.tacz.legacy.common.resource.TACZGunPackPresentation
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry
import com.tacz.legacy.common.registry.LegacyCreativeTabs
import com.tacz.legacy.common.gui.LegacyGuiIds
import net.minecraft.block.state.IBlockState
import net.minecraft.client.util.ITooltipFlag
import net.minecraft.creativetab.CreativeTabs
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.item.Item
import net.minecraft.item.ItemBlock
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.util.ActionResult
import net.minecraft.util.EnumActionResult
import net.minecraft.util.EnumFacing
import net.minecraft.util.EnumHand
import net.minecraft.util.NonNullList
import net.minecraft.util.ResourceLocation
import net.minecraft.util.math.BlockPos
import net.minecraft.util.text.TextFormatting
import net.minecraftforge.items.CapabilityItemHandler
import net.minecraftforge.items.IItemHandler
import net.minecraftforge.fml.relauncher.Side
import net.minecraftforge.fml.relauncher.SideOnly
import net.minecraftforge.registries.IForgeRegistry
import net.minecraft.world.World

internal object LegacyItems {
    internal val MODERN_KINETIC_GUN: ModernKineticGunItem = ModernKineticGunItem().named("modern_kinetic_gun", LegacyCreativeTabs.GUNS)
    internal val AMMO: AmmoItem = AmmoItem().named("ammo", LegacyCreativeTabs.AMMO)
    internal val ATTACHMENT: AttachmentItem = AttachmentItem().named("attachment", LegacyCreativeTabs.PARTS)
    internal val AMMO_BOX: AmmoBoxItem = AmmoBoxItem().named("ammo_box", LegacyCreativeTabs.OTHER)
    internal val TARGET_MINECART: LegacySimpleItem = LegacySimpleItem(maxStackSize = 1).named("target_minecart", LegacyCreativeTabs.OTHER)

    internal val GUN_SMITH_TABLE: LegacyBlockItem = createBlockItem(LegacyBlocks.GUN_SMITH_TABLE)
    internal val WORKBENCH_A: LegacyBlockItem = createBlockItem(LegacyBlocks.WORKBENCH_A)
    internal val WORKBENCH_B: LegacyBlockItem = createBlockItem(LegacyBlocks.WORKBENCH_B)
    internal val WORKBENCH_C: LegacyBlockItem = createBlockItem(LegacyBlocks.WORKBENCH_C)
    internal val TARGET: LegacyBlockItem = createBlockItem(LegacyBlocks.TARGET)
    internal val STATUE: LegacyBlockItem = createBlockItem(LegacyBlocks.STATUE)

    internal val allItems: List<Item> = listOf(
        MODERN_KINETIC_GUN,
        AMMO,
        ATTACHMENT,
        AMMO_BOX,
        TARGET_MINECART,
        GUN_SMITH_TABLE,
        WORKBENCH_A,
        WORKBENCH_B,
        WORKBENCH_C,
        TARGET,
        STATUE,
    )

    internal fun registerAll(registry: IForgeRegistry<Item>): Unit {
        allItems.forEach(registry::register)
        GunItemManager.clear()
        val itemTypes = TACZGunPackRuntimeRegistry.getSnapshot().gunItemTypes().ifEmpty { setOf(ModernKineticGunItem.TYPE_NAME) }
        itemTypes.forEach { typeName ->
            GunItemManager.registerGunItem(typeName, MODERN_KINETIC_GUN)
        }
        TACZLegacy.logger.info("[GunPackRuntime] Registered {} gun item type mapping(s): {}", itemTypes.size, itemTypes.joinToString())
    }

    private fun createBlockItem(block: net.minecraft.block.Block): LegacyBlockItem = LegacyBlockItem(block).apply {
        registryName = requireNotNull(block.registryName)
        setTranslationKey("${TACZLegacy.MOD_ID}.${requireNotNull(block.registryName).path}")
        setCreativeTab(LegacyCreativeTabs.DECORATION)
    }

    private fun <T : Item> T.named(path: String, tab: CreativeTabs): T {
        registryName = ResourceLocation(TACZLegacy.MOD_ID, path)
        setTranslationKey("${TACZLegacy.MOD_ID}.$path")
        setCreativeTab(tab)
        return this
    }
}

internal open class LegacySimpleItem(maxStackSize: Int = 64) : Item() {
    init {
        this.maxStackSize = maxStackSize
    }
}

/**
 * 弹药物品。实现 IAmmo 使其可被 hasInventoryAmmo 识别。
 * NBT 格式与上游 TACZ AmmoItemDataAccessor 一致。
 */
internal class AmmoItem : Item(), IAmmo {
    init {
        maxStackSize = 1
    }

    override fun getCreativeTabs(): Array<CreativeTabs> = arrayOf(LegacyCreativeTabs.AMMO)

    override fun getSubItems(tab: CreativeTabs, items: NonNullList<ItemStack>) {
        if (!isInCreativeTab(tab)) {
            return
        }
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val ammos = TACZGunPackPresentation.sortedAmmos(snapshot)
        if (ammos.isEmpty()) {
            items += ItemStack(this)
            return
        }
        ammos.forEach { (ammoId, _) ->
            val stack = ItemStack(this)
            setAmmoId(stack, ammoId)
            items += stack
        }
    }

    override fun getAmmoId(ammo: ItemStack): ResourceLocation {
        val tag = ammo.tagCompound ?: return DefaultAssets.EMPTY_AMMO_ID
        val str = tag.getString(AMMO_ID_TAG)
        return if (str.isBlank()) DefaultAssets.EMPTY_AMMO_ID else ResourceLocation(str)
    }

    override fun setAmmoId(ammo: ItemStack, ammoId: ResourceLocation?) {
        ensureTag(ammo).setString(AMMO_ID_TAG, (ammoId ?: DefaultAssets.DEFAULT_AMMO_ID).toString())
    }

    override fun isAmmoOfGun(gun: ItemStack, ammo: ItemStack): Boolean {
        val iGun = gun.item as? IGun ?: return false
        val iAmmo = ammo.item as? IAmmo ?: return false
        val gunId = iGun.getGunId(gun)
        val ammoId = iAmmo.getAmmoId(ammo)
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val gunEntry = snapshot.guns[gunId] ?: return false
        return gunEntry.data.ammoId == ammoId
    }

    override fun getItemStackLimit(stack: ItemStack): Int {
        val id = getAmmoId(stack)
        val def = TACZGunPackRuntimeRegistry.getSnapshot().ammos[id]
        return def?.stackSize ?: 1
    }

    override fun getItemStackDisplayName(stack: ItemStack): String {
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val ammoId = getAmmoId(stack)
        return TACZGunPackPresentation.localizedAmmoName(snapshot, ammoId, super.getItemStackDisplayName(stack))
    }

    @SideOnly(Side.CLIENT)
    override fun addInformation(stack: ItemStack, worldIn: World?, tooltip: MutableList<String>, flagIn: ITooltipFlag) {
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val ammoId = getAmmoId(stack)
        TACZGunPackPresentation.localizedAmmoTooltip(snapshot, ammoId)?.let { tooltip += "${TextFormatting.GRAY}$it" }
        TACZGunPackPresentation.localizedPackName(snapshot, ammoId)?.let { tooltip += "${TextFormatting.BLUE}${TextFormatting.ITALIC}$it" }
        appendAdvancedTooltip(
            tooltip = tooltip,
            flag = flagIn,
            runtimeId = ammoId,
            displayId = TACZGunPackPresentation.resolveAmmoDisplayId(snapshot, ammoId),
        )
    }

    internal companion object {
        internal const val AMMO_ID_TAG: String = "AmmoId"
    }
}

internal class AttachmentItem : Item(), IAttachment {
    init {
        maxStackSize = 1
    }

    override fun getCreativeTabs(): Array<CreativeTabs> = LegacyCreativeTabs.attachmentCategoryTabs

    override fun getSubItems(tab: CreativeTabs, items: NonNullList<ItemStack>) {
        if (!isInCreativeTab(tab)) {
            return
        }
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val requestedType = LegacyCreativeTabs.attachmentTypeForTab(tab)
        val attachments = TACZGunPackPresentation.sortedAttachments(snapshot)
        if (attachments.isEmpty()) {
            items += ItemStack(this)
            return
        }
        attachments.asSequence()
            .filter { attachment -> requestedType == null || AttachmentType.fromSerializedName(attachment.index.type) == requestedType }
            .forEach { attachment ->
            val stack = ItemStack(this)
            setAttachmentId(stack, attachment.id)
            items += stack
        }
    }

    override fun getAttachmentId(stack: ItemStack): ResourceLocation {
        val value = stack.tagCompound?.getString(ATTACHMENT_ID_TAG).orEmpty()
        return value.takeIf(String::isNotBlank)?.let(::ResourceLocation) ?: DefaultAssets.EMPTY_ATTACHMENT_ID
    }

    override fun setAttachmentId(stack: ItemStack, attachmentId: ResourceLocation?) {
        if (attachmentId == null) {
            ensureTag(stack).removeTag(ATTACHMENT_ID_TAG)
            return
        }
        ensureTag(stack).setString(ATTACHMENT_ID_TAG, attachmentId.toString())
    }

    override fun getZoomNumber(stack: ItemStack): Int {
        return stack.tagCompound?.getInteger(ZOOM_NUMBER_TAG) ?: 0
    }

    override fun setZoomNumber(stack: ItemStack, zoomNumber: Int) {
        ensureTag(stack).setInteger(ZOOM_NUMBER_TAG, zoomNumber)
    }

    override fun getType(stack: ItemStack): AttachmentType {
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val attachmentId = getAttachmentId(stack)
        val rawType = snapshot.attachments[attachmentId]?.index?.type
        return AttachmentType.fromSerializedName(rawType)
    }

    override fun hasCustomLaserColor(stack: ItemStack): Boolean {
        return stack.tagCompound?.hasKey(LASER_COLOR_TAG, NbtType.INT) ?: false
    }

    override fun getLaserColor(stack: ItemStack): Int {
        if (!hasCustomLaserColor(stack)) {
            return DEFAULT_LASER_COLOR
        }
        return stack.tagCompound?.getInteger(LASER_COLOR_TAG) ?: DEFAULT_LASER_COLOR
    }

    override fun setLaserColor(stack: ItemStack, color: Int) {
        ensureTag(stack).setInteger(LASER_COLOR_TAG, color)
    }

    override fun getItemStackDisplayName(stack: ItemStack): String {
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val attachmentId = getAttachmentId(stack)
        return TACZGunPackPresentation.localizedAttachmentName(snapshot, attachmentId, super.getItemStackDisplayName(stack))
    }

    @SideOnly(Side.CLIENT)
    override fun addInformation(stack: ItemStack, worldIn: World?, tooltip: MutableList<String>, flagIn: ITooltipFlag) {
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val attachmentId = getAttachmentId(stack)
        TACZGunPackPresentation.localizedAttachmentTooltip(snapshot, attachmentId)?.let { tooltip += "${TextFormatting.GRAY}$it" }
        TACZGunPackPresentation.localizedAttachmentTypeName(snapshot, attachmentId)?.let { tooltip += "${TextFormatting.DARK_AQUA}$it" }
        val compatibleGuns = TACZGunPackPresentation.compatibleGunCount(snapshot, attachmentId)
        if (compatibleGuns > 0) {
            tooltip += "${TextFormatting.GRAY}Compatible guns: $compatibleGuns"
        }
        TACZGunPackPresentation.localizedPackName(snapshot, attachmentId)?.let { tooltip += "${TextFormatting.BLUE}${TextFormatting.ITALIC}$it" }
        appendAdvancedTooltip(
            tooltip = tooltip,
            flag = flagIn,
            runtimeId = attachmentId,
            displayId = TACZGunPackPresentation.resolveAttachmentDisplayId(snapshot, attachmentId),
        )
    }

    internal companion object {
        internal const val ATTACHMENT_ID_TAG: String = "AttachmentId"
        internal const val ZOOM_NUMBER_TAG: String = "ZoomNumber"
        internal const val LASER_COLOR_TAG: String = "LaserColor"
        private const val DEFAULT_LASER_COLOR: Int = 0xFF0000
    }
}

/**
 * 子弹盒物品。实现 IAmmoBox 使其可被 hasInventoryAmmo 识别。
 * NBT 格式与上游 TACZ AmmoBoxItemDataAccessor 一致。
 */
internal class AmmoBoxItem : Item(), IAmmoBox {
    init {
        maxStackSize = 1
    }

    override fun getCreativeTabs(): Array<CreativeTabs> = arrayOf(LegacyCreativeTabs.OTHER, LegacyCreativeTabs.AMMO)

    override fun onItemRightClick(worldIn: World, playerIn: EntityPlayer, handIn: EnumHand): ActionResult<ItemStack> {
        val stack = playerIn.getHeldItem(handIn)
        // 创意/全口径创意弹箱不开放 GUI，避免无限刷取
        if (isCreative(stack) || isAllTypeCreative(stack)) {
            return ActionResult.newResult(EnumActionResult.PASS, stack)
        }
        if (!worldIn.isRemote) {
            playerIn.openGui(TACZLegacy, LegacyGuiIds.AMMO_BOX, worldIn, 0, 0, 0)
        }
        return ActionResult.newResult(EnumActionResult.SUCCESS, stack)
    }

    override fun getSubItems(tab: CreativeTabs, items: NonNullList<ItemStack>) {
        if (!isInCreativeTab(tab)) return
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val ammos = TACZGunPackPresentation.sortedAmmos(snapshot)
        if (ammos.isEmpty()) {
            items += ItemStack(this)
            return
        }
        ammos.forEach { (ammoId, _) ->
            val stack = ItemStack(this)
            setAmmoId(stack, ammoId)
            val baseStack = snapshot.ammos[ammoId]?.stackSize ?: 64
            // 兼容层 ③ C7：创意弹箱容量与玩法侧一致（弹药 stackSize × ammoBoxStackSize 配置）
            val capacity = (baseStack * LegacyConfigManager.server.ammoBoxStackSize).coerceAtLeast(1)
            setAmmoCount(stack, capacity)
            items += stack
        }
    }

    override fun getItemStackDisplayName(stack: ItemStack): String {
        return LegacyRuntimeTooltipSupport.resolveDisplayName(stack, super.getItemStackDisplayName(stack))
    }

    override fun getAmmoId(ammoBox: ItemStack): ResourceLocation {
        val tag = ammoBox.tagCompound ?: return DefaultAssets.EMPTY_AMMO_ID
        val str = tag.getString(AMMO_ID_TAG)
        return if (str.isBlank()) DefaultAssets.EMPTY_AMMO_ID else ResourceLocation(str)
    }

    override fun setAmmoId(ammoBox: ItemStack, ammoId: ResourceLocation) {
        ensureTag(ammoBox).setString(AMMO_ID_TAG, ammoId.toString())
    }

    override fun getAmmoCount(ammoBox: ItemStack): Int {
        if (isAllTypeCreative(ammoBox) || isCreative(ammoBox)) return Int.MAX_VALUE
        return ammoBox.tagCompound?.getInteger(AMMO_COUNT_TAG) ?: 0
    }

    override fun setAmmoCount(ammoBox: ItemStack, count: Int) {
        if (isCreative(ammoBox)) {
            ensureTag(ammoBox).setInteger(AMMO_COUNT_TAG, Int.MAX_VALUE)
            return
        }
        ensureTag(ammoBox).setInteger(AMMO_COUNT_TAG, count)
    }

    override fun isAmmoBoxOfGun(gun: ItemStack, ammoBox: ItemStack): Boolean {
        val iGun = gun.item as? IGun ?: return false
        if (isAllTypeCreative(ammoBox)) return true
        val boxAmmoId = getAmmoId(ammoBox)
        if (boxAmmoId == DefaultAssets.EMPTY_AMMO_ID) return false
        val gunId = iGun.getGunId(gun)
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val gunEntry = snapshot.guns[gunId] ?: return false
        return gunEntry.data.ammoId == boxAmmoId
    }

    override fun isCreative(ammoBox: ItemStack): Boolean {
        return ammoBox.tagCompound?.getBoolean(CREATIVE_TAG) ?: false
    }

    override fun isAllTypeCreative(ammoBox: ItemStack): Boolean {
        return ammoBox.tagCompound?.getBoolean(ALL_TYPE_CREATIVE_TAG) ?: false
    }

    internal companion object {
        internal const val AMMO_ID_TAG: String = "AmmoId"
        internal const val AMMO_COUNT_TAG: String = "AmmoCount"
        internal const val CREATIVE_TAG: String = "Creative"
        internal const val ALL_TYPE_CREATIVE_TAG: String = "AllTypeCreative"
    }

    @SideOnly(Side.CLIENT)
    override fun addInformation(stack: ItemStack, worldIn: World?, tooltip: MutableList<String>, flagIn: ITooltipFlag) {
        LegacyRuntimeTooltipSupport.appendTooltip(stack, tooltip, flagIn.isAdvanced)
    }
}

internal class ModernKineticGunItem : Item(), IGun {
    init {
        maxStackSize = 1
    }

    override fun getCreativeTabs(): Array<CreativeTabs> = LegacyCreativeTabs.gunCategoryTabs

    override fun getSubItems(tab: CreativeTabs, items: NonNullList<ItemStack>) {
        if (!isInCreativeTab(tab)) {
            return
        }
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val requestedType = LegacyCreativeTabs.gunTypeForTab(tab)
        val guns = TACZGunPackPresentation.sortedGuns(snapshot)
        if (guns.isEmpty()) {
            items += ItemStack(this)
            return
        }
        guns.asSequence()
            .filter { gun -> requestedType == null || gun.index.type.equals(requestedType, ignoreCase = true) }
            .forEach { gun ->
            val stack = ItemStack(this)
            setGunId(stack, gun.id)
            setCurrentAmmoCount(stack, gun.data.ammoAmount.coerceAtLeast(0))
            val firstFireMode = GunDataAccessor.getGunData(gun.id)?.fireModesSet?.firstOrNull()?.let(::parseFireMode)
            setFireMode(stack, firstFireMode ?: FireMode.UNKNOWN)
            val boltType = GunDataAccessor.getGunData(gun.id)?.boltType ?: BoltType.OPEN_BOLT
            if (boltType != BoltType.OPEN_BOLT) {
                setBulletInBarrel(stack, true)
            }
            items += stack
        }
    }

    override fun getGunId(stack: ItemStack): ResourceLocation {
        val explicitGunId = stack.tagCompound
            ?.getString(IGun.GUN_ID_TAG)
            ?.takeIf { it.isNotBlank() }
            ?.let(::ResourceLocation)
        return explicitGunId
            ?: TACZGunPackRuntimeRegistry.getSnapshot().resolveDefaultGunId(TYPE_NAME)
            ?: DefaultAssets.DEFAULT_GUN_ID
    }

    override fun setGunId(stack: ItemStack, gunId: ResourceLocation?) {
        if (gunId != null) {
            ensureTag(stack).setString(IGun.GUN_ID_TAG, gunId.toString())
        }
    }

    override fun getAimingZoom(stack: ItemStack): Float {
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        var zoom = 1.0f
        var scopeId = getAttachmentId(stack, AttachmentType.SCOPE)
        var builtinScope = false
        if (scopeId == DefaultAssets.EMPTY_ATTACHMENT_ID) {
            scopeId = getBuiltInAttachmentId(stack, AttachmentType.SCOPE)
            builtinScope = true
        }
        if (scopeId != DefaultAssets.EMPTY_ATTACHMENT_ID) {
            val zoomLevels = TACZGunPackPresentation.resolveAttachmentZoomLevels(snapshot, scopeId)
            if (zoomLevels != null && zoomLevels.isNotEmpty()) {
                val zoomNumber = if (builtinScope) {
                    0
                } else {
                    getAttachmentTag(stack, AttachmentType.SCOPE)?.getInteger(AttachmentItem.ZOOM_NUMBER_TAG) ?: 0
                }
                zoom = zoomLevels[Math.floorMod(zoomNumber, zoomLevels.size)]
            }
        } else {
            zoom = TACZGunPackPresentation.resolveGunIronZoom(snapshot, getGunId(stack))
        }
        return zoom.coerceAtLeast(1.0f)
    }

    override fun getFireMode(stack: ItemStack): FireMode {
        val gunId = getGunId(stack)
        val configuredModes = GunDataAccessor.getGunData(gunId)?.fireModesSet.orEmpty()
        val configuredFallback = configuredModes.firstOrNull()
            ?.let(::parseFireMode)
            ?.takeIf { it != FireMode.UNKNOWN }

        val tag = stack.tagCompound
        val storedMode = tag?.getString(IGun.FIRE_MODE_TAG)
            ?.takeIf(String::isNotBlank)
            ?.let {
                try {
                    FireMode.valueOf(it)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }

        if (storedMode != null && storedMode != FireMode.UNKNOWN) {
            if (configuredModes.isEmpty() || configuredModes.any { mode -> mode.equals(storedMode.name, ignoreCase = true) }) {
                return storedMode
            }
        }

        return configuredFallback ?: storedMode ?: FireMode.UNKNOWN
    }

    override fun setFireMode(stack: ItemStack, fireMode: FireMode?) {
        ensureTag(stack).setString(IGun.FIRE_MODE_TAG, (fireMode ?: FireMode.UNKNOWN).name)
    }

    override fun getCurrentAmmoCount(stack: ItemStack): Int {
        return stack.tagCompound?.getInteger(IGun.AMMO_COUNT_TAG) ?: 0
    }

    override fun setCurrentAmmoCount(stack: ItemStack, ammoCount: Int) {
        ensureTag(stack).setInteger(IGun.AMMO_COUNT_TAG, ammoCount.coerceAtLeast(0))
    }

    override fun reduceCurrentAmmoCount(stack: ItemStack) {
        if (!useInventoryAmmo(stack)) {
            setCurrentAmmoCount(stack, getCurrentAmmoCount(stack) - 1)
        }
    }

    override fun hasBulletInBarrel(stack: ItemStack): Boolean {
        return stack.tagCompound?.getBoolean(IGun.BULLET_IN_BARREL_TAG) ?: false
    }

    override fun setBulletInBarrel(stack: ItemStack, bulletInBarrel: Boolean) {
        ensureTag(stack).setBoolean(IGun.BULLET_IN_BARREL_TAG, bulletInBarrel)
    }

    override fun useInventoryAmmo(stack: ItemStack): Boolean {
        // 由 gun data 中的 reload.type 决定是否为背包直读
        // 与上游 TACZ 一致，"inventory" 和 "fuel" 类型都直接从背包读弹
        val gunId = getGunId(stack)
        val gun = TACZGunPackRuntimeRegistry.getSnapshot().guns[gunId] ?: return false
        val reloadType = gun.data.raw.getAsJsonObject("reload")?.get("type")?.asString
        return reloadType == "inventory" || reloadType == "fuel"
    }

    override fun hasInventoryAmmo(shooter: EntityLivingBase, stack: ItemStack, needCheckAmmo: Boolean): Boolean {
        if (!useInventoryAmmo(stack)) return false
        if (!needCheckAmmo) return true
        if (useDummyAmmo(stack)) return getDummyAmmoAmount(stack) > 0
        // 通过 IItemHandler capability 搜索背包弹药（与上游 TACZ 逻辑一致）
        val handler = shooter.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null)
        if (handler != null) {
            for (i in 0 until handler.slots) {
                val checkStack = handler.getStackInSlot(i)
                if (checkStack.isEmpty) continue
                val ammo = checkStack.item as? IAmmo
                if (ammo != null && ammo.isAmmoOfGun(stack, checkStack)) return true
                val box = checkStack.item as? IAmmoBox
                if (box != null && box.isAmmoBoxOfGun(stack, checkStack)) return true
            }
        }
        return false
    }

    /**
     * 从背包中查找并消耗弹药。返回实际消耗的弹药数量。
     * 与上游 TACZ AbstractGunItem.findAndExtractInventoryAmmo 行为一致。
     */
    public fun findAndExtractInventoryAmmo(handler: IItemHandler, gunItem: ItemStack, needAmmoCount: Int): Int {
        var remaining = needAmmoCount
        for (i in 0 until handler.slots) {
            if (remaining <= 0) break
            val checkStack = handler.getStackInSlot(i)
            if (checkStack.isEmpty) continue
            val ammo = checkStack.item as? IAmmo
            if (ammo != null && ammo.isAmmoOfGun(gunItem, checkStack)) {
                val extracted = handler.extractItem(i, remaining, false)
                remaining -= extracted.count
                continue
            }
            val box = checkStack.item as? IAmmoBox
            if (box != null && box.isAmmoBoxOfGun(gunItem, checkStack)) {
                val boxCount = box.getAmmoCount(checkStack)
                val extract = minOf(boxCount, remaining)
                val leftover = boxCount - extract
                box.setAmmoCount(checkStack, leftover)
                if (leftover <= 0) {
                    box.setAmmoId(checkStack, DefaultAssets.EMPTY_AMMO_ID)
                }
                remaining -= extract
            }
        }
        return needAmmoCount - remaining
    }

    override fun useDummyAmmo(stack: ItemStack): Boolean {
        return stack.tagCompound?.hasKey(IGun.DUMMY_AMMO_TAG) ?: false
    }

    override fun getDummyAmmoAmount(stack: ItemStack): Int {
        return (stack.tagCompound?.getInteger(IGun.DUMMY_AMMO_TAG) ?: 0).coerceAtLeast(0)
    }

    override fun setDummyAmmoAmount(stack: ItemStack, amount: Int) {
        ensureTag(stack).setInteger(IGun.DUMMY_AMMO_TAG, amount.coerceAtLeast(0))
    }

    override fun addDummyAmmoAmount(stack: ItemStack, amount: Int) {
        if (!useDummyAmmo(stack)) return
        val current = getDummyAmmoAmount(stack)
        setDummyAmmoAmount(stack, (current + amount).coerceAtLeast(0))
    }

    override fun getAttachmentTag(stack: ItemStack, type: AttachmentType): NBTTagCompound? {
        if (!allowAttachmentType(stack, type)) {
            return null
        }
        val root = stack.tagCompound ?: return null
        val key = attachmentKey(type)
        if (!root.hasKey(key, NbtType.COMPOUND)) {
            return null
        }
        val attachmentStackTag = root.getCompoundTag(key)
        return if (attachmentStackTag.hasKey("tag", NbtType.COMPOUND)) attachmentStackTag.getCompoundTag("tag") else null
    }

    override fun getAttachment(stack: ItemStack, type: AttachmentType): ItemStack {
        if (!allowAttachmentType(stack, type)) {
            return ItemStack.EMPTY
        }
        val root = stack.tagCompound ?: return ItemStack.EMPTY
        val key = attachmentKey(type)
        if (!root.hasKey(key, NbtType.COMPOUND)) {
            return ItemStack.EMPTY
        }
        return ItemStack(root.getCompoundTag(key))
    }

    override fun getBuiltinAttachment(stack: ItemStack, type: AttachmentType): ItemStack {
        val attachmentId = getBuiltInAttachmentId(stack, type)
        if (attachmentId == DefaultAssets.EMPTY_ATTACHMENT_ID) {
            return ItemStack.EMPTY
        }
        return ItemStack(LegacyItems.ATTACHMENT).apply {
            LegacyItems.ATTACHMENT.setAttachmentId(this, attachmentId)
        }
    }

    override fun getAttachmentId(stack: ItemStack, type: AttachmentType): ResourceLocation {
        val tag = getAttachmentTag(stack, type) ?: return DefaultAssets.EMPTY_ATTACHMENT_ID
        val value = tag.getString(AttachmentItem.ATTACHMENT_ID_TAG)
        return value.takeIf(String::isNotBlank)?.let(::ResourceLocation) ?: DefaultAssets.EMPTY_ATTACHMENT_ID
    }

    override fun getBuiltInAttachmentId(stack: ItemStack, type: AttachmentType): ResourceLocation {
        if (type == AttachmentType.NONE) {
            return DefaultAssets.EMPTY_ATTACHMENT_ID
        }
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        return TACZGunPackPresentation.resolveBuiltinAttachmentId(snapshot, getGunId(stack), type)
            ?: DefaultAssets.EMPTY_ATTACHMENT_ID
    }

    override fun installAttachment(gun: ItemStack, attachment: ItemStack) {
        val iAttachment = attachment.item as? IAttachment ?: return
        if (!allowAttachment(gun, attachment)) {
            return
        }
        val key = attachmentKey(iAttachment.getType(attachment))
        ensureTag(gun).setTag(key, attachment.writeToNBT(NBTTagCompound()))
    }

    override fun unloadAttachment(gun: ItemStack, type: AttachmentType) {
        if (!allowAttachmentType(gun, type)) {
            return
        }
        ensureTag(gun).setTag(attachmentKey(type), ItemStack.EMPTY.writeToNBT(NBTTagCompound()))
    }

    override fun allowAttachment(gun: ItemStack, attachmentItem: ItemStack): Boolean {
        val iAttachment = attachmentItem.item as? IAttachment ?: return false
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        return TACZGunPackPresentation.allowsAttachment(snapshot, getGunId(gun), iAttachment.getAttachmentId(attachmentItem))
    }

    override fun allowAttachmentType(gun: ItemStack, type: AttachmentType): Boolean {
        if (type == AttachmentType.NONE) {
            return false
        }
        val gunId = getGunId(gun)
        val gunEntry = TACZGunPackRuntimeRegistry.getSnapshot().guns[gunId] ?: return false
        return gunEntry.data.allowAttachmentTypes.any { AttachmentType.fromSerializedName(it) == type }
    }

    override fun hasCustomLaserColor(stack: ItemStack): Boolean {
        return stack.tagCompound?.hasKey(IGun.LASER_COLOR_TAG, NbtType.INT) ?: false
    }

    override fun getLaserColor(stack: ItemStack): Int {
        if (!hasCustomLaserColor(stack)) {
            return DEFAULT_LASER_COLOR
        }
        return stack.tagCompound?.getInteger(IGun.LASER_COLOR_TAG) ?: DEFAULT_LASER_COLOR
    }

    override fun setLaserColor(stack: ItemStack, color: Int) {
        ensureTag(stack).setInteger(IGun.LASER_COLOR_TAG, color)
    }

    override fun hasAttachmentLock(stack: ItemStack): Boolean {
        return stack.tagCompound?.getBoolean(IGun.ATTACHMENT_LOCK_TAG) ?: false
    }

    override fun setAttachmentLock(stack: ItemStack, locked: Boolean) {
        ensureTag(stack).setBoolean(IGun.ATTACHMENT_LOCK_TAG, locked)
    }

    override fun isOverheatLocked(stack: ItemStack): Boolean {
        return stack.tagCompound?.getBoolean(IGun.OVERHEAT_LOCK_TAG) ?: false
    }

    override fun setOverheatLocked(stack: ItemStack, locked: Boolean) {
        ensureTag(stack).setBoolean(IGun.OVERHEAT_LOCK_TAG, locked)
    }

    override fun getHeatAmount(stack: ItemStack): Float {
        return (stack.tagCompound?.getFloat(IGun.HEAT_AMOUNT_TAG) ?: 0f).coerceAtLeast(0f)
    }

    override fun setHeatAmount(stack: ItemStack, amount: Float) {
        ensureTag(stack).setFloat(IGun.HEAT_AMOUNT_TAG, amount.coerceAtLeast(0f))
    }

    override fun getItemStackDisplayName(stack: ItemStack): String {
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val gunId = getGunId(stack)
        return TACZGunPackPresentation.localizedGunName(snapshot, gunId, super.getItemStackDisplayName(stack))
    }

    @SideOnly(Side.CLIENT)
    override fun addInformation(stack: ItemStack, worldIn: World?, tooltip: MutableList<String>, flagIn: ITooltipFlag) {
        LegacyRuntimeTooltipSupport.appendTooltip(stack, tooltip, flagIn.isAdvanced)
    }

    override fun onEntitySwing(entityLiving: EntityLivingBase, stack: ItemStack): Boolean = true

    internal companion object {
        internal const val TYPE_NAME: String = "modern_kinetic"
        private const val DEFAULT_LASER_COLOR: Int = 0xFF0000
    }
}

internal class LegacyBlockItem(block: net.minecraft.block.Block) : ItemBlock(block) {
    init {
        maxStackSize = 1
    }

    override fun getCreativeTabs(): Array<CreativeTabs> = arrayOf(LegacyCreativeTabs.OTHER)

    fun getBlockId(stack: ItemStack): ResourceLocation {
        val explicitBlockId = stack.tagCompound
            ?.getString(BLOCK_ID_TAG)
            ?.takeIf { it.isNotBlank() }
            ?.let(::ResourceLocation)
        return explicitBlockId ?: requireNotNull(registryName)
    }

    fun setBlockId(stack: ItemStack, blockId: ResourceLocation?) {
        if (blockId == null) {
            ensureTag(stack).removeTag(BLOCK_ID_TAG)
            return
        }
        ensureTag(stack).setString(BLOCK_ID_TAG, blockId.toString())
    }

    override fun getSubItems(tab: CreativeTabs, items: NonNullList<ItemStack>) {
        if (!isInCreativeTab(tab)) {
            return
        }
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val entries = TACZGunPackPresentation.sortedBlocksForItem(snapshot, requireNotNull(registryName))
        if (entries.isEmpty()) {
            items += ItemStack(this)
            return
        }
        entries.forEach { blockEntry ->
            val stack = ItemStack(this)
            setBlockId(stack, blockEntry.id)
            items += stack
        }
    }

    override fun getItemStackDisplayName(stack: ItemStack): String {
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val blockId = getBlockId(stack)
        return TACZGunPackPresentation.localizedBlockName(snapshot, blockId, super.getItemStackDisplayName(stack))
    }

    @SideOnly(Side.CLIENT)
    override fun addInformation(stack: ItemStack, worldIn: World?, tooltip: MutableList<String>, flagIn: ITooltipFlag) {
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val blockId = getBlockId(stack)
        TACZGunPackPresentation.localizedBlockTooltip(snapshot, blockId)?.let { tooltip += "${TextFormatting.GRAY}$it" }
        val tabs = TACZGunPackPresentation.localizedWorkbenchTabs(snapshot, blockId)
        if (tabs.isNotEmpty()) {
            val preview = tabs.take(3).joinToString(", ")
            val suffix = if (tabs.size > 3) ", …" else ""
            tooltip += "${TextFormatting.DARK_AQUA}$preview$suffix"
        }
        TACZGunPackPresentation.localizedPackName(snapshot, blockId)?.let { tooltip += "${TextFormatting.BLUE}${TextFormatting.ITALIC}$it" }
        if (flagIn.isAdvanced) {
            val recipeCount = TACZGunPackPresentation.visibleRecipeCount(snapshot, blockId)
            tooltip += "${TextFormatting.DARK_GRAY}Recipes: $recipeCount"
            snapshot.blocks[blockId]?.data?.filter?.let { filterId ->
                tooltip += "${TextFormatting.DARK_GRAY}Filter: $filterId"
            }
        }
        appendAdvancedTooltip(
            tooltip = tooltip,
            flag = flagIn,
            runtimeId = blockId,
            displayId = TACZGunPackPresentation.resolveBlockDisplayId(snapshot, blockId),
        )
    }

    override fun placeBlockAt(
        stack: ItemStack,
        player: EntityPlayer,
        world: World,
        pos: BlockPos,
        side: EnumFacing,
        hitX: Float,
        hitY: Float,
        hitZ: Float,
        newState: IBlockState,
    ): Boolean {
        val placed = super.placeBlockAt(stack, player, world, pos, side, hitX, hitY, hitZ, newState)
        if (!placed) {
            return false
        }
        val tile = world.getTileEntity(pos) as? GunSmithTableTileEntity ?: return true
        tile.blockId = getBlockId(stack)
        tile.markDirty()
        return true
    }

    internal companion object {
        internal const val BLOCK_ID_TAG: String = "BlockId"
    }
}

private fun ensureTag(stack: ItemStack): NBTTagCompound {
    val existing = stack.tagCompound
    if (existing != null) {
        return existing
    }
    val created = NBTTagCompound()
    stack.tagCompound = created
    return created
}

private fun parseFireMode(rawValue: String): FireMode =
    runCatching { FireMode.valueOf(rawValue.uppercase()) }.getOrDefault(FireMode.UNKNOWN)

private fun attachmentKey(type: AttachmentType): String = "${IGun.ATTACHMENT_BASE_TAG}${type.name}"

private object NbtType {
    const val COMPOUND: Int = 10
    const val INT: Int = 3
}

@SideOnly(Side.CLIENT)
private fun appendAdvancedTooltip(
    tooltip: MutableList<String>,
    flag: ITooltipFlag,
    runtimeId: ResourceLocation,
    displayId: ResourceLocation?,
) {
    if (!flag.isAdvanced || !LegacyConfigManager.client.enableTaczIdInTooltip) {
        return
    }
    tooltip += "${TextFormatting.DARK_GRAY}TACZ ID: $runtimeId"
    displayId?.let { tooltip += "${TextFormatting.DARK_GRAY}Display: $it" }
}
