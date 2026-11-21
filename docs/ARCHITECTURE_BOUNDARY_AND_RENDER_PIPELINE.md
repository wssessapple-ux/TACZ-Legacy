# 架构边界与渲染管线

> 本文记录 **当前代码真实落地** 的模块边界，以及自研静态网格渲染管线的结构、扩展点与生命周期。
> 目标分层与未来渲染方向见 [`PROJECT_OVERVIEW.md`](./PROJECT_OVERVIEW.md)；镜片 stencil 专项见
> [`SCOPE_RENDER_MIGRATION_PLAN.md`](./SCOPE_RENDER_MIGRATION_PLAN.md)。
> 管线仍在演进，文中标注「演进中」的部分后续可能调整。

## 1. 模块分层（现状）

包根 `com.tacz.legacy`，源码分 Java / Kotlin 两棵树（`src/main/java`、`src/main/kotlin`）。

| 层 | 包路径 | 职责 |
| --- | --- | --- |
| `api` | `api/` | 对外数据模型、动画常量、Lua 库常量，供上层与兼容层引用 |
| `common` | `kotlin/common/` | 注册、物品 / 方块 / 实体、网络、配置、枪包运行时资源，服务端与客户端共用 |
| `client` | `client/`（java + kotlin） | 渲染、模型、资源加载、GUI、输入、粒子、FPV、音频、客户端事件 |
| `compat` | `client/compat/`、`kotlin/compat/` | 可选模组兼容（PlayerAnimator 等），不向领域层渗透 |
| `mixin` | `java/mixin/` | 必要的底层钩子补丁 |
| `command` | `kotlin/command/` | 客户端 / 服务端命令 |
| 支撑 | `runtime/`、`util/`、`sound/` | 运行时工具与音频支撑 |

**边界现状（迁移期）**：理想方向是领域规则放在纯 Kotlin、不直接引用 `net.minecraft.*`，MC 适配集中在基础设施层（见 `PROJECT_OVERVIEW.md` 3.1 节）。这一目标尚未完全强制落地，改造在逐模块进行；新增逻辑尽量往该方向靠。

## 2. 两套并存的模型渲染

当前客户端有 **两条互不相干** 的模型渲染路径，改动前先确认自己在改哪一套：

1. **Bedrock JSON 模型**：枪械、配件、部分实体走 BedrockModel POJO + 实体 / 物品渲染器，动画来自 bedrock 关键帧或 PlayerAnimator 接入。
2. **自研静态网格管线**（本文重点）：glTF / OBJ 文本 → 统一中间表示 → Mesh 包装 → VBO 缓存或 immediate 兜底绘制。

两条路径共享同一套变换工具与 GL 状态约束，但模型数据、缓存和渲染器彼此独立，**不要把 bedrock 节点逻辑塞进静态网格管线**。

## 3. 静态网格管线数据流

```
枪包 / classpath 资源
        │
        ▼
TACZClientAssetManager（按 id 缓存 StaticMeshData，定位 classpath → 枪包目录/zip）
        │
        ├── GltfMeshParser.parse(json, binLoader)
        └── ObjMeshParser.parse(text)
        │
        ▼
StaticMeshData（position / UV / normal / 三角索引，格式无关）
        │
        ▼
Mesh（持有 MeshVbo；setScale / setFlipY 触发脏标记）
        │
        ▼
MeshTransform（VBO 路径与 immediate 兜底共用，变换不会漂移）
        │
        ├── MeshVbo.ensureUploaded → GPU 缓存绘制（READY）
        └── immediate 模式兜底（NEW / DIRTY / FAILED）
        │
        ▼
MeshVboStats 遥测（F3 右侧三行）
```

### 3.1 资源定位与缓存：`TACZClientAssetManager`

- 入口：`getGltfMesh(ResourceLocation)`、`getObjMesh(ResourceLocation)`，返回 `StaticMeshData?`。
- 缓存：两个 `LinkedHashMap<ResourceLocation, StaticMeshData>`，miss 时加载；**解析为空的结果不进缓存**。
- 定位顺序：先 classpath `assets/<namespace>/geo_models/<path>.gltf|.obj`，再已打开的枪包（目录 / zip）`openPackAsset`。
- glTF 外部 `.bin` URI 由 `resolveMeshBufferPath` 按文件所在目录相对解析；解析异常记一条 WARN 并返回 `null`，不抛到渲染层。

### 3.2 解析器输出统一中间表示：`StaticMeshData`

`java/com/tacz/legacy/client/resource/mesh/StaticMeshData.java`

