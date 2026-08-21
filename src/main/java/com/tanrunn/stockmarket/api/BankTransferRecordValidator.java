package com.tanrunn.stockmarket.api;

import java.util.EnumSet;
import java.util.Set;

/**
 * 转账账本行的整行校验（fail closed 的统一依据）。
 *
 * <p><b>阶段感知</b>：
 * <ul>
 *   <li><b>安全终态</b>（{@link BankTransferPhase#isSafeTerminal()}）：只读重放，只需要能
 *       识别与判冲突的最小结构（requestId/direction/phase/status/金额），<b>不要求</b>
 *       providerId / operationIdVersion / stateMachineVersion / runtimeEpoch / opId——
 *       旧版安全终态记录也可零资金只读重放；</li>
 *   <li><b>非安全终态</b>：必须满足全部新版本字段（providerId 非空、operationIdVersion==
 *       {@link OperationIds#VERSION}、stateMachineVersion=={@link BankTransferRecord#STATE_MACHINE_VERSION}、
 *       runtimeEpoch 非 0、必填 opId 存在且前缀/长度合法、金额不变量、阶段状态组合）。</li>
 * </ul></p>
 *
 * <p><b>PREPARED 明确未动账失败白名单</b>：PREPARED + 白名单内的“明确未动账”状态可安全重放
 * 原失败，不升级为 MANUAL_REVIEW；PREPARED + INCOMPLETE_TRANSFER 表示来源侧状态不确定
 * （可能崩溃）→ 由服务层按 MANUAL_REVIEW 处理；PREPARED + SUCCESS / COMPENSATION_FAILED
 * 等非法组合 → 校验失败（fail closed）。</p>
 */
public final class BankTransferRecordValidator {

    /**
     * REJECTED 允许的状态白名单（第五轮）：可证明“无净资金变化”的明确拒绝/未动账终态，
     * 可只读幂等重放并建立墓碑。COMPENSATION_FAILED / SUCCESS / INCOMPLETE_TRANSFER /
     * MANUAL_REVIEW / RECOVERY_REQUIRED 一律不在此列。
     */
    private static final Set<BankTransferStatus> REJECTED_ELIGIBLE = EnumSet.of(
            BankTransferStatus.INSUFFICIENT_FUNDS,
            BankTransferStatus.UNAVAILABLE,
            BankTransferStatus.BANK_ERROR,
            BankTransferStatus.SECURITIES_ERROR,
            BankTransferStatus.PARTIAL_OPERATION,
            BankTransferStatus.RATE_LIMITED,
            BankTransferStatus.QUARANTINED,
            BankTransferStatus.WRONG_THREAD,
            BankTransferStatus.INVALID_AMOUNT,
            BankTransferStatus.INVALID_REQUEST,
            BankTransferStatus.REQUEST_CONFLICT);

    /** 该失败状态是否可进入 REJECTED（= 白名单内）。 */
    public static boolean isRejectedEligible(BankTransferStatus status) {
        return status != null && REJECTED_ELIGIBLE.contains(status);
    }

    private BankTransferRecordValidator() {
    }

