package com.tanrunn.stockmarket.server.market;

import java.util.HashMap;
import java.util.Map;

/**
 * Pure trade engine. Returns a new account state instead of mutating, so it can
 * be unit-tested without any Minecraft classes.
 *
 * <p>金额约定：所有现金计算都按"分"四舍五入（{@link #round2}），且
 * 预留（reserve）与退款（refund）使用完全相同的舍入公式，保证撤单后退回
 * 的金额与预留时扣除的金额精确一致，不存在浮点误差导致的资金漂移。
 */
public final class TradeEngine {
    private TradeEngine() {
    }

    public record Result(boolean success, String message, HoldingAccount account, double fee) {
    }

    /**
     * 所有交易入口（客户端网络市价、/market buy、/market sell、限价买单、
     * 限价卖单）共用的服务端门禁。返回错误提示；校验通过返回 {@code null}。
     * 纯函数，便于脱离 Minecraft 环境单测。
     */
    public static String validateEntry(boolean enabled, int quantity, int maxQuantity) {
        if (!enabled) {
            return "股市已关闭，交易功能暂停";
        }
        if (quantity <= 0) {
            return "数量必须大于 0";
        }
        if (quantity > maxQuantity) {
            return "单笔委托数量不能超过 " + maxQuantity;
        }
        return null;
    }

    /**
     * 限价单价格的服务端校验：拒绝非有限数（NaN、±Infinity）以及低于 0.01 的价格。
     * 返回错误提示；通过返回 {@code null}。
     */
    public static String validatePrice(double price) {
        if (!Double.isFinite(price) || price < 0.01) {
            return "委托价格无效（至少 0.01）";
        }
        return null;
    }

    public static Result buy(HoldingAccount account, String stockId, double price, int quantity, double feeRate) {
        if (quantity <= 0) {
            return new Result(false, "数量必须大于 0", account, 0);
        }
        double gross = round2(price * quantity);
        double fee = round2(gross * feeRate);
        double total = round2(gross + fee);
        if (account.cash() < total) {
            return new Result(false, "现金不足（需要 " + fmt(total) + "）", account, 0);
        }
        Map<String, Integer> holdings = new HashMap<>(account.holdings());
        holdings.merge(stockId, quantity, Integer::sum);
        double cash = round2(account.cash() - total);
        Map<String, Double> costBasis = new HashMap<>(account.costBasis());
        costBasis.merge(stockId, total, Double::sum);
        return new Result(true, "买入 " + quantity + " 股 @" + fmt(price),
                new HoldingAccount(cash, holdings, costBasis, account.realizedPnl()), fee);
    }

    public static Result sell(HoldingAccount account, String stockId, double price, int quantity, double feeRate) {
        if (quantity <= 0) {
            return new Result(false, "数量必须大于 0", account, 0);
        }
        int held = account.holdings().getOrDefault(stockId, 0);
        if (held < quantity) {
            return new Result(false, "持仓不足（持有 " + held + " 股）", account, 0);
        }
        double gross = round2(price * quantity);
        double fee = round2(gross * feeRate);
        double cash = round2(account.cash() + gross - fee);
        Map<String, Integer> holdings = new HashMap<>(account.holdings());
        int left = held - quantity;
        if (left == 0) {
            holdings.remove(stockId);
        } else {
            holdings.put(stockId, left);
        }
        double soldBasis = costBasisForSale(account, stockId, quantity);
        Map<String, Double> costBasis = new HashMap<>(account.costBasis());
        if (left == 0) {
            costBasis.remove(stockId);
        } else if (costBasis.containsKey(stockId)) {
            costBasis.put(stockId, round2(Math.max(0, costBasis.get(stockId) - soldBasis)));
        }
        double realizedPnl = round2(account.realizedPnl() + gross - fee - soldBasis);
        return new Result(true, "卖出 " + quantity + " 股 @" + fmt(price),
                new HoldingAccount(cash, holdings, costBasis, realizedPnl), fee);
    }

    private static String fmt(double value) {
        return String.format("%.2f", value);
    }

    /** Rounds to cents. All persisted cash values go through this so the stored
     *  amounts are always exact cents and refund/reserve stay reversible. */
    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public static double feeFor(double price, int quantity, double feeRate) {
        return round2(round2(price * quantity) * feeRate);
    }

    // ---- limit order helpers (pure) ----

    /** Reserve cash for a buy limit order; the shares are added on fill. */
    public static Result reserveBuy(HoldingAccount account, double price, int quantity, double feeRate) {
        if (quantity <= 0) {
            return new Result(false, "数量必须大于 0", account, 0);
        }
        double total = buyReservation(price, quantity, feeRate);
        if (account.cash() < total) {
            return new Result(false, "现金不足（需要 " + fmt(total) + "）", account, 0);
        }
        double cash = round2(account.cash() - total);
        return new Result(true, "已挂买单",
                new HoldingAccount(cash, account.holdings(), account.costBasis(), account.realizedPnl()), 0);
    }

