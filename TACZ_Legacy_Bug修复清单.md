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

### 16. 高射速空枪 / 弹药"扣了又回"（QA 确认过热修复通过）
- 现象：加特林（发热 0.1）/150 发机枪高射速连发偶发"空枪"（无子弹实体）；暂停/网络波动时弹药"扣了又回"抖动。
- 根因：高射速下客户端与服务端数据不同步；服务端网络时间戳校验窗口过严（±300/600ms），重型整合包低 TPS/暂停时把正常玩家误判成时间异常 → 拒绝射击并回滚弹药。
- 修复：
  - 新增 S2C `ServerMessageShootState`（result+gunId+ammoCount+heatAmount+overheatLocked），服务端回传射击结果；客户端预测扣弹、拒绝时停连发、按结果同步发热/锁枪。
  - 方案1 自适应时间戳：新增配置 `serverShootNetworkTimeout`（默认 600ms）；单机/局域网（IntegratedServer）放宽≥2000ms，远程服务器用配置值；`NETWORK_FAIL` 时客户端不回滚弹药（只静默停一发）。
- 涉及文件：`ServerMessageShootState.kt`（新建）、`TACZNetworkHandler.kt`、`ClientMessagePlayerShoot.kt`、`LegacyClientShootCoordinator.kt`、`LivingEntityShoot.kt`、`LegacyConfigManager.kt`。
- 状态：已编译入 0.5.1-alpha jar；QA「笨狐狸」确认过热修复通过（4tps/210mspt、tps20 波动两次卡顿仍能发射）。

### 17. 子弹击退（对齐原版 TaCZ 无击退）
- 现象：1.12.2 移植版子弹命中产生击退，加特林把 20 血怪打飞；原版 TaCZ 无击退，玩家反馈"感觉怪"。
- 根因：`EntityKineticBullet.applyImpactKnockback` 无条件应用 `knockback` 字段（直接来自枪械基础 JSON），移植带入击退。
- 修复：命中时移除 `applyImpactKnockback` 调用并删除方法本体；`knockback` 字段保留（存档兼容 + 爆炸弹配置不受影响）。
- 涉及文件：`LegacyEntities.kt`。
- 状态：已编译入 0.5.1-alpha jar（10-31 13:26）。

### 18. 配件 ignite modifier 接入（生效 3→4）
- 现象：配件 ignite modifier 注册读取但零调用点，战斗不生效（运行时生效仅 recoil/inaccuracy/silence）。
- 修复：`EntityKineticBullet` 新增 `applyAttachmentIgnite`（配件点燃覆盖 igniteEntity/igniteBlock）；`LivingEntityShoot.performSingleFire` 用 `evalIgnite` 合并"枪械基础点燃 + 装配的 ignite 配件 modifier"应用到每发子弹。
- 涉及文件：`LegacyEntities.kt`、`LivingEntityShoot.kt`。
- 状态：已编译入 0.5.1-alpha jar；生效数对齐 Next（recoil/inaccuracy/silence/ignite）。

### 19. modifier 注册↔消费守门审计（立守门机制）
- 根因：此前 16 个 modifier 只有 3 个在战斗消费，其余仅 GUI 预览或无人读，靠人肉 grep 排查。
- 修复：新增 `TACZModifierAudit`（`TACZGunPackRuntime.kt`）：16 个注册 modifier 逐个标定消费状态（4 APPLIED / 7 GUI_ONLY / 5 UNCONSUMED），重载时自检——注册但无审计条目打 error，GUI_ONLY/UNCONSUMED 打 warn 如实暴露空转。
- 状态：已编译入 0.5.1-alpha jar；后续把 damage/rpm/effective_range/pierce/knockback 逐个接进战斗时逐条降级 UNCONSUMED。

### 20. 枪包转换器 zip-slip 防护 + 原子发布（已完成）
- 根因：`TACZPackConvertor` 解压枪包/写临时文件时对条目名不做校验，恶意包可用 `../` 或绝对路径越界写；发布过程非原子，中断会残留半成品。
- 修复（`TACZPackConvertor.kt`，照 Next 分支）：
  - `requireSafeEntryName`：空名 / 反斜杠 / 前导 `/` / 盘符前缀（`^[A-Za-z]:.*`）/ split 后 `..` 全量拒绝。
  - `putEntry` 统一所有写入入口（writeEntry/parseRecipe/addMeta 都走它）。
  - `convertTo` 先写 `$newName.tmp-${nanoTime}` 临时文件 → `publish()`（`Files.move` ATOMIC_MOVE+REPLACE_EXISTING，不支持时退化普通 move）→ 失败删临时文件再 rethrow。
- 状态：已编译入 0.5.1-alpha jar。

