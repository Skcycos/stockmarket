package com.tanrunn.stockmarket.server.transfer;

import com.tanrunn.stockmarket.api.BankTransferService;
import com.tanrunn.stockmarket.api.StockMarketApi;
import com.tanrunn.stockmarket.api.TransactionResult;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.UUID;

/**
 * 生产版证券账户桥：把 {@link BankTransferService.Securities} 接到 {@link StockMarketApi}。
 * 证券账户的一切变更仍由 StockMarket 自身服务权威执行；桥接只做单向入金/出金。
 * 证券侧资金操作一律使用内部 opId（{@code requestId} 参数 = opId），不用原始 requestId。
 */
public final class StockMarketSecuritiesAccount implements BankTransferService.Securities {

    private static final String REASON_DEPOSIT = "入金到证券账户";
    private static final String REASON_WITHDRAW = "提现到银行";

    @Override
    public boolean isAvailable(UUID playerId) {
        return StockMarketApi.isAvailable() && resolve(playerId) != null;
    }

    @Override
    public long balanceCents(UUID playerId) {
        ServerPlayer player = resolve(playerId);
        if (player == null || !StockMarketApi.isAvailable()) {
            return 0;
        }
        try {
            return StockMarketApi.balanceCents(player);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    @Override
    public BankTransferService.TransferOutcome deposit(UUID playerId, long cents,
                                                       String source, String reason, String requestId) {
        ServerPlayer player = resolve(playerId);
        if (player == null || !StockMarketApi.isAvailable()) {
            return new BankTransferService.TransferOutcome(false, 0, "证券账户暂不可用");
        }
        try {
            TransactionResult result = StockMarketApi.deposit(player, cents, source, reason, requestId);
            return new BankTransferService.TransferOutcome(result.success(), result.balanceCents(), result.message());
        } catch (RuntimeException e) {
            return new BankTransferService.TransferOutcome(false, 0, "证券账户入账失败");
        }
    }

    @Override
    public BankTransferService.TransferOutcome withdraw(UUID playerId, long cents,
                                                        String source, String reason, String requestId) {
        ServerPlayer player = resolve(playerId);
        if (player == null || !StockMarketApi.isAvailable()) {
            return new BankTransferService.TransferOutcome(false, 0, "证券账户暂不可用");
        }
        try {
            TransactionResult result = StockMarketApi.withdraw(player, cents, source, reason, requestId);
            return new BankTransferService.TransferOutcome(result.success(), result.balanceCents(), result.message());
        } catch (RuntimeException e) {
            return new BankTransferService.TransferOutcome(false, 0, "证券账户扣款失败");
        }
    }

    static ServerPlayer resolve(UUID playerId) {
        if (playerId == null) {
            return null;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? null : server.getPlayerList().getPlayer(playerId);
    }
}
