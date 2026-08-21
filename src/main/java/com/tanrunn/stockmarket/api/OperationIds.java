package com.tanrunn.stockmarket.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * 银行 ⇄ 证券转账内部资金操作幂等键（operationId）生成器（纯逻辑，集中实现，可单测）。
 *
 * <p>算法与 Server Menu 的 {@code com.tanrunn.servermenu.api.economy.EconomyOperationIds}
 * 完全一致（SHA-256 + Base64 URL-safe 无填充 + 业务域前缀，总长 ≤ 64），保证跨模块
 * 计算相同输入得到相同 opId。客户端原始 requestId 只作为哈希材料，绝不直接作为
 * 资金操作幂等键传给 LC / 证券资金 API。</p>
 */
public final class OperationIds {

    public static final int MAX_LENGTH = 64;

    /** operationId 算法/格式版本（持久化到转账记录；恢复时版本不识别一律 MANUAL_REVIEW）。 */
    public static final int VERSION = 1;

    /** StockMarket 银行扣款（入金方向，LC 侧）。 */
    public static final String SM_BANK_DEBIT = "sm:bd:";
    /** StockMarket 银行入账（出金方向，LC 侧）。 */
    public static final String SM_BANK_CREDIT = "sm:bc:";
    /** StockMarket 证券扣款（出金方向，证券侧）。 */
    public static final String SM_SECURITIES_DEBIT = "sm:sd:";
    /** StockMarket 证券入账（入金方向，证券侧）。 */
    public static final String SM_SECURITIES_CREDIT = "sm:sc:";
    /** StockMarket 补偿（任意方向的失败回滚）。 */
    public static final String SM_ROLLBACK = "sm:rb:";
    /** BuildShop 域（供碰撞反例测试引用；本模块不生成）。 */
    public static final String BS_WITHDRAW = "bs:wd:";
    /** BuildShop 域（供碰撞反例测试引用；本模块不生成）。 */
    public static final String BS_REFUND = "bs:rf:";

    private OperationIds() {
    }

    public static String generate(String domain, String provider, String source,
                                  String operationType, String requestId, String direction) {
        String safeDomain = domain == null ? "" : domain;
        String material = safeDomain + "\n"
                + (provider == null ? "" : provider) + "\n"
                + (source == null ? "" : source) + "\n"
                + (operationType == null ? "" : operationType) + "\n"
                + (requestId == null ? "" : requestId) + "\n"
                + (direction == null ? "" : direction);
        String hash = sha256Base64Url(material);
        String id = safeDomain + hash;
        if (id.length() > MAX_LENGTH) {
            int budget = MAX_LENGTH - safeDomain.length();
            if (budget <= 0) {
                return hash.substring(0, Math.min(MAX_LENGTH, hash.length()));
            }
            id = safeDomain + hash.substring(0, Math.min(budget, hash.length()));
        }
        return id;
    }

    private static String sha256Base64Url(String material) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
    }
}
