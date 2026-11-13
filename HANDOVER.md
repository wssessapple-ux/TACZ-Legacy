# TACZ-Legacy 项目交接文档

> 本文档面向接手开发的 AI/开发者。读前必读，读完再动代码。

---

## 1. 项目概况

| 项 | 值 |
|---|---|
| 模组名 | Timeless & Classics Guns: Legacy（简称 TACZ-Legacy） |
| MC 版本 | **1.12.2**（锁死，不要升级） |
| 加载器 | **Forge** 14.23.5.2847+（不是 Fabric，不是 1.16+） |
| 语言 | Kotlin 2.3.0（主）+ Java 8（部分模型/渲染层） |
| 构建工具 | RetroFuturaGradle 1.4.9（不是 ForgeGradle） |
| mod_id | `tacz`（注意：不是 `tacz_legacy`，注册名都用这个） |
| maven_group | `com.tacz.legacy` |
| 当前版本 | 0.7.0.1-alpha |
| 许可证 | GPL-3.0 |
| 上游参考 | MCModderAnchor/TACZ（1.20.1），本地副本 `E:\vip\TACZ-Legacy-Next-master` |
| GitHub | https://github.com/wssessapple-ux/TACZ-Legacy.git |

### 核心依赖

| 依赖 | 版本 | 作用 |
|---|---|---|
| Forgelin Continuous | 2.3.0.0 | Kotlin 运行时，必须在 dependencies 里声明 |
| MixinBooter | 10.7 | Mixin 加载（1.12.2 没有原生 Mixin） |
| luaj-jse | 3.0.1 (shaded) | 枪包 Lua 脚本状态机 |
| joml | 1.10.5 (shaded) | 数学库（矩阵/向量） |
| commons-math3 | 3.6.1 (shaded) | 数学库 |
| lumenized | CurseForge | Bloom 泛光渲染 |
| ctm / codechickenlib | CurseForge | 渲染依赖 |
| JEI | 4.15.0.1（1.12.2） | 配方查看 |
| playeranimator-1.12.2-1.0.2-rc1.jar | `libs/` 目录 | **软依赖，compileOnly，不打进 jar** |

### 构建环境

- JDK 21 用于跑 Gradle：`C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`
- 编译目标 Java 8（toolchain）
- 构建命令：
  ```powershell
  $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
  .\gradlew.bat build -x test --console=plain
  ```
- **必须转后台任务跑**（前台 15 秒自动转后台），然后用 TaskOutput 等结果
- Git 不在 PATH，用绝对路径：`D:\Github\Git\cmd\git.exe`
- push 需要开代理

---

## 2. 关键目录与文件

### 项目根

```
E:\vip\TACZ-Legacy\
├── build.gradle.kts          # 构建脚本，改依赖/版本从这里
├── gradle.properties         # mod_version = 0.7.0.1-alpha，改版本号改这里
├── settings.gradle.kts
├── gradlew.bat               # Windows 构建入口
├── libs\
│   └── playeranimator-1.12.2-1.0.2-rc1.jar  # 【绝对不能删】软依赖 API
├── src\main\
│   ├── java\com\tacz\legacy\   # Java 层：模型/渲染/mixin
│   ├── kotlin\com\tacz\legacy\ # Kotlin 层：主逻辑
│   └── resources\
│       ├── mcmod.info          # Forge 模组元数据
│       ├── pack.mcmeta
│       ├── mixins.tacz.json    # Mixin 配置
│       └── assets\             # 模组自带资源（字体/UI贴图等）
└── build\libs\                 # 构建产物输出
```

### Kotlin 包结构（`src/main/kotlin/com/tacz/legacy/`）

