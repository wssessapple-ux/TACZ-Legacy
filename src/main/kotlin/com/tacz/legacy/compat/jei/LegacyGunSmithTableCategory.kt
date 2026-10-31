package com.tacz.legacy.compat.jei

import mezz.jei.api.IGuiHelper
import mezz.jei.api.ingredients.VanillaTypes
import mezz.jei.api.gui.IDrawable
import mezz.jei.api.gui.IGuiItemStackGroup
import mezz.jei.api.gui.IRecipeLayout
import mezz.jei.api.ingredients.IIngredients
import mezz.jei.api.recipe.IRecipeCategory
import net.minecraft.item.ItemStack
import net.minecraft.util.ResourceLocation

/**
 * 一个工作台方块对应一个 JEI 分类。
 * 布局沿用上游 1.20.1 的排布：输出槽在左，输入槽 <7 单行、>=7 双行。
 * 注意 1.12.2 的 IRecipeCategory 泛型必须是 IRecipeWrapper，故用 LegacyGunSmithTableWrapper。
 */
public class LegacyGunSmithTableCategory(
    private val guiHelper: IGuiHelper,
    private val uid: ResourceLocation,
    private val title: String,
    private val icon: ItemStack,
) : IRecipeCategory<LegacyGunSmithTableWrapper> {

    private val background: IDrawable = guiHelper.createBlankDrawable(160, 40)
    private val iconDraw: IDrawable = guiHelper.createDrawableIngredient(icon)

    override fun getUid(): String = uid.toString()

    override fun getTitle(): String = title

    override fun getModName(): String = "TACZ-Legacy"

    override fun getBackground(): IDrawable = background

    override fun getIcon(): IDrawable = iconDraw

    override fun setRecipe(recipeLayout: IRecipeLayout, wrapper: LegacyGunSmithTableWrapper, ingredients: IIngredients) {
        val itemStacks: IGuiItemStackGroup = recipeLayout.itemStacks
        val inputs = ingredients.getInputs(VanillaTypes.ITEM)
        val outputs = ingredients.getOutputs(VanillaTypes.ITEM)

        var slot = 0
        for (output in outputs) {
            itemStacks.init(slot, false, 3, 12)
            itemStacks.set(slot, output)
            slot++
        }
        if (inputs.size < 7) {
            for (i in inputs.indices) {
                itemStacks.init(slot, true, 35 + 20 * i, 12)
                itemStacks.set(slot, inputs[i])
                slot++
            }
        } else {
            for (i in inputs.indices) {
                val x = 35 + 20 * (i % 6)
                val y = if (i < 6) 2 else 22
                itemStacks.init(slot, true, x, y)
                itemStacks.set(slot, inputs[i])
                slot++
            }
        }
    }
}
