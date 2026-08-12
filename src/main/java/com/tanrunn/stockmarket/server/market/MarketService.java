package com.tanrunn.stockmarket.server.market;

import com.tanrunn.stockmarket.Config;
import com.tanrunn.stockmarket.api.event.OrderEvent;
import com.tanrunn.stockmarket.api.event.PriceChangedEvent;
import com.tanrunn.stockmarket.api.event.TradeEvent;
import com.tanrunn.stockmarket.common.AccountInfo;
import com.tanrunn.stockmarket.common.MarketIndexInfo;
import com.tanrunn.stockmarket.common.MarketNews;
import com.tanrunn.stockmarket.common.OrderInfo;
import com.tanrunn.stockmarket.common.StockInfo;
import com.tanrunn.stockmarket.common.TradeInfo;
import com.tanrunn.stockmarket.common.network.MarketSnapshotC2S;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Server-authoritative market simulation: prices evolve on a global tick
 * interval, trades execute at the current price, limit orders fill when the
 * price crosses them, account state is per-player.
 */
public final class MarketService {
    private static MarketService INSTANCE;

    private final Map<String, Stock> stocks = new LinkedHashMap<>();
    private final Random random = new Random();
    private final Set<UUID> viewers = new HashSet<>();
    private MarketSavedData savedData;
    private long tickCount;
    private long lastDayIndex = Long.MIN_VALUE;
    private double previousIndexValue;
    private double currentIndexValue;

    public record LimitOrderPlacement(boolean success, String message, HoldingAccount account, long orderId) {
    }

    private MarketService() {
    }

    public static MarketService get() {
        return INSTANCE;
    }

    public static void init(MinecraftServer server) {
        MarketService service = new MarketService();
        service.savedData = MarketSavedData.load(server.overworld());
        service.loadStocks();
        service.lastDayIndex = service.marketDay(server);
        service.currentIndexValue = service.calculateIndexValue();
        service.previousIndexValue = service.currentIndexValue;
        INSTANCE = service;
    }

    private void loadStocks() {
        for (StockRegistry.Definition def : StockRegistry.get().definitions()) {
            MarketSavedData.StockState state = savedData.get(def.id());
            double price = state != null ? state.price() : def.initialPrice();
            double open = state != null ? state.dayOpen() : def.initialPrice();
            double prev = state != null ? state.prevClose() : def.initialPrice();
            double high = state != null ? state.dayHigh() : price;
            double low = state != null ? state.dayLow() : price;
            long volume = state != null ? state.volume() : 0;
            java.util.List<com.tanrunn.stockmarket.common.Candle> history =
                    state != null ? state.history() : seedHistory(def);
            Stock stock = new Stock(def.id(), def.name(), def.initialPrice(), def.drift(), def.volatility(),
                    price, open, prev, high, low, volume, history, def.industry(),
                    state != null && state.halted(), state != null ? state.haltRemainingCycles() : 0);
            if (state != null && state.referencePrice() > 0) stock.setReferencePrice(state.referencePrice());
            stocks.put(def.id(), stock);
        }
        currentIndexValue = calculateIndexValue();
        if (!Double.isFinite(previousIndexValue) || previousIndexValue <= 0) previousIndexValue = currentIndexValue;
    }

    /** Rebuilds stock instances after a datapack reload, keeping persisted prices. */
    public void reloadStocks() {
        stocks.clear();
        loadStocks();
    }

    /** Fresh world: synthesize ~30 back-candles so the K-line chart has data on day one. */
    private java.util.List<com.tanrunn.stockmarket.common.Candle> seedHistory(StockRegistry.Definition def) {
        java.util.List<com.tanrunn.stockmarket.common.Candle> candles = new java.util.ArrayList<>();
        double price = def.initialPrice();
        for (int i = 0; i < 30; i++) {
            double open = price;
            double close = PriceModel.nextPrice(price, def.drift(), def.volatility(), random);
            double high = Math.max(open, close) * (1 + random.nextDouble() * 0.012);
            double low = Math.min(open, close) * (1 - random.nextDouble() * 0.012);
            candles.add(new com.tanrunn.stockmarket.common.Candle(i, open, close,
                    PriceModel.round(high), PriceModel.round(low), random.nextInt(4000) + 500));
            price = close;
        }
        return candles;
    }

