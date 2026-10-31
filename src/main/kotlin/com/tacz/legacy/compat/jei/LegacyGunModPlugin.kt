package com.tacz.legacy.compat.jei

import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.common.application.gunsmith.LegacyGunSmithingRuntime
import com.tacz.legacy.common.item.LegacyItems
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry
import mezz.jei.api.IModPlugin
import mezz.jei.api.IModRegistry
import mezz.jei.api.ISubtypeRegistry
import mezz.jei.api.JEIPlugin
import net.minecraft.item.ItemStack
import net.minecraft.item.crafting.Ingredient
import net.minecraft.util.ResourceLocation

/**
 * TACZ-Legacy 的 JEI 插件（1.12.2 / JEI 4.x）。
 *
 * 行为对齐上游 1.20.1 的 GunModPlugin：
 *  - 每个工作台方块（data.tabs 非空）注册一个配方分类，配方按 blockId 过滤；
 *  - 工作台物品作为该分类的 catalyst（点工作台能看到配方）；
 *  - 给枪 / 弹药 / 配件 / 弹药箱 / 工作台注册 NBT 子类型，让 JEI 按 NBT 区分不同 id 的物品，
 *    从而在 JEI 里搜 @tacz 能看到所有配方与物品。
 *
 * 该插件类仅被 JEI 存在时扫描加载；未装 JEI 时不会被加载，不影响 mod 本体。
 */
@JEIPlugin
public class LegacyGunModPlugin : IModPlugin {

    override fun registerItemSubtypes(subtypeRegistry: ISubtypeRegistry) {
        subtypeRegistry.registerSubtypeInterpreter(LegacyItems.MODERN_KINETIC_GUN, object : ISubtypeRegistry.ISubtypeInterpreter {
            override fun apply(stack: ItemStack): String? = LegacyItems.MODERN_KINETIC_GUN.getGunId(stack)?.toString()
        })
        subtypeRegistry.registerSubtypeInterpreter(LegacyItems.AMMO, object : ISubtypeRegistry.ISubtypeInterpreter {
            override fun apply(stack: ItemStack): String? = LegacyItems.AMMO.getAmmoId(stack)?.toString()
        })
        subtypeRegistry.registerSubtypeInterpreter(LegacyItems.ATTACHMENT, object : ISubtypeRegistry.ISubtypeInterpreter {
            override fun apply(stack: ItemStack): String? = LegacyItems.ATTACHMENT.getAttachmentId(stack)?.toString()
        })
        subtypeRegistry.registerSubtypeInterpreter(LegacyItems.AMMO_BOX, object : ISubtypeRegistry.ISubtypeInterpreter {
            override fun apply(stack: ItemStack): String? = LegacyItems.AMMO_BOX.getAmmoId(stack)?.toString()
        })
        subtypeRegistry.registerSubtypeInterpreter(LegacyItems.GUN_SMITH_TABLE, object : ISubtypeRegistry.ISubtypeInterpreter {
            override fun apply(stack: ItemStack): String? = LegacyItems.GUN_SMITH_TABLE.getBlockId(stack)?.toString()
        })
    }

    override fun register(registry: IModRegistry) {
        val guiHelper = registry.getJeiHelpers().getGuiHelper()
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()

        val workbenchBlockIds = snapshot.blocks.values
            .filter { it.data.tabs.isNotEmpty() }
            .map { it.id }
            .sortedBy { it.toString() }

        for (blockId in workbenchBlockIds) {
            val recipes = LegacyGunSmithingRuntime.visibleRecipes(
                blockId = blockId,
                selectedTab = null,
                selectedNamespaces = emptySet(),
                searchText = "",
                heldStack = ItemStack.EMPTY,
                byHandOnly = false,
            )
            if (recipes.isEmpty()) {
                continue
            }
            val icon = LegacyGunSmithingRuntime.workbenchIcon(blockId)
            val uid = ResourceLocation(TACZLegacy.MOD_ID, "gun_smith/${blockId.namespace}_${blockId.path}")
            val title = icon.displayName
            registry.addRecipeCategories(LegacyGunSmithTableCategory(guiHelper, uid, title, icon))
            registry.addRecipes(recipes.map { LegacyGunSmithTableWrapper(it) }, uid.toString())
            registry.addRecipeCatalyst(Ingredient.fromStacks(icon), uid.toString())
        }
    }
}