    public static boolean isWellFormed(BankTransferRecord r) {
        if (r == null) {
            return false;
        }
        if (r.requestId() == null || r.requestId().isBlank()
                || r.requestId().length() > BankTransferRequest.MAX_REQUEST_ID_LENGTH) {
            return false;
        }
        if (r.direction() == null || r.phase() == null || r.status() == null) {
            return false;
        }
        if (!amountsSatisfyInvariants(r)) {
            return false;
        }
        if (!phaseStatusComboOk(r)) {
            return false;
        }
        if (!allOpIdsWithinLimit(r)) {
            return false;
        }
        if (r.isSafeTerminal()) {
            // 安全终态：只读重放所需最小结构即可（旧版记录也可零资金重放）。
            return true;
        }
        // 非安全终态：需要完整新版本字段。
        if (r.providerId() == null || r.providerId().isBlank()) {
            return false;
        }
        if (r.operationIdVersion() != OperationIds.VERSION) {
            return false;
        }
        if (r.stateMachineVersion() != BankTransferRecord.STATE_MACHINE_VERSION) {
            return false;
        }
        if (r.runtimeEpoch() == BankTransferRecord.UNKNOWN_EPOCH) {
            return false;
        }
        if (!requiredOpIdsPresent(r)) {
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- amounts

    private static boolean amountsSatisfyInvariants(BankTransferRecord r) {
        if (r.requestedCopper() < 0 || r.requestedSecuritiesCents() < 0
                || r.actualDebitCents() < 0 || r.copperAmount() < 0) {
            return false;
        }
        if (r.direction() == BankTransferRequest.Direction.DEPOSIT_TO_SECURITIES) {
            if (r.requestedCopper() <= 0) {
                return false;
            }
            try {
                long expect = ExchangeRates.copperToSecuritiesCents(r.requestedCopper());
                return r.requestedSecuritiesCents() == expect
                        && r.actualDebitCents() == expect
                        && r.copperAmount() == r.requestedCopper();
            } catch (ArithmeticException e) {
                return false;
            }
        }
        if (r.requestedCopper() != 0 || r.requestedSecuritiesCents() <= 0) {
            return false;
        }
        try {
            long copper = ExchangeRates.securitiesCentsToCopperCeil(r.requestedSecuritiesCents());
            long debit = ExchangeRates.copperToSecuritiesCents(copper);
            return r.copperAmount() == copper && r.actualDebitCents() == debit;
        } catch (ArithmeticException | IllegalArgumentException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- phase/status

    private static boolean phaseStatusComboOk(BankTransferRecord r) {
        switch (r.phase()) {
            case COMPLETED:
                return r.status() == BankTransferStatus.SUCCESS;
            case COMPENSATION_FAILED:
                return r.status() == BankTransferStatus.COMPENSATION_FAILED;
            case MANUAL_REVIEW:
                return r.status() == BankTransferStatus.MANUAL_REVIEW;
            case COMPENSATED:
                return r.status() != BankTransferStatus.SUCCESS
                        && r.status() != BankTransferStatus.INCOMPLETE_TRANSFER;
            case DESTINATION_CREDITED:
                return r.status() == BankTransferStatus.INCOMPLETE_TRANSFER;
            case DESTINATION_CREDIT_PENDING:
            case COMPENSATION_PENDING:
                // 这两个是「意图已写、资金未动」的进行中阶段。
                return r.status() == BankTransferStatus.INCOMPLETE_TRANSFER;
            case SOURCE_DEBITED:
                // 旧版含义：仅进行中/恢复相关状态。
                return r.status() == BankTransferStatus.INCOMPLETE_TRANSFER
                        || r.status() == BankTransferStatus.RECOVERY_REQUIRED;
            case REJECTED:
                // 明确无净资金变化的拒绝终态（服务层写 REJECTED + 原失败状态）。
                return isRejectedEligible(r.status());
            case PREPARED:
                // INCOMPLETE_TRANSFER = 来源侧状态不确定（服务层 → MANUAL_REVIEW）。
                // 旧版 PREPARED + 明确未动账失败会在迁移时改为 REJECTED。
                return r.status() == BankTransferStatus.INCOMPLETE_TRANSFER;
            default:
                return false;
        }
    }

    // ---------------------------------------------------------------- opids

    private static boolean allOpIdsWithinLimit(BankTransferRecord r) {
        return withinLimit(r.opBankDebit()) && withinLimit(r.opBankCredit())
                && withinLimit(r.opSecuritiesDebit()) && withinLimit(r.opSecuritiesCredit())
                && withinLimit(r.opRollback());
    }

    private static boolean withinLimit(String opId) {
        return opId == null || opId.length() <= OperationIds.MAX_LENGTH;
    }

    private static boolean prefixOk(String opId, String expectedPrefix) {
        return opId != null && !opId.isBlank()
                && opId.length() <= OperationIds.MAX_LENGTH
                && opId.startsWith(expectedPrefix);
    }

    /** SOURCE_DEBITED/DESTINATION_CREDIT_PENDING/DESTINATION_CREDITED/COMPENSATION_PENDING 需要的方向 op 集。 */
    private static boolean requiredOpIdsPresent(BankTransferRecord r) {
        if (r.direction() == BankTransferRequest.Direction.DEPOSIT_TO_SECURITIES) {
            return prefixOk(r.opBankDebit(), OperationIds.SM_BANK_DEBIT)
                    && prefixOk(r.opSecuritiesCredit(), OperationIds.SM_SECURITIES_CREDIT)
                    && prefixOk(r.opRollback(), OperationIds.SM_ROLLBACK)
                    && (r.opBankCredit().isEmpty() || withinLimit(r.opBankCredit()))
                    && (r.opSecuritiesDebit().isEmpty() || withinLimit(r.opSecuritiesDebit()));
        }
        return prefixOk(r.opSecuritiesDebit(), OperationIds.SM_SECURITIES_DEBIT)
                && prefixOk(r.opBankCredit(), OperationIds.SM_BANK_CREDIT)
                && prefixOk(r.opRollback(), OperationIds.SM_ROLLBACK)
                && (r.opBankDebit().isEmpty() || withinLimit(r.opBankDebit()))
                && (r.opSecuritiesCredit().isEmpty() || withinLimit(r.opSecuritiesCredit()));
    }
}
