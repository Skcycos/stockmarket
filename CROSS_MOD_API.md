# StockMarket 跨 Mod API

StockMarket 提供稳定的服务端 Java API，供任务、商店、NPC、活动和经济类 Mod 快速接入。
公共入口是 `com.tanrunn.stockmarket.api.StockMarketApi`，当前 `API_VERSION` 为 **`4`**。调用方不应直接操作
`AccountData`、`OrderBook` 或 `TradeEngine`。

> **API_VERSION 4 契约变更**：银行桥接金额单位明确为 LC 铜币（1 铜币 = 1 LC `main` core
> value），与证券内部 cents 的换算集中到 `ExchangeRates`（玩家可见规则 **1 证券资金 =
> 1 铜币**，内部存储 1 铜币 = 100 证券 cents）；`CurrencyBridge` 以 `balanceCopper` /
> `BridgeResult.actualCopper` 表达单位；转账改为阶段状态机（`BankTransferPhase`）；
> 资金操作幂等键一律使用 `OperationIds` 生成的内部 opId（业务命名空间隔离），客户端原始
> requestId 只用于转账账本查重/审计。网络协议同步升级为 `4`。

## 银行 ⇄ 证券转账（手动入金 / 出金）

只有**玩家主动入金/出金**才经过银行桥接；买入、卖出、限价单冻结、撤单、撮合、持仓、
分红、股价 tick、市值计算全部只操作 StockMarket 自己的证券账户，绝不调用桥。

- 桥接由经济 Mod 通过 `StockMarketApi.registerCurrencyBridge(new CurrencyBridge ...)` 注册；
  StockMarket 侧配置 `bankBridgeId`（默认 `server_menu:lc_bank_main`）选择要用的桥。
- 客户端 C2S：`BankTransferRequestC2S{ direction, requestedCopper | requestedSecuritiesCents, requestId }`
  （严格 codec：非法枚举、负的请求金额、超长 requestId 解码即拒）。服务端主线程执行，
  受冷却与金额上限限制；客户端只携带原始请求，不携带余额/结果。

### 兑换规则（1 证券资金 = 1 铜币）

- 入金：LC 扣 N 铜币 → 证券显示 +N（内部 `ExchangeRates.copperToSecuritiesCents(N)` = N×100 cents）；
  ×100 溢出必须检查。
- 出金：请求证券 R cents → `copper = ExchangeRates.securitiesCentsToCopperCeil(R)`（向上取整到整数铜币），
  证券<b>实际扣</b> `copper×100` cents，ATM 到账 `copper` 铜币；余额不足以支付取整后的
  实际扣款时拒绝（防止小数出金凭空增发铜币）。

| 出金请求 | copper | 证券实际扣 | ATM 到账 |
|---|---|---|---|
| 1.00   | 1 | 1.00 | 1 铜币 |
| 1.01   | 2 | 2.00 | 2 铜币 |
| 1.50   | 2 | 2.00 | 2 铜币 |
| 2.00   | 2 | 2.00 | 2 铜币 |

### 阶段状态机与幂等

`BankTransferRecord` 持久化（上限 256）：原始 requestId、方向、phase、status、三个金额
（`requestedSecuritiesCents` / `actualDebitCents` / `copperAmount`）、内部 opId、余额、审计消息。
阶段：`PREPARED → SOURCE_DEBITED → DESTINATION_CREDITED → COMPLETED`；失败走
`COMPENSATED / COMPENSATION_FAILED / MANUAL_REVIEW`。

- <b>write-ahead 阶段（v2）</b>：任何资金副作用前先持久化“意图”——
  PREPARED →（扣来源）→ SOURCE_DEBITED → DESTINATION_CREDIT_PENDING →（入目标）→
  DESTINATION_CREDITED → COMPLETED；目标失败 → COMPENSATION_PENDING →（补偿）→
  COMPENSATED / COMPENSATION_FAILED；证据不足 → MANUAL_REVIEW。<b>不做单边推断</b>。
- 入金失败退<b>原整数铜币</b>；出金 LC 入账失败向证券补偿<b>完整实际扣款</b>。
- COMPLETED 重放不重复动账；COMPENSATED 重放不重复补偿；COMPENSATION_FAILED /
  MANUAL_REVIEW 重放不再动账（人工审计）。