### 21. modifier 注册↔消费守门测试（已建立）
- 目标：对齐 Next 的 `ModifierConsumptionCoverageTest` —— "注册了就一定有人消费"，这是"注册但没人读"缺陷唯一能机器发现的入口。
- 新增 `ModifierConsumptionCoverageTest.kt`（3 断言：注册⊆消费 / 消费无陈旧 / 注册恒 16）。维护方式：每接入一个 modifier 就补一行 consumers 登记。
- 连带修复：`S2CMessageSerializationTest` 因 S2C 广播瘦身遗留编译失败（事件已改 ResourceLocation gunId，测试仍传 ItemStack.EMPTY），已改 `ResourceLocation("tacz","ak47")`。**此前 `build -x test` 不编译 test 源集，这个编译错误一直被躲着。**

### 22. 配件 modifier 第一批接入玩法（4→10，待接入 6）
- 目标：把 12 个未接入的 modifier 逐个接进战斗（对齐 Next 16/16 全做法消费）。
- 第一批（子弹层 7 个）：
  - 收集层：`TACZGunPropertyResolver` 新增 `collectNumericModifiers` / `AttachmentModifierLists` / `collectAttachmentModifiers`（armor_ignore/head_shot/pierce/damage/ammo_speed/effective_range）。注：未含 C10 exclusive_attachments 整份替换（独立治理项）。
  - 子弹层：`EntityKineticBullet` 新增 `applyAttachmentModifiers`（armor_ignore clamp 0-1 / head_shot ≥0 / pierce ≥1）、`applyAttachmentDamageModifier`（逐距离段 eval）、`applyAttachmentEffectiveRange`（改第一段距离）、`bakeDamageBaseMultiplier`（配置倍率先烘进基准再 eval，幂等）。
  - 结算修正：`resolveEffectiveDamageAt` 改为先 `bakeDamageBaseMultiplier()`，**不再乘 damage_base_multiplier**（已烘进，防双倍）；超出最后有限距离档 → 0（对齐上游，原为回落末档伤害）。
  - 发射层：`LivingEntityShoot.performSingleFire` 一次 `collectAttachmentModifiers` → `ammo_speed` eval（≥0.1）+ 每发子弹应用 3 个 applyAttachment*。
  - knockback：豁免（命中击退已移除，无消费点；守门注释已说明）。
- 守门：`ModifierConsumptionCoverageTest` consumers 4→10 登记；编译 BUILD SUCCESSFUL（仅 1 无害 warning）；测试预期红（剩 6：ads/explosion/knockback/rpm/movement_speed/weight_modifier）。
- 状态：第一批已编译入 jar；第二批（explosion typed 化 + rpm/ads/movement_speed/weight_modifier）待接。

### 23. 配件 modifier 第二批：explosion + rpm（已编译入 jar）
- explosion（typed 化，热路径不碰 Gson）：
  - `TACZGunPackRuntime` 新增 `TACZExplosionModifierValue`（explode/radius/damage/knockback/destroyBlock/delay）+ `TACZExplosionModifierReader`（解析期一次成型，替代 TACZRawJsonModifierReader，格式错误直抛 JsonSyntaxException），注册 `"explosion" to ...`。
  - 新建 `TACZExplosionAggregation`（纯函数：DEFAULT_RADIUS=0.5/DAMAGE=2/DELAY=30；aggregate 返回 null 保持原状态；normalizeDelay 负→正无穷）。
  - `EntityKineticBullet` 加 `hasExplosionData` + `applyAttachmentExplosion`（聚合写回）；发射循环应用。
- rpm：
  - `GunDataAccessor.getShootIntervalMs()` 带参版 evalNumeric + `clamp(1,1200)` + `60_000/clamped`（**不乘 heat**——heat 在我方是分离的 resolver lerp，避免改现网语义）。
  - 接入 3 个 Kotlin 调用点（LivingEntityShoot / TACZGunScriptAPI / LegacyClientShootCoordinator）。
  - Java 侧 `GunAnimationStateContext.java` 走无参版（Kotlin internal 被名字 mangling 不可调，已注释差异）。
- 守门：consumers 10→12。

### 24. 末影人瞬移修复（方案 A，已编译，用户实测掉血正常）
- 现象：末影人能躲子弹（官方高版本几乎躲不了、能打出伤害）。
- 根因：子弹继承 EntityThrowable，1.12.2 末影人 `attackEntityFrom` 对 EntityThrowable 走瞬移分支。
- 修复（命中层，100% 可控）：`createBulletDamageSource(target, ignoreArmor)` —— 命中 EntityEnderman 且 thrower 是 EntityLivingBase 时改用 `DamageSource.causeMobDamage(thrower)`（immediateSource=射手，非 EntityThrowable）→ 原版 else 正常伤害不瞬移；其他实体保持 causeThrownDamage。两处调用点传 target。
- 过程：曾用 `EndermanMixin @Redirect ordinal=1` 方案（编译过、refmap 正确，但用户实测仍瞬移——ordinal 运行时脆弱可能静默加载失败），已删除该 mixin 与 mixins.tacz.json 注册，改用命中层方案。
- 方案 A（用户拍板）：子弹能正常打出伤害即可，末影人 AI 瞬移保留（官方同款概率）；`EndermanMixin.java` 已删除。
- 状态：用户实测"打末影人已正常掉血"；已编译入 jar。

