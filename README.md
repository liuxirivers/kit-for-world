# RandomGift · 随机世界礼包

> 在世界地表随机生成一个个"礼包",玩家右键即可领取随机奖励。

一个面向 **Paper 服务端**的 Minecraft 插件。礼包以隐形小盔甲架实现,头部顶着一块方块(默认箱子),
玩家在野外探索时遇到它、右键打开,插件按配置执行奖励指令。

适用于生存服的活动掉落、节日礼物、寻宝玩法。

---

## 目录

- [特性](#特性)
- [环境要求](#环境要求)
- [构建](#构建)
- [安装](#安装)
- [快速上手](#快速上手)
- [配置说明](#配置说明)
- [指令与权限](#指令与权限)
- [工作原理](#工作原理)
- [项目结构](#项目结构)
- [数据文件](#数据文件)
- [常见问题](#常见问题)

---

## 特性

- **随机散布** —— 在世界出生点周围指定半径内随机选区块,优先挑选本批次没用过的区块,礼包不会挤成一堆。
- **异步分块生成** —— 通过 `World#getChunkAtAsync` 预加载/生成区块,不卡主线程;每个礼包有独立的"尝试链",
  某区块找不到合法落点时自动换区块重试,尽量凑满配置要求的数量。
- **严格的地表判定** —— 只落在实心完整方块的顶面上,避开液体、花草、栅栏、台阶、耕地、多层雪,
  实体 Y 坐标精确对齐可站立面,**不会卡在方块里或陷进地里**。
- **两种时间模式** —— 按现实时间(24 小时/天,时间戳持久化,重启不丢进度)或按游戏内时间(1 MC 天 = 20 分钟)。
- **可交互 & 防拆** —— 空手或手持物品右键都能领取;装备交换被拦截,玩家无法把头部的箱子方块扒下来;
  礼包免疫一切伤害(火焰、爆炸、攻击)。
- **精确清理** —— 记录每个礼包的世界 + 区块坐标,卸载区块里的礼包也能被找出来清掉,不会留下隐形实体垃圾。
- **完全可配置** —— 掉落指令、随机数值区间、礼包方块、发光、周期、范围、数量全在 `config.yml` 里,无硬编码。

---

## 环境要求

| 项目 | 要求 |
|---|---|
| 服务端 | Paper **26.1.2** 或更高(`api-version: '26.1.2'`) |
| JDK | **25+**(Paper 26.1.2 本身要求 JDK 25) |
| 构建工具 | 自带 Gradle Wrapper + `paperweight-userdev`(会自动拉取 Paper 开发包) |

---

## 构建

```bash
git clone https://github.com/liuxirivers/kit-for-world.git
cd kit-for-world

# Windows
gradlew.bat build

# macOS / Linux
./gradlew build
```

产物在 `build/libs/` 下。

> **注意 `gradle.properties` 里的这一行**
>
> ```properties
> org.gradle.java.installations.paths=C:/Users/Administrator/.cache/jdk25
> ```
>
> 这是某台机器的本地 JDK 25 路径。如果你机器上 JDK 25 在别的位置,**改掉它或直接删掉这一行**
> (删掉后 Gradle 会靠 `foojay-resolver` 自动下载工具链)。

---

## 安装

1. 把构建出的 jar 放进服务端的 `plugins/` 目录。
2. 启动服务端,插件会自动生成 `plugins/RandomGift/config.yml`。
3. 按需修改配置,执行 `/gift reload`(或重启)使其生效。

---

## 快速上手

```
/gift cr 10     # 清掉旧的,在当前世界生成 10 个礼包
/gift           # 看帮助
```

进游戏走到野外,留意发光的箱子 —— 右键它就能拿到奖励。

想让礼包立刻出现在脚下,用 `/gift spawn`。

---

## 配置说明

配置文件:`plugins/RandomGift/config.yml`

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `target-world` | `world` | 目标世界名,需与 `Bukkit.getWorld()` 的键一致(通常就是世界文件夹名)。**必须填实际存在的世界**,填错会导致自动生成报「目标世界未找到」 |
| `spawn-range` | `1000` | 以世界出生点为中心的方块半径。默认值即 **±1000 格**,也就是 2001×2001 的范围 |
| `spawn-count` | `20` | 每个周期生成的礼包数量 |
| `amount-given` | `5-25` | `{AMG}` 占位符的取值区间,格式 `最小值-最大值`。写错会回退到 `5-25` 并打警告 |
| `helmet-block` | `CHEST` | 礼包外观使用的方块材质名(装备在隐形盔甲架头部)。非法材质名回退为 `CHEST` |
| `glow-enabled` | `true` | 是否给礼包加发光描边,方便玩家在渲染距离内发现。改完执行 `/gift reload` 会同步应用到**已存在**的礼包 |
| `use-real-time` | `false` | `true` = 现实时间模式(1 天 = 24 小时,进度写入 `gift-data.yml`,重启不丢);`false` = 游戏 tick 模式(1 天 = 20 分钟) |
| `period-days` | `3` | 生成周期(天)。现实模式下一个"天"是 24 小时,游戏模式下是 1 个 MC 游戏天 |
| `commands` | 见下 | 领取时要执行的指令列表 |

### 关于 `commands`

指令由**控制台**执行,支持两个占位符:

- `{player}` —— 领取者的玩家名
- `{AMG}` —— 在 `amount-given` 区间内随机抽到的整数

默认值:

```yaml
commands:
  - give {player} diamond {AMG}
  - say 恭喜 {player} 获得了 {AMG} 颗钻石！
```

> 想让不同礼包给不同奖励?目前一次领取执行的是**同一组**指令,`{AMG}` 是其中唯一的随机变量。
> 要做多种奖励,可以把随机逻辑写进指令本身,例如用服务端自带的记分板/random 机制。

### 关于 `helmet-block`

填 Minecraft 材质名,例如 `CHEST`、`OAK_PLANKS`、`NOTE_BLOCK`……

用 `CHEST` 时,客户端在 **12 月会自动把箱子渲染成圣诞礼物陷阱箱贴图** —— 想做过节活动的话很省事。

---

## 指令与权限

主指令 `/gift`,别名 `/randomgift`。

| 指令 | 说明 |
|---|---|
| `/gift` | 显示帮助 |
| `/gift spawn` | 在**执行者脚下**生成 1 个礼包(仅玩家可用) |
| `/gift reload` | 重载 `config.yml`,并同步发光状态与调度周期 |
| `/gift cr [数量]` | 清除全部旧礼包,并在**当前世界**重新生成;数量范围 1 ~ 10000,不填则用 `spawn-count` |

权限:`randomgift.admin`(默认仅 OP)。所有子指令都要求该权限,无权限时提示「你没有执行该指令的权限！」。

Tab 补全支持子指令与 `cr` 的数量示例。

---

## 工作原理

### 生成

`GiftManager.SpawnBatch` 为**每个**礼包开一条独立的尝试链:

1. 在 `spawn-range` 范围内随机挑一个区块(最多试 6 次,优先挑本批次没用过的,保证散开);
2. `world.getChunkAtAsync(cx, cz, true)` 异步生成/加载该区块;
3. 区块就绪后,打乱内部 16×16 这 256 列的顺序,最多探测 64 列,找合法落点;
4. 找到就生成,否则记一次失败、换区块重来。单个礼包最多换 **10** 个区块,防止在纯海洋地形上死循环。

最终回调实际生成数量。如果没凑够目标数量,控制台会给出提示,建议调大范围或减少数量。

### 地表判定

对每个候选 `(x, z)` 列,从最高方块往下扫最多 8 格:

- 遇到**液体**直接放弃整列;
- 无碰撞的覆盖物(花、草、火把)跳过,继续往下找真地面;
- 地面必须是**完整一格高、有碰撞箱**的方块 —— 用 `BoundingBox#getMaxY` 判断顶面是否恰好等于 `y + 1`,
  以此排除台阶、栅栏、耕地、雪层等异形方块;
- **脚部格**必须是空气或可通行装饰物;唯一例外是恰好 **1 层**的薄雪(实体抬高 2/16 格站到雪面上),
  多层雪或实体方块包围一律拒绝,避免卡在坑里;
- **头部格**(`y + 2`)必须可通行且非液体,保证头顶露天、射线点得到。

实体坐标取方块中心 `(x + 0.5, standOnY, z + 0.5)`,Y 精确对齐站立面。

### 礼包实体

一个**隐形小盔甲架**:

```java
stand.setMarker(false);      // 非 marker,保留可交互性
stand.setVisible(false);     // 本体不可见
stand.setSmall(true);
stand.setGravity(false);
stand.setCanPickupItems(false);
stand.setInvulnerable(true);
stand.setCollidable(false);  // 不与实体发生碰撞
stand.setPersistent(true);
stand.setGlowing(config.isGlowEnabled());
stand.getPersistentDataContainer().set(giftKey, PersistentDataType.BYTE, (byte) 1);
```

头部装备配置的方块材质(`ItemMeta#setItemModel` 设为该方块自身的 key,确保渲染成方块外观)。

同时打上一个 **PDC 标签** `randomgift:is-gift`,作为追踪记录之外的"双保险"——即使 `gift-data.yml`
丢了,插件依然认得出自己的实体。

### 领取

非 marker 盔甲架有**两条**右键事件路径,必须分别处理,否则同一次点击会发两次奖励:

| 情况 | 事件 |
|---|---|
| 空手右键 | `PlayerInteractEntityEvent` |
| 手持物品右键 | `PlayerArmorStandManipulateEvent`(装备交换) |

后者是前者的**子类**,所以父类处理器用 `instanceof` 把它排除掉。

手持物品右键会触发原版"把手上东西戴到盔甲架头上、把箱子头颅挤掉"的行为,因此该事件被**显式取消**,
但仍然正常发奖 —— 玩家拿不走礼包外观。

领取流程:

1. 用 `ConcurrentHashMap.newKeySet()` 对实体 UUID 原子占位,**防止同一 tick 内多事件重复发奖**;
2. 在 `[amountMin, amountMax]` 内随机取整数(`ThreadLocalRandom`);
3. 逐条执行 `commands`,替换 `{player}` 与 `{AMG}` 占位符,以**控制台身份**派发;
4. 移除实体并写盘,输出一条日志(玩家名、随机值、坐标)。

### 保护

`EntityDamageEvent` 对礼包实体一律 `setCancelled(true)` —— 火焰、爆炸、玩家攻击都伤不到它。

### 调度

由主类 `RandomGiftPlugin` 负责,两种模式的调度方式不同:

- **现实时间模式**:每 **1200 tick(60 秒)** 检查一次 `System.currentTimeMillis() - lastSpawnTime`
  是否超过周期;`lastSpawnTime` 持久化在 `gift-data.yml` 里,所以**服务器重启不会重置进度**。
- **游戏 tick 模式**:用 `runTaskLater` 延迟 `period-days × 24000` tick 执行,执行完再排下一周期
  (而不是固定周期轮询),避免任务重叠。

两种模式都用一个 `volatile boolean autoSpawnInProgress` 做重入保护。

### 启动与关闭

- **启用时**:现实时间模式下若从未生成过或已过期,立即执行一次「先清后建」;
  否则只清理上次异常退出残留下来的礼包。
- **禁用时**:取消调度任务,同步清除全部礼包(含卸载区块,失败不阻塞关服),
  写盘后输出清理数量。

### 清理

`clearAllGiftsAsync` 分两个阶段,目的是**让实体数量与追踪记录精确对账**:

1. 遍历所有已加载世界的实体,按 `isOurGift` 判定后删除;
2. 剩下的记录说明礼包在**未加载区块**里 —— 按 `世界 + 区块` 分组,
   用 `getChunkAtAsync(cx, cz, false)`(**`gen=false`,只加载已生成的地形,不触发新地形生成**)异步加载后精确删除。

区块成功加载后,**无论实体是否还在**(可能已被外部清除),对应记录都会移除,保证计数不永久失真。

关闭流程用的是同步版本 `clearAllGifts()`,任何异常都被吞掉并打日志 —— 关服绝不能因为清理失败而卡住。

### 实体识别

`isOurGift` 按三层判定,依次为:

1. **追踪记录**命中 UUID;
2. **PDC 标签** `randomgift:is-gift` 命中;
3. **旧版指纹**兜底 —— 用于识别老版本插件生成、没有 PDC 标签的残留孤儿实体:
   隐形 + 小型 + 无重力 + 不可碰撞 + 头盔是指定方块,且**位于目标世界**(限定世界是为了避免误伤其他世界的装饰性盔甲架)。

---

## 项目结构

```
src/main/java/io/github/randomgift/
├── RandomGiftPlugin.java              # 插件主类:生命周期、调度、生成流程编排
├── command/
│   └── GiftCommand.java               # /gift 指令与 Tab 补全
├── config/
│   └── PluginConfig.java              # config.yml 读取与解析
├── data/
│   └── GiftDataStore.java             # gift-data.yml 持久化(UUID + 世界 + 区块坐标 + 上次生成时间)
├── listener/
│   └── GiftInteractionListener.java   # 右键领取、装备交换拦截、伤害保护
└── manager/
    └── GiftManager.java               # 核心:异步生成、地表判定、精确清理、实体识别

src/main/resources/
├── config.yml                         # 默认配置(带中文注释)
└── plugin.yml                         # 插件描述、指令、权限声明
```

一共 6 个类,职责单一:`PluginConfig` 只管读配置,`GiftDataStore` 只管存数据,
`GiftManager` 承担全部世界操作,`GiftInteractionListener` 只处理事件。

---

## 数据文件

| 文件 | 位置 | 内容 |
|---|---|---|
| `config.yml` | `plugins/RandomGift/` | 用户配置 |
| `gift-data.yml` | `plugins/RandomGift/` | 礼包追踪记录 + 上次生成时间 |

`gift-data.yml` 结构:

```yaml
last-spawn-time: 1730000000000   # 现实时间模式用,毫秒时间戳
gifts:
  0:
    uuid: 1f2e3d4c-...
    world: world
    chunk-x: 12
    chunk-z: -34
```

> 插件兼容旧格式 `gift-entities: [uuid, ...]`(没有区块坐标)。这类记录无法精确定位,清理时会被丢弃并打警告,
> 以免计数永久失真。

---

## 常见问题

**Q:控制台报「自动生成失败:目标世界 xxx 未找到」**

`target-world` 填错了。填世界文件夹名(通常 `world`),或先用 `/gift cr` 在你想放礼包的世界里手动生成。

**Q:礼包数量总是凑不够配置的 `spawn-count`**

在 `spawn-range` 范围内找不到足够的合法地表。常见于纯海洋、超平坦铺满水、或者大量建筑遮挡的地形。
调大 `spawn-range` 或减小 `spawn-count`,控制台会告诉你实际生成了多少。

**Q:礼包找不着**

`glow-enabled: true` 会让它有发光描边,但仍可能被地形挡住。它落在**地表**,不会在地下。
用 `/gift spawn` 在脚下生成一个确认外观。

**Q:改了 `helmet-block` 但旧礼包外观没变**

`/gift reload` 只同步**发光状态**,不会重建已存在的实体。用 `/gift cr` 清掉重新生成。

**Q:玩家反馈右键没反应**

礼包实体是隐形小盔甲架,判定依赖射线点击。如果它被方块包住(比如后续有人盖了房子),就点不到了。
插件的生成阶段已经尽力避免这种位置,但地形被后续改动是无法预料的。

**Q:服务器崩了会不会留下一堆隐形实体?**

不会。实体会在启用时被「先清后建」清掉。另外每个礼包都带 PDC 标签,
即使 `gift-data.yml` 损坏,插件依然能靠标签 + 指纹把孤儿实体认出来。

---

## 许可

本仓库目前**未附带开源许可证**。在添加 LICENSE 之前,默认保留所有权利。
