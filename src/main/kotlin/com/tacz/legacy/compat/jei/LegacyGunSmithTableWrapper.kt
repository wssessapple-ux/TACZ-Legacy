package com.tacz.legacy.compat.jei

import com.tacz.legacy.common.application.gunsmith.LegacyGunSmithRecipe
import mezz.jei.api.ingredients.VanillaTypes
import mezz.jei.api.ingredients.IIngredients
import mezz.jei.api.recipe.IRecipeWrapper

/**
 * 1.12.2 的 JEI 配方包装：把 LegacyGunSmithRecipe 的 materials/result 填进 IIngredients。
 * 在 1.12.2 中 IRecipeCategory 的泛型必须是 IRecipeWrapper，所以分类直接以本类为泛型。
 */
public class LegacyGunSmithTableWrapper(private val recipe: LegacyGunSmithRecipe) : IRecipeWrapper {

    override fun getIngredients(ingredients: IIngredients) {
        ingredients.setInputLists(
            VanillaTypes.ITEM,
            recipe.materials.map { material ->
                material.ingredient.matchingStacks.map { stack ->
                    stack.copy().apply { count = material.count }
                }
            }
        )
        ingredients.setOutput(VanillaTypes.ITEM, recipe.result)
    }
}
