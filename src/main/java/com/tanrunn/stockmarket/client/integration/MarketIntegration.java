package com.tanrunn.stockmarket.client.integration;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.screen.ApricityScreen;
import com.tanrunn.stockmarket.common.AccountInfo;
import com.tanrunn.stockmarket.common.StockInfo;
import com.tanrunn.stockmarket.common.network.MarketRequestC2S;
import com.tanrunn.stockmarket.common.network.MarketSnapshotC2S;
import com.tanrunn.stockmarket.common.network.TradeRequestC2S;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

import java.text.DecimalFormat;
import java.util.List;
import java.util.Map;

/**
 * ApricityUI market screen (HARD dependency — AUI is always present). Opens on
 * the first snapshot, refreshes on later ones. All interactions are bound from
 * Java (selection, quantity stepper, buy/sell, refresh), matching the verified
 * pattern from the fortune screen.
 */
public final class MarketIntegration {
    private static final String PATH = "screens/market.html";
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");

    private static MarketScreen screen;

    private MarketIntegration() {
    }

    public static void onSnapshot(MarketSnapshotC2S payload) {
        if (payload.openPanel()) {
            screen = new MarketScreen();
            Minecraft.getInstance().setScreen(screen.setPauseGame(false).setShowDefaultBackground(true));
            screen.update(payload);
        } else if (screen != null) {
            // periodic push for the open panel; ignored when the panel is closed
            screen.update(payload);
        }
    }

    private static final class MarketScreen extends ApricityScreen {
        private String selectedStockId;
        private int quantity = 100;
        private List<StockInfo> stocks = List.of();
        private MarketSnapshotC2S pending;

        MarketScreen() {
            super(PATH);
        }

        @Override
        public void removed() {
            super.removed();
            PacketDistributor.sendToServer(new MarketRequestC2S(false, true));
            screen = null;
        }

        @Override
        protected void init() {
            super.init();
            Document doc = getLinkedDocument();
            if (doc == null) return;

            bindStepper(doc, "aui-qty-minus", -100);
            bindStepper(doc, "aui-qty-plus", +100);
            Element buy = doc.getElementById("aui-buy");
            if (buy != null) {
                buy.addEventListener("click", event -> trade(true));
            }
            Element sell = doc.getElementById("aui-sell");
            if (sell != null) {
                sell.addEventListener("click", event -> trade(false));
            }
            Element refresh = doc.getElementById("aui-refresh");
            if (refresh != null) {
                refresh.addEventListener("click", event ->
                        PacketDistributor.sendToServer(new MarketRequestC2S(false, false)));
            }
            applyPending();
        }

        private void bindStepper(Document doc, String id, int delta) {
            Element stepper = doc.getElementById(id);
            if (stepper == null) return;
            stepper.addEventListener("click", event -> {
                quantity = Math.max(1, Math.min(quantity + delta, 9999));
                Element qty = doc.getElementById("aui-qty");
                if (qty != null) {
                    qty.setTextContent(String.valueOf(quantity));
                }
            });
        }

        void update(MarketSnapshotC2S payload) {
            pending = payload;
            Minecraft.getInstance().execute(this::applyPending);
        }

        private void applyPending() {
            if (pending == null) return;
            Document doc = getLinkedDocument();
            if (doc == null) return;
            stocks = pending.stocks();
            if (selectedStockId == null && !stocks.isEmpty()) {
                selectedStockId = stocks.get(0).id();
            }
            if (pending.message() != null) {
                setText(doc, "aui-msg", pending.message());
            }
            renderStocks(doc);
            renderAccount(doc, pending.account());
            renderSelected(doc);
        }

        private void renderStocks(Document doc) {
            Element rows = doc.getElementById("aui-stocks");
            if (rows == null) return;
            for (Element child : new java.util.ArrayList<>(rows.getChildren())) {
                rows.removeChild(child);
            }
            for (StockInfo stock : stocks) {
                Element row = doc.createElement("div");
                row.setAttribute("class", "stock-row" + (stock.id().equals(selectedStockId) ? " selected" : ""));
                row.setAttribute("data-stock", stock.id());

                Element name = doc.createElement("span");
                name.setAttribute("class", "stock-name");
                name.setTextContent(stock.name());
                row.appendChild(name);

                double change = stock.changePct();
                String color = change >= 0 ? "stock-up" : "stock-down";
                Element price = doc.createElement("span");
                price.setAttribute("class", "stock-price " + color);
                price.setTextContent(MONEY.format(stock.price()) + " (" + String.format("%+.2f%%", change) + ")");
                row.appendChild(price);

                row.addEventListener("click", event -> {
                    selectedStockId = stock.id();
                    Document d = getLinkedDocument();
                    if (d != null) {
                        renderStocks(d);
                        renderSelected(d);
                    }
                });
                rows.appendChild(row);
            }
        }

