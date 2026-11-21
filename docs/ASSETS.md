# 资产与第三方组件清单（ASSETS.md）

> 本文件是 README「版权与许可」一节的详细展开：逐项列明本仓库的代码、美术资产、模型、贴图、图标、音效、第三方代码库与运行前置的**来源、协议与归属**。
>
> **协议总纲**：本模组整体按 **GPL-3.0** 开源，完整协议见根目录 `LICENSE`。除明确标注为维护者原创保留权利的资产外，其余代码与资源均在 GPL-3.0 框架下分发。

---

## 1. 归属总览

仓库内的内容按来源与权利状态分为四类：

| 类别 | 归属 | 协议 / 状态 | 典型位置 |
| --- | --- | --- | --- |
| 上游 TaCZ 移植代码与默认枪包 | KasumiNova / TaCZ Team | 继承 GPL-3.0 | `src/main/**`、`assets/tacz/custom/tacz_default_gun/**` |
| 维护者原创资产 | wssessapple-ux（本仓库维护者） | 随模组 GPL-3.0 分发，权利保留（见 §3.1） | 无人机 / 区块加载器 / 防弹衣 / 手雷等模型、贴图、图标、GUI、文案 |
| 第三方代码库 | 各开源作者 | 各按其原始开源协议 | shade 进产物 jar、编译期引用、运行期模组（见 §4） |
| 外部枪包资源 | 对应枪包作者 | **不在本仓库授权与承诺范围内** | 游戏目录 `tacz/` 下玩家自行放入的枪包 |

---

## 2. 上游 TaCZ 代码与默认枪包

### 2.1 移植代码

本模组基于 KasumiNova（TaCZ Team）开发的《永恒枪械工坊：零 (TaCZ)》源码，针对 Minecraft 1.12.2 进行非官方移植与维护。

- `src/main/**` 下的移植层、Bug 修复、1.12.2 兼容层与渲染适配代码，**继承上游 GPL-3.0**，本仓库同样以 GPL-3.0 开源。
- 上游作者从未发布过针对 1.12.2 的正式构建版本；本仓库为社区非官方移植分发工程，与官方 TaCZ 项目无关。
- 授权背景与接手声明见 [AUTHORIZATION.md](./AUTHORIZATION.md)。

### 2.2 默认枪包 `tacz_default_gun`

仓库内置的默认枪包位于 `assets/tacz/custom/tacz_default_gun/`，随上游 TaCZ 体系按 GPL-3.0 分发。规模（按文件类型统计）：

| 类型 | 数量 | 内容 |
| --- | --- | --- |
| `.json` | 1052 | 枪械 / 弹药 / 配件 / 方块数据、索引、显示配置、动画与配方 |
| `.lua` | 33 | 枪包脚本 |
| `.ogg` | 1140 | 各枪械、配件、近战与抛壳音效 |
| `.png` | 718 | 枪械 / 弹药 / 配件 / 方块贴图与图标 |
| `.txt` | 1 | 枪包编辑说明（`README.txt`） |

主要子目录：

- `assets/tacz/geo_models/`：枪械 / 弹药 / 配件 / 方块 / 抛壳的几何模型。
- `assets/tacz/textures/`：对应 UV 贴图、槽位图标、LOD 贴图。
- `assets/tacz/display/`：枪、配件、弹药、方块的手持 / 掉落显示配置。
- `assets/tacz/animations/`、`player_animator/`：动画与第三人称动作数据。
- `assets/tacz/tacz_sounds/`：按枪械名分目录的音效。
- `data/tacz/`：枪械 / 弹药 / 配件 / 方块数据、索引、配方、标签与脚本。

### 2.3 随上游沿用的顶层资源

`assets/tacz/` 顶层（非 `custom/`）下，以下资源属于上游 TaCZ 既有体系，本移植仅做 1.12.2 路径 / 键名适配，版权归上游：

- 准星与命中标记：`textures/crosshair/**`。
- HUD 火控模式与热量条：`textures/hud/**`。
- 弹体 / 光束实体贴图：`textures/entity/basic_bullet*.png`、`beam*.png`。
- 靶子 / 雕像 / 靶车：`models/block|item|bedrock/` 下的 `target*`、`statue*`、`target_minecart*` 及对应贴图。
- 枪匠台 / 配件工作台：`gun_smith_table*`、`weapon_workbench*` 的模型、GUI 与贴图。
- 弹药箱图标与模型：`ammo_box*`。
- 多语言基础翻译：`lang/**`。
- 动画、粒子与动作适配数据：`animations/**`、`particles/**`、`player_animator/**`。

---

## 3. 维护者原创资产（版权保留）

### 3.1 权利声明与使用限制

