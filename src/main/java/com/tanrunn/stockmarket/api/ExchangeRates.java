package com.tanrunn.stockmarket.api;

/**
 * 铜币 ⇄ 证券账户内部金额的换算工具（集中实现，禁止在各处散落手工计算）。
 *
 * <p><b>经济比例</b>：玩家可见规则为「1 证券资金 = 1 铜币」。证券账户内部仍以
 * <b>分</b>（cents）保存：显示金额 1 = 内部 100 cents。因此<b>内部存储换算</b>为
 * {@code 1 铜币 = 100 证券 cents}——这不是玩家经济比例 100:1，只是内部存储单位。</p>
 *
 * <ul>
 *   <li>入金：LC 扣 N 铜币 → 证券内部增加 {@code N*100} cents（显示 +N），无取整；</li>
 *   <li>出金：玩家请求证券金额 R cents → 向上取整到整数铜币
 *       {@code copper = ceil(R / 100)}，证券实际扣 {@code copper*100} cents
 *       （提高到与到账铜币相同的整数金额，防止小数出金凭空增发铜币），ATM 到账
 *       {@code copper} 枚铜币。</li>
 * </ul>
 */
public final class ExchangeRates {

    /** 内部存储换算：1 铜币 = 100 证券 cents（显示 1 = 100 cents）。 */
    public static final long CENTS_PER_COPPER = 100L;

    private ExchangeRates() {
    }

    /** 铜币 → 证券内部 cents（入金、补偿证券等精确换算）；溢出抛
     * {@link ArithmeticException}，调用方必须处理。 */
    public static long copperToSecuritiesCents(long copper) {
        return Math.multiplyExact(copper, CENTS_PER_COPPER);
    }

    /** 证券 cents → 铜币（向上取整）；出金用。cents ≤ 0 抛
     * {@link IllegalArgumentException}；遇到 Long.MAX 溢出抛
     * {@link ArithmeticException}。 */
    public static long securitiesCentsToCopperCeil(long cents) {
        if (cents <= 0) {
            throw new IllegalArgumentException("cents must be positive");
        }
        return Math.addExact(cents, (CENTS_PER_COPPER - 1)) / CENTS_PER_COPPER;
    }
}
