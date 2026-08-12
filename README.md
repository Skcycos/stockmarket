# 股市风云（Stock Market）

面向 Minecraft 服务器的服务端权威证券市场 Mod，适用于 NeoForge 1.21.1。
玩家可以查看行情、买卖股票、提交限价委托，并通过 K 线和技术指标观察价格走势。

项目的核心原则是：交易规则和账户数据由服务端决定，客户端 UI 只负责展示与发送请求。

## 当前版本

- Mod ID：`stockmarket`
- Mod 版本：`1.0.0`
- Minecraft：`1.21.1`
- NeoForge：`21.1.248`
- Java：`21`
- 客户端 UI：ApricityUI `1.2.1`

## 功能

### 玩家交易

- 行情列表、当前价格、涨跌幅和成交量
- 现金、总资产、可用资金、持仓市值和持仓盈亏
- 持仓明细中的成本价、当前市值、当日盈亏、持仓盈亏和盈亏百分比
- 持仓按盈亏、市值和涨跌幅排序
- 一键卖出全部可用持仓、快速撤销全部委托（均需二次确认）
- 市价买入与卖出
- 限价买入、限价卖出和撤单
- 委托资金与持仓预留，撤单后按分精确退回
- 订单、成交和账户数据持久化
- 玩家离线时限价单保留，重新上线后继续尝试撮合
- 市场总开关、单笔数量上限、价格和资金的服务端校验
- 行业分类、食韵综合指数、公司新闻和事件提示
- 分红、拆股、临时停牌，事件对离线账户上线后补算

### K 线与 UI

- 浅色证券交易界面
- 行情、持仓、委托页面切换
- 行情筛选和排序
- K 线、成交量、MA5、MA10
- 鼠标悬停十字线
- 开盘价、最高价、最低价、收盘价和成交量的彩色 OHLCV 提示
- 高 DPI Canvas 绘制，减少缩放后的模糊问题

### 服务端与扩展

- 股票定义由数据包 JSON 驱动
- 价格模拟、撮合、账户和交易逻辑全部在服务端运行
- 向其他 Mod 开放入金、出金、账户、行情和交易 Java API
- 支持余额变更、委托、成交、价格变化事件
- 支持外部经济 Mod 的 `CurrencyBridge` 适配

## 安装

### 客户端

客户端需要同时安装：

1. `stockmarket-1.0.0.jar`
2. ApricityUI NeoForge 1.21.1 `1.2.1`

