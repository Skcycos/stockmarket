package com.tanrunn.stockmarket.common.network;

import com.tanrunn.stockmarket.common.AccountInfo;
import com.tanrunn.stockmarket.common.MarketIndexInfo;
import com.tanrunn.stockmarket.common.MarketNews;
import com.tanrunn.stockmarket.common.StockInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server → client: full market snapshot (stocks + viewer's account). Carries an
 * `openPanel` flag so the client knows whether to open the AUI screen or refresh.
 * Also carries the optional bank-bridge state (LC not installed → unavailable).
 */
public record MarketSnapshotC2S(
        boolean openPanel,
        String message,
        List<StockInfo> stocks,
        AccountInfo account,
        List<MarketIndexInfo> indices,
        List<MarketNews> news,
        int maxOrderQty,
        boolean bankBridgeAvailable,
        long bankBalanceCopper,
        String bankBridgeName) implements CustomPacketPayload {

    public MarketSnapshotC2S(boolean openPanel, String message, List<StockInfo> stocks, AccountInfo account) {
        this(openPanel, message, stocks, account, List.of(), List.of(), 9999, false, 0, "");
    }

    public MarketSnapshotC2S(boolean openPanel, String message, List<StockInfo> stocks, AccountInfo account,
                             List<MarketIndexInfo> indices, List<MarketNews> news) {
        this(openPanel, message, stocks, account, indices, news, 9999, false, 0, "");
    }

    public MarketSnapshotC2S(boolean openPanel, String message, List<StockInfo> stocks, AccountInfo account,
                             List<MarketIndexInfo> indices, List<MarketNews> news, int maxOrderQty) {
        this(openPanel, message, stocks, account, indices, news, maxOrderQty, false, 0, "");
    }

    public MarketSnapshotC2S {
        stocks = List.copyOf(stocks == null ? List.of() : stocks);
        indices = List.copyOf(indices == null ? List.of() : indices);
        news = List.copyOf(news == null ? List.of() : news);
        maxOrderQty = Math.max(1, maxOrderQty);
        bankBridgeName = bankBridgeName == null ? "" : bankBridgeName;
        bankBalanceCopper = Math.max(0, bankBalanceCopper);
    }

    public static final Type<MarketSnapshotC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("stockmarket", "market_snapshot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MarketSnapshotC2S> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public MarketSnapshotC2S decode(RegistryFriendlyByteBuf buf) {
            boolean open = buf.readBoolean();
            String message = MarketRequestC2S.readOptionalString(buf);
            int stockCount = buf.readVarInt();
            List<StockInfo> stocks = new ArrayList<>(stockCount);
            for (int i = 0; i < stockCount; i++) {
                String stockId = buf.readUtf(64);
                String name = buf.readUtf(64);
                double price = buf.readDouble();
                double prevClose = buf.readDouble();
                double dayHigh = buf.readDouble();
                double dayLow = buf.readDouble();
                long volume = buf.readVarLong();
                String industry = buf.readUtf(64);
                boolean halted = buf.readBoolean();
                int haltRemainingCycles = buf.readVarInt();
                int candleCount = buf.readVarInt();
                List<com.tanrunn.stockmarket.common.Candle> history = new ArrayList<>(candleCount);
                for (int j = 0; j < candleCount; j++) {
                    history.add(new com.tanrunn.stockmarket.common.Candle(
                            buf.readVarLong(), buf.readDouble(), buf.readDouble(),
                            buf.readDouble(), buf.readDouble(), buf.readVarLong()));
                }
                stocks.add(new StockInfo(stockId, name, price, prevClose, dayHigh, dayLow, volume, history,
                        industry, halted, haltRemainingCycles));
            }
            int indexCount = buf.readVarInt();
            List<MarketIndexInfo> indices = new ArrayList<>(indexCount);
            for (int i = 0; i < indexCount; i++) {
                indices.add(new MarketIndexInfo(buf.readUtf(64), buf.readUtf(64), buf.readDouble(), buf.readDouble()));
            }
            int newsCount = buf.readVarInt();
            List<MarketNews> news = new ArrayList<>(newsCount);
            for (int i = 0; i < newsCount; i++) {
                news.add(new MarketNews(buf.readVarLong(), buf.readVarLong(), buf.readUtf(64), buf.readUtf(64),
                        buf.readUtf(32), buf.readUtf(256), buf.readUtf(512), buf.readDouble()));
            }
            int maxOrderQty = buf.readVarInt();
            boolean bankBridgeAvailable = buf.readBoolean();
            long bankBalanceCopper = buf.readVarLong();
            String bankBridgeName = buf.readUtf(32);
            double cash = buf.readDouble();
            double totalValue = buf.readDouble();
            double holdingsValue = buf.readDouble();
            double unrealizedPnl = buf.readDouble();
            double realizedPnl = buf.readDouble();
            double dailyPnl = buf.readDouble();
            double totalPnl = buf.readDouble();
            double reservedCash = buf.readDouble();
            double availableHoldingsValue = buf.readDouble();
            double reservedHoldingsValue = buf.readDouble();
            int availableHoldingsQuantity = buf.readVarInt();
            int reservedHoldingsQuantity = buf.readVarInt();
            int holdingCount = buf.readVarInt();
            Map<String, Integer> holdings = new LinkedHashMap<>();
            for (int i = 0; i < holdingCount; i++) {
                holdings.put(buf.readUtf(64), buf.readVarInt());
            }
            int basisCount = buf.readVarInt();
            Map<String, Double> costBasis = new LinkedHashMap<>();
            for (int i = 0; i < basisCount; i++) {
                costBasis.put(buf.readUtf(64), buf.readDouble());
            }
            int orderCount = buf.readVarInt();
            List<com.tanrunn.stockmarket.common.OrderInfo> orders = new ArrayList<>(orderCount);
            for (int i = 0; i < orderCount; i++) {
                orders.add(new com.tanrunn.stockmarket.common.OrderInfo(
                        buf.readVarLong(), buf.readUtf(64), buf.readBoolean(),
                        buf.readDouble(), buf.readVarInt()));
            }
            int tradeCount = buf.readVarInt();
            List<com.tanrunn.stockmarket.common.TradeInfo> trades = new ArrayList<>(tradeCount);
            for (int i = 0; i < tradeCount; i++) {
                trades.add(new com.tanrunn.stockmarket.common.TradeInfo(
                        buf.readVarLong(), buf.readUtf(64), buf.readBoolean(),
                        buf.readDouble(), buf.readVarInt(), buf.readDouble()));
            }
            return new MarketSnapshotC2S(open, message, stocks,
                new AccountInfo(cash, totalValue, holdingsValue, unrealizedPnl, realizedPnl,
                            dailyPnl, totalPnl, reservedCash, availableHoldingsValue,
                            reservedHoldingsValue, availableHoldingsQuantity, reservedHoldingsQuantity,
                            holdings, costBasis, orders, trades), indices, news,
                    maxOrderQty, bankBridgeAvailable, bankBalanceCopper, bankBridgeName);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, MarketSnapshotC2S value) {
            buf.writeBoolean(value.openPanel());
            MarketRequestC2S.writeOptionalString(buf, value.message());
            buf.writeVarInt(value.stocks().size());
            for (StockInfo stock : value.stocks()) {
                buf.writeUtf(stock.id(), 64);
                buf.writeUtf(stock.name(), 64);
                buf.writeDouble(stock.price());
                buf.writeDouble(stock.prevClose());
                buf.writeDouble(stock.dayHigh());
                buf.writeDouble(stock.dayLow());
                buf.writeVarLong(stock.volume());
                buf.writeUtf(stock.industry(), 64);
                buf.writeBoolean(stock.halted());
                buf.writeVarInt(stock.haltRemainingCycles());
                List<com.tanrunn.stockmarket.common.Candle> history = stock.history();
                buf.writeVarInt(history.size());
                for (com.tanrunn.stockmarket.common.Candle candle : history) {
                    buf.writeVarLong(candle.dayIndex());
                    buf.writeDouble(candle.open());
                    buf.writeDouble(candle.close());
                    buf.writeDouble(candle.high());
                    buf.writeDouble(candle.low());
                    buf.writeVarLong(candle.volume());
                }
            }
            buf.writeVarInt(value.indices().size());
            for (MarketIndexInfo index : value.indices()) {
                buf.writeUtf(index.id(), 64);
                buf.writeUtf(index.name(), 64);
                buf.writeDouble(index.value());
                buf.writeDouble(index.changePct());
            }
            buf.writeVarInt(value.news().size());
            for (MarketNews item : value.news()) {
                buf.writeVarLong(item.id());
                buf.writeVarLong(item.dayIndex());
                buf.writeUtf(item.stockId(), 64);
                buf.writeUtf(item.industry(), 64);
                buf.writeUtf(item.type(), 32);
                buf.writeUtf(item.title(), 256);
                buf.writeUtf(item.detail(), 512);
                buf.writeDouble(item.impactPct());
            }
            buf.writeVarInt(value.maxOrderQty());
            buf.writeBoolean(value.bankBridgeAvailable());
            buf.writeVarLong(value.bankBalanceCopper());
            buf.writeUtf(value.bankBridgeName(), 32);
            buf.writeDouble(value.account().cash());
            buf.writeDouble(value.account().totalValue());
            buf.writeDouble(value.account().holdingsValue());
            buf.writeDouble(value.account().unrealizedPnl());
            buf.writeDouble(value.account().realizedPnl());
            buf.writeDouble(value.account().dailyPnl());
            buf.writeDouble(value.account().totalPnl());
            buf.writeDouble(value.account().reservedCash());
            buf.writeDouble(value.account().availableHoldingsValue());
            buf.writeDouble(value.account().reservedHoldingsValue());
            buf.writeVarInt(value.account().availableHoldingsQuantity());
            buf.writeVarInt(value.account().reservedHoldingsQuantity());
            Map<String, Integer> holdings = value.account().holdings();
            buf.writeVarInt(holdings.size());
            holdings.forEach((id, qty) -> {
                buf.writeUtf(id, 64);
                buf.writeVarInt(qty);
            });
            Map<String, Double> costBasis = value.account().costBasis();
            buf.writeVarInt(costBasis.size());
            costBasis.forEach((id, basis) -> {
                buf.writeUtf(id, 64);
                buf.writeDouble(basis);
            });
            List<com.tanrunn.stockmarket.common.OrderInfo> orders = value.account().orders();
            buf.writeVarInt(orders.size());
            for (com.tanrunn.stockmarket.common.OrderInfo order : orders) {
                buf.writeVarLong(order.orderId());
                buf.writeUtf(order.stockId(), 64);
                buf.writeBoolean(order.buy());
                buf.writeDouble(order.price());
                buf.writeVarInt(order.quantity());
            }
            List<com.tanrunn.stockmarket.common.TradeInfo> trades = value.account().trades();
            buf.writeVarInt(trades.size());
            for (com.tanrunn.stockmarket.common.TradeInfo trade : trades) {
                buf.writeVarLong(trade.dayIndex());
                buf.writeUtf(trade.stockId(), 64);
                buf.writeBoolean(trade.buy());
                buf.writeDouble(trade.price());
                buf.writeVarInt(trade.quantity());
                buf.writeDouble(trade.fee());
            }
        }
    };

    @Override
    public Type<MarketSnapshotC2S> type() {
        return TYPE;
    }
}