```
TACZLegacy.kt                  # 【主入口】@Mod 类，FML 生命周期
api/                           # 对外接口层（IGun, IAmmo, IAttachment...）
client/                        # 客户端代码
├── ClientProxy.kt             # 客户端代理（注册渲染/键位/GUI）
├── animation/                 # 动画状态机
├── audio/                     # 客户端音效
├── compat/playeranimator/     # PlayerAnimator 软依赖钩子
├── event/                     # 客户端事件（输入/HUD/准星/受击标记）
├── gameplay/                   # 客户端玩法
├── gui/                       # 客户端 GUI（枪匠台/弹箱）
├── input/                     # 键位绑定
├── particle/                  # 粒子工厂
├── registry/                  # 客户端注册（ModelRegisterer）
└── renderer/                  # 渲染器
    ├── item/                  # 物品渲染（TACZGunItemRenderer 是核心）
    ├── entity/                # 实体渲染（子弹/无人机）
    ├── block/                 # 方块实体渲染
    ├── bloom/                 # Bloom 泛光桥接
    └── scope/                 # 高倍镜 FBO 渲染
command/                       # /tacz 服务器命令
common/                        # 公共代码（服务端+客户端）
├── CommonProxy.kt             # 公共代理（注册网络/事件/GUI handler）
├── application/gunsmith/      # 枪匠台逻辑
├── block/                     # 方块 + 方块实体
├── config/                    # LegacyConfigManager（Forge Configuration）
├── entity/                    # 实体
│   ├── EntityDrone.kt         # 自杀式无人机（原创功能）
│   ├── LegacyProjectileExplosion.kt
│   ├── shooter/                # 射击状态机（LivingEntityShoot/Reload/Bolt...）
│   └── TACZExplosionAggregation.kt
├── event/                     # Forge 事件处理器
├── foundation/                # 启动诊断 + Bootstrap
├── gui/                       # GUI ID 注册
├── inventory/                 # Container
├── item/                      # 【核心】LegacyItems.kt（所有物品定义）
├── network/                   # 网络包
│   ├── TACZNetworkHandler.kt  # 【核心】SimpleNetworkWrapper 注册
│   └── message/               # C2S + S2C 消息
├── registry/                  # 注册器（Item/Block/Entity/Sound/CreativeTabs）
└── resource/                  # 枪包加载/解析/运行时注册表
compat/jei/                    # JEI 兼容
sound/                         # 音效注册
```

### Java 包结构（`src/main/java/com/tacz/legacy/`）

```
mixin/
├── TACZMixinLoader.java       # 【Coremod 入口】FMLCorePlugin
└── minecraft/
    ├── LivingEntityMixin.java  # 服务端 mixin
    └── client/                 # 客户端 mixin
        ├── EntityRendererMixin.java
        ├── ItemRendererMixin.java
        ├── MinecraftMixin.java
        └── ModelBipedGunPoseMixin.java  # 【关键】第三人称手臂姿势
client/
├── model/                     # Bedrock 模型系统
│   ├── BedrockGunModel.java   # 【核心】枪模型 + 骨骼路径解析
│   ├── bedrock/               # BedrockPart/BedrockModel/BedrockCube
│   └── papi/                  # PlayerAnimator 集成
└── renderer/                  # 渲染底层
api/                           # Java 侧 API 接口
runtime/                       # 运行时
util/                          # 工具类
```

### 绝对不能删的文件

- `libs/playeranimator-1.12.2-1.0.2-rc1.jar` — PlayerAnimator 软依赖 API 编译期
- `src/main/resources/mixins.tacz.json` — Mixin 配置
- `src/main/java/com/tacz/legacy/mixin/TACZMixinLoader.java` — Coremod 入口
- `build.gradle.kts` 里的 shade/relocate 配置 — luaj/joml/commons-math3 必须 relocate

---

## 3. 已实现功能清单

