# 深度开发记忆与上下文说明

> 这份文件不是代码规范，是"只有我们俩知道的为什么"。新 AI 读完代码再读这个，才能真正理解项目。

---

## 一、核心难题的解决过程

### 1. 第三人称枪"横着拿"——整场马拉松

**问题表象**：F5 第三人称看玩家，AK47 是横着端在腰前的，枪管指向身体左侧，不是朝前。

**第一步：对比上游 Next**
- 用户给了 B 站 Next 演示视频截图，要求对比。
- 直接 diff 两个仓库的 `TACZGunItemRenderer.kt`。
- 发现我们硬编码 `translate(0.3f, 1.5f, 0.3f)`，Next 是 `translate(0.5f, 2.0f, 0.5f)` + `applyPositioningTransform()` + `applyScaleTransform()`。
- 我们的 `BedrockGunModel` 里 grep `fixedOriginPath/groundOriginPath/thirdPersonHandOriginPath` 零命中——完全没有这套定位骨骼机制。

**第二步：三条路选哪条**
- A：调常数（加个 rotate 硬编码角度）——最快但每个枪都不一样，不通用。
- B：读枪包 `thirdperson_hand` 骨骼变换（数据驱动）——通用，但要移植骨骼路径解析。
- C：绕开 LayerHeldItem 自己写 layer——工程量最大。
- 用户选了 B。

**第三步：移植 B 方案踩的坑**
- 在 `BedrockGunModel.java` 加了三个 `List<BedrockPart>` 字段，构造时从 `modelMap.get("fixed"/"ground"/"thirdperson_hand")` 沿 parent 链回溯路径。
- `TACZGunItemRenderer.kt` 重写 `renderGunModel`：偏移改成 (0.5, 2.0, 0.5)，加 `applyPositioningTransform()`（从叶到根逐骨 rotate(-xRot)、translate(-x/16)），加 `applyScaleTransform()`（绕 Y=1.5 锚点缩放）。
- Build SUCCESS，用户实测反馈："枪管不是朝前"。

**第四步：排查朝向问题**
- 查 AK47 模型 JSON：`thirdperson_hand` 骨骼存在，pivot [0, 6.825, 5.425]，无旋转。父骨骼 `positioning` pivot [0,0,0]，也是根级骨骼。
- 关键发现：`root`（枪几何挂这里）和 `positioning`（定位节点）是**兄弟根级骨骼**，不在同一条链上。逆矩阵只把 positioning 链顶到锚点，root 链自己有 pivot [0, 8.2, 6.5]。
- 但因为这两个骨骼都没有旋转，逆矩阵纯平移，枪的朝向没变。
- 结论：定位矩阵解决了"握把位置对不对"，没解决"枪朝哪指"。需要一个补偿旋转。

**第五步：加补偿旋转**
- 在 `applyScaleTransform()` 之后加了 `if (THIRD_PERSON_RIGHT_HAND) GlStateManager.rotate(90f, 0f, 1f, 0f)`。
- Build SUCCESS，正在等用户实测。如果 90° 不对，试 -90° 或 180°。

**为什么不直接抄 Next 的矩阵？**
因为 Next 跑在 1.20.1，它的 `LayerHeldItem` 矩阵链和 1.12.2 不一样。1.12.2 的 `LayerHeldItem` 有固定的 `rotate(-90 X)` + `rotate(180 Y)`，这是给 2D 物品设计的。3D bedrock 模型在这个矩阵空间里需要额外补偿。

---

### 2. 弹药打一发弹匣变 0%——fuel vs inventory 供弹类型

**问题表象**：现代枪包（1.20+ 格式）的枪，打一发弹匣显示直接变 0%。

**排查过程**：
- 一开始以为是弹药计数同步 bug，查了半天网络包。
- 后来读 `useInventoryAmmo()` 逻辑，发现我们把 `reload.type` 为 `"fuel"` 的枪也当成了背包直读。
- **根因**：`"fuel"` 是燃料罐式供弹——换弹时消耗一个物品把弹匣一次性填满，弹匣本身依然计数。`"inventory"` 才是每发从背包扣一颗。
- 我们之前的代码不区分这两种，fuel 枪打一发就 `reduceCurrentAmmoCount()`，同时背包直读逻辑又在跑，一发就空。