### 25. 配件收尾：ads + movement_speed + weight_modifier（守门 16/16 转绿）
- ads：`LivingEntityAim.tickAimingProgress` 收集 `ads` modifier eval 修正开镜时长（对齐 Next / 上游 LocalPlayerAim 读缓存后的 aimTime）。
- movement_speed / weight_modifier：新建 `LivingEntitySpeedModifier`（`updateSpeedModifier` 挂 MOVEMENT_SPEED 的 AttributeModifier——weight_modifier→MULTIPLY_BASE 惩罚、movement_speed→MULTIPLY_TOTAL），`ShooterTickHandler.onLivingTick` 每 tick 驱动（客户端+服务端，1.12.2 玩家位移由客户端预测驱动）。
  - movement_speed 只叠加配件 raw json 的 base/aim/reload 逐分量（我方未解析"枪自身基础值" moveSpeedBase/Aim/Reload，已注释；后续可补）。
  - weight_modifier：枪基础 weight + 配件 modifier eval → -weight × `weightSpeedMultiplier`（默认 0.015）。
- 守门：`ModifierConsumptionCoverageTest` 加 `exempted` 豁免集（knockback——命中击退已移除无消费点）+ 登记 ads/movement_speed/weight_modifier（12→15）。**守门 3 断言全绿：15 消费 + 1 豁免 = 16/16。**
- 状态：主代码编译 BUILD SUCCESSFUL；守门测试 BUILD SUCCESSFUL。

### 26. 弹药箱容量上限过低（308 只能装 48）
- 现象：308 弹箱最高只能装 48 发；玩家改 `AmmoBoxStackSize` 配置（如 256）不生效。
- 根因：`AmmoBoxInventory.maxStackLimit` = 弹药 stackSize（308=48），完全没读 `ammoBoxStackSize` 配置。
- 修复（对齐 Next）：弹箱容量 = 弹药 stackSize × `ammoBoxStackSize` 配置（默认 3 → 308 弹箱 48×3=144）；创意弹箱容量同步。玩家改配置（如 256）生效 → 48×256=12288 发。
- 涉及文件：`AmmoBoxContainer.kt`（maxStackLimit）、`LegacyItems.kt`（AmmoBoxItem.getSubItems）。
- 注：弹箱物品自身 maxStackSize=1 保留（每个弹箱独立 NBT 库存，叠加会 NBT 冲突）。
- 状态：编译 BUILD SUCCESSFUL，已入 jar。

### 27. 射击中换弹（换弹与射击状态机冲突）
- 现象：射击连发中按 R 可换弹，与射击状态冲突。
- 根因：`LivingEntityReload.reload()` 只查"已在换弹/切枪/拉栓"，未查射击冷却。
- 修复：reload() 加 `getSynShootCoolDown() > 0L` 拦截（对齐官方，开火未完禁止换弹）。
- 涉及文件：`LivingEntityReload.kt`。
- 状态：编译 BUILD SUCCESSFUL，已入 jar。

### 28. 空枪换空弹（换弹满发但只能开一次空枪）
- 现象：没子弹按 R 换弹，动画走完显示满弹匣，但只能开一次空枪（没子弹实体）。
- 根因：客户端 tickReload 也跑 defaultReloadFinishing，用预测值（consumeAmmoFromPlayer 客户端直接返回 neededAmount）把弹匣写满；服务端从背包实际提取可能不足（弹药不够/类型不匹配）→ 两端不一致。
- 修复：服务端补弹完成后复用 ServerMessageShootState(SUCCESS) 回传实际弹匣数，客户端 applyServerShootState 覆盖（SUCCESS 不触发取消连发副作用）。
- 涉及文件：`LivingEntityReload.kt`（defaultReloadFinishing 末尾服务端回传）。
- 状态：编译 BUILD SUCCESSFUL，已入 jar。

### 附：疾跑开空枪（复测确认）
- 现状：三层拦截已在源码（客户端 340 setSprinting + 147 getSynSprintTime 拦截 + 服务端 96 sprintTimeS 拦截）。QA 反馈可能测旧包，需对当前 dev 复测确认是否已修复。

## 待处理（玩家「笨狐狸」反馈其余项）
- 疾跑开空枪。
- 射击中换弹不响应。
- 配件卸载图标不显示。
- 空枪换空弹。
- 第三人称持枪姿势（枪悬浮，绑定骨骼）。
- 暂停后不能开枪（现象待复现：单机/联机、暂停时长、枪型；等笨狐狸补细节）。
