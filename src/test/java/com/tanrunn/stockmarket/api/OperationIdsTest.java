package com.tanrunn.stockmarket.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link OperationIds}（sm: 域）幂等键规范测试：隔离、确定性、长度、无前缀截断碰撞。 */
class OperationIdsTest {

    private static final String PROVIDER = "server_menu:lc_bank_main";

    @Test
    void idsWithinLimitAndDeterministic() {
        String a = OperationIds.generate(OperationIds.SM_BANK_DEBIT, PROVIDER, "buildshop",
                "withdraw", "req-1", "");
        String b = OperationIds.generate(OperationIds.SM_BANK_DEBIT, PROVIDER, "buildshop",
                "withdraw", "req-1", "");
        assertTrue(a.length() <= OperationIds.MAX_LENGTH);
        assertEquals(a, b);
        assertEquals(49, a.length()); // 6 + 43
    }

    @Test
    void domainsAreIsolated() {
        String bankDebit = OperationIds.generate(OperationIds.SM_BANK_DEBIT, PROVIDER,
                "server_menu_lc_bank", "bank_debit", "req", "DEPOSIT_TO_SECURITIES");
        String secCredit = OperationIds.generate(OperationIds.SM_SECURITIES_CREDIT, PROVIDER,
                "server_menu_lc_bank", "sec_credit", "req", "DEPOSIT_TO_SECURITIES");
        String rollback = OperationIds.generate(OperationIds.SM_ROLLBACK, PROVIDER,
                "server_menu_lc_bank", "rollback_bank", "req", "DEPOSIT_TO_SECURITIES");
        assertNotEquals(bankDebit, secCredit);
        assertNotEquals(bankDebit, rollback);
        assertNotEquals(secCredit, rollback);
    }

    @Test
    void fullRawRequestIdIsHashedNoPrefixTruncation() {
        String base = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789AAAAAAAAAA";
        String other = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789BBBBBBBBBB";
        assertNotEquals(
                OperationIds.generate(OperationIds.SM_BANK_CREDIT, PROVIDER, "s", "c", base, "d"),
                OperationIds.generate(OperationIds.SM_BANK_CREDIT, PROVIDER, "s", "c", other, "d"));
    }

    @Test
    void rollbackOfXDoesNotEqualNormalOpOfRawRbX() {
        String rollbackOfX = OperationIds.generate(OperationIds.SM_ROLLBACK, PROVIDER, "s",
                "rollback_bank", "x", "d");
        String creditOfRbX = OperationIds.generate(OperationIds.SM_BANK_CREDIT, PROVIDER, "s",
                "bank_credit", "rb:x", "d");
        assertNotEquals(rollbackOfX, creditOfRbX);
        // BuildShop 退款（bs:rf:）与 StockMarket 补偿（sm:rb:）隔离
        String bsRefund = OperationIds.generate(OperationIds.BS_REFUND, PROVIDER, "buildshop",
                "refund", "x", "");
        assertNotEquals(rollbackOfX, bsRefund);
    }

    @Test
    void directionIsPartOfFingerprint() {
        assertNotEquals(
                OperationIds.generate(OperationIds.SM_SECURITIES_DEBIT, PROVIDER, "s", "sec_debit", "dup", "A"),
                OperationIds.generate(OperationIds.SM_SECURITIES_DEBIT, PROVIDER, "s", "sec_debit", "dup", "B"));
    }
}
