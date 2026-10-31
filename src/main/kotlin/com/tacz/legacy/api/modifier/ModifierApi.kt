package com.tacz.legacy.api.modifier

import com.tacz.legacy.TACZLegacy
import net.minecraft.util.text.ITextComponent
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaNumber
import org.luaj.vm2.LuaValue
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.Bit32Lib
import org.luaj.vm2.lib.PackageLib
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.jse.JseBaseLib
import org.luaj.vm2.lib.jse.JseMathLib
import java.io.StringReader
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

public data class Modifier(
    val addend: Double = 0.0,
    val percent: Double = 0.0,
    val multiplier: Double = 1.0,
    val function: String? = null,
)

public class CacheValue<T>(public var value: T)

public abstract class JsonProperty<T>(private var value: T?) {
    protected val componentList: MutableList<ITextComponent> = mutableListOf()

    public fun getValue(): T? = value

    public fun setValue(value: T?): Unit {
        this.value = value
    }

    public fun getComponents(): List<ITextComponent> = componentList.toList()

    public abstract fun initComponents()
}

public interface IAttachmentModifier<T, K> {
    public fun getId(): String

    public fun getOptionalFields(): String = ""

    public fun readJson(json: String): JsonProperty<T>

    public fun initCache(defaultValue: K): CacheValue<K>

    public fun eval(modifiedValues: List<T>, cache: CacheValue<K>): Unit
}

public class ParameterizedCache<T> private constructor(
    modifiers: List<Modifier>,
    private val defaultValue: T,
) {
    private val scripts: List<String>
    private val addend: Double
    private val percent: Double
    private val multiplier: Double

    init {
        var addendAccumulator = 0.0
        var percentAccumulator = 1.0
        var multiplierAccumulator = 1.0
        val scriptAccumulator = mutableListOf<String>()
        modifiers.forEach { modifier ->
            addendAccumulator += modifier.addend
            percentAccumulator += modifier.percent
            multiplierAccumulator *= modifier.multiplier.coerceAtLeast(0.0)
            if (!modifier.function.isNullOrBlank()) {
                scriptAccumulator += modifier.function
            }
        }
        addend = addendAccumulator
        percent = percentAccumulator
        multiplier = multiplierAccumulator
        scripts = scriptAccumulator.toList()
    }

    public fun getDefaultValue(): T = defaultValue

    public fun eval(input: Double): Double {
        var value = (input + addend) * percent.coerceAtLeast(0.0) * multiplier
        scripts.forEach { function ->
            value = ModifierEvaluator.functionEval(value, input, function)
        }
        return value
    }

    public fun eval(input: Double, extraAddend: Double, extraPercent: Double, extraMultiplier: Double): Double {
        var value = (input + addend + extraAddend) * (percent + extraPercent).coerceAtLeast(0.0) * multiplier * extraMultiplier.coerceAtLeast(0.0)
        scripts.forEach { function ->
            value = ModifierEvaluator.functionEval(value, input, function)
        }
        return value
    }

    public companion object {
        @JvmStatic
        public fun <T> of(defaultValue: T): ParameterizedCache<T> = ParameterizedCache(emptyList(), defaultValue)

        @JvmStatic
        public fun <T> of(modifiers: List<Modifier>, defaultValue: T): ParameterizedCache<T> = ParameterizedCache(modifiers, defaultValue)
    }
}

public class ParameterizedCachePair<L, R> private constructor(
    private val leftCache: ParameterizedCache<L>,
    private val rightCache: ParameterizedCache<R>,
) {
    public fun left(): ParameterizedCache<L> = leftCache

    public fun right(): ParameterizedCache<R> = rightCache

    public companion object {
        @JvmStatic
        public fun <L, R> of(defaultLeft: L, defaultRight: R): ParameterizedCachePair<L, R> =
            ParameterizedCachePair(ParameterizedCache.of(defaultLeft), ParameterizedCache.of(defaultRight))

        @JvmStatic
        public fun <L, R> of(
            left: List<Modifier>,
            right: List<Modifier>,
            defaultLeft: L,
            defaultRight: R,
        ): ParameterizedCachePair<L, R> = ParameterizedCachePair(
            ParameterizedCache.of(left, defaultLeft),
            ParameterizedCache.of(right, defaultRight),
        )
    }
}

/**
 * 受控 Lua 求值器（对齐 Next / 上游 `AttachmentScriptManager.secureStandardGlobals`）。
 *
 * 之前这里用 `LuaScriptEngineFactory().scriptEngine`（= `JsePlatform.standardGlobals()`），
 * 会把 `os` / `io` / `luajava` 全部装回来 —— 枪包里的 `"function"` 是一段任意 Lua 源码，
 * 于是 `luajava.newInstance("java.lang.Runtime")` 就能执行系统命令。已废弃该用法。
 */
public object ModifierEvaluator {
    /**
     * 上游 `AttachmentScriptManager.UNSAFE_GLOBALS`。
     *
     * 它们能绕过"没有 io/os/luajava"的限制：`load` 可动态编译任意源码，
     * `getmetatable` 能拿到内部表，`print` 会污染控制台。
     * **必须声明在 [globals] 之前** —— object 的属性按声明顺序初始化，
     * 放在后面会让 `secureStandardGlobals()` 读到 null。
     */
    private val UNSAFE_GLOBALS = arrayOf(
        "collectgarbage", "dofile", "loadfile", "load", "print", "getmetatable",
    )