    /** Call once per server tick. */
    public void tick(MinecraftServer server) {
        tickCount++;
        long day = marketDay(server);
        if (lastDayIndex != Long.MIN_VALUE && day != lastDayIndex) {
            stocks.values().forEach(stock -> stock.rollDay(lastDayIndex));
            stocks.values().forEach(Stock::advanceHaltCycle);
            resetDailyBaselines(server, day);
            runMarketCycleEvents(server, day);
        }
        lastDayIndex = day;

        int interval = Config.TICK_INTERVAL.get();
        if (interval <= 0 || tickCount % interval != 0) return;
        double beforeIndex = calculateIndexValue();
        for (Stock stock : stocks.values()) {
            if (stock.halted()) continue;
            updatePrice(stock, PriceModel.nextPrice(stock.price(), stock.drift(), stock.volatility(), random));
            matchOrders(stock, server);
        }
        previousIndexValue = beforeIndex;
        currentIndexValue = calculateIndexValue();
        pushToViewers(server, null);
    }

    private void matchOrders(Stock stock, MinecraftServer server) {
        // 撮合的唯一入口：关闭期间禁止一切成交，任何路径（tick、placeOrder、
        // 管理员 /market setprice 改价）都不得触发限价单撮合。
        if (!Config.ENABLED.get() || stock.halted()) {
            return;
        }
        OrderBook book = savedData.orderBook();
        if (book.size() == 0) return;
        book.match(stock.id(), stock.price(), (order, fillPrice) -> {
            ServerPlayer player = server.getPlayerList().getPlayer(order.player());
            if (player == null) {
                // 玩家离线：不成交、不移除订单。预留的资金/持仓继续留在玩家账上，
                // 玩家重新上线后，后续撮合轮次会再次尝试成交（或玩家主动撤单退款）。
                return false;
            }
            HoldingAccount account = AccountService.get(player);
            HoldingAccount updated;
            if (order.buy()) {
                double reservedCash = order.reservedCash() > 0
                        ? order.reservedCash()
                        : TradeEngine.buyReservation(order.price(), order.quantity(), Config.FEE_RATE.get());
                updated = TradeEngine.fillBuy(account, order.stockId(), order.quantity(), reservedCash);
            } else {
                updated = TradeEngine.fillSell(account, order.stockId(), fillPrice, order.quantity(),
                        Config.FEE_RATE.get(), order.reservedCostBasis());
            }
            AccountService.set(player, updated);
            AccountService.recordTrade(player, new TradeInfo(
                    marketDay(server),
                    order.stockId(), order.buy(), fillPrice, order.quantity(),
                    TradeEngine.feeFor(fillPrice, order.quantity(), Config.FEE_RATE.get())));
            stock.addVolume(order.quantity());
            NeoForge.EVENT_BUS.post(new TradeEvent(player, order.id(), order.stockId(), order.buy(),
                    cents(fillPrice), order.quantity(),
                    cents(TradeEngine.feeFor(fillPrice, order.quantity(), Config.FEE_RATE.get())), true));
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§a委托成交§r：" + (order.buy() ? "买入" : "卖出") + " " + order.quantity()
                            + " 股 @" + String.format("%.2f", fillPrice) + "（" + stock.name() + "）"));
            return true;
        });
    }

    public void setPrice(String id, double price, MinecraftServer server) {
        Stock stock = stocks.get(id);
        if (stock != null && !stock.halted()) {
            updatePrice(stock, PriceModel.round(price));
            matchOrders(stock, server);
        }
    }

    private void updatePrice(Stock stock, double price) {
        double oldPrice = stock.price();
        stock.setPrice(price);
        if (Double.compare(oldPrice, stock.price()) != 0) {
            NeoForge.EVENT_BUS.post(new PriceChangedEvent(stock.id(), cents(oldPrice), cents(stock.price())));
        }
    }

    // ---- viewer tracking & auto push ----

    public void addViewer(UUID uuid) {
        viewers.add(uuid);
    }

    public void removeViewer(UUID uuid) {
        viewers.remove(uuid);
    }

    public boolean isViewer(UUID uuid) {
        return viewers.contains(uuid);
    }

    public void pushToViewers(MinecraftServer server, String message) {
        if (viewers.isEmpty()) return;
        for (UUID uuid : viewers) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                sendSnapshot(player, false, message);
            }
        }
    }

    // ---- quotes & trading ----

    public List<StockInfo> snapshot() {
        return stocks.values().stream().map(Stock::info).toList();
    }

    public List<MarketIndexInfo> indices() {
        double value = currentIndexValue > 0 ? currentIndexValue : calculateIndexValue();
        double change = previousIndexValue <= 0 ? 0 : (value - previousIndexValue) / previousIndexValue * 100.0;
        return List.of(new MarketIndexInfo("market", "食韵综合指数", value, change));
    }

    public List<MarketNews> news() {
        return savedData == null ? List.of() : savedData.news();
    }

    public Stock stock(String id) {
        return stocks.get(id);
    }

    public boolean has(String id) {
        return stocks.containsKey(id);
    }

    private double priceOf(String stockId) {
        Stock stock = stocks.get(stockId);
        return stock == null ? 0 : stock.price();
    }

    public AccountInfo accountInfo(ServerPlayer player) {
        HoldingAccount account = normalizedAccount(player);
        double availableHoldingsValue = account.holdingsValue(this::priceOf);
        double unrealizedPnl = account.unrealizedPnl(this::priceOf);
        double reservedCash = 0;
        double reservedHoldingsValue = 0;
        int availableHoldingsQuantity = account.holdings().values().stream()
                .mapToInt(quantity -> Math.max(0, quantity)).sum();
        int reservedHoldingsQuantity = 0;
        List<OrderBook.Order> playerOrders = savedData.orderBook().ordersOf(player.getUUID());
        for (OrderBook.Order order : playerOrders) {
            if (order.buy()) {
                // Reserved buy cash remains part of total assets until the order
                // fills or is cancelled; only the fee becomes a real cost on fill.
                reservedCash += order.reservedCash() > 0
                        ? order.reservedCash()
                        : TradeEngine.buyReservation(order.price(), order.quantity(), Config.FEE_RATE.get());
            } else {
                double reservedValue = priceOf(order.stockId()) * order.quantity();
                reservedHoldingsValue += reservedValue;
                reservedHoldingsQuantity += order.quantity();
                unrealizedPnl += reservedValue - order.reservedCostBasis();
            }
        }
        availableHoldingsValue = round2(availableHoldingsValue);
        reservedHoldingsValue = round2(reservedHoldingsValue);
        double holdingsValue = round2(availableHoldingsValue + reservedHoldingsValue);
        unrealizedPnl = round2(unrealizedPnl);
        double totalValue = round2(account.cash() + holdingsValue + reservedCash);
        long day = currentDay(player);
        AccountService.ensureDailyBaseline(player, day, totalValue);
        double dailyPnl = round2(totalValue - AccountService.dailyBaseline(player));
        double totalPnl = round2(account.realizedPnl() + unrealizedPnl);
        List<OrderInfo> orders = playerOrders.stream()
                .map(o -> new OrderInfo(o.id(), o.stockId(), o.buy(), o.price(), o.quantity()))
                .toList();
        return new AccountInfo(account.cash(), totalValue, holdingsValue, unrealizedPnl, account.realizedPnl(),
                dailyPnl, totalPnl, round2(reservedCash), availableHoldingsValue, reservedHoldingsValue,
                availableHoldingsQuantity, reservedHoldingsQuantity, account.holdings(), account.costBasis(),
                orders, AccountService.trades(player));
    }

    public TradeEngine.Result trade(ServerPlayer player, String stockId, int quantity, boolean buy) {
        TradeEngine.Result gate = entryGate(quantity);
        if (gate != null) {
            return gate;
        }
        Stock stock = stocks.get(stockId);
        if (stock == null) {
            return new TradeEngine.Result(false, "未知股票：" + stockId, null, 0);
        }
        if (stock.halted()) {
            return new TradeEngine.Result(false, "股票停牌中，暂不可交易：" + stock.name(), null, 0);
        }
        HoldingAccount account = normalizedAccount(player);
        TradeEngine.Result result = buy
                ? TradeEngine.buy(account, stockId, stock.price(), quantity, Config.FEE_RATE.get())
                : TradeEngine.sell(account, stockId, stock.price(), quantity, Config.FEE_RATE.get());
        if (result.success() && result.account() != null) {
            AccountService.set(player, result.account());
            AccountService.recordTrade(player, new TradeInfo(
                    currentDay(player), stockId, buy, stock.price(), quantity, result.fee()));
            stock.addVolume(quantity);
            NeoForge.EVENT_BUS.post(new TradeEvent(player, -1, stockId, buy, cents(stock.price()), quantity,
                    cents(result.fee()), false));
        }
        return result;
    }

    /** Compatibility result for commands and network handlers. */
    public TradeEngine.Result placeOrder(ServerPlayer player, String stockId, boolean buy, double price, int quantity) {
        LimitOrderPlacement placement = placeLimitOrder(player, stockId, buy, price, quantity);
        return new TradeEngine.Result(placement.success(), placement.message(), placement.account(), 0);
    }

    /** Places a limit order with a stable order id for cross-Mod callers. */
    public LimitOrderPlacement placeLimitOrder(ServerPlayer player, String stockId, boolean buy,
                                               double price, int quantity) {
        TradeEngine.Result gate = entryGate(quantity);
        if (gate != null) {
            return new LimitOrderPlacement(false, gate.message(), null, -1);
        }
        // 服务端权威价格校验：网络包/命令传入的价格都不可信。
        // NaN、±Infinity 与 < 0.01 一律拒绝（价格至少为 0.01）。
        String priceError = TradeEngine.validatePrice(price);
        if (priceError != null) {
            return new LimitOrderPlacement(false, priceError, null, -1);
        }
        // 先统一按分四舍五入，再执行预留：保证预留金额、订单保存价格、
        // 撤单退款三者使用同一个价格，杜绝 10.009 预留/10.01 退款的资金误差。
        price = PriceModel.round(price);
        Stock stock = stocks.get(stockId);
        if (stock == null) {
            return new LimitOrderPlacement(false, "未知股票：" + stockId, null, -1);
        }
        if (stock.halted()) {
            return new LimitOrderPlacement(false, "股票停牌中，暂不可挂单：" + stock.name(), null, -1);
        }
        HoldingAccount account = normalizedAccount(player);
        double reservedCostBasis = buy ? 0 : TradeEngine.costBasisForSale(account, stockId, quantity);
        TradeEngine.Result result = buy
                ? TradeEngine.reserveBuy(account, price, quantity, Config.FEE_RATE.get())
                : TradeEngine.reserveSell(account, stockId, quantity);
        if (!result.success()) {
            return new LimitOrderPlacement(false, result.message(), result.account(), -1);
        }
        AccountService.set(player, result.account());
        double reservedCash = buy ? TradeEngine.buyReservation(price, quantity, Config.FEE_RATE.get()) : 0;
        long orderId = savedData.orderBook().place(player.getUUID(), stockId, buy, PriceModel.round(price), quantity,
                reservedCostBasis, reservedCash);
        NeoForge.EVENT_BUS.post(new OrderEvent(player, OrderEvent.Type.PLACED, orderId, stockId, buy,
                cents(price), quantity));
        // 挂单后立刻尝试撮合一次（可能价格已经越过限价）
        matchOrders(stock, player.server);
        return new LimitOrderPlacement(true, (buy ? "已挂买单 #" : "已挂卖单 #") + orderId,
                result.account(), orderId);
    }

    /**
     * 所有交易入口（客户端网络市价、/market buy、/market sell、限价买单、限价卖单）
     * 的统一服务端门禁：关闭时拒绝一切交易，且每笔委托数量不得超过上限。
     * 通过返回 null，失败时返回带明确提示的 Result。
     */
    private TradeEngine.Result entryGate(int quantity) {
        String error = TradeEngine.validateEntry(Config.ENABLED.get(), quantity, Config.MAX_ORDER_QTY.get());
        return error == null ? null : new TradeEngine.Result(false, error, null, 0);
    }

    /** Cancels a limit order and refunds the reservation. */
    public TradeEngine.Result cancelOrder(ServerPlayer player, long orderId) {
        OrderBook.Order order = savedData.orderBook().get(orderId);
        if (order == null || !order.player().equals(player.getUUID())) {
            return new TradeEngine.Result(false, "找不到委托 #" + orderId, null, 0);
        }
        order = savedData.orderBook().cancel(orderId);
        HoldingAccount account = AccountService.get(player);
        HoldingAccount updated = order.buy()
                ? (order.reservedCash() > 0
                        ? TradeEngine.refundBuy(account, order.reservedCash())
                        : TradeEngine.refundBuy(account, order.price(), order.quantity(), Config.FEE_RATE.get()))
                : TradeEngine.refundSell(account, order.stockId(), order.quantity(), order.reservedCostBasis());
        AccountService.set(player, updated);
        NeoForge.EVENT_BUS.post(new OrderEvent(player, OrderEvent.Type.CANCELLED, order.id(), order.stockId(),
                order.buy(), cents(order.price()), order.quantity()));
        return new TradeEngine.Result(true, "已撤单 #" + orderId, updated, 0);
    }

    /** Cancels every outstanding order owned by the player and refunds each reservation. */
    public TradeEngine.Result cancelAllOrders(ServerPlayer player) {
        List<OrderBook.Order> orders = savedData.orderBook().ordersOf(player.getUUID());
        if (orders.isEmpty()) {
            return new TradeEngine.Result(true, "暂无可撤销委托", AccountService.get(player), 0);
        }
        int cancelled = 0;
        HoldingAccount account = AccountService.get(player);
        for (OrderBook.Order order : orders) {
            TradeEngine.Result result = cancelOrder(player, order.id());
            if (result.success()) {
                cancelled++;
                account = result.account();
            }
        }
        return new TradeEngine.Result(true, "已撤销 " + cancelled + " 笔委托", account, 0);
    }

    /** Sells all currently available holdings, chunking large positions by MAX_ORDER_QTY. */
    public TradeEngine.Result sellAllHoldings(ServerPlayer player) {
        TradeEngine.Result gate = entryGate(1);
        if (gate != null) return gate;
        HoldingAccount start = normalizedAccount(player);
        int soldShares = 0;
        int blockedStocks = 0;
        String firstError = null;
        for (Map.Entry<String, Integer> entry : start.holdings().entrySet()) {
            int remaining = Math.max(0, entry.getValue());
            while (remaining > 0) {
                int chunk = Math.min(remaining, Config.MAX_ORDER_QTY.get());
                TradeEngine.Result result = trade(player, entry.getKey(), chunk, false);
                if (!result.success()) {
                    blockedStocks++;
                    if (firstError == null) firstError = result.message();
                    break;
                }
                soldShares += chunk;
                remaining -= chunk;
            }
        }
        HoldingAccount finalAccount = AccountService.get(player);
        if (soldShares == 0 && blockedStocks > 0) {
            return new TradeEngine.Result(false, firstError == null ? "没有可卖出的持仓" : firstError, finalAccount, 0);
        }
        String message = soldShares == 0 ? "没有可卖出的持仓" : "已卖出可用持仓 " + soldShares + " 股";
        if (blockedStocks > 0) message += "（部分股票未成交）";
        return new TradeEngine.Result(true, message, finalAccount, 0);
    }

    public void sendSnapshot(ServerPlayer player, boolean openPanel, String message) {
        PacketDistributor.sendToPlayer(player, snapshotFor(player, openPanel, message));
    }

    public MarketSnapshotC2S snapshotFor(ServerPlayer player, boolean openPanel, String message) {
        return new MarketSnapshotC2S(openPanel, message, snapshot(), accountInfo(player), indices(), news(),
                Config.MAX_ORDER_QTY.get());
    }

    private HoldingAccount normalizedAccount(ServerPlayer player) {
        HoldingAccount account = AccountService.get(player);
        Map<String, Double> basis = new LinkedHashMap<>(account.costBasis());
        boolean changed = false;
        for (Map.Entry<String, Integer> entry : account.holdings().entrySet()) {
            if (entry.getValue() <= 0 || basis.containsKey(entry.getKey())) continue;
            Stock stock = stocks.get(entry.getKey());
            if (stock != null) {
                // Legacy accounts predate cost-basis persistence. Use the stock's
                // original price once as a deterministic migration baseline.
                basis.put(entry.getKey(), round2(stock.initialPrice() * entry.getValue()));
                changed = true;
            }
        }
        if (!changed) return account;
        HoldingAccount migrated = new HoldingAccount(account.cash(), account.holdings(), basis, account.realizedPnl());
        AccountService.set(player, migrated);
        return migrated;
    }

    private void resetDailyBaselines(MinecraftServer server, long day) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            HoldingAccount account = normalizedAccount(player);
            AccountService.ensureDailyBaseline(player, day, totalEquity(player, account));
        }
    }

    private double totalEquity(ServerPlayer player, HoldingAccount account) {
        double value = account.totalValue(this::priceOf);
        for (OrderBook.Order order : savedData.orderBook().ordersOf(player.getUUID())) {
            value += order.buy()
                    ? (order.reservedCash() > 0
                            ? order.reservedCash()
                            : TradeEngine.buyReservation(order.price(), order.quantity(), Config.FEE_RATE.get()))
                    : priceOf(order.stockId()) * order.quantity();
        }
        return round2(value);
    }

    private long currentDay(ServerPlayer player) {
        return marketDay(player.server);
    }

    private long marketDay(MinecraftServer server) {
        return server.overworld().getDayTime() / Math.max(1, Config.MARKET_CYCLE_TICKS.get());
    }

    public long marketDayIndex(MinecraftServer server) {
        return marketDay(server);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static long cents(double value) {
        return Math.round(value * 100.0);
    }

    private double calculateIndexValue() {
        if (stocks.isEmpty()) return Config.INDEX_BASE_VALUE.get();
        double sum = 0;
        int count = 0;
        for (Stock stock : stocks.values()) {
            if (stock.initialPrice() <= 0 || !Double.isFinite(stock.price())) continue;
            sum += stock.price() / stock.initialPrice();
            count++;
        }
        return count == 0 ? Config.INDEX_BASE_VALUE.get()
                : round2(Config.INDEX_BASE_VALUE.get() * sum / count);
    }

    /** Runs low-frequency, configurable market events at the cycle boundary. */
    private void runMarketCycleEvents(MinecraftServer server, long day) {
        if (stocks.isEmpty()) return;
        List<Stock> candidates = stocks.values().stream().filter(stock -> !stock.halted()).toList();
        if (candidates.isEmpty()) return;

        if (random.nextDouble() < Config.NEWS_EVENT_PROBABILITY.get()) {
            Stock stock = candidates.get(random.nextInt(candidates.size()));
            double impact = (random.nextDouble() * 2.0 - 1.0) * Config.NEWS_IMPACT_MAX.get();
            double before = stock.price();
            updatePrice(stock, PriceModel.round(before * (1.0 + impact)));
            savedData.addNews(day, stock.id(), stock.industry(), "NEWS",
                    stock.industry() + "板块出现新消息",
                    (impact >= 0 ? "市场情绪转暖，" : "市场情绪转弱，") + stock.name() + "价格受到影响",
                    impact * 100.0);
            matchOrders(stock, server);
        }

        if (random.nextDouble() < Config.DIVIDEND_PROBABILITY.get()) {
            Stock stock = candidates.get(random.nextInt(candidates.size()));
            MarketSavedData.CorporateAction action = savedData.addCorporateAction(day, stock.id(), "DIVIDEND",
                    1, 1, Config.DIVIDEND_PER_SHARE.get());
            applyPendingCorporateActions(server, action.id());
            savedData.addNews(day, stock.id(), stock.industry(), "DIVIDEND",
                    stock.name() + "发放分红",
                    "每股分红 " + String.format("%.2f", action.dividendPerShare()) + "，按持仓股数入账",
                    0);
        }

        if (random.nextDouble() < Config.SPLIT_PROBABILITY.get()) {
            Stock stock = candidates.get(random.nextInt(candidates.size()));
            MarketSavedData.CorporateAction action = savedData.addCorporateAction(day, stock.id(), "SPLIT",
                    2, 1, 0);
            stock.applySplit(action.numerator(), action.denominator());
            savedData.orderBook().applySplit(stock.id(), action.numerator(), action.denominator());
            applyPendingCorporateActions(server, action.id());
            savedData.addNews(day, stock.id(), stock.industry(), "SPLIT",
                    stock.name() + "进行拆股",
                    "按 " + action.numerator() + ":" + action.denominator() + " 拆分，持仓股数同步调整",
                    0);
        }

        if (random.nextDouble() < Config.HALT_PROBABILITY.get()) {
            Stock stock = candidates.get(random.nextInt(candidates.size()));
            int duration = Config.HALT_DURATION_CYCLES.get();
            stock.halt(duration);
            MarketSavedData.CorporateAction action = savedData.addCorporateAction(day, stock.id(), "HALT",
                    1, 1, duration);
            savedData.addNews(day, stock.id(), stock.industry(), "HALT",
                    stock.name() + "临时停牌",
                    "预计停牌 " + duration + " 个市场周期，期间不可交易",
                    0);
            applyPendingCorporateActions(server, action.id());
        }
        currentIndexValue = calculateIndexValue();
    }

    private void applyPendingCorporateActions(MinecraftServer server, long throughId) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            applyPendingCorporateActions(player, throughId);
        }
    }

    /** Applies missed dividends/splits to a player who was offline during an event. */
    public void applyPendingCorporateActions(ServerPlayer player) {
        applyPendingCorporateActions(player, Long.MAX_VALUE);
    }

    private void applyPendingCorporateActions(ServerPlayer player, long throughId) {
        AccountService.ensureInitialized(player);
        AccountData data = player.getData(com.tanrunn.stockmarket.StockMarketMod.ACCOUNT.get());
        for (MarketSavedData.CorporateAction action : savedData.corporateActions()) {
            if (action.id() <= data.lastCorporateActionId || action.id() > throughId) continue;
            HoldingAccount account = AccountService.get(player);
            if ("SPLIT".equals(action.type())) {
                account = TradeEngine.applySplit(account, action.stockId(), action.numerator(), action.denominator());
            } else if ("DIVIDEND".equals(action.type())) {
                int shares = account.holdings().getOrDefault(action.stockId(), 0);
                shares += savedData.orderBook().ordersOf(player.getUUID()).stream()
                        .filter(order -> !order.buy() && order.stockId().equals(action.stockId()))
                        .mapToInt(OrderBook.Order::quantity).sum();
                account = TradeEngine.payDividend(account, shares, action.dividendPerShare());
            }
            AccountService.set(player, account);
            data.lastCorporateActionId = action.id();
            player.setData(com.tanrunn.stockmarket.StockMarketMod.ACCOUNT.get(), data);
        }
    }

    public long latestCorporateActionId() {
        return savedData == null ? 0 : savedData.latestCorporateActionId();
    }

    /** Persist price state so the market survives a restart. */
    public void save() {
        if (savedData == null) return;
        for (Stock stock : stocks.values()) {
            savedData.put(stock.id(), new MarketSavedData.StockState(
                    stock.price(), stock.dayOpen(), stock.prevClose(), stock.dayHigh(), stock.dayLow(),
                    stock.volume(), stock.history(), stock.halted(), stock.haltRemainingCycles(), stock.initialPrice()));
        }
        savedData.setDirty();
    }
}
