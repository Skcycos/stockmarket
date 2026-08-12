package com.tanrunn.stockmarket.client.integration;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.screen.ApricityScreen;
import com.sighs.apricityui.event.MouseEvent;
import com.tanrunn.stockmarket.common.Candle;
import com.tanrunn.stockmarket.common.AccountInfo;
import com.tanrunn.stockmarket.common.MarketIndexInfo;
import com.tanrunn.stockmarket.common.MarketNews;
import com.tanrunn.stockmarket.common.OrderInfo;
import com.tanrunn.stockmarket.common.StockInfo;
import com.tanrunn.stockmarket.common.TradeInfo;
import com.tanrunn.stockmarket.common.network.CancelOrderRequestC2S;
import com.tanrunn.stockmarket.common.network.CancelAllOrdersRequestC2S;
import com.tanrunn.stockmarket.common.network.LimitOrderRequestC2S;
import com.tanrunn.stockmarket.common.network.MarketRequestC2S;
import com.tanrunn.stockmarket.common.network.MarketSnapshotC2S;
import com.tanrunn.stockmarket.common.network.TradeRequestC2S;
import com.tanrunn.stockmarket.common.network.SellAllHoldingsRequestC2S;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * ApricityUI market screen (HARD dependency — AUI is always present). Opens on
 * the first snapshot, refreshes on later ones. All interactions are bound from
 * Java (selection, quantity/price steppers, market & limit buy/sell, cancel,
 * refresh). DOM updates reuse existing elements; no page refresh is used as the
 * high-frequency update mechanism.
 */
public final class MarketIntegration {
    private static final String PATH = "screens/market.html";
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");
    private static final double PRICE_STEP = 0.10;
    private static final int DEFAULT_MAX_QTY = 9999;
    private static final long CONFIRM_WINDOW_MS = 5_000;
    private static final long REQUEST_COOLDOWN_MS = 900;
    private static final DecimalFormat VOLUME = new DecimalFormat("#,##0");
    private static final double KLINE_RASTER_SCALE = 2.0;

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
        private int maxQty = DEFAULT_MAX_QTY;
        private double limitPrice = 1.0;
        private List<StockInfo> stocks = List.of();
        private MarketSnapshotC2S pending;
        private String currentView = "quotes";
        private String orderFilter = "all";
        private String stockFilter = "all";
        private String stockSort = "default";
        private String portfolioSort = "default";
        private String armedAction;
        private long armedUntil;
        private long lastTradeRequestAt;
        private boolean showMa5 = true;
        private boolean showMa10 = true;
        private boolean showVolume = true;
        private int hoverCandleIndex = -1;
        private double hoverX = Double.NaN;
        private double hoverY = Double.NaN;
        private String lastKlineRenderSignature;
        private Document boundDocument;
        private long boundGeneration = Long.MIN_VALUE;

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