    /** 预编译缓存上限；枪包的 modifier 函数数量很少，超了直接整体丢弃重来即可。 */
    private const val MAX_COMPILED_SCRIPTS = 512

    /**
     * 受控 Lua 环境（对齐上游 `AttachmentScriptManager.secureStandardGlobals`）。
     */
    private val globals: Globals by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { secureStandardGlobals() }

    /**
     * 预编译缓存：上游在读取时编译一次并复用 closure，而不是每次求值都编译一遍。
     * value 允许为 null —— 表示"这个脚本编译不过"，负缓存避免坏脚本每次求值都重编译。
     */
    private val compiled: MutableMap<String, LuaValue?> = HashMap()

    /** 失败日志去重：modifier 求值在射击热路径上，坏脚本不去重会每发刷一行。 */
    private val reportedFailures: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun reportFailure(stage: String, script: String, throwable: Throwable) {
        if (!reportedFailures.add("$stage|$script")) {
            return
        }
        TACZLegacy.logger.warn(
            "[GunPackRuntime] modifier function {} failed; falling back to the incoming value. Script: {}",
            stage, script, throwable,
        )
    }

    private fun secureStandardGlobals(): Globals {
        val g = Globals()
        g.load(JseBaseLib())
        // PackageLib 必须先于其它库加载：luaj 的 Bit32Lib / TableLib / StringLib / JseMathLib
        // 在 call() 里会通过 package.loaded 注册自己，缺 package 表会直接抛
        // "attempt to index ? (a nil value)"（已实测）。加载完立刻把入口抹掉，见下方。
        g.load(PackageLib())
        g.load(Bit32Lib())
        g.load(TableLib())
        g.load(StringLib())
        g.load(JseMathLib())
        LuaC.install(g)
        // 上游额外抹掉这些入口：它们能绕开"没有 io/os/luajava"的限制
        // （load 可以动态编译任意源码，getmetatable 能拿到内部表，print 会污染控制台）
        for (name in UNSAFE_GLOBALS) {
            g.set(name, LuaValue.NIL)
        }
        // PackageLib 只为初始化库而加载，脚本本身不允许 require 任何东西
        // （上游 AttachmentScriptManager 干脆没有 package，这里等价地收回）
        g.set("require", LuaValue.NIL)
        g.set("package", LuaValue.NIL)
        // 注意：这里**不**设 `_G`。把 globals 自身作为键塞进自己时哈希不稳定，
        // 实测脚本里 `_G` 仍为 nil；上游 AttachmentScriptManager 同样没有 `_G`。
        // 脚本通过 _ENV 访问全局（`x` / `r` / `y` 均可用），不依赖 `_G` 这个惯例名。
        return g
    }

    /**
     * 编译（并缓存）一段脚本。
     *
     * 用 [Globals.load] 而不是 `compilePrototype`：前者内部走 `LoadState.load`，
     * 与 `TACZDataScriptManager` 加载枪包脚本是同一条路径。
     */
    private fun compile(script: String): LuaValue? = synchronized(compiled) {
        if (compiled.containsKey(script)) {
            return@synchronized compiled[script]
        }
        val loaded = runCatching {
            globals.load(StringReader(script), "attachment_modifier")
        }.onFailure { reportFailure("compile", script, it) }.getOrNull()
        if (compiled.size >= MAX_COMPILED_SCRIPTS) {
            compiled.clear()
        }
        // 负缓存：编译不过的脚本也记下来（null），避免热路径上每次重编译 + 重刷日志
        compiled[script] = loaded
        loaded
    }

    @JvmStatic
    public fun eval(modifier: Modifier, defaultValue: Double): Double = eval(listOf(modifier), defaultValue)

    @JvmStatic
    public fun eval(modifiers: List<Modifier>, defaultValue: Double): Double {
        var addend = defaultValue
        var percent = 1.0
        var multiplier = 1.0
        modifiers.forEach { modifier ->
            addend += modifier.addend
            percent += modifier.percent
            multiplier *= modifier.multiplier.coerceAtLeast(0.0)
        }
        var value = addend * percent.coerceAtLeast(0.0) * multiplier
        modifiers.forEach { modifier ->
            if (!modifier.function.isNullOrBlank()) {
                value = functionEval(value, defaultValue, modifier.function)
            }
        }
        return value
    }

    @JvmStatic
    public fun eval(modified: List<Boolean>, defaultValue: Boolean): Boolean {
        return if (defaultValue) {
            modified.all { it }
        } else {
            modified.any { it }
        }
    }

    @JvmStatic
    public fun functionEval(value: Double, defaultValue: Double, script: String): Double {
        // 上游在编译期就做了小写化（AttachmentScriptManager.compile → script.toLowerCase）
        val normalizedScript = script.lowercase(Locale.ENGLISH)
        val closure = compile(normalizedScript) ?: return value
        synchronized(globals) {
            // 变量约定与上游一致：x = 当前值，r = 默认值，脚本写 y 作为结果
            globals.rawset("x", LuaValue.valueOf(value))
            globals.rawset("r", LuaValue.valueOf(defaultValue))
            globals.rawset("y", LuaValue.NIL)
            return runCatching {
                closure.call()
                (globals.rawget("y") as? LuaNumber)?.todouble() ?: value
            }.onFailure { reportFailure("exec", normalizedScript, it) }.getOrDefault(value)
        }
    }
}
