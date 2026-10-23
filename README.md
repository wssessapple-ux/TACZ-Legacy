# TACZ-Legacy

> ⚠️【非官方移植构建版】本项目为社区非官方移植，与原作者及官方 TaCZ 项目无关。

📢 如果这个项目帮到了你，请点右上角 ⭐ Star 支持一下！你的每一次 Star 都是让更多玩家看到 1.12.2 唯一 TaCZ 的机会！

📥 **Download on CurseForge:** https://www.curseforge.com/minecraft/mc-mods/tacz-legacy

📥 **Download on Modrinth:** https://modrinth.com/mod/tacz-legacy

⚠️ **紧急通知：0.1.0 为早期纯编译版，未修复任何 Bug（高倍镜黑屏、服务器卡顿等）真正的修复版 (0.2.0/0.3.0) 已在 CurseForge 上架，请前往下载！想提前体验最新内测版的玩家，请前往 GitHub Releases 下载！
---

**📢 Modrinth 更新延迟说明**：由于 Modrinth 官方审核排队极慢（已卡 3 周），目前最新版本（0.3.0/0.4.0）仅在 CurseForge 和 GitHub Releases 提供。请前往这两处下载，不要等 Modrinth 了！
---

### ⚠️ 非官方移植构建版重要声明

本项目为社区非官方移植构建，与原作者及官方 TaCZ 项目无关。

本模组基于 KasumiNova（TaCZ Team）开发的《永恒枪械工坊：零 (TaCZ)》源码，针对 **Minecraft 1.12.2** 版本进行非官方移植和维护。原作者从未发布过针对 1.12.2 的正式构建版本。

- 源码版权归原作者及上游 TaCZ 项目所有，本项目遵循 **GPL-3.0** 协议开源。
- **当前版本已包含自行修复的 bug 和改进**，不再只是原样编译。
- 请勿用于商业用途、勿向原项目反馈本版问题、勿冒充官方传播。

---

### 📦 版本与构建信息

- **游戏版本**：Minecraft 1.12.2 (Forge)
- **模组版本**：0.4.0 Beta (Unofficial)
- **Java 版本**：Java 8
- **构建可验证性**：发布版 JAR 的 SHA-256 哈希值将在每次发布时更新。

---
### 🛠️ 已知问题与修复进展

已彻底修复的问题（经过实机测试验证）：**

- **底层 Mixin 修复**：移除了导致所有注入被拦截的 `TransformerExclusions`，渲染、动画、后坐力等 Hook 现已全面生效。
- **后坐力重构**：从“帧间增量”改为“绝对偏移 + 峰值施加”，连发时视角会真实上爬。
- **子弹重力优化**：修复了弹道下坠过快的问题，霰弹枪等武器射程显著恢复。
- **第三人称持枪**：修复了枪械悬浮，现在角色会双手持枪。
- **工作台 UI 切换**：标签按钮可正常切换枪械种类。
- **近战攻击重做**：重写近战判定！新增 3 格基础攻击距离、放宽判定角度、清除无敌帧吞伤、支持刺刀配件加成，且彻底修复了近战崩溃。
- **音效丢失修复**：为 OpenAL 增加了通道配额保护与重试机制，连发扫射不再“哑火”。
- **网络层性能优化**：增加了时间戳同步冷却，防止极端网络环境下双向发包引发服务器卡死或掉线。(注:目前为0.5.0专属,请等待更新.)
- **枪匠台合成修复 (0.5.0)**: 彻底修复了点击制作按钮无反应的恶性 Bug（根因为 GUI 漏注册 BUTTON_CRAFT 按钮）。同时修复了高版本材料（如 crying_obsidian）在 1.12.2 下解析失败导致的配方失效问题。

**⚠️ 仍存在的问题（受限于 1.12.2 底层限制或仍在排查）：**

- **高倍镜黑屏/镜框消失**：1.12.2 老旧 OpenGL 渲染管线与现代渲染（FBO/后处理）的底层冲突。目前 0.5.0 开发分支已初步实现开镜出十字准星，但依然在攻坚。
- **临时解决方案**：请搭配 **Actinium** 模组使用，它可以缓解部分倍镜黑屏问题。请勿与 BetterFPS 同时使用，两者会冲突。


