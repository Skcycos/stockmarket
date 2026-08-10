package com.tanrunn.stockmarket.common.network;

import com.tanrunn.stockmarket.common.AccountInfo;
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
 */
public record MarketSnapshotC2S(
        boolean openPanel,
        String message,
        List<StockInfo> stocks,
        AccountInfo account) implements CustomPacketPayload {

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
                int candleCount = buf.readVarInt();
                List<com.tanrunn.stockmarket.common.Candle> history = new ArrayList<>(candleCount);
                for (int j = 0; j < candleCount; j++) {
                    history.add(new com.tanrunn.stockmarket.common.Candle(
                            buf.readVarLong(), buf.readDouble(), buf.readDouble(),
                            buf.readDouble(), buf.readDouble(), buf.readVarLong()));
                }
                stocks.add(new StockInfo(stockId, name, price, prevClose, dayHigh, dayLow, volume, history));
            }
            double cash = buf.readDouble();
            double totalValue = buf.readDouble();
            int holdingCount = buf.readVarInt();
            Map<String, Integer> holdings = new LinkedHashMap<>();
            for (int i = 0; i < holdingCount; i++) {
                holdings.put(buf.readUtf(64), buf.readVarInt());
            }
            int orderCount = buf.readVarInt();
            List<com.tanrunn.stockmarket.common.OrderInfo> orders = new ArrayList<>(orderCount);
            for (int i = 0; i < orderCount; i++) {
                orders.add(new com.tanrunn.stockmarket.common.OrderInfo(
                        buf.readVarLong(), buf.readUtf(64), buf.readBoolean(),
                        buf.readDouble(), buf.readVarInt()));
            }
            return new MarketSnapshotC2S(open, message, stocks, new AccountInfo(cash, totalValue, holdings, orders));
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
            buf.writeDouble(value.account().cash());
            buf.writeDouble(value.account().totalValue());
            Map<String, Integer> holdings = value.account().holdings();
            buf.writeVarInt(holdings.size());
            holdings.forEach((id, qty) -> {
                buf.writeUtf(id, 64);
                buf.writeVarInt(qty);
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
        }
    };

    @Override
    public Type<MarketSnapshotC2S> type() {
        return TYPE;
    }
}