- <b>恢复一律不自动调用 LC（v2）</b>：LC 内存幂等账本可能 LRU 淘汰（≤2048 条，同
  runtimeEpoch 也不能证明某 opId 仍在），因此 runtimeEpoch 仅作审计。允许的自动恢复：
  DESTINATION_CREDIT_PENDING 且目标为证券 → 用账本内 opSecuritiesCredit 持久幂等重试；
  COMPENSATION_PENDING 且补偿目标为证券 → 用账本内 opRollback 持久幂等重试；目标/补偿为
  LC、旧版 SOURCE_DEBITED、字段畸形 → MANUAL_REVIEW 且零资金调用。
- <b>持久化防重墓碑（v2）</b>：`AccountData.transferTombstones`（requestId → 安全终态记录，
  不可淘汰）保证详细记录即使淘汰到 256 条之外，旧 requestId 依旧返回重复/冲突、零资金调用；
  NBT 阻断并保留可读。<b>旧版安全终态在反序列化时自动补建墓碑</b>；淘汰详细行前校验墓碑存在且
  方向/金额指纹一致；同 requestId 指纹冲突 → MANUAL_REVIEW 且保留原合法指纹。</b>
- <b>REJECTED（第五轮）</b>：明确“无净资金变化”的安全拒绝终态（INSUFFICIENT_FUNDS /
  UNAVAILABLE / INVALID_* / REQUEST_CONFLICT / RATE_LIMITED / WRONG_THREAD / QUARANTINED /
  PARTIAL_OPERATION（仅确认全额冲回）），建立墓碑、可只读上幂等重放；COMPENSATION_FAILED 与
  actualCopper>0 的失败绝不 REJECTED → COMPENSATION_FAILED/MANUAL_REVIEW。旧版
  PREPARED+明确未动账失败迁移为 REJECTED。
- <b>WAL（第五/六轮：生产恢复闭环）</b>：`server/transfer/FileTransferWal` 在世界数据目录
  追加 + flush + `FileChannel.force(true)` 持久化资金意图；资金副作用前必须 WAL+账本都落盘
  成功（每次 write-ahead 校验返回值），失败 fail closed、零资金调用。`setData()` 仅更新内存
  附件，不声明为同步落盘。
  - <b>复合键</b>：全部索引/隔离/压缩/恢复/冲突用 `TransferKey(playerUUID, requestId)`，
    双玩家同 requestId 完全隔离；损坏行若能归属仅隔离该键，否则全局隔离（银行转账全部
    fail closed，普通证券不受影响）。
  - <b>生产入口对账（第七轮）</b>：`ReconciledBankTransferLedger` 查找优先级 = WAL 全局隔离
    （抛 blocked，global→UNAVAILABLE）→ WAL 键隔离（global=false→MANUAL_REVIEW）→ WAL 最新
    与附件经 `decide` 对账 → 附件详细 → 附件墓碑；冷却 known 判断含 WAL；WAL 恢复用 WAL 内
    opId。<b>对账规则</b>：任一侧记录未过 Validator → MANUAL_REVIEW；附件缺失 WAL 存在 →
    WAL 恢复；WAL 缺失附件安全终态 → 只读、非安全终态 → MANUAL_REVIEW；防重指纹冲突 →
    MANUAL_REVIEW；任一侧 MANUAL_REVIEW → 保守优先（绝不被 pending/completed 覆盖）；WAL 阶段
    在 `TransferPhases` 状态图上合法领先/等于附件 → 采用 WAL；附件领先/不同分支/无法证明 →
    MANUAL_REVIEW（零资金）。登录对账只对裁决 USE_WAL 安全写回，绝不无条件覆盖附件证据，
    不调用 LC/证券。
  - <b>隔离跨压缩/重启（第七/八轮）</b>：存在任何 keyed/global 隔离证据时拒绝压缩，保留原
    WAL 完整证据（损坏行与校验和），重启后继续 fail closed；压缩输出按原 seq 严格升序；压缩
    读取中途异常整体失败不替换原文件；重复 seq 隔离首次与本次涉及的所有键；超长行只消费当前
    行不吞下一行；损坏行可提取的正数 seq 计入水位（append 用 max+1、Math.addExact 溢出即全局
    fail closed）；append/flush/force 失败后当前实例 poisoned（global quarantine+不可写，
    后续转账 UNAVAILABLE/资金 0，普通股票交易不受影响）。
  - <b>持久隔离 marker（第八轮）</b>：对账产生的 MANUAL_REVIEW_BLOCK 会把带校验和、seq、
    复合键、有界原因枚举的 marker append+flush+force 写入 WAL（吸收态：重启/压缩/登录写回
    不得解除）；marker 持久化失败 → 抛 blocked(global) fail closed（不得继续用原 pending 自动
    恢复）。<b>恢复身份</b>：可能触发自动证券恢复时严格比较 providerId/operationIdVersion/
    stateMachineVersion/runtimeEpoch/方向所需全部 opId（`recoveryIdentityMatches`），任一不同
    → 持久 keyed quarantine；旧版安全终态仅只读。阶段图统一为 `TransferPhases`。写到 WAL 文件
    （服务端启动绑定世界路径加载、停止关闭清空、切世界重建）。
  - <b>收尾（第 9 轮）</b>：可能自动恢复证券资金的路径（DESTINATION_CREDIT_PENDING /
    COMPENSATION_PENDING 目标证券）恢复前必须校验当前桥存在、可用、id 非空且与持久记录
    providerId 一致，否则 MANUAL_REVIEW 且资金 0（WAL 单独存在、附件丢失同样适用；安全终态
    只读不要求桥可用；不重算 opId）。全局（跨 key）seq 倒退 fail closed。行计数在加载时统计
    一次、append 在内存 O(1) 更新（正常转账写入不再整文件重扫）；压缩失败/被拒后写次数退避。