### 核心枪械系统
- **物品注册**：`LegacyItems.kt` — `ModernKineticGunItem`（枪）、`AmmoItem`（弹药）、`AttachmentItem`（配件）、`AmmoBoxItem`（弹箱）
- **枪包加载**：`TACZGunPackRuntimeRegistry` + `TACZGunPackRuntime` + `TACZPackConvertor` — 从游戏目录下 `tacz/` 文件夹加载现代 TACZ（1.20+）格式枪包
- **Lua 脚本状态机**：`TACZDataScriptManager` — 枪包 Lua 动画/逻辑脚本执行
- **射击状态机**：`common/entity/shooter/` 目录下 10+ 个 `LivingEntity*.kt`（Shoot/Reload/Bolt/Draw/Melee/FireSelect/AmmoCheck/SpeedModifier/Sprint）
- **弹道**：`EntityKineticBullet`（子弹实体）+ `TACZBulletSpreadResolver`（散布计算）
- **配件系统**：4 类配件（瞄准镜/激光/枪口/弹匣），NBT 存储，枪匠台安装/拆卸
- **过热系统**：加特林持续射击过热锁，`IGun.HEAT_AMOUNT_TAG` / `OVERHEAT_LOCK_TAG`
- **爆炸物**：`LegacyProjectileExplosion` + `TACZExplosionAggregation` — 跳过中心方块防自伤
- **水中阻力**：`EntityKineticBullet.onUpdate()` 里 `isInsideWater()` 查 `Material.WATER`，子弹入水减速+气泡

### 原创功能：自杀式无人机
- `DroneItem.kt` — 民用/军用两种，右键锁定目标→再右键发射
- `EntityDrone.kt` — 追踪飞行 + 爆炸
- 配方：民用=铁锭3+红石1+火药1+9mm子弹×2；军用=铁锭5+金锭3+下界石英2+7.62mm子弹×3+手枪瞄准镜×1
- 配方 JSON 放枪包 `data/tacz/recipes/drone/`，type=`tacz:gun_smith_table_crafting`

### 渲染系统
- **Bedrock 模型**：`client/model/bedrock/` — 解析 Bedrock geo.json + animation.json
- **物品渲染**：`TACZGunItemRenderer.kt` — TEISR，第一人称+第三人称
- **第三人称定位**：`applyPositioningTransform()` — 读枪包 `thirdperson_hand` 骨骼，从叶到根求逆矩阵，把枪握把对齐到手部锚点 (0, 1.5, 0)
- **Bloom 泛光**：`client/renderer/bloom/TACZBloomBridge` — 基于 lumenized
- **高倍镜 FBO**：`client/renderer/scope/` — 开镜渲染（部分功能未完成）
- **准星/HUD**：`client/event/LegacyClientOverlayEventHandler` + `crosshair/`

### 网络同步
- `TACZNetworkHandler.kt` — SimpleNetworkWrapper，14 个 C2S + 12 个 S2C 消息
- 射击/换弹/拉栓/开火模式/近战/瞄准/枪匠台合成/改装 全走网络包

### 其他
- **枪匠台**：3 级工作台（A/B/C），配方系统，`GunSmithTableTileEntity`
- **弹箱 GUI**：`AmmoBoxScreen` + `AmmoBoxContainer`
- **JEI 集成**：`compat/jei/` — 每个工作台配方分类 + catalyst + NBT 子类型
- **PlayerAnimator 兼容**：`client/compat/playeranimator/` — 软依赖，装了才生效
- **启动诊断**：`BootstrapDiagnostics` + `FoundationBootstrap` — 记录启动步骤日志

---

## 4. 代码规范与架构约定

### 注册写法

**物品注册**：不要直接在 `RegistryEvent.Register<Item>` 里 new Item。统一走 `LegacyItems.kt`：
1. 在 `LegacyItems` object 里声明 `val XXX: YourItem = YourItem().named("registry_name", CreativeTab)`
2. 加到 `allItems` list
3. `registerAll()` 遍历注册

**命名方法**：`named(path, tab)` 扩展函数自动设 `registryName` + `translationKey` + `creativeTab`。

**方块注册**：`LegacyBlocks.kt` 定义 Block，`LegacyBlockItem` 包装成 ItemBlock，block item 自动从 block 取 registryName。

**实体注册**：`EntityRegisterer.kt` 里 `EntityEntryBuilder` 风格，`LegacyEntities.kt` 定义实体属性。

**音效注册**：`SoundRegisterer.kt` 注册 `SoundEvent`。

### 事件监听