        private void renderAccount(Document doc, AccountInfo account) {
            setText(doc, "aui-cash", MONEY.format(account.cash()));
            setText(doc, "aui-total", MONEY.format(account.totalValue()));
            StringBuilder holdings = new StringBuilder();
            if (account.holdings().isEmpty()) {
                holdings.append("暂无持仓");
            } else {
                for (Map.Entry<String, Integer> entry : account.holdings().entrySet()) {
                    if (holdings.length() > 0) holdings.append("、");
                    holdings.append(entry.getKey()).append("×").append(entry.getValue());
                }
            }
            setText(doc, "aui-holdings", holdings.toString());

            StringBuilder orders = new StringBuilder();
            if (account.orders().isEmpty()) {
                orders.append("暂无委托");
            } else {
                for (var order : account.orders()) {
                    if (orders.length() > 0) orders.append("、");
                    orders.append(order.buy() ? "买" : "卖").append(order.stockId())
                            .append("×").append(order.quantity())
                            .append("@").append(MONEY.format(order.price()));
                }
            }
            setText(doc, "aui-orders", orders.toString());
        }

        private void renderSelected(Document doc) {
            StockInfo stock = findSelected();
            setText(doc, "aui-selected", stock == null ? "未选择" : stock.name() + " · " + MONEY.format(stock.price()));
            setText(doc, "aui-qty", String.valueOf(quantity));
            setText(doc, "aui-estimate", stock == null ? "—" : "≈ " + MONEY.format(stock.price() * quantity));
            renderKline(doc, stock);
        }

        private void renderKline(Document doc, StockInfo stock) {
            com.sighs.apricityui.element.Canvas canvas;
            try {
                canvas = (com.sighs.apricityui.element.Canvas) doc.getElementById("aui-kline");
            } catch (ClassCastException e) {
                return;
            }
            if (canvas == null) return;
            var ctx = canvas.getContext("2d");
            int width = canvas.getWidth();
            int height = canvas.getHeight();
            ctx.clearRect(0, 0, width, height);
            if (stock == null || stock.history().size() < 2) return;

            List<com.tanrunn.stockmarket.common.Candle> history = stock.history();
            double lo = Double.MAX_VALUE;
            double hi = -Double.MAX_VALUE;
            for (var candle : history) {
                lo = Math.min(lo, Math.min(candle.low(), candle.close()));
                hi = Math.max(hi, Math.max(candle.high(), candle.open()));
            }
            final double min = lo;
            final double max = hi <= lo ? lo + 1 : hi;
            double pad = 10;
            double plotH = height - pad * 2;
            double slot = (double) (width - pad * 2) / history.size();
            double bodyW = Math.max(2, slot * 0.55);
            java.util.function.DoubleUnaryOperator yOf = v -> pad + (max - v) / (max - min) * plotH;

            ctx.setStrokeStyle("#33363d");
            ctx.setLineWidth(1);
            for (int i = 1; i < 4; i++) {
                double y = pad + plotH * i / 4.0;
                ctx.beginPath();
                ctx.moveTo(pad, y);
                ctx.lineTo(width - pad, y);
                ctx.stroke();
            }

            for (int i = 0; i < history.size(); i++) {
                var candle = history.get(i);
                double x = pad + slot * i + slot / 2;
                boolean up = candle.close() >= candle.open();
                String color = up ? "#e05555" : "#58c270";
                ctx.setStrokeStyle(color);
                ctx.setFillStyle(color);
                ctx.beginPath();
                ctx.moveTo(x, yOf.applyAsDouble(candle.high()));
                ctx.lineTo(x, yOf.applyAsDouble(candle.low()));
                ctx.stroke();
                double yOpen = yOf.applyAsDouble(candle.open());
                double yClose = yOf.applyAsDouble(candle.close());
                double top = Math.min(yOpen, yClose);
                double bodyHeight = Math.max(1.0, Math.abs(yOpen - yClose));
                ctx.fillRect(x - bodyW / 2, top, bodyW, bodyHeight);
            }

            var last = history.get(history.size() - 1);
            ctx.setStrokeStyle("#d9a93f");
            ctx.beginPath();
            ctx.moveTo(pad, yOf.applyAsDouble(last.close()));
            ctx.lineTo(width - pad, yOf.applyAsDouble(last.close()));
            ctx.stroke();
        }

        private StockInfo findSelected() {
            for (StockInfo stock : stocks) {
                if (stock.id().equals(selectedStockId)) return stock;
            }
            return null;
        }

        private void trade(boolean buy) {
            if (selectedStockId == null) return;
            PacketDistributor.sendToServer(new TradeRequestC2S(selectedStockId, quantity, buy));
        }

        private static void setText(Document doc, String id, String text) {
            Element el = doc.getElementById(id);
            if (el != null) {
                el.setTextContent(text);
            }
        }
    }
}
