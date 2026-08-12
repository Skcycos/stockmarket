# StockMarket 跨 Mod API

StockMarket 提供稳定的服务端 Java API，供任务、商店、NPC、活动和经济类 Mod 快速接入。
公共入口是 `com.tanrunn.stockmarket.api.StockMarketApi`，当前 `API_VERSION` 为 `2`。调用方不应直接操作
`AccountData`、`OrderBook` 或 `TradeEngine`。

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

实现 `CurrencyBridge` 后，可以向 `StockMarketApi.registerCurrencyBridge` 注册外部货币适配器。
桥接器只负责自己的货币余额、扣款和返还；证券账户入金仍通过 `deposit` 完成。桥接器属于受信任的
服务端 Mod，尤其要谨慎实现出金失败时的补偿逻辑。

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