**公共事件**：在 `CommonProxy.init` 里 `MinecraftForge.EVENT_BUS.register(XXX)`。
**客户端事件**：在 `ClientProxy.init` 里注册。
**Kotlin object 的 @SubscribeEvent 方法**：必须加 `@JvmStatic`，且用 `MinecraftForge.EVENT_BUS.register(XXX::class.java)` 注册（Forge 1.12 只扫静态方法）。

### 网络包

- 通道名：`TACZLegacy.MOD_ID`（即 "tacz"）
- C2S 消息：消息类自己实现 `IMessageHandler<T, IMessage?>`，用 `registerC2S<T>()` 内联注册
- S2C 消息：handler 和 message 分开，用 `registerS2C(Handler::class.java, Message::class.java)`
- packetId 自增，**不要手动编号**，新增消息加到 `init()` 里末尾即可
- 发送工具：`TACZNetworkHandler.sendToPlayer/sendToTrackingEntity/sendToDimension/sendToServer`

### 配置

- `LegacyConfigManager.kt` — 用 Forge 原生 `Configuration`，分 client/common/server 三段
- 配置目录：`event.modConfigurationDirectory`（即 `.minecraft/config/`）
- 预加载配置在 `preInit` 早期读（`loadPreloadConfig`），主配置在 `loadMainConfigs`
- 配置值用 `data class` 持有，不要到处 `Configuration.get(...)` 裸调

### NBT 约定

- 枪物品 NBT tag key：`IGun.GUN_ID_TAG`、`IGun.AMMO_COUNT_TAG`、`IGun.FIRE_MODE_TAG`、`IGun.BULLET_IN_BARREL_TAG`、`IGun.ATTACHMENT_BASE_TAG`、`IGun.HEAT_AMOUNT_TAG`、`IGun.OVERHEAT_LOCK_TAG`
- 配件 NBT：每个 `AttachmentType` 一个子 CompoundTag，key 格式 `AttachmentBase_${typeName}`
- 弹药 NBT：`AmmoId`（字符串 ResourceLocation）
- 弹箱 NBT：`AmmoId`、`AmmoCount`、`Creative`、`AllTypeCreative`
- **不要改这些 tag key 名**，枪包和存档兼容性靠它

### 枪包加载

- 枪包根目录：游戏目录下 `tacz/` 文件夹，zip 直接丢入不用解压
- 默认枪包首次启动自动导出到 `tacz/tacz_default_gun/`（`DefaultGunPackExporter`）
- 运行时注册表：`TACZGunPackRuntimeRegistry.getSnapshot()` 返回不可变快照
- **改了枪包数据后，游戏实际加载的解压目录不会自动更新**，需手动同步到 `tacz/tacz_default_gun/`

---

## 5. 绝对不能动的地方（红线）

### 5.1 渲染层

- **`TACZGunItemRenderer.kt` 的矩阵链顺序**：`translate(0.5,2.0,0.5)` → `scale(-1,-1,1)` → `applyPositioningTransform()` → `applyScaleTransform()`。TEISR 内部不再加任何固定补偿旋转；枪的朝向全靠模型定位节点求逆 + 手臂姿势层，不要再手塞 `rotate(90, Y)` 这类补偿（早期试过全部否决，见 6.3）。
- **`BedrockPart.translateAndRotateAndScale()`**：先 translate offsetX/Y/Z，再 translate x/16,y/16,z/16，再 rotate。顺序改了模型全歪。
- **`BedrockModel.java` 的 `convertPivot()`**：Y 轴对子骨骼取反（`parentPivot - thisPivot`），这是 Bedrock 坐标系和 MC 坐标系的 Y 轴差异。改了所有枪模型位置全错。

### 5.2 网络层

- **packetId 自增顺序**：新增消息必须加到 `init()` 末尾，不要插中间。旧版本客户端/服务端握手靠消息序号。
- **`sendToTrackingEntity` 的半径 128.0**：这个值是音效/粒子同步范围，不要随意改。

### 5.3 软依赖