以下由维护者（wssessapple-ux）原创制作的模型、贴图、图标、界面、合成方案与文案，**版权归维护者本人所有，保留所有权利**。这些资产随模组整体按 GPL-3.0 分发；在遵守 GPL-3.0 的前提下，可以在整合包、二次开发中随模组使用，但不得：

- 剥离、抠取或单独搬运上述资产用于其他项目或商用；
- 去除、篡改本声明与署名；
- 将原创资产冒充为上游 TaCZ 官方内容单独发布。

### 3.2 逐项清单

| 原创资产 | 模型 / 数据路径 | 贴图 / 图标路径 | 备注 |
| --- | --- | --- | --- |
| 自杀式无人机（民用 / 军用） | `models/bedrock/drone.json`；`models/item/drone_civilian.json`、`models/item/drone_military.json`；`geo_models/drone.obj` | `textures/item/drone_civilian.png`、`drone_military.png`；`textures/entity/drone_civilian.png`、`drone_military.png` | 含枪匠台民用 / 军用合成配方（`assets/tacz/custom/tacz_default_gun/data/tacz/recipes/drone/`） |
| FPV 侦察无人机 | `models/item/fpv_drone.json`；遥控器 `models/bedrock/fpv_remote.json` | 遥控器 `textures/entity/fpv_remote.png` | 本体模型与实体贴图复用民用无人机资产，未单独另绘 |
| 区块加载器（Chunk Loader） | `models/block/chunk_loader.json`；`models/item/chunk_loader.json` | `textures/block/chunk_loader_top.png`、`chunk_loader_side.png`、`chunk_loader_front.png`、`chunk_loader_bottom.png` | 含 GUI 面板、TESR 动态发光核心渲染与合成配方 |
| 防弹衣（Ballistic Vest） | `models/item/ballistic_vest.json` | `textures/item/ballistic_vest.png`；护甲层 `assets/minecraft/textures/models/armor/ballistic_vest_layer_1.png` | 胸甲槽一档 |
| 破片手雷 / M67 | `models/bedrock/grenade.json`；`models/gltf/grenade.gltf`；`models/item/grenade.json`、`models/item/m67.json` | `textures/entity/grenade.png` | 含实体与合成配方 |
| 创造栏标签图标 | `models/item/tab_icon.json` | `textures/item/tab_icon.png` | 投掷物 / 无人机专用创造栏 |

此外，上述物品的**原创名称、提示栏（tooltip）与操作提示文案**同样属于原创资产，对应语言文件中本项目新增的键（中英文见 `lang/zh_cn.*`、`lang/en_us.*`）。

### 3.3 复用与未单独另绘说明

- FPV 侦察无人机的模型与实体贴图复用民用自杀式无人机资产，未单独绘制。
- 部分 GUI 背景、槽位图标在沿用上游既有视觉风格的基础上由本项目调整配色与排版；若某一资产归属在原创 / 上游之间存在疑问，以随上游分发为准，不在此主张原创保留权利。

---

## 4. 第三方代码库

### 4.1 打包进产物 jar（shade，重定位）

以下依赖通过 `shade` 配置打进发布 jar，并在 `org.apache` / `org.joml` / `org.luaj` 等包名前重定位（relocate）到 `com.tacz.legacy.shadow.*`，避免与其他模组的同名库冲突。均随模组按其原始协议分发：

| 依赖 | 版本 | 协议 | 用途 | 重定位包名 |
| --- | --- | --- | --- | --- |
| `org.luaj:luaj-jse` | 3.0.1 | MIT | 枪包 Lua 脚本引擎 | `com.tacz.legacy.shadow.org.luaj` |
| `org.joml:joml` | 1.10.5 | MIT | 3D 向量 / 矩阵数学库 | `com.tacz.legacy.shadow.org.joml` |
| `org.apache.commons:commons-math3` | 3.6.1 | Apache-2.0 | 数值计算工具库 | `com.tacz.legacy.shadow.org.apache.commons.math3` |

### 4.2 编译期引用，不进产物 jar

| 组件 | 位置 / 坐标 | 协议 | 说明 |
| --- | --- | --- | --- |
| PlayerAnimator 1.12.2 移植版 | `libs/playeranimator-1.12.2-1.0.2-rc1.jar`（`compileOnly`） | MIT | 第三人称玩家动作动画底层。上游 KosmX `minecraftPlayerAnimator` `1.0.2-rc1`，1.12.2 移植由 zsjs110110-cpu 完成。本仓库只做数据接入，**产物 jar 不内嵌其类文件**；MIT 许可全文见 `libs/LICENSE-PLAYERANIMATOR.md` |
| ASM 调试工具 | `org.ow2.asm:asm-debug-all:5.2`（annotationProcessor） | ASM / BSD 风格 | 仅编译期注解处理，不进运行包 |
| Guava | `com.google.guava:guava:24.1.1-jre`（annotationProcessor） | Apache-2.0 | 仅编译期注解处理 |
| Gson | `com.google.code.gson:gson:2.8.6`（annotationProcessor） | Apache-2.0 | 仅编译期注解处理 |

