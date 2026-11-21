# 迁移与演进规划索引

> 本文件是 README 指向的**顶层导航入口**，只做归类与指路，不承载阶段细节。
> 各轨道的当前状态、smoke 证据、reopen 结论都在对应分册里；改动分工 / 顺序 / 边界时维护分册入口，不要把长篇阶段日志堆回本页。

## 1. 主迁移计划（1.12.2 Forge 移植）

上游 `TACZ`（1.20.1）向 `TACZ-Legacy`（1.12.2）的移植，按轨道拆分为短入口 + 分类分册。

| 文档 | 角色 |
| --- | --- |
| [`migration_plan/MAIN.md`](./migration_plan/MAIN.md) | **正式总入口**：轨道边界、推荐顺序、Prompt 总路由、分册维护规则 |
| [`migration_plan/2026-03-10-FOUNDATION-TO-CLIENT-UX.md`](./migration_plan/2026-03-10-FOUNDATION-TO-CLIENT-UX.md) | Foundation / Data / Combat / Audio / Client UX 分类分册 |
| [`migration_plan/2026-03-10-RENDER-VALIDATION-AND-PROMPTS.md`](./migration_plan/2026-03-10-RENDER-VALIDATION-AND-PROMPTS.md) | Render / Animation / 视觉验收 / Prompt 细分与并行建议 |
| [`TACZ_AGENT_MIGRATION_PLAN.md`](./TACZ_AGENT_MIGRATION_PLAN.md) | 兼容跳板页：仅为旧引用保留，**不要再往回堆阶段正文** |

阅读顺序：先 `MAIN.md` 看全局结构与轨道边界，再按要改的轨道进对应分册。

## 2. 专项规划

| 文档 | 主题 | 状态口径 |
| --- | --- | --- |
| [`SCOPE_RENDER_MIGRATION_PLAN.md`](./SCOPE_RENDER_MIGRATION_PLAN.md) | 瞄准镜 / 镜片 stencil 渲染迁移 | 含落地状态、focused smoke 与截图证据 |
| [`TACZ_AUDIO_ENGINE_PLAN.md`](./TACZ_AUDIO_ENGINE_PLAN.md) | 专用音频引擎设计方案 | 设计方向与接线规划 |

## 3. 历史检查点

| 文档 | 主题 |
| --- | --- |
| [`TACZ_WORKTREE_CHECKPOINT_2026-03-09.md`](./TACZ_WORKTREE_CHECKPOINT_2026-03-09.md) | 2026-03-09 工作区整理与迭代检查点 |
| [`TACZ_WORKTREE_CHECKPOINT_2026-03-14.md`](./TACZ_WORKTREE_CHECKPOINT_2026-03-14.md) | 2026-03-14 工作区整理与迭代检查点 |

## 4. 配套规范与现状文档

| 文档 | 用途 |
| --- | --- |
| [`TACZ_AGENT_WORKFLOW.md`](./TACZ_AGENT_WORKFLOW.md) | Agent 交付、阶段报告、文档更新与 handoff 规范 |
| [`ARCHITECTURE_BOUNDARY_AND_RENDER_PIPELINE.md`](./ARCHITECTURE_BOUNDARY_AND_RENDER_PIPELINE.md) | 当前落地的模块边界与静态网格渲染管线契约 |
| [`PROJECT_OVERVIEW.md`](./PROJECT_OVERVIEW.md) | 项目目标与建议分层、渲染框架方向蓝图 |

## 5. 维护规则

- 轨道边界、推荐顺序、Prompt 总路由变化 → 改 `migration_plan/MAIN.md`。
- 某条轨道的阶段状态、编译 / smoke / 验收结论 → 进对应分类分册。
- 协作规范、报告模板、handoff 机制 → 改 `TACZ_AGENT_WORKFLOW.md`。
- 本索引只在**新增 / 归档规划文档、导航归类变化**时更新；保持短、稳、可导航。
