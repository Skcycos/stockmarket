package com.tanrunn.stockmarket.server.market;

import com.tanrunn.stockmarket.Config;
import com.tanrunn.stockmarket.common.AccountInfo;
import com.tanrunn.stockmarket.common.OrderInfo;
import com.tanrunn.stockmarket.common.StockInfo;
import com.tanrunn.stockmarket.common.network.MarketSnapshotC2S;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
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

    private MarketService() {
    }

    public static MarketService get() {
        return INSTANCE;
    }

    public static void init(MinecraftServer server) {
        MarketService service = new MarketService();
        service.savedData = MarketSavedData.load(server.overworld());
        service.loadStocks();
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
            stocks.put(def.id(), new Stock(def.id(), def.name(), def.initialPrice(), def.drift(), def.volatility(),
                    price, open, prev, high, low, volume, history));
        }
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
        long day = server.overworld().getDayTime() / 24000;
        if (lastDayIndex != Long.MIN_VALUE && day != lastDayIndex) {
            stocks.values().forEach(stock -> stock.rollDay(lastDayIndex));
        }
        lastDayIndex = day;

        int interval = Config.TICK_INTERVAL.get();
        if (interval <= 0 || tickCount % interval != 0) return;
        for (Stock stock : stocks.values()) {
            stock.setPrice(PriceModel.nextPrice(stock.price(), stock.drift(), stock.volatility(), random));
            matchOrders(stock, server);
        }
        pushToViewers(server, null);
    }

    private void matchOrders(Stock stock, MinecraftServer server) {
        OrderBook book = savedData.orderBook();
        if (book.size() == 0) return;
        book.match(stock.id(), stock.price(), (order, fillPrice) -> {
            ServerPlayer player = server.getPlayerList().getPlayer(order.player());
            if (player != null) {
                HoldingAccount account = AccountService.get(player);
                HoldingAccount updated;
                if (order.buy()) {
                    updated = TradeEngine.fillBuy(account, order.stockId(), order.quantity());
                } else {
                    updated = TradeEngine.fillSell(account, order.stockId(), fillPrice, order.quantity(), Config.FEE_RATE.get());
                }
                AccountService.set(player, updated);
                stock.addVolume(order.quantity());
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "§a委托成交§r：" + (order.buy() ? "买入" : "卖出") + " " + order.quantity()
                                + " 股 @" + String.format("%.2f", fillPrice) + "（" + stock.name() + "）"));
            }
        });
    }

    public void setPrice(String id, double price, MinecraftServer server) {
        Stock stock = stocks.get(id);
        if (stock != null) {
            stock.setPrice(PriceModel.round(price));
            matchOrders(stock, server);
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
        HoldingAccount account = AccountService.get(player);
        List<OrderInfo> orders = savedData.orderBook().ordersOf(player.getUUID()).stream()
                .map(o -> new OrderInfo(o.id(), o.stockId(), o.buy(), o.price(), o.quantity()))
                .toList();
        return new AccountInfo(account.cash(), account.totalValue(this::priceOf), account.holdings(), orders);
    }

    public TradeEngine.Result trade(ServerPlayer player, String stockId, int quantity, boolean buy) {
        Stock stock = stocks.get(stockId);
        if (stock == null) {
            return new TradeEngine.Result(false, "未知股票：" + stockId, null, 0);
        }
        HoldingAccount account = AccountService.get(player);
        TradeEngine.Result result = buy
                ? TradeEngine.buy(account, stockId, stock.price(), quantity, Config.FEE_RATE.get())
                : TradeEngine.sell(account, stockId, stock.price(), quantity, Config.FEE_RATE.get());
        if (result.success() && result.account() != null) {
            AccountService.set(player, result.account());
            stock.addVolume(quantity);
        }
        return result;
    }

    /** Places a limit order, reserving cash/shares. */
    public TradeEngine.Result placeOrder(ServerPlayer player, String stockId, boolean buy, double price, int quantity) {
        Stock stock = stocks.get(stockId);
        if (stock == null) {
            return new TradeEngine.Result(false, "未知股票：" + stockId, null, 0);
        }
        HoldingAccount account = AccountService.get(player);
        TradeEngine.Result result = buy
                ? TradeEngine.reserveBuy(account, price, quantity, Config.FEE_RATE.get())
                : TradeEngine.reserveSell(account, stockId, quantity);
        if (!result.success()) {
            return result;
        }
        AccountService.set(player, result.account());
        long orderId = savedData.orderBook().place(player.getUUID(), stockId, buy, PriceModel.round(price), quantity);
        // 挂单后立刻尝试撮合一次（可能价格已经越过限价）
        matchOrders(stock, player.server);
        return new TradeEngine.Result(true, (buy ? "已挂买单 #" : "已挂卖单 #") + orderId, result.account(), 0);
    }

    /** Cancels a limit order and refunds the reservation. */
    public TradeEngine.Result cancelOrder(ServerPlayer player, long orderId) {
        OrderBook.Order order = savedData.orderBook().cancel(orderId);
        if (order == null || !order.player().equals(player.getUUID())) {
            return new TradeEngine.Result(false, "找不到委托 #" + orderId, null, 0);
        }
        HoldingAccount account = AccountService.get(player);
        HoldingAccount updated = order.buy()
                ? TradeEngine.refundBuy(account, order.price(), order.quantity(), Config.FEE_RATE.get())
                : TradeEngine.refundSell(account, order.stockId(), order.quantity());
        AccountService.set(player, updated);
        return new TradeEngine.Result(true, "已撤单 #" + orderId, updated, 0);
    }

    public void sendSnapshot(ServerPlayer player, boolean openPanel, String message) {
        PacketDistributor.sendToPlayer(player, snapshotFor(player, openPanel, message));
    }

    public MarketSnapshotC2S snapshotFor(ServerPlayer player, boolean openPanel, String message) {
        return new MarketSnapshotC2S(openPanel, message, snapshot(), accountInfo(player));
    }

    /** Persist price state so the market survives a restart. */
    public void save() {
        if (savedData == null) return;
        for (Stock stock : stocks.values()) {
            savedData.put(stock.id(), new MarketSavedData.StockState(
                    stock.price(), stock.dayOpen(), stock.prevClose(), stock.dayHigh(), stock.dayLow(),
                    stock.volume(), stock.history()));
        }
        savedData.setDirty();
    }
}