- 字段：`float[] positions`、`float[] uvs`、`float[] normals`、`int[] indices`，四者对齐同一张索引表。
- 管线当前只表达固定管线能画的子集：**骨骼蒙皮、形变目标、节点层级动画、多材质组、切线 / 副切线一律丢弃**。
- 提供 `getVertexCount()` / `isEmpty()` 等只读访问。

### 3.3 网格包装：`Mesh`

`java/com/tacz/legacy/client/model/mesh/Mesh.java`

| 公开成员 | 说明 |
| --- | --- |
| `Mesh(data)` / `Mesh(data, tag)` | 包装一份 `StaticMeshData`，tag 用于遥测与排障 |
| `getData()` / `isEmpty()` | 取原始数据 / 判断空网格 |
| `setScale(float)` / `setFlipY(boolean)` | 与现值不同才写入，并对 VBO 打脏标记；相同值不触发重传 |
| `draw()` | **单一绘制入口**：VBO 就绪走 GPU，否则 immediate 兜底；分别上报 hit / fallback |
| `render()` | 在 `draw()` 外再包一层矩阵 push / pop |
| `compile(BufferBuilder)` | immediate 路径喂顶点，供外部 batch 复用 |
| `destroyVbo()` | 释放缓存的 VBO（teardown / 后续资源重载调用） |

调用方不需要判断上传状态，分支只发生在 `draw()` 内部。

### 3.4 同源变换：`MeshTransform`

`java/com/tacz/legacy/client/model/mesh/MeshTransform.java`

- 静态方法 `transform(positions, normals, index, scale, flipY, out)`，输出复用的 6 元素数组 `[px, py, pz, nx, ny, nz]`。
- 位置按 `scale / 16.0F` 缩放到 bedrock 单位；`flipY` 时翻转源模型 Y-up 到 MC Y-down；法线归一化；**UV 直通不做变换**。
- VBO 上传与 immediate 兜底走同一个方法，两条路径的顶点 / 法线结果不可能漂移。

### 3.5 GPU 缓存与降级：`MeshVbo`

`java/com/tacz/legacy/client/model/mesh/MeshVbo.java`

显式上传状态机：

| 状态 | 含义 |
| --- | --- |
| `NEW` | 尚未上传（一次性未就绪） |
| `READY` | 缓冲已上传，从 GPU 内存绘制 |
| `DIRTY` | 可恢复失败导致缓冲被丢，下帧重试上传 |
| `FAILED` | 持久失败，永久降级 immediate，不再重试 |

- 交错布局：stride 32 字节（3 position + 2 UV + 3 normal），UV 偏移 12，法线偏移 20。
- 索引类型：≤ 65535 顶点用 `UNSIGNED_SHORT`，超出用 `UNSIGNED_INT`。
- `GL_OUT_OF_MEMORY` 视为可恢复：丢缓冲转 `DIRTY`；其余持久失败转 `FAILED`。
- **所有 GL 调用只在渲染线程执行**。

### 3.6 遥测：`MeshVboStats`

`kotlin/com/tacz/legacy/client/model/mesh/MeshVboStats.kt`，进程级计数器，渲染线程自增、F3 覆盖层读取（良性竞态）。

- 计数入口：`recordHit()` / `recordFallback()` / `recordRebuild()` / `recordRecoverableFailure()` / `recordPersistentFailure()` / `addLive(bytes)` / `removeLive(bytes)`。
- `debugLines()` 输出 F3 右侧三行：命中率、重建与失败计数、当前 VBO 数量与占用显存。

### 3.7 渲染桥：`GltfMeshRenderer`

`kotlin/com/tacz/legacy/client/renderer/gltf/GltfMeshRenderer.kt`，`@SideOnly(CLIENT)` 内部 object。

| 公开方法 | 数据源 | 缓存键 |
| --- | --- | --- |
| `tryRenderBundled(fileName, scale, flipY)` | classpath `models/gltf/<file>.gltf`（内置静态物品模型） | 文件名 |
| `tryRenderManaged(id, scale, flipY)` | 枪包 glTF，经 `TACZClientAssetManager` 解析 | `ResourceLocation` |
| `tryRenderManagedObj(id, scale, flipY)` | 枪包 OBJ，经 `TACZClientAssetManager` 解析 | `ResourceLocation` |

- 三个缓存 `bundledMeshes` / `managedGltfMeshes` / `managedObjMeshes`；**缓存值为 `null` 表示「试过但缺失或无效」**，不重复解析。
- Mesh 跨帧复用，VBO 因此能在帧间存活。
- **桥的职责边界**：调用方需要先推好矩阵、绑好贴图、开好光照 / 混合；桥只开一个 `GL_TRIANGLES` batch，编译网格并绘制。桥内不做业务渲染状态管理。

