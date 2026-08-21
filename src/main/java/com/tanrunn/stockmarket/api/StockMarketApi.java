package com.tanrunn.stockmarket.api;

import com.tanrunn.stockmarket.common.AccountInfo;
import com.tanrunn.stockmarket.common.MarketIndexInfo;
import com.tanrunn.stockmarket.common.MarketNews;
import com.tanrunn.stockmarket.common.StockInfo;
import com.tanrunn.stockmarket.server.market.AccountService;
import com.tanrunn.stockmarket.server.market.HoldingAccount;
import com.tanrunn.stockmarket.server.market.MarketService;
import com.tanrunn.stockmarket.server.market.TradeEngine;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.tanrunn.stockmarket.api.event.BalanceChangedEvent;

/**
 * Stable cross-Mod facade for the server-side stock market.
 *
 * <p>Other Mods should depend on this class and the public records in this
 * package. They must not reach into {@code AccountData}, {@code OrderBook}, or
 * {@code TradeEngine}. All mutating methods are server-authoritative and must
 * be called on the server thread.</p>
 *
 * <p><b>API_VERSION 4 变更</b>：银行桥接金额单位明确为 LC 铜币（1 铜币 = 1 LC
 * {@code main} core value），与证券内部 cents 的换算集中到 {@link ExchangeRates}
 * （玩家可见规则「1 证券资金 = 1 铜币」）；{@link CurrencyBridge} 以
 * {@code balanceCopper} / {@code actualCopper} 表达单位；转账改用阶段状态机
 * （{@link BankTransferPhase}），资金操作幂等键一律使用 {@link OperationIds}
 * 生成的内部 opId。证券资产日常交易仍完全操作 StockMarket 自有证券账户。</p>
 */
public final class StockMarketApi {
    public static final String API_VERSION = "4";
    private static final Map<String, CurrencyBridge> CURRENCY_BRIDGES = new ConcurrentHashMap<>();
    private static final Set<String> AUTHORIZED_WITHDRAWAL_SOURCES = ConcurrentHashMap.newKeySet();

    private StockMarketApi() {
    }

    public static boolean isAvailable() {
        return MarketService.get() != null;
    }

    // ---- account / cash ----

    public static TransactionResult deposit(ServerPlayer player, long cents, String source, String reason) {
        return deposit(player, cents, source, reason, "");
    }

    public static TransactionResult deposit(ServerPlayer player, long cents, String source,
                                            String reason, String requestId) {
        String amountError = CashTransactionRules.validatePositiveAmount(cents);
        if (amountError != null) return TransactionResult.failure(amountError, safeBalance(player));
        return changeCash(player, cents, source, reason, requestId, BalanceChangedEvent.Type.DEPOSIT);
    }

    public static TransactionResult withdraw(ServerPlayer player, long cents, String source, String reason) {
        return withdraw(player, cents, source, reason, "");
    }

    public static TransactionResult withdraw(ServerPlayer player, long cents, String source,
                                             String reason, String requestId) {
        String amountError = CashTransactionRules.validatePositiveAmount(cents);
        if (amountError != null) return TransactionResult.failure(amountError, safeBalance(player));
        return changeCash(player, -cents, source, reason, requestId, BalanceChangedEvent.Type.WITHDRAWAL);
    }