ApricityUI 是客户端 UI 运行时依赖。相关文档：[ApricityUI 文档](https://doc.sighs.cc/ApricityUI)。

### 专用服务器

将 `stockmarket-1.0.0.jar` 放入服务器的 `mods` 目录即可。ApricityUI 依赖声明为客户端侧依赖，专用服务器不需要安装 AUI。

构建产物位于：

```text
build/libs/stockmarket-1.0.0.jar
```

## 玩家命令

打开交易界面：

```text
/market
```

常用查询和交易命令：

```text
/market list
/market account
/market buy <股票ID> <数量>
/market sell <股票ID> <数量>
/market order buy <股票ID> <价格> <数量>
/market order sell <股票ID> <价格> <数量>
/market order list
/market order cancel <委托ID>
```

管理员命令：

```text
/market reload
/market reset <玩家>
/market setprice <股票ID> <价格>
```

`reload`、`reset` 和 `setprice` 需要 OP 权限。`setprice` 适合调试或管理行情，生产环境请谨慎使用。

## 默认股票

股票定义位于 `src/main/resources/data/stockmarket/stocks/`：

| ID | 名称 | 行业 | 初始价格 |
| --- | --- | --- | ---: |
| `songzhu` | 松竹银行 | 金融 | 31.20 |
| `zhujia` | 筑家建设 | 建设 | 8.20 |
| `yanhuo` | 烟火食铺 | 消费 | 12.50 |
| `liuyun` | 流云商贸 | 商贸 | 15.30 |
| `changg` | 长歌矿业 | 资源 | 23.60 |
| `qingyun` | 青云科技 | 科技 | 45.80 |

股票 JSON 支持以下字段：

```json
{
  "name": "示例公司",
  "industry": "科技",
  "initialPrice": 10.00,
  "drift": 0.000002,
  "volatility": 0.006
}
```

修改数据包后可以使用 `/market reload` 重载股票定义。已有世界中的价格、历史和账户数据不会因为普通代码构建而被重置。

## 配置

配置文件通常位于：

```text
config/stockmarket-common.toml
```

| 配置项 | 默认值 | 说明 |
| --- | ---: | --- |
| `enabled` | `true` | 是否允许交易；行情查看和撤单仍可用 |
| `initialCash` | `1000.0` | 新玩家初始现金 |
| `feeRate` | `0.001` | 交易手续费率，`0.001` 为 0.1% |
| `tickInterval` | `100` | 行情更新间隔，100 tick 约为 5 秒 |
| `maxOrderQty` | `9999` | 单笔委托最大数量 |
| `marketCycleTicks` | `24000` | 市场周期长度，默认等于一个 Minecraft 日 |
| `newsEventProbability` | `0.25` | 每个周期生成公司新闻的概率 |
| `dividendProbability` | `0.04` | 每个周期触发分红的概率 |
| `splitProbability` | `0.01` | 每个周期触发 2:1 拆股的概率 |
| `haltProbability` | `0.015` | 每个周期触发临时停牌的概率 |
| `haltDurationCycles` | `1` | 临时停牌持续周期数 |
| `dividendPerShare` | `0.05` | 每股分红金额 |
| `indexBaseValue` | `1000.0` | 食韵综合指数基准值 |
| `newsImpactMax` | `0.08` | 公司新闻对价格的最大相对影响 |

所有交易入口都会经过服务端门禁。客户端网络请求、命令和跨 Mod API 不能绕过关闭状态、数量上限、价格、现金或持仓校验。

## 跨 Mod API

公共入口：

```java
com.tanrunn.stockmarket.api.StockMarketApi
```

支持：

- 入金、出金、余额和账户流水
- `requestId` 幂等请求
- 账户、持仓盈亏、订单和成交查询
- 股票行情和历史 K 线查询
- 行业分类、食韵综合指数和最近市场新闻查询（`StockMarketApi.stocks()`、`indices()`、`news()`）
- 市价交易、限价委托和撤单
- `sellAllHoldings()` 一键卖出可用持仓、`cancelAllOrders()` 批量撤销本人委托
- 余额变更、委托、成交、价格变化事件
- 外部货币桥接

金额统一使用整数分，涉及 `ServerPlayer` 的调用必须在服务端主线程执行。出金来源必须先显式注册：

```java
import com.tanrunn.stockmarket.api.StockMarketApi;

var deposit = StockMarketApi.deposit(
        player, 10_00L, "my_quests", "完成每日任务", "daily-player-001");

StockMarketApi.registerWithdrawalSource("my_shop");
var withdraw = StockMarketApi.withdraw(
        player, 5_00L, "my_shop", "购买配方", "shop-order-001");
```

完整说明见：[CROSS_MOD_API.md](CROSS_MOD_API.md)。

## 开发与验证

```bash
# 运行全部单元测试
./gradlew test

# 测试、编译并打包
./gradlew build

# 启动客户端开发环境
./gradlew runClient

# 启动不加载客户端 AUI 的专用服务器开发环境
./gradlew runServer -PserverOnly
```

当前测试覆盖价格模型、交易精度、订单簿、离线委托、行情模拟、K 线计算和跨 Mod API 契约。
GitHub Actions 会在 push 和 pull request 时执行 `./gradlew build`。

## 项目结构

```text
src/main/java/com/tanrunn/stockmarket/
├── api/                  # 跨 Mod 公共 API、快照类型和事件
├── client/integration/   # ApricityUI 页面、K 线和客户端交互
├── common/               # 网络包与客户端/服务端共享数据
├── server/market/        # 行情、账户、撮合、持久化和交易规则
└── server/command/       # /market 命令

src/main/resources/
├── assets/apricityui/    # AUI 页面资源
└── data/stockmarket/     # 股票数据包定义
```

## 许可证

当前项目许可证为 `All Rights Reserved`。未经授权不得重新分发或用于其他项目的发行包。