            bindDocument(doc);
            applyPending();
        }

        /**
         * AUI refreshes a Document in place but rebuilds every child Element.
         * External Java listeners therefore have to be installed again for each
         * refresh generation.
         */
        private void bindDocument(Document doc) {
            long generation = doc.getRefreshGeneration();
            if (boundDocument == doc && boundGeneration == generation) return;
            boundDocument = doc;
            boundGeneration = generation;

            bindStepper(doc, "aui-qty-minus", -100);
            bindStepper(doc, "aui-qty-plus", +100);
            bindStepper(doc, "aui-qty-plus-one", +1);
            bindStepper(doc, "aui-qty-plus-ten", +10);
            bindStepper(doc, "aui-qty-plus-hundred", +100);
            Element sellAll = doc.getElementById("aui-sell-all");
            if (sellAll != null) {
                sellAll.addEventListener("click", event -> setHoldingQuantity(doc));
            }
            bindPriceStepper(doc, "aui-price-minus", -PRICE_STEP);
            bindPriceStepper(doc, "aui-price-plus", +PRICE_STEP);
            bindViewTab(doc, "aui-tab-quotes", "quotes");
            bindViewTab(doc, "aui-tab-portfolio", "portfolio");
            bindViewTab(doc, "aui-tab-orders", "orders");
            bindViewTab(doc, "aui-tab-news", "news");
            bindOrderFilter(doc, "aui-order-filter-all", "all");
            bindOrderFilter(doc, "aui-order-filter-buy", "buy");
            bindOrderFilter(doc, "aui-order-filter-sell", "sell");
            bindStockFilter(doc, "aui-stock-filter-all", "all");
            bindStockFilter(doc, "aui-stock-filter-held", "held");
            bindStockFilter(doc, "aui-stock-filter-up", "up");
            bindStockFilter(doc, "aui-stock-filter-down", "down");
            bindStockSort(doc, "aui-stock-sort-default", "default");
            bindStockSort(doc, "aui-stock-sort-change", "change");
            bindStockSort(doc, "aui-stock-sort-price", "price");
            bindPortfolioSort(doc, "aui-portfolio-sort-default", "default");
            bindPortfolioSort(doc, "aui-portfolio-sort-pnl", "pnl");
            bindPortfolioSort(doc, "aui-portfolio-sort-value", "value");
            bindPortfolioSort(doc, "aui-portfolio-sort-change", "change");
            bindBatchAction(doc, "aui-sell-all-holdings", "sell-all-holdings");
            bindBatchAction(doc, "aui-cancel-all-orders", "cancel-all-orders");
            bindKlineToggle(doc, "aui-kline-ma5", "ma5");
            bindKlineToggle(doc, "aui-kline-ma10", "ma10");
            bindKlineToggle(doc, "aui-kline-volume", "volume");
            bindKlineHover(doc);
            Element buy = doc.getElementById("aui-buy");
            if (buy != null) {
                buy.addEventListener("click", event -> trade(doc, true));
            }
            Element sell = doc.getElementById("aui-sell");
            if (sell != null) {
                sell.addEventListener("click", event -> trade(doc, false));
            }
            Element limitBuy = doc.getElementById("aui-limit-buy");
            if (limitBuy != null) {
                limitBuy.addEventListener("click", event -> limitTrade(doc, true));
            }
            Element limitSell = doc.getElementById("aui-limit-sell");
            if (limitSell != null) {
                limitSell.addEventListener("click", event -> limitTrade(doc, false));
            }
            Element refresh = doc.getElementById("aui-refresh");
            if (refresh != null) {
                refresh.addEventListener("click", event ->
                        PacketDistributor.sendToServer(new MarketRequestC2S(false, false)));
            }
        }

        @Override
        public void tick() {
            super.tick();
            Document doc = getLinkedDocument();
            if (doc != null && (boundDocument != doc || boundGeneration != doc.getRefreshGeneration())) {
                bindDocument(doc);
                applyPending();
            }
        }

        private void bindStepper(Document doc, String id, int delta) {
            Element stepper = doc.getElementById(id);
            if (stepper == null) return;
            stepper.addEventListener("click", event -> {
                quantity = Math.max(1, Math.min(quantity + delta, maxQty));
                Element qty = doc.getElementById("aui-qty");
                if (qty != null) {
                    qty.setTextContent(String.valueOf(quantity));
                }
                updateEstimate(doc);
            });
        }

        private void bindViewTab(Document doc, String id, String view) {
            Element tab = doc.getElementById(id);
            if (tab != null) {
                tab.addEventListener("click", event -> switchView(doc, view));
            }
        }

        private void switchView(Document doc, String view) {
            currentView = view;
            setActive(doc, "aui-tab-quotes", "quotes".equals(view));
            setActive(doc, "aui-tab-portfolio", "portfolio".equals(view));
            setActive(doc, "aui-tab-orders", "orders".equals(view));
            setActive(doc, "aui-tab-news", "news".equals(view));
            setActive(doc, "aui-quotes-view", "quotes".equals(view));
            setActive(doc, "aui-portfolio-view", "portfolio".equals(view));
            setActive(doc, "aui-orders-view", "orders".equals(view));
            setActive(doc, "aui-news-view", "news".equals(view));
            setActive(doc, "aui-quotes-tail", "quotes".equals(view));
        }

        private void setHoldingQuantity(Document doc) {
            if (pending == null || selectedStockId == null) return;
            int held = pending.account().holdings().getOrDefault(selectedStockId, 0);
            if (held > 0) {
                quantity = Math.min(held, maxQty);
                setText(doc, "aui-qty", String.valueOf(quantity));
                updateEstimate(doc);
            }
        }

        private void bindOrderFilter(Document doc, String id, String filter) {
            Element button = doc.getElementById(id);
            if (button != null) {
                button.addEventListener("click", event -> {
                    orderFilter = filter;
                    renderOrderFilters(doc);
                    if (pending != null) renderOrdersPage(doc, pending.account());
                });
            }
        }

        private void renderOrderFilters(Document doc) {
            setActive(doc, "aui-order-filter-all", "all".equals(orderFilter));
            setActive(doc, "aui-order-filter-buy", "buy".equals(orderFilter));
            setActive(doc, "aui-order-filter-sell", "sell".equals(orderFilter));
        }

        private void bindStockFilter(Document doc, String id, String filter) {
            Element button = doc.getElementById(id);
            if (button != null) {
                button.addEventListener("click", event -> {
                    stockFilter = filter;
                    renderStockControls(doc);
                    renderStocks(doc);
                });
            }
        }

        private void bindStockSort(Document doc, String id, String sort) {
            Element button = doc.getElementById(id);
            if (button != null) {
                button.addEventListener("click", event -> {
                    stockSort = sort;
                    renderStockControls(doc);
                    renderStocks(doc);
                });
            }
        }

        private void bindPortfolioSort(Document doc, String id, String sort) {
            Element button = doc.getElementById(id);
            if (button == null) return;
            button.addEventListener("click", event -> {
                portfolioSort = sort;
                renderPortfolioControls(doc);
                if (pending != null) renderPortfolio(doc, pending.account());
            });
        }

        private void renderPortfolioControls(Document doc) {
            setActive(doc, "aui-portfolio-sort-default", "default".equals(portfolioSort));
            setActive(doc, "aui-portfolio-sort-pnl", "pnl".equals(portfolioSort));
            setActive(doc, "aui-portfolio-sort-value", "value".equals(portfolioSort));
            setActive(doc, "aui-portfolio-sort-change", "change".equals(portfolioSort));
        }

        private void bindBatchAction(Document doc, String id, String action) {
            Element button = doc.getElementById(id);
            if (button == null) return;
            button.addEventListener("click", event -> {
                long now = System.currentTimeMillis();
                if (now - lastTradeRequestAt < REQUEST_COOLDOWN_MS) {
                    setText(doc, "aui-msg", "上一笔请求仍在处理中，请稍候");
                    return;
                }
                String key = "batch:" + action;
                if (!key.equals(armedAction) || now > armedUntil) {
                    armedAction = key;
                    armedUntil = now + CONFIRM_WINDOW_MS;
                    setText(doc, "aui-msg", "再次点击确认" + ("sell-all-holdings".equals(action)
                            ? "清仓出货" : "尽数撤单"));
                    return;
                }
                armedAction = null;
                armedUntil = 0;
                lastTradeRequestAt = now;
                if ("sell-all-holdings".equals(action)) {
                    PacketDistributor.sendToServer(new SellAllHoldingsRequestC2S());
                } else {
                    PacketDistributor.sendToServer(new CancelAllOrdersRequestC2S());
                }
                setText(doc, "aui-msg", "批量请求已发送，请等待服务器确认");
            });
        }

        private void renderStockControls(Document doc) {
            setActive(doc, "aui-stock-filter-all", "all".equals(stockFilter));
            setActive(doc, "aui-stock-filter-held", "held".equals(stockFilter));
            setActive(doc, "aui-stock-filter-up", "up".equals(stockFilter));
            setActive(doc, "aui-stock-filter-down", "down".equals(stockFilter));
            setActive(doc, "aui-stock-sort-default", "default".equals(stockSort));
            setActive(doc, "aui-stock-sort-change", "change".equals(stockSort));
            setActive(doc, "aui-stock-sort-price", "price".equals(stockSort));
        }

        private void bindKlineToggle(Document doc, String id, String indicator) {
            Element button = doc.getElementById(id);
            if (button == null) return;
            button.addEventListener("click", event -> {
                switch (indicator) {
                    case "ma5" -> showMa5 = !showMa5;
                    case "ma10" -> showMa10 = !showMa10;
                    case "volume" -> showVolume = !showVolume;
                    default -> {
                        return;
                    }
                }
                renderKlineControls(doc);
                renderKline(doc, findSelected());
            });
        }

        private void renderKlineControls(Document doc) {
            setActive(doc, "aui-kline-ma5", showMa5);
            setActive(doc, "aui-kline-ma10", showMa10);
            setActive(doc, "aui-kline-volume", showVolume);
        }

        private List<StockInfo> visibleStocks() {
            if (pending == null) return List.of();
            Map<String, Integer> holdings = pending.account().holdings();
            java.util.stream.Stream<StockInfo> stream = stocks.stream().filter(stock -> switch (stockFilter) {
                case "held" -> holdings.getOrDefault(stock.id(), 0) > 0;
                case "up" -> stock.changePct() >= 0;
                case "down" -> stock.changePct() < 0;
                default -> true;
            });
            if ("change".equals(stockSort)) {
                stream = stream.sorted(Comparator.comparingDouble(StockInfo::changePct).reversed());
            } else if ("price".equals(stockSort)) {
                stream = stream.sorted(Comparator.comparingDouble(StockInfo::price));
            }
            return stream.toList();
        }

        private void bindPriceStepper(Document doc, String id, double delta) {
            Element stepper = doc.getElementById(id);
            if (stepper == null) return;
            stepper.addEventListener("click", event -> {
                limitPrice = Math.max(0.01, Math.round((limitPrice + delta) * 100.0) / 100.0);
                Element price = doc.getElementById("aui-price");
                if (price != null) {
                    price.setTextContent(MONEY.format(limitPrice));
                }
                updateEstimate(doc);
            });
        }

        private void updateEstimate(Document doc) {
            setText(doc, "aui-estimate", "≈ " + MONEY.format(limitPrice * quantity));
        }

        void update(MarketSnapshotC2S payload) {
            pending = payload;
            Minecraft.getInstance().execute(this::applyPending);
        }

        private void applyPending() {
            if (pending == null) return;
            Document doc = getLinkedDocument();
            if (doc == null) return;
            bindDocument(doc);
            maxQty = Math.max(1, pending.maxOrderQty());
            quantity = Math.min(quantity, maxQty);
            stocks = pending.stocks();
            if (selectedStockId == null && !stocks.isEmpty()) {
                selectedStockId = stocks.get(0).id();
                limitPrice = stocks.get(0).price();
            }
            if (pending.message() != null) {
                setText(doc, "aui-msg", pending.message());
            }
            renderStockControls(doc);
            renderStocks(doc);
            renderMarketOverview(doc);
            renderNewsPage(doc);
            renderAccount(doc, pending.account());
            renderKlineControls(doc);
            renderSelected(doc);
            switchView(doc, currentView);
        }

        private void renderMarketOverview(Document doc) {
            if (pending == null) return;
            MarketIndexInfo index = pending.indices().isEmpty() ? null : pending.indices().get(0);
            if (index != null) {
                setText(doc, "aui-index-summary", index.name() + " " + MONEY.format(index.value()));
                setText(doc, "aui-index-change", String.format("%+.2f%%", index.changePct()));
                Element change = doc.getElementById("aui-index-change");
                if (change != null) change.setAttribute("class", index.changePct() >= 0 ? "stock-up" : "stock-down");
            }
            Element list = doc.getElementById("aui-news-list");
            if (list == null) return;
            List<MarketNews> items = pending.news().stream().limit(1).toList();
            List<Element> existing = new ArrayList<>(list.getChildren());
            if (items.isEmpty()) {
                if (existing.size() != 1 || !"true".equals(existing.get(0).getAttribute("data-empty"))) {
                    for (Element child : existing) list.removeChild(child);
                    Element empty = doc.createElement("span");
                    empty.setAttribute("data-empty", "true");
                    empty.setTextContent("暂无见闻");
                    list.appendChild(empty);
                }
                return;
            }
            MarketNews item = items.get(0);
            Element row = existing.isEmpty() ? null : existing.get(0);
            if (row == null || !"news-row".equals(row.getAttribute("class"))) {
                if (row != null) list.removeChild(row);
                row = doc.createElement("div");
                row.setAttribute("class", "news-row");
                row.appendChild(doc.createElement("span"));
                row.appendChild(doc.createElement("span"));
                list.appendChild(row);
            }
            List<Element> cells = new ArrayList<>(row.getChildren());
            while (cells.size() < 2) {
                row.appendChild(doc.createElement("span"));
                cells = new ArrayList<>(row.getChildren());
            }
            cells.get(0).setTextContent(item.title());
            cells.get(1).setTextContent("D" + item.dayIndex() + " · " + item.detail());
        }

        private void renderNewsPage(Document doc) {
            if (pending == null) return;
            Element list = doc.getElementById("aui-news-page");
            if (list == null) return;
            List<MarketNews> items = pending.news();
            List<Element> existing = new ArrayList<>(list.getChildren());
            if (items.isEmpty()) {
                if (existing.size() == 1 && "true".equals(existing.get(0).getAttribute("data-empty"))) return;
                for (Element child : existing) list.removeChild(child);
                Element empty = doc.createElement("div");
                empty.setAttribute("class", "empty-state");
                empty.setAttribute("data-empty", "true");
                empty.setTextContent("暂无见闻");
                list.appendChild(empty);
                setText(doc, "aui-news-hint", "暂无见闻");
                return;
            }
            setText(doc, "aui-news-hint", "共 " + items.size() + " 条 · 最新见闻在上");
            boolean sameStructure = existing.size() == items.size();
            if (sameStructure) {
                for (int i = 0; i < items.size(); i++) {
                    if (!String.valueOf(items.get(i).id()).equals(existing.get(i).getAttribute("data-news"))) {
                        sameStructure = false;
                        break;
                    }
                }
            }
            if (!sameStructure) {
                for (Element child : existing) list.removeChild(child);
                existing.clear();
            }
            for (int i = 0; i < items.size(); i++) {
                MarketNews item = items.get(i);
                Element row = i < existing.size() ? existing.get(i) : null;
                if (row == null) {
                    row = createNewsPageRow(doc);
                    list.appendChild(row);
                }
                updateNewsPageRow(row, item);
            }
        }

        private Element createNewsPageRow(Document doc) {
            Element row = doc.createElement("div");
            row.setAttribute("class", "news-page-row");
            Element meta = doc.createElement("div");
            meta.setAttribute("class", "news-page-meta");
            meta.appendChild(doc.createElement("span"));
            meta.appendChild(doc.createElement("span"));
            row.appendChild(meta);
            row.appendChild(doc.createElement("div"));
            row.appendChild(doc.createElement("div"));
            Element foot = doc.createElement("div");
            foot.setAttribute("class", "news-page-foot");
            foot.appendChild(doc.createElement("span"));
            foot.appendChild(doc.createElement("span"));
            row.appendChild(foot);
            return row;
        }

        private void updateNewsPageRow(Element row, MarketNews item) {
            row.setAttribute("data-news", String.valueOf(item.id()));
            List<Element> children = new ArrayList<>(row.getChildren());
            List<Element> meta = new ArrayList<>(children.get(0).getChildren());
            meta.get(0).setTextContent(newsTypeLabel(item.type()) + " · " + item.industry());
            meta.get(1).setTextContent("第" + item.dayIndex() + "日");
            children.get(1).setAttribute("class", "news-page-title");
            children.get(1).setTextContent(item.title());
            children.get(2).setAttribute("class", "news-page-detail");
            children.get(2).setTextContent(item.detail());
            List<Element> foot = new ArrayList<>(children.get(3).getChildren());
            foot.get(0).setTextContent(stockName(item.stockId()));
            double impact = item.impactPct();
            Element impactElement = foot.get(1);
            impactElement.setAttribute("class", "news-page-impact"
                    + (impact > 0 ? " up" : impact < 0 ? " down" : ""));
            impactElement.setTextContent(impact == 0 ? "价格影响：—" : "价格影响 " + String.format("%+.2f%%", impact));
        }

        private String newsTypeLabel(String type) {
            return switch (type) {
                case "DIVIDEND" -> "分红喜报";
                case "SPLIT" -> "拆股公告";
                case "HALT" -> "停牌通告";
                case "RATING" -> "评级风云";
                case "BUYBACK" -> "回购喜报";
                case "ISSUE" -> "增发公告";
                case "EARNINGS" -> "财报披露";
                case "POLICY" -> "政策风向";
                case "CONTRACT" -> "订单捷报";
                default -> "市井见闻";
            };
        }

        private void renderStocks(Document doc) {
            Element rows = doc.getElementById("aui-stocks");
            if (rows == null) return;
            List<StockInfo> visible = visibleStocks();
            List<Element> existing = new java.util.ArrayList<>(rows.getChildren());
            boolean structureChanged = existing.size() != visible.size();
            if (!structureChanged) {
                for (int i = 0; i < visible.size(); i++) {
                    if (!visible.get(i).id().equals(existing.get(i).getAttribute("data-stock"))) {
                        structureChanged = true;
                        break;
                    }
                }
            }
            // 行情推送每 5 秒一次：股票集合不变时只更新已有行的文本/样式，
            // 避免高频重建 DOM；只有股票列表变化（重载）才重建行。
            if (structureChanged) {
                for (Element child : existing) {
                    rows.removeChild(child);
                }
                existing.clear();
            }
            for (int i = 0; i < visible.size(); i++) {
                StockInfo stock = visible.get(i);
                Element row = i < existing.size() ? existing.get(i) : null;
                if (row == null) {
                    row = createStockRow(doc);
                    rows.appendChild(row);
                }
                updateStockRow(row, stock);
            }
            if (visible.isEmpty() && rows.getChildren().isEmpty()) {
                Element empty = doc.createElement("div");
                empty.setAttribute("class", "empty-state");
                empty.setAttribute("data-empty", "true");
                empty.setTextContent("暂无符合条件的股票");
                rows.appendChild(empty);
            } else if (!visible.isEmpty()) {
                for (Element child : new ArrayList<>(rows.getChildren())) {
                    if ("true".equals(child.getAttribute("data-empty"))) rows.removeChild(child);
                }
            }
        }

        private Element createStockRow(Document doc) {
            Element row = doc.createElement("div");
            Element name = doc.createElement("span");
            name.setAttribute("class", "stock-name");
            row.appendChild(name);
            Element price = doc.createElement("span");
            price.setAttribute("class", "stock-price");
            row.appendChild(price);
            row.addEventListener("click", event -> {
                selectedStockId = row.getAttribute("data-stock");
                StockInfo selected = findSelected();
                if (selected != null) {
                    limitPrice = selected.price();
                }
                resetKlineHover();
                Document d = getLinkedDocument();
                if (d != null) {
                    renderStocks(d);
                    renderSelected(d);
                }
            });
            return row;
        }

        private void updateStockRow(Element row, StockInfo stock) {
            row.setAttribute("data-stock", stock.id());
            row.setAttribute("class", "stock-row" + (stock.id().equals(selectedStockId) ? " selected" : ""));
            double change = stock.changePct();
            String color = change >= 0 ? "stock-up" : "stock-down";
            List<Element> children = new java.util.ArrayList<>(row.getChildren());
            children.get(0).setTextContent(stock.name() + " · " + stock.industry()
                    + (stock.halted() ? "（停牌）" : ""));
            Element price = children.get(1);
            price.setAttribute("class", "stock-price " + color);
            price.setTextContent(MONEY.format(stock.price()) + " (" + String.format("%+.2f%%", change) + ")");
        }

        /**
         * 用当前行情列表把股票内部 ID 解析为中文名称；
         * 找不到（如列表为空或股票已下线）时回退返回原始 ID，避免空文本。
         */
        private String stockName(String stockId) {
            for (StockInfo stock : stocks) {
                if (stock.id().equals(stockId)) {
                    return stock.name();
                }
            }
            return stockId;
        }

        private void renderAccount(Document doc, AccountInfo account) {
            setText(doc, "aui-cash", MONEY.format(account.cash()));
            setText(doc, "aui-position-value", MONEY.format(account.holdingsValue()));
            setText(doc, "aui-total", MONEY.format(account.totalValue()));
            setPnl(doc, "aui-daily-pnl", account.dailyPnl());
            setPnl(doc, "aui-total-pnl", account.totalPnl());
            StringBuilder holdings = new StringBuilder();
            if (account.holdings().isEmpty()) {
                holdings.append("暂无持股");
            } else {
                for (Map.Entry<String, Integer> entry : account.holdings().entrySet()) {
                    if (holdings.length() > 0) holdings.append("、");
                    holdings.append(stockName(entry.getKey())).append("×").append(entry.getValue());
                }
            }
            setText(doc, "aui-holdings", holdings.toString());
            setText(doc, "aui-account-detail", "余银 " + MONEY.format(account.cash())
                    + " · 冻结银两 " + MONEY.format(account.reservedCash())
                    + " · 可用持股 " + account.availableHoldingsQuantity() + "股 / "
                    + MONEY.format(account.availableHoldingsValue())
                    + " · 冻结持股 " + account.reservedHoldingsQuantity() + "股 / "
                    + MONEY.format(account.reservedHoldingsValue()));
            renderPortfolio(doc, account);
            renderOrdersPage(doc, account);
        }

        private void renderPortfolio(Document doc, AccountInfo account) {
            setText(doc, "aui-portfolio-value", MONEY.format(account.holdingsValue()));
            setPnl(doc, "aui-unrealized-pnl", account.unrealizedPnl());
            setPnl(doc, "aui-realized-pnl", account.realizedPnl());
            setPnl(doc, "aui-portfolio-daily-pnl", account.dailyPnl());
            setText(doc, "aui-portfolio-hint", "可用 " + account.availableHoldingsQuantity()
                    + " 股 · 冻结 " + account.reservedHoldingsQuantity() + " 股 · 点选股票入市");
            renderPortfolioControls(doc);

            Element list = doc.getElementById("aui-portfolio-list");
            if (list == null) return;
            List<StockInfo> heldStocks = new ArrayList<>();
            for (StockInfo stock : stocks) {
                if (account.holdings().getOrDefault(stock.id(), 0) > 0) {
                    heldStocks.add(stock);
                }
            }
            if ("pnl".equals(portfolioSort)) {
                heldStocks.sort(Comparator.comparingDouble((StockInfo stock) -> portfolioPnl(stock, account)).reversed()
                        .thenComparing(StockInfo::id));
            } else if ("value".equals(portfolioSort)) {
                heldStocks.sort(Comparator.comparingDouble((StockInfo stock) -> portfolioValue(stock, account)).reversed()
                        .thenComparing(StockInfo::id));
            } else if ("change".equals(portfolioSort)) {
                heldStocks.sort(Comparator.comparingDouble(StockInfo::changePct).reversed()
                        .thenComparing(StockInfo::id));
            }
            List<Element> existing = new ArrayList<>(list.getChildren());
            boolean sameStructure = existing.size() == heldStocks.size();
            if (sameStructure) {
                for (int i = 0; i < heldStocks.size(); i++) {
                    if (!heldStocks.get(i).id().equals(existing.get(i).getAttribute("data-stock"))) {
                        sameStructure = false;
                        break;
                    }
                }
            }
            if (!sameStructure) {
                for (Element child : existing) list.removeChild(child);
                existing.clear();
            }
            for (int i = 0; i < heldStocks.size(); i++) {
                StockInfo stock = heldStocks.get(i);
                Element row = i < existing.size() ? existing.get(i) : null;
                if (row == null) {
                    row = createPortfolioRow(doc);
                    list.appendChild(row);
                }
                updatePortfolioRow(row, stock, account);
            }
            if (heldStocks.isEmpty() && list.getChildren().isEmpty()) {
                Element empty = doc.createElement("div");
                empty.setAttribute("class", "empty-state");
                empty.setAttribute("data-empty", "true");
                empty.setTextContent("暂无持股，入市买入后自会显示");
                list.appendChild(empty);
            } else if (!heldStocks.isEmpty()) {
                for (Element child : new ArrayList<>(list.getChildren())) {
                    if ("true".equals(child.getAttribute("data-empty"))) list.removeChild(child);
                }
            }
        }

        private Element createPortfolioRow(Document doc) {
            Element row = doc.createElement("div");
            row.setAttribute("class", "portfolio-row");
            row.addEventListener("click", event -> {
                selectedStockId = row.getAttribute("data-stock");
                StockInfo selected = findSelected();
                if (selected != null) limitPrice = selected.price();
                Document current = getLinkedDocument();
                if (current != null) {
                    switchView(current, "quotes");
                    renderStocks(current);
                    renderSelected(current);
                }
            });
            for (int i = 0; i < 9; i++) {
                Element cell = doc.createElement("span");
                if (i == 0) cell.setAttribute("class", "portfolio-cell-name");
                else if (i == 8) cell.setAttribute("class", "portfolio-action");
                else cell.setAttribute("class", "portfolio-cell");
                row.appendChild(cell);
            }
            Element actions = row.getChildren().get(8);
            actions.setAttribute("class", "portfolio-action");
            addSellButton(doc, row, actions, 25, "25%");
            addSellButton(doc, row, actions, 50, "50%");
            addSellButton(doc, row, actions, 100, "全部");
            return row;
        }

        private void addSellButton(Document doc, Element row, Element actions, int percent, String label) {
            Element action = doc.createElement("button");
            action.setAttribute("type", "button");
            action.setTextContent(label);
            actions.appendChild(action);
            action.addEventListener("click", event -> {
                event.stopPropagation();
                int held = pending == null ? 0 : pending.account().holdings()
                        .getOrDefault(row.getAttribute("data-stock"), 0);
                int amount = percent >= 100 ? held : Math.max(1, (held * percent + 99) / 100);
                if (amount > 0) {
                    Document current = getLinkedDocument();
                    if (current != null) {
                        requestMarketTrade(current, row.getAttribute("data-stock"), amount, false,
                                "卖出" + label);
                    }
                }
            });
        }

        private void updatePortfolioRow(Element row, StockInfo stock, AccountInfo account) {
            int quantity = account.holdings().getOrDefault(stock.id(), 0);
            double basis = account.costBasis().getOrDefault(stock.id(), 0.0);
            double averageCost = quantity <= 0 || basis <= 0 ? 0 : basis / quantity;
            double pnl = stock.price() * quantity - basis;
            double marketValue = stock.price() * quantity;
            row.setAttribute("data-stock", stock.id());
            List<Element> cells = new ArrayList<>(row.getChildren());
            cells.get(0).setTextContent(stock.name() + " · " + stock.industry());
            cells.get(1).setTextContent(String.valueOf(quantity));
            cells.get(2).setTextContent(MONEY.format(marketValue));
            cells.get(3).setTextContent(averageCost <= 0 ? "—" : MONEY.format(averageCost));
            cells.get(4).setTextContent(MONEY.format(stock.price()) + " (" + String.format("%+.2f%%", stock.changePct()) + ")");
            double dailyPnl = (stock.price() - stock.prevClose()) * quantity;
            double pnlPct = basis <= 0 ? 0 : pnl / basis * 100.0;
            cells.get(5).setTextContent(formatPnl(dailyPnl));
            cells.get(5).setAttribute("class", "portfolio-cell pnl" + (dailyPnl < 0 ? " down" : ""));
            cells.get(6).setTextContent(formatPnl(pnl));
            cells.get(6).setAttribute("class", "portfolio-cell pnl" + (pnl < 0 ? " down" : ""));
            cells.get(7).setTextContent(formatPnl(pnlPct) + "%");
            cells.get(7).setAttribute("class", "portfolio-cell pnl" + (pnlPct < 0 ? " down" : ""));
        }

        private double portfolioValue(StockInfo stock, AccountInfo account) {
            return stock.price() * account.holdings().getOrDefault(stock.id(), 0);
        }

        private double portfolioPnl(StockInfo stock, AccountInfo account) {
            return portfolioValue(stock, account) - account.costBasis().getOrDefault(stock.id(), 0.0);
        }

        private void renderOrdersPage(Document doc, AccountInfo account) {
            Element list = doc.getElementById("aui-orders-page");
            if (list != null) {
                List<OrderInfo> filtered = account.orders().stream()
                        .filter(order -> "all".equals(orderFilter)
                                || ("buy".equals(orderFilter) && order.buy())
                                || ("sell".equals(orderFilter) && !order.buy()))
                        .toList();
                renderOrderList(doc, list, filtered, "暂无符合条件的挂单");
            }
            renderTradeHistory(doc, account.trades());
            renderOrderFilters(doc);
        }

        private void renderTradeHistory(Document doc, List<TradeInfo> trades) {
            Element list = doc.getElementById("aui-trade-history");
            if (list == null) return;
            List<Element> existing = new ArrayList<>(list.getChildren());
            if (trades.isEmpty()) {
                if (existing.size() == 1 && "true".equals(existing.get(0).getAttribute("data-empty"))) return;
                for (Element child : existing) list.removeChild(child);
                Element empty = doc.createElement("div");
                empty.setAttribute("data-empty", "true");
                empty.setAttribute("class", "empty-state");
                empty.setTextContent("暂无成交记录");
                list.appendChild(empty);
                return;
            }
            boolean sameStructure = existing.size() == trades.size();
            if (sameStructure) {
                for (int i = 0; i < trades.size(); i++) {
                    if (!String.valueOf(i).equals(existing.get(i).getAttribute("data-trade"))) {
                        sameStructure = false;
                        break;
                    }
                }
            }
            if (!sameStructure) {
                for (Element child : existing) list.removeChild(child);
                existing.clear();
            }
            for (int i = 0; i < trades.size(); i++) {
                Element row = i < existing.size() ? existing.get(i) : null;
                if (row == null) {
                    row = doc.createElement("div");
                    row.setAttribute("class", "trade-history-row");
                    row.setAttribute("data-trade", String.valueOf(i));
                    row.appendChild(doc.createElement("span"));
                    row.appendChild(doc.createElement("span"));
                    list.appendChild(row);
                }
                TradeInfo trade = trades.get(i);
                List<Element> cells = new ArrayList<>(row.getChildren());
                cells.get(0).setAttribute("class", trade.buy() ? "trade-buy" : "trade-sell");
                cells.get(0).setTextContent((trade.buy() ? "买入 " : "卖出 ") + stockName(trade.stockId())
                        + " ×" + trade.quantity() + " @" + MONEY.format(trade.price()));
                cells.get(1).setAttribute("class", "trade-note");
                cells.get(1).setTextContent("第" + trade.dayIndex() + "日 · 手续费 " + MONEY.format(trade.fee()));
            }
        }

        private void renderOrderList(Document doc, Element list, List<OrderInfo> orders, String emptyText) {
            List<Element> existing = new ArrayList<>(list.getChildren());
            boolean emptyPlaceholder = orders.isEmpty() && existing.size() == 1
                    && "true".equals(existing.get(0).getAttribute("data-empty"));
            if (orders.isEmpty()) {
                if (!emptyPlaceholder) {
                    for (Element child : existing) list.removeChild(child);
                    Element placeholder = doc.createElement("span");
                    placeholder.setAttribute("data-empty", "true");
                    placeholder.setTextContent(emptyText);
                    list.appendChild(placeholder);
                }
                return;
            }
            boolean sameStructure = existing.size() == orders.size();
            if (sameStructure) {
                for (int i = 0; i < orders.size(); i++) {
                    if (parseOrderId(existing.get(i).getAttribute("data-order")) != orders.get(i).orderId()) {
                        sameStructure = false;
                        break;
                    }
                }
            }
            if (!sameStructure) {
                for (Element child : existing) list.removeChild(child);
                existing.clear();
            }
            for (int i = 0; i < orders.size(); i++) {
                Element row = i < existing.size() ? existing.get(i) : null;
                if (row == null) {
                    row = createOrderRow(doc, orders.get(i));
                    list.appendChild(row);
                }
                updateOrderRow(row, orders.get(i));
            }
        }

        private Element createOrderRow(Document doc, OrderInfo order) {
            Element row = doc.createElement("div");
            row.setAttribute("class", "order-row");
            row.setAttribute("data-order", String.valueOf(order.orderId()));
            Element text = doc.createElement("span");
            row.appendChild(text);
            Element cancel = doc.createElement("button");
            cancel.setAttribute("class", "cancel");
            cancel.setAttribute("type", "button");
            cancel.setTextContent("撤单");
            cancel.addEventListener("click", event -> {
                long orderId = parseOrderId(row.getAttribute("data-order"));
                if (orderId > 0) {
                    PacketDistributor.sendToServer(new CancelOrderRequestC2S(orderId));
                }
            });
            row.appendChild(cancel);
            updateOrderRow(row, order);
            return row;
        }

        private void updateOrderRow(Element row, OrderInfo order) {
            List<Element> children = new java.util.ArrayList<>(row.getChildren());
            children.get(0).setTextContent((order.buy() ? "买" : "卖") + stockName(order.stockId())
                    + "×" + order.quantity() + "@" + MONEY.format(order.price()));
        }

        private long parseOrderId(String value) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        private void renderSelected(Document doc) {
            StockInfo stock = findSelected();
            setText(doc, "aui-selected", stock == null ? "未选择" : stock.name() + " · " + stock.industry()
                    + " · " + MONEY.format(stock.price()) + (stock.halted() ? " · 停牌" : ""));
            setText(doc, "aui-qty", String.valueOf(quantity));
            setText(doc, "aui-price", MONEY.format(limitPrice));
            setText(doc, "aui-estimate", "≈ " + MONEY.format(limitPrice * quantity));
            renderKline(doc, stock);
        }

        private void bindKlineHover(Document doc) {
            Element element = doc.getElementById("aui-kline");
            if (!(element instanceof com.sighs.apricityui.element.Canvas canvas)) return;
            canvas.addEventListener("mousemove", event -> {
                if (!(event instanceof MouseEvent mouse)) return;
                StockInfo stock = findSelected();
                if (stock == null || stock.history() == null || stock.history().isEmpty()) return;
                Element.DOMRect rect = canvas.getBoundingClientRect();
                if (rect.width <= 0 || rect.height <= 0) return;
                int logicalWidth = Math.max(1, (int) Math.round(canvas.getWidth() / KLINE_RASTER_SCALE));
                int logicalHeight = Math.max(1, (int) Math.round(canvas.getHeight() / KLINE_RASTER_SCALE));
                double x = (mouse.clientX - rect.x) / rect.width * logicalWidth;
                double y = (mouse.clientY - rect.y) / rect.height * logicalHeight;
                double left = 38;
                double right = 46;
                double top = 8;
                double bottom = 18;
                double volumeHeight = showVolume ? Math.min(27, Math.max(16, logicalHeight * 0.22)) : 0;
                double priceBottom = logicalHeight - bottom - volumeHeight;
                double slot = Math.max(1, (logicalWidth - left - right) / (double) stock.history().size());
                int index = KlineChartMath.nearestCandleIndex(stock.history().size(), x, left, slot);
                double nextX = left + slot * index + slot / 2;
                double nextY = Math.max(top, Math.min(priceBottom, y));
                if (index != hoverCandleIndex || !Double.isFinite(hoverY) || Math.abs(nextY - hoverY) >= 0.5) {
                    hoverCandleIndex = index;
                    hoverX = nextX;
                    hoverY = nextY;
                    renderKline(doc, stock);
                }
            });
            canvas.addEventListener("mouseleave", event -> {
                if (hoverCandleIndex >= 0) {
                    resetKlineHover();
                    renderKline(doc, findSelected());
                }
            });
        }

        private void resetKlineHover() {
            hoverCandleIndex = -1;
            hoverX = Double.NaN;
            hoverY = Double.NaN;
            lastKlineRenderSignature = null;
        }

        private void renderKline(Document doc, StockInfo stock) {
            Element summary = doc.getElementById("aui-kline-summary");
            if (stock == null) {
                setText(doc, "aui-kline-summary", "未选股票");
                if (summary != null) summary.setAttribute("class", "kline-summary");
            } else {
                setText(doc, "aui-kline-summary", "现价 " + MONEY.format(stock.price())
                        + " · " + String.format("%+.2f%%", stock.changePct())
                        + " · 日高 " + MONEY.format(stock.dayHigh())
                        + " · 日低 " + MONEY.format(stock.dayLow())
                        + " · 量 " + VOLUME.format(stock.volume()));
                if (summary != null) summary.setAttribute("class",
                        stock.changePct() >= 0 ? "kline-summary stock-up" : "kline-summary stock-down");
            }
            renderKlineHover(doc, stock);

            String signature = KlineChartMath.dataSignature(stock)
                    + ":" + showMa5 + ":" + showMa10 + ":" + showVolume
                    + ":" + hoverCandleIndex + ":" + (Double.isFinite(hoverY) ? Math.round(hoverY * 2) : "none");
            if (signature.equals(lastKlineRenderSignature)) return;

            com.sighs.apricityui.element.Canvas canvas;
            try {
                canvas = (com.sighs.apricityui.element.Canvas) doc.getElementById("aui-kline");
            } catch (ClassCastException e) {
                return;
            }
            if (canvas == null) return;
            lastKlineRenderSignature = signature;
            var ctx = canvas.getContext("2d");
            int bitmapWidth = canvas.getWidth();
            int bitmapHeight = canvas.getHeight();
            ctx.clearRect(0, 0, bitmapWidth, bitmapHeight);
            if (stock == null || stock.history() == null || stock.history().size() < 2
                    || bitmapWidth < 80 || bitmapHeight < 40) return;

            // The HTML canvas is displayed much wider than its logical 600px design
            // size. Render into a 2x backing surface, then let CSS scale it down;
            // otherwise text, grid lines and candle bodies become visibly blurry.
            ctx.save();
            try {
                ctx.scale(KLINE_RASTER_SCALE, KLINE_RASTER_SCALE);
                int width = Math.max(1, (int) Math.round(bitmapWidth / KLINE_RASTER_SCALE));
                int height = Math.max(1, (int) Math.round(bitmapHeight / KLINE_RASTER_SCALE));
                List<Candle> history = stock.history();
                KlineChartMath.PriceRange range = KlineChartMath.priceRange(history);
                double min = range.min();
                double max = range.max();
                long maxVolume = KlineChartMath.maxVolume(history);
                double left = 38;
                double right = 46;
                double top = 8;
                double bottom = 18;
                double volumeHeight = showVolume ? Math.min(27, Math.max(16, height * 0.22)) : 0;
                double priceBottom = height - bottom - volumeHeight;
                double plotHeight = Math.max(20, priceBottom - top);
                double plotWidth = Math.max(20, width - left - right);
                double slot = plotWidth / history.size();
                double bodyWidth = Math.max(2, Math.min(12, slot * 0.62));
                java.util.function.DoubleUnaryOperator yOf = value -> top + (max - value) / (max - min) * plotHeight;
                java.util.function.DoubleUnaryOperator xOf = index -> left + slot * index + slot / 2;

                ctx.setFont("10px sans-serif");
                ctx.setTextAlign("right");
                ctx.setTextBaseline("middle");
                ctx.setLineWidth(1);
                for (int i = 0; i <= 4; i++) {
                    double ratio = i / 4.0;
                    double y = top + plotHeight * ratio;
                    double value = max - (max - min) * ratio;
                    ctx.setStrokeStyle(i == 4 ? "#b9b09f" : "#d8d1c3");
                    ctx.beginPath();
                    ctx.moveTo(left, y);
                    ctx.lineTo(width - right, y);
                    ctx.stroke();
                    ctx.setFillStyle("#6d7168");
                    ctx.fillText(MONEY.format(value), left - 5, y);
                }

                if (showVolume) {
                    double volumeTop = priceBottom + 4;
                    ctx.setStrokeStyle("#d8d1c3");
                    ctx.beginPath();
                    ctx.moveTo(left, volumeTop - 2);
                    ctx.lineTo(width - right, volumeTop - 2);
                    ctx.stroke();
                    for (int i = 0; i < history.size(); i++) {
                        Candle candle = history.get(i);
                        if (candle == null) continue;
                        double barHeight = KlineChartMath.volumeBarHeight(candle.volume(), maxVolume, volumeHeight);
                        String color = candle.close() >= candle.open() ? "#b3483f" : "#36835d";
                        ctx.setFillStyle(color);
                        ctx.setGlobalAlpha(0.45);
                        ctx.fillRect(xOf.applyAsDouble(i) - Math.max(1, bodyWidth / 2),
                                priceBottom + volumeHeight - barHeight, bodyWidth, barHeight);
                    }
                    ctx.setGlobalAlpha(1);
                    ctx.setFillStyle("#8b8c82");
                    ctx.setTextAlign("left");
                    ctx.setTextBaseline("top");
                    ctx.fillText("成交量", left, height - bottom + 2);
                }

                for (int i = 0; i < history.size(); i++) {
                    Candle candle = history.get(i);
                    if (candle == null || !Double.isFinite(candle.open()) || !Double.isFinite(candle.close())
                            || !Double.isFinite(candle.high()) || !Double.isFinite(candle.low())) continue;
                    double x = xOf.applyAsDouble(i);
                    boolean up = candle.close() >= candle.open();
                    String color = up ? "#b3483f" : "#36835d";
                    ctx.setStrokeStyle(color);
                    ctx.setFillStyle(color);
                    ctx.beginPath();
                    ctx.moveTo(x, yOf.applyAsDouble(candle.high()));
                    ctx.lineTo(x, yOf.applyAsDouble(candle.low()));
                    ctx.stroke();
                    double yOpen = yOf.applyAsDouble(candle.open());
                    double yClose = yOf.applyAsDouble(candle.close());
                    double candleTop = Math.min(yOpen, yClose);
                    double candleHeight = Math.max(1.0, Math.abs(yOpen - yClose));
                    ctx.fillRect(x - bodyWidth / 2, candleTop, bodyWidth, candleHeight);
                }

                if (showMa5) drawMovingAverage(ctx, history, 5, "#a47723", left, slot, yOf);
                if (showMa10) drawMovingAverage(ctx, history, 10, "#5b7890", left, slot, yOf);

                Candle last = history.get(history.size() - 1);
                if (last != null && Double.isFinite(last.close())) {
                    double lastY = yOf.applyAsDouble(last.close());
                    ctx.setStrokeStyle("#966b1f");
                    ctx.setLineDash(new double[]{3, 3});
                    ctx.beginPath();
                    ctx.moveTo(left, lastY);
                    ctx.lineTo(width - right, lastY);
                    ctx.stroke();
                    ctx.setLineDash(new double[]{});
                    ctx.setFillStyle("#966b1f");
                    ctx.setTextAlign("left");
                    ctx.setTextBaseline("middle");
                    ctx.fillText(MONEY.format(last.close()), width - right + 5, lastY);
                }

                if (hoverCandleIndex >= 0 && hoverCandleIndex < history.size()
                        && Double.isFinite(hoverX) && Double.isFinite(hoverY)) {
                    ctx.setStrokeStyle("#85877f");
                    ctx.setGlobalAlpha(0.8);
                    ctx.setLineDash(new double[]{2, 2});
                    ctx.beginPath();
                    ctx.moveTo(hoverX, top);
                    ctx.lineTo(hoverX, priceBottom);
                    ctx.moveTo(left, hoverY);
                    ctx.lineTo(width - right, hoverY);
                    ctx.stroke();
                    ctx.setLineDash(new double[]{});
                    ctx.setGlobalAlpha(1);
                    ctx.setFillStyle("#85877f");
                    ctx.fillRect(hoverX - 2, hoverY - 2, 4, 4);
                }

                ctx.setFillStyle("#8b8c82");
                ctx.setTextBaseline("top");
                ctx.setTextAlign("left");
                ctx.fillText("D" + history.get(0).dayIndex(), left, height - bottom + 2);
                ctx.setTextAlign("center");
                ctx.fillText("D" + history.get(history.size() / 2).dayIndex(),
                        xOf.applyAsDouble(history.size() / 2), height - bottom + 2);
                ctx.setTextAlign("right");
                ctx.fillText("D" + lastDay(history), width - right, height - bottom + 2);
            } finally {
                ctx.restore();
            }
        }

        private void renderKlineHover(Document doc, StockInfo stock) {
            if (stock == null || stock.history() == null || hoverCandleIndex < 0
                    || hoverCandleIndex >= stock.history().size()) {
                setKlineHoverEmpty(doc);
                return;
            }
            Candle candle = stock.history().get(hoverCandleIndex);
            if (candle == null) {
                setKlineHoverEmpty(doc);
                return;
            }
            setText(doc, "aui-kline-hover-day", "D" + candle.dayIndex());
            setText(doc, "aui-kline-hover-open", MONEY.format(candle.open()));
            setText(doc, "aui-kline-hover-high", MONEY.format(candle.high()));
            setText(doc, "aui-kline-hover-low", MONEY.format(candle.low()));
            setText(doc, "aui-kline-hover-close", MONEY.format(candle.close()));
            setText(doc, "aui-kline-hover-volume", VOLUME.format(candle.volume()));
        }

        private void setKlineHoverEmpty(Document doc) {
            setText(doc, "aui-kline-hover-day", "移鼠观当日");
            setText(doc, "aui-kline-hover-open", "—");
            setText(doc, "aui-kline-hover-high", "—");
            setText(doc, "aui-kline-hover-low", "—");
            setText(doc, "aui-kline-hover-close", "—");
            setText(doc, "aui-kline-hover-volume", "—");
        }

        private long lastDay(List<Candle> history) {
            for (int i = history.size() - 1; i >= 0; i--) {
                if (history.get(i) != null) return history.get(i).dayIndex();
            }
            return 0;
        }

        private void drawMovingAverage(com.sighs.apricityui.canvas.CanvasRenderingContext2D ctx,
                                       List<Candle> history,
                                       int period, String color, double left, double slot,
                                       java.util.function.DoubleUnaryOperator yOf) {
            if (history.size() < period) return;
            ctx.setStrokeStyle(color);
            ctx.setLineWidth(1.2);
            ctx.beginPath();
            boolean started = false;
            for (int i = period - 1; i < history.size(); i++) {
                double average = KlineChartMath.movingAverage(history, i, period);
                if (!Double.isFinite(average)) continue;
                double x = left + slot * i + slot / 2;
                double y = yOf.applyAsDouble(average);
                if (!started) {
                    ctx.moveTo(x, y);
                    started = true;
                } else {
                    ctx.lineTo(x, y);
                }
            }
            if (started) ctx.stroke();
        }

        private StockInfo findSelected() {
            for (StockInfo stock : stocks) {
                if (stock.id().equals(selectedStockId)) return stock;
            }
            return null;
        }

        private void trade(Document doc, boolean buy) {
            if (selectedStockId == null) return;
            requestMarketTrade(doc, selectedStockId, quantity, buy, buy ? "市价买入" : "市价卖出");
        }

        private void limitTrade(Document doc, boolean buy) {
            if (selectedStockId == null) return;
            String label = buy ? "限价买入" : "限价卖出";
            String key = "limit:" + selectedStockId + ":" + buy + ":" + quantity + ":"
                    + MONEY.format(limitPrice);
            if (!confirmAction(doc, key, label, quantity, true)) return;
            PacketDistributor.sendToServer(new LimitOrderRequestC2S(selectedStockId, buy, limitPrice, quantity));
        }

        private void requestMarketTrade(Document doc, String stockId, int amount, boolean buy, String label) {
            String key = "market:" + stockId + ":" + buy + ":" + amount;
            if (!confirmAction(doc, key, label, amount, false)) return;
            PacketDistributor.sendToServer(new TradeRequestC2S(stockId, amount, buy));
        }

        private boolean confirmAction(Document doc, String key, String label, int amount, boolean limit) {
            long now = System.currentTimeMillis();
            if (now - lastTradeRequestAt < REQUEST_COOLDOWN_MS) {
                setText(doc, "aui-msg", "上一笔交易仍在处理中，请稍候");
                return false;
            }
            if (!key.equals(armedAction) || now > armedUntil) {
                armedAction = key;
                armedUntil = now + CONFIRM_WINDOW_MS;
                StringBuilder prompt = new StringBuilder("再次点击").append(label).append("确认（")
                        .append(amount).append(" 股");
                if (amount >= 100) prompt.append("，数量较大");
                if (limit) {
                    StockInfo stock = findSelected();
                    if (stock != null && stock.price() > 0
                            && Math.abs(limitPrice - stock.price()) / stock.price() <= 0.02) {
                        prompt.append("，可能立即成交");
                    }
                }
                prompt.append("）");
                setText(doc, "aui-msg", prompt.toString());
                return false;
            }
            armedAction = null;
            armedUntil = 0;
            lastTradeRequestAt = now;
            setText(doc, "aui-msg", "交易请求已发送，请等待服务器确认");
            return true;
        }

        private static void setText(Document doc, String id, String text) {
            Element el = doc.getElementById(id);
            if (el != null) {
                el.setTextContent(text);
            }
        }

        private static void setPnl(Document doc, String id, double value) {
            Element el = doc.getElementById(id);
            if (el == null) return;
            el.setTextContent(formatPnl(value));
            el.setAttribute("class", value < 0 ? "pnl-down" : "pnl-up");
        }

        private static String formatPnl(double value) {
            return (value >= 0 ? "+" : "") + MONEY.format(value);
        }

        private static void setActive(Document doc, String id, boolean active) {
            Element el = doc.getElementById(id);
            if (el == null) return;
            String base = el.getAttribute("class");
            base = base.replace(" active", "").replace("active", "").trim();
            el.setAttribute("class", active ? base + " active" : base);
        }
    }
}