### 4.3 运行期第三方模组（不 shade）

以下为运行期实现依赖或前置 / 软依赖，**不打包进本模组产物 jar**，由玩家实例或整合包环境提供。版权归各自作者，具体协议以其 CurseForge / Modrinth 发布页为准：

| 模组 | 作用 | 来源 / 坐标 | 必要性 |
| --- | --- | --- | --- |
| MixinBooter | 为 1.12.2 引导、加载 Mixin | `zone.rong:mixinbooter:10.7`（CleanroomMC 体系） | 强制前置，缺失会崩 |
| Forgelin-Continuous | Kotlin 语言运行库 | `io.github.chaosunity.forgelin`（版本见 `gradle.properties` 的 `forgelin_continuous_version`） | 强制前置，缺失会崩 |
| JEI (Just Enough Items) | 游戏内查看枪械 / 弹药 / 工作台配方 | CurseMaven `jei-238222`，兼容 4.16.x | 软依赖 / 强烈推荐，缺失不影响运行 |
| lumenized | 光晕（bloom）效果及依赖 | CurseMaven `lumenized-1234162:6734060` | 实现依赖 |
| CTM (Connected Textures Mod) | 连接纹理支持 | CurseMaven `ctm-267602:2915363` | 实现依赖 |
| CodeChickenLib | 代码库（上述模组的支撑库） | CurseMaven `codechickenlib-242818:2779848` | 实现依赖 |

### 4.4 构建期工具（不分发到玩家环境）

Gradle 插件、Kotlin Gradle 插件、Shadow 插件、RetroFuturaGradle、CurseGradle、jacoco 等仅参与构建过程，不属于运行期资产，亦不进入产物 jar。

---

## 5. 外部枪包资源（不在授权范围）

游戏目录 `tacz/` 下由玩家自行放入的第三方枪包（枪械模型、贴图、音效、动画、弹药与配件数据、脚本等），**版权归对应枪包作者所有，不在本仓库的授权、担保与承诺范围内**。本模组仅提供枪包加载与解析运行时。

---

## 6. 字体、音效与其他素材

### 6.1 字体

仓库**未内嵌任何第三方字体文件**（无 `.ttf` / `.otf` / `.woff` / `.woff2`）。界面文字使用 Minecraft 原版字体渲染。

### 6.2 音效归属

- 默认枪包音效（1140 个 `.ogg`）随 §2.2 默认枪包分发，归上游 TaCZ Team。
- 顶层独立音效共 8 个 `.ogg`，随上游移植体系：
  - `sounds/block/target_block_hit/*.ogg`：靶子命中音效（7 个）。
  - `sounds/gun/empty.ogg`：空仓挂机音效（1 个）。

---

## 7. 安全说明

本模组为开源项目，源码在本仓库可逐行核对。模组本身**不包含**远程下载并执行外部代码、窃取账号或凭据、修改系统启动项 / 注册表、后台联网上报等行为。

jar 内 shade 的公开开源依赖（见 §4.1）用于脚本执行与数学计算，均随模组按其原始协议分发。部分静态扫描工具（例如 Minecraft 模组恶意软件检查器）会对打包进 jar 的第三方库做启发式匹配，把其中的文件 IO、反射、动态脚本执行、临时文件清理等常规写法标为风险项；这些命中来自上述开源依赖，**不代表本模组存在恶意行为**，如需核实可对照本仓库源码与这些依赖的官方公开源码。

模组内唯一的浏览器跳转（`Desktop.browse`）仅发生在枪匠台界面、由玩家手动确认后触发，用于打开枪包作者提供的链接，不会自动发起下载。

---

## 8. 署名与致谢

- **上游移植起点**：Kasumi_Nova (Hikari_Nova) 与 TaCZ Team，授权与声明见 [AUTHORIZATION.md](./AUTHORIZATION.md)。
- **第三人称动画底层**：zsjs110110-cpu 移植的 PlayerAnimator 1.12.2（MIT，上游 KosmX `minecraftPlayerAnimator`）；本仓库只做数据接入，未修改、未内嵌其代码。
- **Lua 沙箱安全加固**：移除 `os` / `io` / `luajava` / `load` 等高危入口的方案（`secureStandardGlobals`），是参考 zsjs110110-cpu 源码后**自行实现**的，并非照搬其代码。

完整贡献者与测试团队名单见 README 文末「贡献者」一节。