**完整已知问题列表请查看 [GitHub Discussions](https://github.com/wssessapple-ux/TACZ-Legacy/discussions)。**

**仍存在的问题（部分列举）：**

- 泵动式霰弹枪生存模式装填中断
- 性能问题（帧率下降、内存占用偏高）

更多细节和最新进展，请见 Discussions  页面。

---

### 🔌 前置模组依赖（缺失将导致游戏崩溃）

本模组依赖以下两个前置模组，已随本发布版一同提供：

**1. MixinBooter**
- **作者**：Rongmario（及 CleanroomMC 团队维护）
- **作用**：专为 1.12.2 等老版本设计的核心库，用于引导和加载 Mixin。
- **版本**：10.7

**2. Forgelin-Continuous**
- **作者**：Community / Continuity Maintainers
- **作用**：Kotlin 语言运行库。为使用 Kotlin 编写的现代模组提供必要的运行环境支持。
- **版本**：2.3.20.0

---

### 🚧 项目边界说明

本仓库维护 Minecraft 1.12.2-Forge 的非官方构建：

- ✅ **工作范围**：工程构建、环境适配、打包发布、bug 修复、社区反馈处理。
- ❌ **不工作范围**：上游模组核心业务代码开发。如果你想要原版新功能，请前往上游仓库KasumiNova 的原始 TACZ 仓库 [KasumiNova/TACZ-Legacy](https://github.com/KasumiNova/TACZ-Legacy)。

---

### 📝 问题反馈指引

- Bug 反馈请使用仓库的 Issue 模板提交，请尽可能附上游戏日志、版本信息、复现步骤。
- 安装、配置、使用类求助请到 **Discussions** 讨论区。
- 上游原始代码产生的缺陷请不要向 KasumiNova 的上游仓库提交反馈。
- 本仓库为 KasumiNova/TACZ-Legacy 上游源码的编译分发仓库。
- 上游项目许可证：GPL-3.0，本分发项目同样以 GPL-3.0 开源。
- 如需要提交源代码层面的功能补丁，欢迎提交 PR 或向本仓库反馈。

---
### 🤖 AI 辅助开发声明

本项目的部分 Bug 修复（包括 Mixin 失效修复、后坐力重构等）在开发过程中使用了 AI 工具（豆包）进行代码分析、提供修改思路和代码片段辅助。

**但请注意：**
- 所有 AI 提供的代码和修改方案，均由维护者（wssessapple-ux）**在游戏内实测验证通过后**才合并到官方版本。
- 核心移植架构和最终版本发布由人工完成。

  
### 📌 项目状态

目前处于**低频维护**阶段。学业期间更新较慢，但会持续处理社区反馈和关键 bug 修复。寒暑假期间会有集中更新。感谢理解与支持！

---

`TACZ-Legacy` 是 `TACZ` 的 **Minecraft 1.12.2 Forge 移植工程**，并采用 **Kotlin + RetroFuturaGradle** 技术栈。

## 项目目标

- 迁移并保持 TACZ 的核心玩法、交互与功能一致性
- 最大化复用现有美术素材与资源命名体系
- 让 1.20 时代的“模组数据包 / 枪包”尽可能平滑迁移并保持兼容
- 重构 1.12.2 渲染侧架构，提供可扩展的渲染管线

---

## 技术栈

- Minecraft Forge `1.12.2`（`14.23.5.2847`）
- Kotlin（Forgelin-Continuous）
- RetroFuturaGradle
- Sponge Mixin（通过 MixinBooter 接入）

---

# 开发者编译指南

## 环境准备



* JDK 8（用于编译模组代码）

* JDK 21（用于运行 Gradle 进程，RFG 1.4.9 强制要求）

* 推荐使用 IntelliJ IDEA

## 配置 Java 路径（必须）

打开根目录的 `gradle.properties`，在文件末尾添加以下内容（注意修改为你电脑上的实际安装路径）「macOS/Linux 用户请对应替换路径」：



```
org.gradle.java.home=C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.12.101-hotspot
org.gradle.java.installations.paths=C:\\Program Files\\Eclipse Adoptium\\jdk-8.0.504.1-hotspot
```

## 配置 Gradle 镜像（国内网络必须）

打开 `gradle/wrapper/gradle-wrapper.properties`，将 `distributionUrl` 替换为国内镜像，否则会报 SSL 证书错误：



```
distributionUrl=https://mirrors.cloud.tencent.com/gradle/gradle-8.1.1-bin.zip
```

## 修复依赖与工具链

打开 `build.gradle.kts`，做以下两处修改：



1. 将第 19 行的 `id("com.gtnewhorizons.retrofuturagradle") version "1.3.34"` 改为 `"1.4.9"`（旧版已被仓库移除）。

2. 在 `java { toolchain { ... } }` 代码块中，将 `vendor.set(JvmVendorSpec.AZUL)` 改为 `vendor.set(JvmVendorSpec.ADOPTIUM)`。

## 开始构建

执行以下命令：



```
gradlew.bat setupDecompWorkspace
gradlew.bat build
```

构建成功的 JAR 文件将生成在 `build/libs/` 目录下。

### 自动化烟雾测试

- 纯编译检查：`./gradlew classes`
- 客户端加载烟测：`bash scripts/runclient_smoke.sh`

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

#### 👥 贡献者

- [KasumiNova](https://github.com/KasumiNova)：上游 TACZ-Legacy 原始仓库作者
- [wssessapple-ux](https://github.com/wssessapple-ux)：Minecraft 1.12.2-Forge 构建发行者
- **BH_wueif**：为本版修复提供了思路参考（Mixin 失效、后坐力）
- **DragoWolfHunter**：反馈了子弹重力异常（霰弹枪射程问题），协助定位了物理引擎参数
