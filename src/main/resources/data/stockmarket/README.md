# 股市风云 · 数据包说明

命名空间：`stockmarket`。股票定义由数据包驱动，`/market reload` 热重载。

## stocks/（股票定义）

```json
{
  "name": "烟火食铺",
  "initialPrice": 12.50,
  "drift": 0.0002,
  "volatility": 0.020
}
```

- 文件名 = 股票 id（小写英文，如 `yanhuo.json` → id `yanhuo`）
- `name`：显示名（中文）
- `initialPrice`：初始/基准价
- `drift`：每步漂移（正=长期看涨，负=看跌）
- `volatility`：每步波动率（越大越刺激，建议 0.01~0.05）

价格每 `tickInterval`（默认 100 tick ≈ 5 秒）更新一次：`price *= exp(drift + vol * 高斯噪声)`，四舍五入到分。

## 内置股票

| id | 名称 | 初始价 | 波动率 |
|----|------|-------:|------:|
| yanhuo | 烟火食铺 | 12.50 | 0.020 |
| zhujia | 筑家建设 | 8.20 | 0.018 |
| changg | 长歌矿业 | 23.60 | 0.030 |
| liuyun | 流云商贸 | 15.30 | 0.024 |
| qingyun | 青云科技 | 45.80 | 0.042 |
| songzhu | 松竹银行 | 31.20 | 0.010 |

## 玩法与命令

- `/market`：打开 AUI 行情面板（行情列表 + K线 + 买卖 + 我的委托，自动推送）
- `/market list` / `/market account`：聊天查看行情 / 账户
- `/market buy <股票> <数量>` / `/market sell <股票> <数量>`：市价单（立即按现价成交）
- `/market order buy|sell <股票> <价格> <数量>`：限价单（挂单；价格触发即成交，整单成交）
- `/market order cancel <单号>`：撤单并退还预留资金/股票
- `/market order list`：查看我的委托
- OP：`/market reload`（重载股票）、`/market reset <玩家>`（重置账户）、`/market setprice <股票> <价格>`（改价并触发撮合）

## 持久化

- 玩家账户（现金/持仓）：随玩家存档（Attachment）
- 股票价格 + 日 K 历史 + 委托簿：世界 SavedData（`stockmarket`）
- 新开世界自动生成 30 根历史 K 线，开服即有图
