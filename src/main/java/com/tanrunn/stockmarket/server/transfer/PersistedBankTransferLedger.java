package com.tanrunn.stockmarket.server.transfer;

import com.tanrunn.stockmarket.api.BankTransferLedger;
import com.tanrunn.stockmarket.api.BankTransferRecord;
import com.tanrunn.stockmarket.server.market.AccountService;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/**
 * 生产版银行 ⇄ 证券转账账本：持久化在玩家附件（{@code AccountData}，有界）。
 * 见 {@link com.tanrunn.stockmarket.server.market.AccountData#MAX_TRANSFER_RECORDS}。
 */
public final class PersistedBankTransferLedger implements BankTransferLedger {

    @Override
    public BankTransferRecord find(UUID playerId, String requestId) {
        ServerPlayer player = StockMarketSecuritiesAccount.resolve(playerId);
        if (player == null || requestId == null) {
            return null;
        }
        return AccountService.findTransferByRequestId(player, requestId);
    }

    @Override
    public boolean write(UUID playerId, BankTransferRecord record) {
        ServerPlayer player = StockMarketSecuritiesAccount.resolve(playerId);
        if (player == null || record == null) {
            return false;
        }
        return AccountService.upsertTransfer(player, record);
    }

    @Override
    public List<BankTransferRecord> recent(UUID playerId) {
        ServerPlayer player = StockMarketSecuritiesAccount.resolve(playerId);
        return player == null ? List.of() : AccountService.transfers(player);
    }
}
