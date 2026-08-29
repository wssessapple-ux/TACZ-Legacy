# TACZ-Legacy
---
### ⚠️ 非官方移植构建版重要声明

> **本项目为社区非官方移植构建，与原作者及官方 TaCZ 项目无关！**
> 本模组是基于 KasumiNova（TaCZ Team）开发的《永恒枪械工坊：零 (TaCZ)》源码，针对 **Minecraft 1.12.2** 版本的非官方移植构建。原作者从未发布过针对 1.12.2 的正式构建版本。
> * 源码版权归原作者及上游 TaCZ 项目所有，本项目遵循 **GPL-3.0** 协议开源。
> * 本构建未修改任何核心逻辑，仅提供版本适配构建。
> * **请勿用于商业用途、勿向原项目反馈本版问题、勿冒充官方传播。**

---

### 📦 版本与构建信息

* **游戏版本**：Minecraft 1.12.2 (Forge)
* **模组版本**：0.1.0 Beta (Unofficial)
* **Java 版本**：Java 8
* **构建可验证性**：附件 `TACZ-Legacy-1.12.2-v0.1.0-beta-source.zip` 为构建时刻精确源码快照（含完整 LICENSE）。发布版 JAR 的 SHA-256 哈希值为：
  `ED572240107BDBAA17F54B80C3945F23B5310A2D985E06E1FDF34893CBFA9047`

### 🛠️ 已知问题

* **部分瞄具/倍镜视角黑屏**：源于 1.12.2 移植版源码本身的渲染兼容性缺陷，非本构建问题，当前无法修复，需等待上游代码更新。

---

### 🔌 前置模组依赖（缺失将导致游戏崩溃）

本模组依赖以下两个前置模组，已随本发布版一同提供：

**1. MixinBooter**
* **作者**：Rongmario (及 CleanroomMC 团队维护)
* **作用**：专为 1.12.2 等老版本设计的核心库，用于引导和加载 Mixin，解决模组间的核心冲突，是现代模组在老版本平稳运行的基石。

**2. Forgelin-Continuous**
* **作者**：Community / Continuity Maintainers
* **作用**：Kotlin 语言运行库。为使用 Kotlin 编写的现代模组（如本模组）提供必要的运行环境支持。`Continuous` 版本修复了新版 Java 环境下的兼容性问题。

---
*(以下内容整理自上游官方 README，仅作为基础功能参考，部分特性可能与本 1.12.2 移植版存在差异)*

`TACZ-Legacy` 是 `TACZ` 的 **Minecraft 1.12.2 Forge 移植工程**，并采用 **Kotlin + RetroFuturaGradle** 技术栈。

项目目标：

- 迁移并保持 TACZ 的核心玩法、交互与功能一致性
- 最大化复用现有美术素材与资源命名体系
- 让 1.20 时代的“模组数据包 / 枪包”尽可能平滑迁移并保持兼容
- 重构 1.12.2 渲染侧架构，提供可扩展的渲染管线

---

## 当前阶段

当前仓库已完成：

- Kotlin 1.12.2 工程基础配置初始化
- 模组主入口/代理骨架建立
- Mixin 基础环境初始化（`mixins.tacz.json`）
- 项目级 Copilot 指导文件初始化（`.github/copilot-instructions.md`）
- 迁移蓝图文档初始化（`docs/MIGRATION_PLAN.md`）

---

## 技术栈

- Minecraft Forge `1.12.2`（`14.23.5.2847`）
- Kotlin（Forgelin-Continuous）
- RetroFuturaGradle
- Sponge Mixin（通过 MixinBooter 接入）

---

## 快速开始

1. 初始化反编译工作区：`gradlew setupDecompWorkspace`
2. 导入/刷新 Gradle 工程（IDEA）
3. 运行开发环境：`gradlew runClient` / `gradlew runServer`

> 若修改了 `gradle.properties` 中的 `use_mixins/use_coremod/use_access_transformer` 等开关，建议重新执行 setup 并刷新 Gradle。

### 自动化烟雾测试

- 纯编译检查：`./gradlew classes`
- 客户端加载烟测：`bash scripts/runclient_smoke.sh`
- 自定义超时（秒）：`bash scripts/runclient_smoke.sh 120`

该脚本会将日志输出到 `build/smoke-tests/`，并在以下任一条件满足时判定通过：

- 在超时前到达 `FoundationSmoke` + `Forge Mod Loader has successfully loaded` 启动完成标记
- 命中标记后脚本会自动关闭客户端并返回，不会一直卡住控制台

这适合做“能否成功启动到真实 MC 环境”的回归验证；若要验证具体玩法链路（射击、换弹、工作台等），建议在此基础上继续补更细的交互脚本或集成测试。

---

## 文档导航

- 项目总览：`PROJECT_OVERVIEW.md`
- 迁移蓝图：`docs/MIGRATION_PLAN.md`
- 架构与渲染蓝图：`docs/ARCHITECTURE_BOUNDARY_AND_RENDER_PIPELINE.md`
- Copilot 协作指引：`.github/copilot-instructions.md`

---

## 设计原则（迁移期）

1. **兼容优先**：先保证行为与数据兼容，再做结构升级。
2. **分层迁移**：资源/数据层、逻辑层、渲染层分阶段推进。
3. **可观测性**：关键系统在迁移期必须有日志与诊断开关。
4. **可回滚**：高风险改动（尤其渲染）要支持灰度开关。
5. **分离但不断裂 MC**：核心逻辑尽量去 MC 依赖以支持单测，同时保留清晰 MC 适配层。

---

## License

继承上游 TACZ 的版权与许可约束；本仓库仅包含移植开发所需代码骨架与文档。
## 👥 贡献者
[![](https://github.com/KasumiNova.png?size=60)](https://github.com/KasumiNova)
[![](https://github.com/wssessapple‑ux.png?size=60)](https://github.com/wssessapple‑ux)

- [KasumiNova](https://github.com/KasumiNova)：上游 TACZ‑Legacy 原始仓库作者
- [wssessapple‑ux](https://github.com/wssessapple‑ux)：Minecraft 1.12.2‑Forge 构建发行者
