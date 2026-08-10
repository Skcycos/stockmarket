package com.tanrunn.stockmarket.server.market;

import java.util.Map;

/**
 * Pure trade engine. Returns a new account state instead of mutating, so it can
 * be unit-tested without any Minecraft classes.
 */
public final class TradeEngine {
    private TradeEngine() {
    }

    public record Result(boolean success, String message, HoldingAccount account, double fee) {
    }

    public static Result buy(HoldingAccount account, String stockId, double price, int quantity, double feeRate) {
        if (quantity <= 0) {
            return new Result(false, "数量必须大于 0", account, 0);
        }
        double gross = price * quantity;
        double fee = Math.round(gross * feeRate * 100.0) / 100.0;
        double total = gross + fee;
        if (account.cash() < total) {
            return new Result(false, "现金不足（需要 " + fmt(total) + "）", account, 0);
        }
        java.util.Map<String, Integer> holdings = new java.util.HashMap<>(account.holdings());
        holdings.merge(stockId, quantity, Integer::sum);
        double cash = Math.round((account.cash() - total) * 100.0) / 100.0;
        return new Result(true, "买入 " + quantity + " 股 @" + fmt(price), new HoldingAccount(cash, holdings), fee);
    }

    public static Result sell(HoldingAccount account, String stockId, double price, int quantity, double feeRate) {
        if (quantity <= 0) {
            return new Result(false, "数量必须大于 0", account, 0);
        }
        int held = account.holdings().getOrDefault(stockId, 0);
        if (held < quantity) {
            return new Result(false, "持仓不足（持有 " + held + " 股）", account, 0);
        }
        double gross = price * quantity;
        double fee = Math.round(gross * feeRate * 100.0) / 100.0;
        double cash = Math.round((account.cash() + gross - fee) * 100.0) / 100.0;
        java.util.Map<String, Integer> holdings = new java.util.HashMap<>(account.holdings());
        int left = held - quantity;
        if (left == 0) {
            holdings.remove(stockId);
        } else {
            holdings.put(stockId, left);
        }
        return new Result(true, "卖出 " + quantity + " 股 @" + fmt(price), new HoldingAccount(cash, holdings), fee);
    }

    private static String fmt(double value) {
        return String.format("%.2f", value);
    }

    // ---- limit order helpers (pure) ----

    /** Reserve cash for a buy limit order; the shares are added on fill. */
    public static Result reserveBuy(HoldingAccount account, double price, int quantity, double feeRate) {
        if (quantity <= 0) {
            return new Result(false, "数量必须大于 0", account, 0);
        }
        double total = price * quantity * (1 + feeRate);
        if (account.cash() < total) {
            return new Result(false, "现金不足（需要 " + fmt(total) + "）", account, 0);
        }
        double cash = Math.round((account.cash() - total) * 100.0) / 100.0;
        return new Result(true, "已挂买单", new HoldingAccount(cash, account.holdings()), 0);
    }

    /** Refund the reserved cash when a buy limit order is cancelled. */
    public static HoldingAccount refundBuy(HoldingAccount account, double price, int quantity, double feeRate) {
        double refund = Math.round(price * quantity * (1 + feeRate) * 100.0) / 100.0;
        return new HoldingAccount(Math.round((account.cash() + refund) * 100.0) / 100.0, account.holdings());
    }

    /** Add the filled shares to the account. */
    public static HoldingAccount fillBuy(HoldingAccount account, String stockId, int quantity) {
        java.util.Map<String, Integer> holdings = new java.util.HashMap<>(account.holdings());
        holdings.merge(stockId, quantity, Integer::sum);
        return new HoldingAccount(account.cash(), holdings);
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
        java.util.Map<String, Integer> holdings = new java.util.HashMap<>(account.holdings());
        int left = held - quantity;
        if (left == 0) {
            holdings.remove(stockId);
        } else {
            holdings.put(stockId, left);
        }
        return new Result(true, "已挂卖单", new HoldingAccount(account.cash(), holdings), 0);
    }

    /** Return the reserved shares when a sell limit order is cancelled. */
    public static HoldingAccount refundSell(HoldingAccount account, String stockId, int quantity) {
        java.util.Map<String, Integer> holdings = new java.util.HashMap<>(account.holdings());
        holdings.merge(stockId, quantity, Integer::sum);
        return new HoldingAccount(account.cash(), holdings);
    }

    /** Credit cash (minus fee) when a sell limit order fills. */
    public static HoldingAccount fillSell(HoldingAccount account, String stockId, double price, int quantity, double feeRate) {
        double gross = price * quantity;
        double fee = Math.round(gross * feeRate * 100.0) / 100.0;
        double cash = Math.round((account.cash() + gross - fee) * 100.0) / 100.0;
        return new HoldingAccount(cash, account.holdings());
    }
}