**修法**：`useInventoryAmmo()` 只在 `reload.type == "inventory"` 时返回 true。fuel 枪走正常弹匣计数。

**教训**：枪包 JSON 的 `reload.type` 字段有两种语义，名字像但行为完全不同。

---

### 3. 爆炸 AOE 自伤——中心方块没跳过

**问题表象**：RPG 爆炸把自己炸残。

**排查**：查 `LegacyProjectileExplosion`，发现 AOE 伤害计算时没有跳过爆炸中心方块位置。子弹打在自己脚边的方块上，爆炸范围第一个命中的就是自己。

**修法**：AOE 遍历时跳过中心方块，同时微推步长避免贴脸判定。

---

### 4. 网络射击同步——客户端预测 + 服务端回滚

**问题表象**：联机时客户端按住鼠标连发，服务端实际只收到几发请求，客户端和服务端弹药数对不上。

**试错过程**：
- 方案 1：S2C 回传 ShootResult，客户端收到才停——延迟感强，手感卡。
- 方案 2：过热即时同步（加特林）——只解决了加特林。
- 方案 3（最终采用）：客户端预测扣弹 + 服务端确认/回滚。客户端先扣弹播动画，服务端校验后回传结果，不一致就回滚。

**为什么这样**：1.12.2 网络延迟高，等服务端确认再扣弹手感太烂。预测+回滚是 FPS 联机的标准做法。

---

### 5. PlayerAnimator 软依赖——compileOnly + 运行时守卫

**背景**：PlayerAnimator 官方最低 1.16.4，1.12.2 没有。但 zsjs110110-cpu 做了非官方移植。

**决策**：
- `build.gradle.kts` 里 `compileOnly(files("libs/playeranimator-1.12.2-1.0.2-rc1.jar"))`——编译期有 API，运行期不打进我们的 jar。
- 玩家自己决定装不装。装了才有第三人称动画，不装也能玩。
- 所有引用 PlayerAnimator 的类放在 `client/compat/playeranimator/` 下，调用点用 try/catch 或 Class.forName 守卫。

**为什么不做成 hard dependency**：不是所有玩家都需要第三人称动画，强行要求装会劝退。

---

## 二、关键架构决策的原因

### 为什么 `convertPivot()` 对子骨骼 Y 取反？

Bedrock 模型坐标系和 MC 坐标系 Y 轴方向相反。子骨骼的 pivot 在 JSON 里是绝对坐标，我们转成相对父骨骼时要做 `parentPivot - thisPivot`，X/Z 轴正常，Y 轴要反过来。这是 Bedrock geo.json 规范决定的，改了所有枪模型位置全歪。

### 为什么模型渲染要 `scale(-1, -1, 1)`？

1.12.2 的 `LayerHeldItem` 有固定旋转（rotate -90 X + rotate 180 Y），这个矩阵是给 2D 物品设计的。bedrock 模型在这个空间里上下颠倒、左右镜像，所以用 `scale(-1,-1,1)` 翻回来。Z 轴不翻是因为前后方向本来就是对的。

### 为什么物品注册全塞在 `LegacyItems` 一个 object 里？

因为 Forge 1.12.2 的 `RegistryEvent.Register<Item>` 要求在事件里注册，`allItems` list 是唯一注册入口。拆成多个文件反而容易漏注册。

### 为什么网络包 C2S 和 S2C 注册方式不一样？

C2S 消息的 handler 就是消息类自己（`registerC2S<T>()` 内联），因为客户端发出去不需要 handler。S2C 消息的 handler 和 message 分开（`registerS2C(Handler::class.java, Message::class.java)`），因为 handler 在客户端，message 在公共代码。这是 SimpleNetworkWrapper 的 API 限制。

### 为什么 `ModelBipedGunPoseMixin` 要写死手臂姿势？

因为 PlayerAnimator 1.12.2 没有动画状态机驱动手臂。我们 mixin 进 `ModelBiped`，在渲染时写死持枪手臂姿势。这是 hack，不是优雅方案，但在 1.12.2 上没有更好的选择。

### 为什么枪包要导出到 `tacz/` 文件夹而不是读 jar 内？

