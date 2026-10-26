# TACZ-Legacy 1.12.2 移植版 Bug 修复清单

维护者：wssessapple-ux ｜ 环境：1.12.2-Forge 14.23.5.2864 ｜ 依据：0.17.0 社区修复版源码吸收 + 源码定位
说明：状态标记「已验收」= 用户进游戏实测确认；「已交付」= 完整文件已给、待用户覆盖编译；「部分完成」= 主逻辑已生效、有遗留项。

## 一、渲染类

### 1. TransformerExclusions 注解移除（已验收）
- 现象：Mixin 因 `@TransformerExclusions` 冲突不生效。
- 修复：移除注解，Mixin 正常加载，第三人称手臂能抬起持枪。

### 2. 后坐力自我抵消（已验收）
- 现象：`TACZCameraRecoilHandler` 用「帧间 delta」每帧只减增量，视角自我抵消，看不出抬枪。
- 修复：改「绝对偏移」（每帧 = 基础视角 - 总偏移）+ 开火瞬间把峰值直接施加到真实视角（rotationPitch/Yaw）+ spline 只用于回弹动画。

### 3. 第三人称无持枪动作（部分完成）
- 现象：第一人称正常，第三人称玩家只有默认姿势，枪飘在旁边，无开火/换弹/拉栓动作。
- 修复：补 `ModelBipedGunPoseMixin`，用 `ArmPose` 修改玩家手臂姿势。手臂已抬起（已验收）。
- 遗留：枪模型仍悬浮在脸前，缺「把枪 bind 到玩家骨骼」一步（对齐 1.20 的 ItemRenderer / 骨骼偏移 / display 配置）。

## 二、UI 类（GunSmithTableScreen）

### 4. 切枪型无效（已验收）
- 现象：点按钮无法从手枪切到步枪，但搜索能找到。
- 修复：标签切换补 `refreshRecipeState(preserveSelection = true)`。

### 5. 翻页按钮无反应（已验收）
- 现象：`BUTTON_TYPE_PREV / NEXT` 点击后页面不变。
- 修复：补齐翻页逻辑的刷新（缺 `rebuildWidgets()` / `typePage` 判断）。

### 6. UI 层级穿模（已验收）
- 现象：左侧大背景面板盖住右侧物品列表左半部分。
- 修复：调整绘制顺序 / `GlStateManager` 层级（Z-level）。

## 三、战斗 / 弹道类

### 7. 子弹重力过大、霰弹枪无射程（已验收）
- 现象：子弹飞出去像自由落体，霰弹枪完全没有射程。
- 修复：`LegacyEntities` / `EntityKineticBullet.onUpdate` 中多套用的重力 / `motionY` 扣减常量修正。

### 8. 高倍镜开镜黑屏（已验收）
- 现象：开镜只画镜筒，视野骨骼被 `hideIndexedNodes(DIVISION_NODE)` 主动隐藏。
- 修复（`BedrockAttachmentModel` / `AttachmentRender` / `BedrockGunModel`）：
  - 新增 `collectSightPictureNodes` / `hideSightPictureNodes` / `renderWithSightPicture`。
  - 几何判据：名字含 division、旋转点 Z < -40、cube minZ < -40。
  - 区分「黑底板 surround（跨度≥40 且厚度≤2）」与「刻线 reticle」。
  - 新增 `isOpticBodyNode`（名字排除）、`isOversizedOpticBone`（尺寸闸门 ≥40 跳过）、`oversizedNodes`（开镜隐藏）。
  - 开镜关闭深度测试（`GL11.glDisable(GL11.GL_DEPTH_TEST)`）绘制视野，刻线十字准星已能显示。

### 9. 近战崩溃 / 无伤害（已交付，待覆盖编译）
- 现象：近战攻击崩溃 / 打不出伤害。
- 修复（`GunDataAccessor.kt` + `LivingEntityMelee.kt`）：
  - `GunMeleeCombatData` 补 `distance` 字段，`fromRawJson` 解析 melee.distance。
  - `BASE_MELEE_REACH = 0.75f` 移植版基础距离，叠加枪包距离。
  - 判定盒 / 清除无敌帧 / 伤害结算重写。

## 四、音频类

### 10. 枪声时有时无（已交付，待覆盖编译）
- 现象：1.12.2 OpenAL 通道配额耗尽，`alGenSources` 返回 0 直接丢声音。
- 修复（`TACZOpenALSoundEngine.kt`）：新增 `allocateSourceGuaranteed()`，通道满时先清理最老/最远声源再重试，确保当前枪声一定响。

## 五、合成 / 配方类

### 11. 枪匠台「次元壁」合成失败（已验收）
- 现象：材料齐全在主背包点合成静默失败；放快捷栏甚至不识别材料。
- 根因：配方 JSON 引用大量 1.12.2 不存在的高版本 tag/item（`crying_obsidian` 等约 30 个 / 41 文件），DataPack 配方机制与 1.12.2 `IRecipe` 断裂。
- 修复：全量扫描 `data/tacz/recipes/`，批量替换不存在的引用 + `BUTTON_CRAFT` 按钮注册修复。用户实测成功。

