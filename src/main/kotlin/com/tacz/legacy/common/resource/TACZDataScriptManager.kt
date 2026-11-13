package com.tacz.legacy.common.resource

import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.api.entity.ReloadState
import com.tacz.legacy.api.item.gun.FireMode
import net.minecraft.util.ResourceLocation
import org.luaj.vm2.*
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.Bit32Lib
import org.luaj.vm2.lib.PackageLib
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.jse.*
import java.io.StringReader

/**
 * 管理服务端数据脚本的加载与缓存。
 * 对应上游 TACZ 的 CommonAssetsManager.ScriptManager。
 */
internal object TACZDataScriptManager {
    private val scriptCache = HashMap<ResourceLocation, LuaTable?>()
    @Volatile private var globals: Globals? = null

    fun reload() {
        scriptCache.clear()
        globals = null
    }

    fun getScript(scriptId: ResourceLocation): LuaTable? {
        scriptCache[scriptId]?.let { return it }
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val source = snapshot.dataScripts[scriptId] ?: run {
            scriptCache[scriptId] = null
            return null
        }
        val g = ensureGlobals(snapshot)
        val table = try {
            val result = g.load(StringReader(source), scriptId.toString()).call()
            if (result is LuaTable) result else null
        } catch (e: Exception) {
            TACZLegacy.logger.error("[ReloadDebug] Data script {} failed to load, falling back to default logic", scriptId, e)
            null
        }
        scriptCache[scriptId] = table
        return table
    }

    private fun ensureGlobals(snapshot: TACZRuntimeSnapshot): Globals {
        globals?.let { return it }
        val g = Globals()
        g.load(JseBaseLib())
        g.load(PackageLib())
        g.load(Bit32Lib())
        g.load(TableLib())
        g.load(StringLib())
        g.load(JseMathLib())
        LoadState.install(g)
        LuaC.install(g)

        // 注入全局常量：ReloadState.StateType 枚举
        for (stateType in ReloadState.StateType.entries) {
            g.set(stateType.name, LuaValue.valueOf(stateType.ordinal))
        }
        // 注入全局常量：FireMode 枚举
        for (fireMode in FireMode.entries) {
            g.set(fireMode.name, LuaValue.valueOf(fireMode.ordinal))
        }

        // 预注册所有脚本到 package.preload 以支持 require
        snapshot.dataScripts.forEach { (id, src) ->
            val moduleName = "${id.namespace}_${id.path}"
            g.get("package").get("preload").set(moduleName, object : LuaFunction() {
                private var loaded: LuaTable? = null
                override fun call(modname: LuaValue, env: LuaValue): LuaValue {
                    loaded?.let { return it }
                    val chunk = g.load(StringReader(src), moduleName)
                    val result = chunk.call()
                    val table = result.checktable(1)
                    loaded = table
                    return table
                }
            })
        }
        globals = g
        return g
    }
}