    public static AccountSnapshot account(ServerPlayer player) {
        requirePlayer(player);
        requireServerThread(player);
        MarketService service = requireService();
        AccountService.ensureInitialized(player);
        AccountInfo info = service.accountInfo(player);
        Map<String, Long> basis = new LinkedHashMap<>();
        info.costBasis().forEach((id, value) -> basis.put(id, cents(value)));
        return new AccountSnapshot(
                player.getUUID(),
                cents(info.cash()),
                cents(info.totalValue()),
                cents(info.holdingsValue()),
                cents(info.unrealizedPnl()),
                cents(info.realizedPnl()),
                cents(info.dailyPnl()),
                cents(info.totalPnl()),
                cents(info.reservedCash()),
                cents(info.availableHoldingsValue()),
                cents(info.reservedHoldingsValue()),
                info.availableHoldingsQuantity(),
                info.reservedHoldingsQuantity(),
                info.holdings(),
                basis,
                info.orders().stream().map(order -> new OrderSnapshot(order.orderId(), order.stockId(),
                        order.buy(), cents(order.price()), order.quantity())).toList(),
                info.trades().stream().map(trade -> new TradeSnapshot(trade.dayIndex(), trade.stockId(),
                        trade.buy(), cents(trade.price()), trade.quantity(), cents(trade.fee()))).toList(),
                AccountService.ledger(player));
    }

    public static long balanceCents(ServerPlayer player) {
        return account(player).cashCents();
    }

    /**
     * 服务端只读的股市账户摘要。
     *
     * <p>复用 {@link #account(ServerPlayer)}（含其服务端线程与异常语义），
     * 不再读取 AccountService / MarketService 或内部账户数据。</p>
     *
     * @param player 目标玩家（必须在线）
     * @return 只读摘要
     * @throws IllegalArgumentException player 为 null
     * @throws IllegalStateException 非服务端主线程调用
     */
    public static MarketSummary summary(ServerPlayer player) {
        return fromAccount(account(player));
    }

    /**
     * 纯转换（包内测试友好）：严格由 {@link AccountSnapshot} 字段推导摘要。
     * holdingKinds 为持仓数量大于 0 的股票种类数；openOrderCount 为
     * {@code orders().size()}。
     */
    static MarketSummary fromAccount(AccountSnapshot account) {
        int holdingKinds = 0;
        for (Integer quantity : account.holdings().values()) {
            if (quantity != null && quantity > 0) {
                holdingKinds++;
            }
        }
        return new MarketSummary(
                account.cashCents(),
                account.totalValueCents(),
                account.dailyPnlCents(),
                holdingKinds,
                account.orders().size());
    }

    public static List<TransactionRecord> ledger(ServerPlayer player) {
        requirePlayer(player);
        requireServerThread(player);
        return AccountService.ledger(player);
    }