- <b>跨重启恢复（runtimeEpoch）</b>：转账记录持久化创建它的
  <code>runtimeEpoch</code>（每服务器进程随机）与 <code>providerId</code> /
  <code>operationIdVersion</code>。SOURCE_DEBITED 恢复：
  - 同 epoch（LC 内存幂等账本仍有效）→ 用账本内 opId 幂等恢复目标，目标暂不可用返回
    RECOVERY_REQUIRED；
  - 跨 epoch 出金（目标 LC）→ <b>绝不自动再次 deposit</b>，转 MANUAL_REVIEW；
  - 跨 epoch 入金（目标证券）→ 仅当证券 opId 持久幂等可靠时补证券入账，失败则
    MANUAL_REVIEW 且不动银行；
  - providerId 不匹配 / operationIdVersion 未识别 / opId 缺失/超长/前缀不符 / 金额
    不变量不符 / 阶段状态矛盾 / epoch 缺失且非安全终态 → 一律 MANUAL_REVIEW。
- 保留期内重放精确一致；**不承诺永久防重 / 不承诺跨硬崩溃 exactly-once**。
  畸形记录保留供审计，但绝不自动动账。

## 账户入金与出金

所有金额使用整数分，避免浮点误差。调用必须发生在服务端主线程，并且玩家必须在线。

```java
import com.tanrunn.stockmarket.api.StockMarketApi;

// requestId 可用于任务重试，重复请求不会重复入金
var result = StockMarketApi.deposit(
        player, 10_00L, "my_quests", "完成每日任务", "daily-2026-08-11-player");

// 出金来源必须在 Mod 初始化时显式注册
StockMarketApi.registerWithdrawalSource("my_shop");
var payout = StockMarketApi.withdraw(
        player, 5_00L, "my_shop", "购买高级配方", "shop-order-1001");
```

结果包含成功状态、提示、最新余额、交易 ID 和是否为幂等重复请求。每笔入金/出金都会持久化到
账户流水，并发布 `BalanceChangedEvent`。来源 Mod 应使用稳定的 `source`，方便审计。
入金不需要注册；出金必须使用已注册的受信任来源，避免普通调用方意外扣款。

## 行情与账户查询

```java
var allStocks = StockMarketApi.stocks();
var quote = StockMarketApi.quote("qingyun");
var account = StockMarketApi.account(player);
var orders = StockMarketApi.orders(player);
var trades = StockMarketApi.trades(player);
var indices = StockMarketApi.indices();
var news = StockMarketApi.news();
```

`StockQuote` 和 `AccountSnapshot` 的金额字段均为分。行情历史使用 API 自己的
`CandleSnapshot`，订单和成交分别使用 `OrderSnapshot`、`TradeSnapshot`；适配 Mod 不需要依赖
`common` 内部数据结构。涉及 `ServerPlayer` 的账户、持仓、订单、成交和流水查询也必须在服务端主线程执行。

## 交易与委托

```java
var market = StockMarketApi.marketOrder(player, "qingyun", true, 10);
var limit = StockMarketApi.limitOrder(player, "qingyun", true, 4_580L, 10);
var cancel = StockMarketApi.cancelOrder(player, limit.orderId());
var liquidate = StockMarketApi.sellAllHoldings(player);
var cancelAll = StockMarketApi.cancelAllOrders(player);
```