### 11.1 生存必需方块/物品原版配方全缺（已验收）
- 现象：弹药箱、枪匠台、弹药/配件工作台、靶子、靶车、雕像、火药**全部无 1.12.2 原版配方**（`data/` 目录原本为空），生存第一步卡死、打完子弹后弹药无来源。
- 根因：Java 物品/方块未补原版合成配方；且配方误放 `data/<modid>/recipes/`（1.13+ 约定），**1.12.2 Forge 只加载 `assets/<modid>/recipes/`**，放错目录完全不生效。
- 修复（`assets/tacz/recipes/`，新增 8 个配方）：
  - `ammo_box`（木板×8+铁锭）、`gun_smith_table`（枪匠台）、`workbench_a`（弹药工作台：铁锭×3+弹药箱）、`workbench_c`（配件工作台：玻璃+红石火把+铁锭+箱子）、`target`、`target_minecart`、`statue`（石英三件套）、`gunpowder`（燧石+糖×2+木炭×3 → 火药×3）。
  - **铜锭→铁锭替代**（1.12.2 原版无铜，本模组独立前置、不依赖第三方铜 mod）；**火药配方必补**（盘活整个弹药循环）。
  - 全部转 1.12.2 格式：`tag`→`item+data:32767`（木板/原木通配）、木炭=`coal:1`、石英半砖=`stone_slab:7`；并把配方从 `data/` 移到 `assets/`。
  - 用户实测全部可合成，生存链闭环（枪匠台→弹药箱→弹药/配件工作台→火药→弹药→造枪）。

## 六、网络类

### 12. 时间戳同步风暴卡服 / 掉线（已验收）
- 现象：连发开火高频发包 / 时间戳回包风暴导致服务器卡死。
- 修复：`LivingEntityShoot` 给 `ServerMessageSyncBaseTimestamp` 加 1.5s 抑制窗口 + 失败不再疯狂发包 + 客户端回发节流。
- 备注：S2C 广播瘦身**已落地**（`ServerMessageGunShoot/GunFire` 去 ItemStack 只留 entityId+gunId）。共改 7 个文件：GunEvents（事件签名 gunItemStack→gunId）、ServerMessageGunShoot/Fire（消息瘦身）、LivingEntityShoot、TACZGunScriptAPI、LegacyClientShootCoordinator、FocusedSmokeRuntime（消费者直接读 gunId）。编译通过，单机连发/换弹/第三人称稳定；多人第三人称广播效果待联机验证。

## 七、装填类（最近主线）

### 13. M870 装填只装一发就中断（已验收）
- 现象：生存模式空枪 + 背包有 12g，按 R 只装一发就停。
- 根因：客户端与服务端**双份执行** `consumeAmmoFromPlayer()` 扣背包 12g（单机共享同一背包）→ 12g 被扣光 → 服务端 `hasAmmoToConsume=false` → 动画中断。
- 修复（`TACZGunScriptAPI`）：`consumeAmmoFromPlayer()` 加 `if (shooter.world.isRemote) return neededAmount`（客户端预测不扣）；`inventoryHasAmmoForGun()` 加 `if (shooter.world.isRemote) return true`（服务端主导搜背包）。用户确认「弹匣能装到满」。

### 14. 扩容弹夹上限不生效（已验收）
- 现象：装了 100 发弹鼓，属性面板显示 100，实际只能装 30 发。
- 根因：装填 / 满弹判定硬编码基础 `ammoAmount`，未接入已存在的 `LegacyGunRefitRuntime.computeAmmoCapacity()`（读 `EXTENDED_MAG` 附件档位 + `extendedMagAmmoAmount[level-1]`）。
- 修复（`TACZGunScriptAPI` + `LivingEntityReload`）：
  - `getMaxAmmoCount()` → `computeAmmoCapacity(itemStack)`。
  - `getNeededAmmoAmount()` → 用 `getMaxAmmoCount()`。
  - `putAmmoInMagazine()` → 上限用 `getMaxAmmoCount()`。
  - `getMagExtentLevel()` → 读 `EXTENDED_MAG` 附件实际档位。
  - `LivingEntityReload` 满弹判定 maxAmmo → `computeAmmoCapacity`。
  - 用户确认「成功」（弹鼓装满 100 发）。

### 15. 调试日志清理（已完成）
- 清理 `[ReloadDebug]` / `[BoltDebug]` / `[AmmoDebug]` 全部临时日志，保留所有修复逻辑。
- 涉及文件：`TACZGunScriptAPI.kt`、`LegacyClientPlayerGunBridge.kt`、`ClientMessagePlayerReload.kt`。
- 编译通过（BUILD SUCCESSFUL）。

## 待处理（玩家「笨狐狸」反馈其余项）
- 疾跑开空枪。
- 射击中换弹不响应。
- 配件卸载图标不显示。
- 空枪换空弹。
- 第三人称持枪姿势（枪悬浮，绑定骨骼）。