    /** Registers a trusted source id that is allowed to withdraw account cash. */
    public static void registerWithdrawalSource(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("withdrawal source must not be blank");
        }
        AUTHORIZED_WITHDRAWAL_SOURCES.add(source.trim());
    }

    public static boolean isWithdrawalSourceAuthorized(String source) {
        return source != null && AUTHORIZED_WITHDRAWAL_SOURCES.contains(source.trim());
    }

    // ---- panel ----

    /**
     * 打开该玩家的股市交易界面（服务端权威）。
     *
     * <p>必须在服务端主线程调用，玩家必须在线。内部登记 viewer 并下发
     * {@code openPanel=true} 的行情快照，客户端据此打开 AUI 交易界面并开始
     * 接收行情推送。玩家关闭界面后，客户端会回报并移除 viewer。</p>
     */
    public static void openPanel(ServerPlayer player) {
        requirePlayer(player);
        requireServerThread(player);
        MarketService service = requireService();
        service.addViewer(player.getUUID());
        service.sendSnapshot(player, true, null);
    }

    // ---- market data ----

    public static List<StockQuote> stocks() {
        return requireService().snapshot().stream().map(StockMarketApi::quote).toList();
    }

    public static List<MarketIndexInfo> indices() {
        return requireService().indices();
    }

    public static List<MarketNews> news() {
        return requireService().news();
    }

    public static Optional<StockQuote> quote(String stockId) {
        if (stockId == null || stockId.isBlank() || !isAvailable()) return Optional.empty();
        var stock = requireService().stock(stockId);
        if (stock == null) return Optional.empty();
        StockInfo info = stock.info();
        return Optional.of(quote(info));
    }

    public static List<OrderSnapshot> orders(ServerPlayer player) {
        return account(player).orders();
    }

    public static List<TradeSnapshot> trades(ServerPlayer player) {
        return account(player).trades();
    }

    // ---- trading ----

    public static TradeResult marketOrder(ServerPlayer player, String stockId, boolean buy, int quantity) {
        requirePlayer(player);
        if (!isServerThread(player)) return TradeResult.failure("必须在服务端主线程调用", 0);
        TradeEngine.Result result = requireService().trade(player, stockId, quantity, buy);
        long balance = result.account() == null ? safeBalance(player) : cents(result.account().cash());
        return new TradeResult(result.success(), result.message(), -1, balance);
    }

    public static TradeResult limitOrder(ServerPlayer player, String stockId, boolean buy,
                                         long priceCents, int quantity) {
        requirePlayer(player);
        if (!isServerThread(player)) return TradeResult.failure("必须在服务端主线程调用", 0);
        if (priceCents < 1) return TradeResult.failure("委托价格无效（至少 0.01）", safeBalance(player));
        MarketService.LimitOrderPlacement placement = requireService().placeLimitOrder(
                player, stockId, buy, priceCents / 100.0, quantity);
        long balance = placement.account() == null ? safeBalance(player) : cents(placement.account().cash());
        return new TradeResult(placement.success(), placement.message(), placement.orderId(), balance);
    }

    public static TradeResult cancelOrder(ServerPlayer player, long orderId) {
        requirePlayer(player);
        if (!isServerThread(player)) return TradeResult.failure("必须在服务端主线程调用", 0);
        TradeEngine.Result result = requireService().cancelOrder(player, orderId);
        long balance = result.account() == null ? safeBalance(player) : cents(result.account().cash());
        return new TradeResult(result.success(), result.message(), orderId, balance);
    }

    public static TradeResult cancelAllOrders(ServerPlayer player) {
        requirePlayer(player);
        if (!isServerThread(player)) return TradeResult.failure("必须在服务端主线程调用", 0);
        TradeEngine.Result result = requireService().cancelAllOrders(player);
        long balance = result.account() == null ? safeBalance(player) : cents(result.account().cash());
        return new TradeResult(result.success(), result.message(), -1, balance);
    }

    public static TradeResult sellAllHoldings(ServerPlayer player) {
        requirePlayer(player);
        if (!isServerThread(player)) return TradeResult.failure("必须在服务端主线程调用", 0);
        TradeEngine.Result result = requireService().sellAllHoldings(player);
        long balance = result.account() == null ? safeBalance(player) : cents(result.account().cash());
        return new TradeResult(result.success(), result.message(), -1, balance);
    }

    // ---- optional economy bridge registry ----

    public static void registerCurrencyBridge(CurrencyBridge bridge) {
        if (bridge == null || bridge.id() == null || bridge.id().isBlank()) {
            throw new IllegalArgumentException("currency bridge id must not be blank");
        }
        CurrencyBridge previous = CURRENCY_BRIDGES.putIfAbsent(bridge.id(), bridge);
        if (previous != null) {
            throw new IllegalStateException("currency bridge already registered: " + bridge.id());
        }
    }

    public static Optional<CurrencyBridge> currencyBridge(String id) {
        return Optional.ofNullable(CURRENCY_BRIDGES.get(id));
    }

    public static List<String> currencyBridgeIds() {
        return CURRENCY_BRIDGES.keySet().stream().sorted().toList();
    }

    private static TransactionResult changeCash(ServerPlayer player, long deltaCents, String source,
                                                String reason, String requestId, BalanceChangedEvent.Type type) {
        if (player == null) return TransactionResult.failure("玩家不能为空", 0);
        if (!isServerThread(player)) return TransactionResult.failure("必须在服务端主线程调用", 0);
        if (!isAvailable()) return TransactionResult.failure("证券市场尚未启动", 0);
        if (deltaCents == 0) return TransactionResult.failure("金额必须大于 0", safeBalance(player));
        if (source == null || source.isBlank()) return TransactionResult.failure("来源不能为空", safeBalance(player));
        String normalizedSource = source.trim();
        String normalizedReason = reason == null || reason.isBlank() ? "跨 Mod 账户变更" : reason.trim();
        if (type == BalanceChangedEvent.Type.WITHDRAWAL
                && !AUTHORIZED_WITHDRAWAL_SOURCES.contains(normalizedSource)) {
            return TransactionResult.failure("出金来源未授权：" + normalizedSource, safeBalance(player));
        }
        String normalizedRequestId = requestId == null ? "" : requestId.trim();
        if (normalizedRequestId.length() > 128) {
            return TransactionResult.failure("requestId 不能超过 128 个字符", safeBalance(player));
        }

        AccountService.ensureInitialized(player);
        TransactionRecord existing = AccountService.findTransactionByRequestId(player, normalizedRequestId);
        if (existing != null) {
            if (existing.deltaCents() == deltaCents) {
                return new TransactionResult(true, "请求已处理", existing.balanceCents(),
                        existing.transactionId(), true);
            }
            return TransactionResult.failure("requestId 已用于另一笔金额变更", existing.balanceCents());
        }

        HoldingAccount account = AccountService.get(player);
        long current = cents(account.cash());
        if (type == BalanceChangedEvent.Type.WITHDRAWAL && !CashTransactionRules.canWithdraw(current, -deltaCents)) {
            return TransactionResult.failure("证券账户余额不足", current);
        }
        long next;
        try {
            next = CashTransactionRules.applyDelta(current, deltaCents);
        } catch (ArithmeticException overflow) {
            return TransactionResult.failure("账户余额超出可用范围", current);
        }
        HoldingAccount updated = new HoldingAccount(next / 100.0, account.holdings(), account.costBasis(), account.realizedPnl());
        AccountService.set(player, updated);
        String transactionId = UUID.randomUUID().toString();
        TransactionRecord transaction = new TransactionRecord(transactionId, normalizedRequestId,
                requireService().marketDayIndex(player.server), deltaCents, next,
                normalizedSource, normalizedReason);
        AccountService.recordTransaction(player, transaction);
        NeoForge.EVENT_BUS.post(new BalanceChangedEvent(player, type, deltaCents, next,
                transactionId, normalizedSource, normalizedReason));
        return new TransactionResult(true, type == BalanceChangedEvent.Type.DEPOSIT ? "入金成功" : "出金成功",
                next, transactionId, false);
    }

    private static StockQuote quote(StockInfo info) {
        return new StockQuote(info.id(), info.name(), cents(info.price()), cents(info.prevClose()),
                cents(info.dayHigh()), cents(info.dayLow()), info.volume(), info.history().stream()
                .map(candle -> new CandleSnapshot(candle.dayIndex(), cents(candle.open()), cents(candle.close()),
                        cents(candle.high()), cents(candle.low()), candle.volume()))
                .toList(), info.industry(), info.halted(), info.haltRemainingCycles());
    }

    private static long safeBalance(ServerPlayer player) {
        if (player == null) return 0;
        if (!isServerThread(player)) return 0;
        try {
            return cents(AccountService.ensureInitialized(player).cash());
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static void requirePlayer(ServerPlayer player) {
        if (player == null) throw new IllegalArgumentException("player must not be null");
    }

    private static boolean isServerThread(ServerPlayer player) {
        return player != null && player.server.isSameThread();
    }

    private static void requireServerThread(ServerPlayer player) {
        if (!isServerThread(player)) throw new IllegalStateException("must be called on the server thread");
    }

    private static MarketService requireService() {
        MarketService service = MarketService.get();
        if (service == null) throw new IllegalStateException("stock market server service is not started");
        return service;
    }

    private static long cents(double value) {
        if (!Double.isFinite(value)) return 0;
        return Math.round(value * 100.0);
    }
}