    /** Refund the reserved cash when a buy limit order is cancelled. Uses the
     *  exact same formula as {@link #reserveBuy}, so the refund always equals the
     *  reserved amount and cancel is fully reversible. */
    public static HoldingAccount refundBuy(HoldingAccount account, double price, int quantity, double feeRate) {
        double total = buyReservation(price, quantity, feeRate);
        double cash = round2(account.cash() + total);
        return new HoldingAccount(cash, account.holdings(), account.costBasis(), account.realizedPnl());
    }

    /** Total cash reserved for a buy limit order (gross + fee, cent-rounded). */
    public static double buyReservation(double price, int quantity, double feeRate) {
        double gross = round2(price * quantity);
        double fee = round2(gross * feeRate);
        return round2(gross + fee);
    }

    /** Add the filled shares to the account. */
    public static HoldingAccount fillBuy(HoldingAccount account, String stockId, int quantity) {
        Map<String, Integer> holdings = new HashMap<>(account.holdings());
        holdings.merge(stockId, quantity, Integer::sum);
        return new HoldingAccount(account.cash(), holdings, account.costBasis(), account.realizedPnl());
    }

    /** Add filled limit-buy shares and their fee-inclusive acquisition cost. */
    public static HoldingAccount fillBuy(HoldingAccount account, String stockId, double price,
                                         int quantity, double feeRate) {
        Map<String, Integer> holdings = new HashMap<>(account.holdings());
        holdings.merge(stockId, quantity, Integer::sum);
        double gross = round2(price * quantity);
        double fee = round2(gross * feeRate);
        Map<String, Double> costBasis = new HashMap<>(account.costBasis());
        costBasis.merge(stockId, round2(gross + fee), Double::sum);
        return new HoldingAccount(account.cash(), holdings, costBasis, account.realizedPnl());
    }

    /** Reserve shares for a sell limit order; cash is credited on fill. */
    public static Result reserveSell(HoldingAccount account, String stockId, int quantity) {
        if (quantity <= 0) {
            return new Result(false, "数量必须大于 0", account, 0);
        }
        int held = account.holdings().getOrDefault(stockId, 0);
        if (held < quantity) {
            return new Result(false, "持仓不足（持有 " + held + " 股）", account, 0);
        }
        Map<String, Integer> holdings = new HashMap<>(account.holdings());
        int left = held - quantity;
        if (left == 0) {
            holdings.remove(stockId);
        } else {
            holdings.put(stockId, left);
        }
        Map<String, Double> costBasis = new HashMap<>(account.costBasis());
        double reservedBasis = costBasisForSale(account, stockId, quantity);
        if (left == 0) {
            costBasis.remove(stockId);
        } else if (costBasis.containsKey(stockId)) {
            costBasis.put(stockId, round2(Math.max(0, costBasis.get(stockId) - reservedBasis)));
        }
        return new Result(true, "已挂卖单",
                new HoldingAccount(account.cash(), holdings, costBasis, account.realizedPnl()), 0);
    }

    /** Return the reserved shares when a sell limit order is cancelled. */
    public static HoldingAccount refundSell(HoldingAccount account, String stockId, int quantity) {
        return refundSell(account, stockId, quantity, 0);
    }

    /** Return reserved shares and the exact cost basis attached to the order. */
    public static HoldingAccount refundSell(HoldingAccount account, String stockId, int quantity,
                                            double reservedBasis) {
        Map<String, Integer> holdings = new HashMap<>(account.holdings());
        holdings.merge(stockId, quantity, Integer::sum);
        Map<String, Double> costBasis = new HashMap<>(account.costBasis());
        if (reservedBasis > 0) {
            costBasis.merge(stockId, reservedBasis, Double::sum);
        }
        return new HoldingAccount(account.cash(), holdings, costBasis, account.realizedPnl());
    }

    /** Credit cash (minus fee) when a sell limit order fills. */
    public static HoldingAccount fillSell(HoldingAccount account, String stockId, double price, int quantity, double feeRate) {
        return fillSell(account, stockId, price, quantity, feeRate, 0);
    }

    /** Credit cash and record realized P&L for a reserved sell order. */
    public static HoldingAccount fillSell(HoldingAccount account, String stockId, double price, int quantity,
                                          double feeRate, double reservedBasis) {
        double gross = round2(price * quantity);
        double fee = round2(gross * feeRate);
        double cash = round2(account.cash() + gross - fee);
        double realizedPnl = round2(account.realizedPnl() + gross - fee - reservedBasis);
        return new HoldingAccount(cash, account.holdings(), account.costBasis(), realizedPnl);
    }

    /** Returns the fee-inclusive carrying cost allocated to a partial sale. */
    public static double costBasisForSale(HoldingAccount account, String stockId, int quantity) {
        int held = account.holdings().getOrDefault(stockId, 0);
        double totalBasis = account.costBasis().getOrDefault(stockId, 0.0);
        if (held <= 0 || totalBasis <= 0) return 0;
        if (held == quantity) return round2(totalBasis);
        return round2(totalBasis * quantity / held);
    }
}