因为枪包是用户自己加的，不是模组自带的。首次启动把默认枪包解压到游戏目录下 `tacz/tacz_default_gun/`，用户可以直接改/加新枪包 zip 丢进去。**改了枪包数据后不会自动从 jar 更新解压目录**，必须手动同步。

---

## 三、易混淆概念澄清

| 名字很像但完全不同 | 区别 |
|---|---|
| `EntityKineticBullet` vs `LegacyProjectileExplosion` | 前者是飞行中的子弹实体，后者是爆炸逻辑（RPG/榴弹命中时触发） |
| `TACZGunPackRuntimeRegistry` vs `TACZGunPackRuntime` | 前者是全局快照注册表（getSnapshot() 拿不可变快照），后者是单次加载的运行时对象 |
| `TACZGunPackPresentation` vs `TACZGunPropertyResolver` | 前者是展示层（本地化名字/tooltip/排序），后者是数据层（解析枪的属性数值） |
| `ClientMessage*` vs `ServerMessage*` | ClientMessage 是 C2S（客户端发往服务端），ServerMessage 是 S2C（服务端发往客户端）。命名方向容易看反 |
| `LegacyBlocks` vs `LegacyBlockItem` | 前者是 Block 定义，后者是 ItemBlock 包装 |
| `BedrockPart.x/y/z` vs `BedrockPart.offsetX/Y/Z` | x/y/z 是骨骼 pivot（模型单位，render 时 /16），offsetX/Y/Z 是额外偏移（已经是方块单位）。两个都在 translateAndRotateAndScale 里用 |
| `convertPivot(bones, index)` vs `convertPivot(bones, cube, index)` | 前者转骨骼 pivot，后者转 cube 的相对 pivot。Y 轴取反逻辑都一样 |
| `focusedSmoke` JVM 参数 vs 正常烟雾 | focusedSmoke 是调试用的只渲染命中点烟雾，不是游戏内烟雾效果 |

---

## 四、对话中的隐藏约定

这些不是写在代码里的，是我们在开发过程中形成的默契：

1. **改代码前先读 Next 对应文件**：`E:\vip\TACZ-Legacy-Next-master\` 是参考实现。遇到不确定的行为，先看 Next 怎么做的，不要自己猜。

2. **构建必须转后台任务**：`gradlew.bat` 前台跑 15 秒自动转后台，与其等它自动转不如主动 `run_in_background: true`，然后 TaskOutput 等结果。

3. **PowerShell 纯表达式偶尔吞 stdout**：如果命令没输出，写文件再 Read，不要反复跑同一条命令。

4. **git 不在 PATH**：用 `& "D:\Github\Git\cmd\git.exe"` 绝对路径。push 要开代理。

5. **社区回复必须短、口语、不要两段式**：Candy_sky 直接吐槽过"别用AI回我"。写 mcmod/B站 回复时，不要写"首先...其次...最后..."，不要用"确实"、"辛苦了"这种AI高频词。一句话能说清就一句话。用户后来自己手打了回复，效果比我写的好。

6. **用户喜欢直接给完整文件覆盖**：初期排查时用户只要修改点和思路，确认方案后要求直接生成完整可编译的文件。不要只给 diff 片段。

7. **枪包路径**：游戏实例在 `E:\MC\PCL 正式版\.minecraft\versions\1.12.2-Forge_14.23.5.2864\`，枪包在该目录下 `tacz\`。

8. **README L46 记的是旧 jar 哈希**：每次改代码 build 完，README 和 Release 正文的哈希都要更新，这个经常忘。

9. **不要主动提高倍镜**：用户已经叫停了，优先级最低，不要自己捡起来做。

10. **玩家报 bug 先分层**：谁会遇到这个 bug？只有自己装了第三方枪包的边缘问题，和所有人都遇到的问题，优先级完全不同。

11. **热替换 jar 崩溃不要慌**：MC 运行中换 jar 导致 ClassNotFoundException 是 classloader 问题，重启就好，不是打包漏文件。

12. **m870/m1014 逐发装填**：这俩是霰弹枪，reload 状态机缺了循环分支——打完一发装一发的那个分支没写。约 1~1.5 晚工作量，一直没排期。