- **PlayerAnimator 是 `compileOnly`**：`build.gradle.kts` L189。代码里所有引用 `playeranimator` 的类必须在 `client/compat/playeranimator/` 下，且用 try/catch 或 Class.forName 守卫，玩家没装这个 jar 不能崩。
- **Mixin 不能和 PlayerAnimator 的 Mixin 抢同一组字段**：如果 PlayerAnimator 也 mixin 了某个类，我们的 mixin priority 要协调。

### 5.4 核心 hack

- **`use_mixins = true` + `use_coremod = true`**：`gradle.properties` 里这俩都是 true。`TACZMixinLoader` 是 FMLCorePlugin，启动时就加载。关掉 mixin 整个 mod 不工作。
- **shade/relocate**：luaj/joml/commons-math3 必须 relocate 到 `com.tacz.legacy.shade.*`，否则和其他 mod 的同名库冲突。
- **`groupsToExcludeFromAutoReobfMapping`**：build.gradle.kts L137，shaded 库不参与重混淆，改了会崩。

### 5.5 不要重构的东西

- 不要把 Kotlin object 改成 class 再手动 new —— Forge 事件注册和 `@SidedProxy` 依赖 object 单例。
- 不要把 `LegacyItems` 拆成多个文件 —— 所有物品在一个 object 里，`allItems` list 是注册入口。
- 不要把 `TACZNetworkHandler` 改成 `SimpleChannel`（1.13+ API）—— 1.12.2 只有 `SimpleNetworkWrapper`。

---

## 6. 踩坑记录与排查指南

### 6.1 构建/启动崩溃

| 现象 | 原因 | 解决 |
|---|---|---|
| SplashProgress 崩溃 + glClientActiveTexture | OpenGL 环境问题（Intel GMA/软件渲染） | 不是 mod 的锅，升级显卡驱动 |
| hs_err: Problematic frame=OpenAL | 音频驱动崩溃 | 不是 mod 的锅，更新 OpenAL |
| 游戏中替换 jar 后 ClassNotFoundException + NPE | 热替换 classloader 问题 | 重启游戏，不是打包漏文件 |
| `FMLCorePluginContainsFMLMod` 相关崩溃 | coremod 配置错 | 检查 `build.gradle.kts` manifest 属性 |

### 6.2 枪包兼容

| 现象 | 原因 | 解决 |
|---|---|---|
| 现代枪包（1.20+）JSON 解析崩溃 | 字段值从字符串变对象 | `TACZPackConvertor` 做兼容转换，新增枪包格式时在这里加 |
| 配方跑到 Misc 分类 | gun_smith_table_crafting 的 group 字段缺失 | 枪包 JSON 里 `type` 必须是 `tacz:gun_smith_table_crafting` |
| 材料预览图标不显示 | Ingredient 不支持 NBT 匹配 | 手动补 `previewStack` |
| 弹药打一发弹匣变 0% | `reload.type="fuel"` 被误当 inventory 直读 | `useInventoryAmmo()` 只在 type=="inventory" 时返回 true |
| Lua 脚本调用 Context 方法崩溃 | 1.12.2 没有该方法 | `TACZGunScriptAPI` 里加兜底方法 |

### 6.3 渲染问题

| 现象 | 原因 | 排查方向 |
|---|---|---|
| 枪横着拿/枪管横指侧方约 90° | 物品父模型 JSON 残留 vanilla 扁平物品 `thirdperson_righthand = [0,-90,55]` 显示旋转，hand 上下文包装类把这份矩阵透传给 TEISR，外层多转一个 -90°Y | `modern_kinetic_gun.json` 对齐 Next，整个 `display` 块删掉，只留 `parent: builtin/entity` + `gui_light: front`；TEISR 自行定位，不要在渲染器里手补旋转 |
| 模型贴图不显示 | Bedrock UV 坐标或 texture 路径错 | 检查 `BedrockCubeBox.compile()` |
| Bloom 部分对象糊满屏 | stencil buffer 能力不足 | 先做一次性诊断测 GL 能力 |

### 6.4 绝对不能用的写法