这些调用复用服务端已有的开关、数量、价格、现金、持仓、停牌和订单归属校验；客户端不能绕过这些校验。

`sellAllHoldings` 只卖出未被限价卖单冻结的可用持仓，并自动按服务器的单笔数量上限分批成交。
`cancelAllOrders` 只撤销当前玩家自己的委托，并逐笔退还预留资金或持仓。

`StockQuote` 额外提供 `industry`、`halted` 和 `haltRemainingCycles`；停牌股票仍可查询行情，
但市价单和限价单会被服务端拒绝。`indices()` 返回当前综合指数，`news()` 返回最近的公司新闻、
分红、拆股和停牌提示。

## 打开交易界面

```java
import com.tanrunn.stockmarket.api.StockMarketApi;

// 打开该玩家的股市交易界面（AUI 面板）。必须在服务端主线程调用，玩家必须在线。
StockMarketApi.openPanel(player);
```

内部会登记 viewer 并下发 `openPanel=true` 的行情快照，客户端据此打开界面并开始接收推送；
玩家关闭界面后客户端会自动回报并移除 viewer。

## 事件

其他 Mod 可以注册 NeoForge 事件监听：

- `BalanceChangedEvent`：入金/出金提交后触发
- `OrderEvent`：限价委托创建或撤销后触发
- `TradeEvent`：市价成交或限价单成交后触发
- `PriceChangedEvent`：股票价格变化后触发

```java
NeoForge.EVENT_BUS.addListener((BalanceChangedEvent event) -> {
    // 给完成交易的玩家发放成就或记录外部统计
});
```

事件金额字段同样使用整数分。事件是提交后的通知，不建议在监听器中再次修改证券账户。

## 经济 Mod 桥接

实现 `CurrencyBridge`（`API_VERSION 4` 起为 UUID 玩家标识 + `BridgeResult` 精确结果 +
**铜币单位** + requestId/opId 幂等）后，可以向 `StockMarketApi.registerCurrencyBridge` 注册外部货币适配器
（重复 id 注册抛异常）。桥接器只负责自己的货币余额、扣款和返还；证券账户入金仍通过 `deposit` 完成，
桥接器属于受信任的服务端 Mod。

桥接实现约定：

```java
public interface CurrencyBridge {
    String id();
    String displayName();
    boolean isAvailable();
    long balanceCopper(UUID playerId);             // 余额单位 = 铜币
    BridgeResult withdraw(UUID playerId, long copper, String source, String reason, String requestId);
    BridgeResult deposit(UUID playerId, long copper, String source, String reason, String requestId);
}
```

- 只允许服务端主线程调用；失败绝不得抛异常，一律返回 `BridgeResult`（fail closed）。
- 扣款/入账必须精确：发生部分扣款时桥接内部先全额补偿再返回 `PARTIAL_OPERATION`（不得返回成功），
  补偿失败返回 `COMPENSATION_FAILED` 并输出 critical/error 日志。
- `requestId` 必须是内部 opId（`OperationIds` 生成）：同 opId 同金额重放返回首次结果、
  不重复扣款；同 opId 不同金额/方向返回 `REQUEST_CONFLICT`；不得把客户端原始 requestId 直接传来。
- 单位：1 铜币 = 1 LC `main` core value；与证券内部 cents 的换算由 `ExchangeRates` 负责
  （1 铜币 = 100 证券 cents 的内部存储换算；玩家可见 1 证券资金 = 1 铜币）。

## 依赖方式

适配 Mod 在开发环境中依赖 StockMarket 的 Maven/JAR，并在 `neoforge.mods.toml` 中声明：

```toml
[[dependencies.yourmod]]
    modId="stockmarket"
    type="required"
    versionRange="[1.0.0,)"
    ordering="AFTER"
    side="SERVER"
```

公共 API 版本通过 `StockMarketApi.API_VERSION` 标识。内部服务类不属于兼容承诺范围。

## 脚本与 KubeJS 适配

StockMarket 不强制依赖 KubeJS 或任何经济 Mod。若服务器已经安装脚本桥接层，脚本可以通过其 Java
互操作能力调用 `StockMarketApi` 的公开静态方法；建议仍使用稳定的 `requestId`，并把出金来源先注册为
明确的 Mod 标识。若脚本环境不能安全访问 `ServerPlayer` 或服务端主线程，应由一个很薄的服务端 Mod
适配层接收脚本请求，再调用本 API。
