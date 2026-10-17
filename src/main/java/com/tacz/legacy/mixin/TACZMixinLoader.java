package com.tacz.legacy.mixin;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.spongepowered.asm.mixin.Mixins;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * Early mixin loader for TACZ-Legacy.
 *
 * This registers {@code mixins.tacz.json} during FML coremod loading so that
 * very early vanilla classes such as {@code EntityLivingBase} still have a
 * chance to be transformed before use-site casts rely on injected interfaces.
 */
@IFMLLoadingPlugin.Name("TACZLegacyMixinLoader")
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.SortingIndex(0)
// NOTE: TransformerExclusions removed - it excluded the whole mixin package,
// preventing all mixins in this mod from being applied at runtime.
@SuppressWarnings("unused")
public class TACZMixinLoader implements IFMLLoadingPlugin {

    @Override
    public String[] getASMTransformerClass() {
        return new String[0];
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Nullable
    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(final Map<String, Object> data) {
        Mixins.addConfiguration("mixins.tacz.json");
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}