## 4. 扩展点

### 4.1 新增一种模型格式

按现有 glTF / OBJ 的三段式接入，不需要改 `Mesh` / `MeshVbo`：

1. **写解析器**：输入格式文本（+ 必要的外部资源加载回调），输出 `StaticMeshData`，四数组对齐同一索引表。
2. **接资源管理器**：在 `TACZClientAssetManager` 加一个 `obtain` 方法与一个缓存 `LinkedHashMap`，对齐 `getGltfMesh` / `getObjMesh` 的模式：miss 加载、非空才缓存、异常记 WARN 返回 `null`。
3. **加渲染桥入口**：在 `GltfMeshRenderer` 加 `tryRenderXxx`，复用 `resolveManaged` 的 Mesh 缓存模式，让 VBO 跨帧存活。
4. 调用方走桥的公开方法，**不要在业务渲染器里直接拼 GL 调用**。

当前管线表达不了的属性（骨骼、morph、节点动画、多材质、切线），新格式也需要先在 `StaticMeshData` 层决定如何降级，而不是绕过中间表示直出 GL。

### 4.2 挂自定义渲染通道 / 特效

- 桥不管理矩阵、贴图、光照、混合状态。新特效或新通道在自己的实体 / 物品渲染器里完成：push 状态 → 绑贴图 / 设混合 / 开光照 → `Mesh.draw()` → pop 恢复。
- 不要在 `Mesh` / `MeshVbo` 内部堆业务渲染状态；这两个类只负责「把一份静态网格画出来」。
- 静态管线目前**没有独立 RenderPass 或调度器**；显式帧阶段、RenderPass / Subpass、高层 DrawCommand、Feature 插件化属于 `PROJECT_OVERVIEW.md` 第 4 节的蓝图，尚未落地。

## 5. 生命周期与失效通知

| 层级 | 缓存归属 | 失效 / 释放方式 |
| --- | --- | --- |
| `StaticMeshData` | `TACZClientAssetManager` 的两个 mesh map | 资源重载时 `gltfMeshes` 纳入清空；**`objMeshes` 尚未纳入重载清理**（见已知限制） |
| `Mesh` / `MeshVbo` | `GltfMeshRenderer` 的三个缓存 map | 按键跨帧复用；几何参数变更走 `markDirty()` 下帧重传 |
| 单网格 VBO | `MeshVbo` | `Mesh.destroyVbo()` → `MeshVbo.destroy()` 释放缓冲 |

- **参数变更**：`setScale` / `setFlipY` 与现值不同才打脏标记，避免无谓重传。
- **可恢复失败**：缓冲被丢转 `DIRTY`，下帧自动重试。
- **持久失败**：转 `FAILED`，永久走 immediate 兜底，不再尝试上传。
- **批量释放**：退出世界、换枪包、离屏时的 VBO 批量回收钩子尚未接全，现有 `destroyVbo()` 只是留好的单点口子（见 #160）。

## 6. 已知限制与演进中

- **无骨骼 / 节点动画**：自研 OBJ 解析器挂骨骼 mesh 仍在规划（#145）。
- **无 Forge 原生 OBJ 静态外壳**：待评估（#146）。
- **VBO 离屏 / 重载批量释放未接全**，且 `objMeshes` 未纳入资源重载清理（#160）。
- **高倍镜 FBO 黑屏**：攻坚中；镜片 stencil 渲染见 `SCOPE_RENDER_MIGRATION_PLAN.md`。
- Bedrock 模型与静态网格两套路径并存，尚未统一到一套渲染调度。
- 静态管线未实现蓝图中的 RenderData / RenderPass / Dispatcher / Feature 插件化结构。

## 7. 相关文档

- [`PROJECT_OVERVIEW.md`](./PROJECT_OVERVIEW.md)：项目目标、建议分层与渲染框架方向蓝图。
- [`SCOPE_RENDER_MIGRATION_PLAN.md`](./SCOPE_RENDER_MIGRATION_PLAN.md)：瞄准镜 stencil 渲染迁移专项。
- [`FIRST_PERSON_RENDER_LEGACY_REFERENCE.md`](./FIRST_PERSON_RENDER_LEGACY_REFERENCE.md)：第一人称渲染移植参考。
- [`LOCALIZATION.md`](./LOCALIZATION.md)：本地化与多语言键约定。
- [`AUTHORIZATION.md`](./AUTHORIZATION.md)：授权与资产归属存档。