- **不要用 `World.getBlockState()` 后直接 cast** —— 1.12.2 要用 `IBlockState`
- **不要用 `getTileEntity()` 返回 null 不检查** —— 1.12.2 tile entity 可能不存在
- **不要在客户端代码里直接 new 服务端类** —— 会 ClassNotFoundException
- **不要用 Java 17+ API** —— 编译目标是 Java 8
- **不要在 Mixin 里写 Kotlin** —— Mixin 处理器只认 Java
- **不要用 `@SideOnly(Side.CLIENT)` 标注整个类然后服务端加载** —— 用 SidedProxy 分离

---

## 7. 当前进度与待办事项

### 已完成
- 0.7.0.1-alpha 热修四项（疾跑相关）
- 水阻气泡修复（`isInsideWater()` 查 Material.WATER）
- 第三人称持枪定位变换（移植 Next 的 `thirdperson_hand` 骨骼求逆）
- 第三人称枪管 90° 侧偏修复：根因是 `modern_kinetic_gun.json` 残留 vanilla 第三人称 `[0,-90,55]` 显示旋转，已按 Next 删除整个 `display` 块；实测枪管朝前、激光指前方
- 自杀式无人机（民用/军用 + 配方）
- JEI 全配方分类 + catalyst + NBT 子类型
- PlayerAnimator 软依赖兼容层
- 配件 modifier 注册↔消费守门
- 爆炸 AOE 跳过中心方块
- 网络同步：客户端预测扣弹 + 服务端确认/回滚
- 过热即时同步（加特林）

### 待办
- **逐发装填 bug**：m870/m1014 缺循环分支，换弹动作不连贯
- **高倍镜 FBO 渲染**：用户已叫停，优先级最低
- **README 更新**：最新 jar 哈希未更新到 README L46
- **蓝奏云包**：落后两代，需重传最新版给 Candy_sky
- **0.7.0.1 Release 正文**：未重写
- **远端 tag `0.7.0`**：是否处理未定
- **WaterFriction 默认值**：0.4 还是 0.15 未拍板

### 已知不做
- 高倍镜整体重写
- 近战包配方修复（7 条 copper_ingot 配方丢失）
- 无人机建模（当前是占位模型）
- FPV 侦察无人机（0.8.0+ 规划）

---

## 8. 给新 AI 的工作要求

1. **改任何代码前先读本文件**，特别是第 5 节红线。
2. **改完代码必须构建验证**：
   ```powershell
   $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
   cd E:\vip\TACZ-Legacy
   .\gradlew.bat build -x test --console=plain
   ```
   构建必须 SUCCESS，不能有 warning 之外的错误。
3. **构建后给出 jar 路径和 SHA-256**，方便用户替换测试：
   ```powershell
   Get-FileHash "build\libs\TACZ-Legacy-0.7.0.1-alpha.jar" -Algorithm SHA256
   ```
4. **不要擅自大范围重构**。这个项目是从 1.20.1 逆向移植到 1.12.2 的，很多写法是 hack 出来的，看着丑但能跑。
5. **不要改 NBT tag key 名、不要改注册顺序、不要改 packetId 顺序**。
6. **新增功能先问用户**，不要自己加你觉得"应该有"的东西。
7. **社区回复风格**：用户（wssessapple-ux）要求回 mcmod/B站 评论时必须口语化、短、像真人随手打的，不要写两段式排比句。参考 Candy_sky 吐槽"别用AI回我"的教训。
8. **枪包数据改了之后**，必须手动同步到 `tacz/tacz_default_gun/` 解压目录，游戏不会自动读 jar 里的新版。
9. **Git 操作**：
   - git 路径：`D:\Github\Git\cmd\git.exe`
   - 提交信息用英文短句
   - push 需要代理
   - 不要 `--amend`、不要 `--no-verify`
10. **遇到 1.12.2 特有的崩溃**，先查 hs_err 或 crash report 的 `Caused by`，不要先怀疑自己的代码。SplashProgress/OpenAL/GDI Generic 都是环境问题。
