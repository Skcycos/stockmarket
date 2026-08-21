package com.tanrunn.stockmarket.server.transfer;

import com.tanrunn.stockmarket.Config;
import com.tanrunn.stockmarket.api.BankTransferBlockedException;
import com.tanrunn.stockmarket.api.BankTransferRequest;
import com.tanrunn.stockmarket.api.BankTransferResult;
import com.tanrunn.stockmarket.api.BankTransferService;
import com.tanrunn.stockmarket.api.BankTransferStatus;
import com.tanrunn.stockmarket.api.CurrencyBridge;
import com.tanrunn.stockmarket.api.ReconciledBankTransferLedger;
import com.tanrunn.stockmarket.api.StockMarketApi;
import com.tanrunn.stockmarket.api.TransferKey;
import com.tanrunn.stockmarket.server.market.AccountService;
import com.tanrunn.stockmarket.server.market.MarketService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端主线程上的银行 ⇄ 证券转账门禁（第六轮：WAL 生产恢复闭环）。
 *
 * <p><b>WAL 生命周期</b>：{@link #onServerStarted} 绑定当前唯一世界数据目录并加载恢复索引；
 * {@link #onServerStopping} 关闭 WAL、清空世界路径与冷却缓存；同 JVM 切世界时
 * {@link #bindWorld} 关闭旧实例并重建新 WAL；世界路径未就绪/最新路径变化前拒绝银行转账。</p>
 *
 * <p><b>入口对账</b>：每次执行都经 {@link ReconciledBankTransferLedger} 查询——WAL 全局隔离
 * （fail closed 零资金）、WAL key 隔离（MANUAL_REVIEW）、WAL 最新（崩溃权威）、附件详细、附件
 * 墓碑；冷却的 known 判断同样含 WAL。WAL 恢复一律使用 WAL 内持久化 opId，绝不重算。</p>
 *
 * <p><b>玩家登录</b>：把该玩家 WAL 最新记录幂等写回附件对账；写失败也由 WAL overlay 继续阻止
 * 重复执行，绝不自动调用 LC。玩家退出只清冷却等临时状态，不删除 WAL 防重索引。</p>
 */
public final class BankTransferCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger(BankTransferCoordinator.class);

    /** WAL 文件名（位于世界数据目录）。 */
    public static final String WAL_FILE_NAME = "stockmarket-transfer.wal";

    public static final BankTransferCoordinator INSTANCE = new BankTransferCoordinator();

    private final Map<UUID, Long> lastTransferTick = new ConcurrentHashMap<>();

    private volatile Path boundWorldPath;
    private volatile FileTransferWal wal;

    private BankTransferCoordinator() {
    }

    // ---------------------------------------------------------------- lifecycle

    /** 服务端启动：绑定世界路径并初始化 WAL 恢复索引（构造即加载）。 */
    public synchronized void onServerStarted(net.minecraft.server.MinecraftServer server) {
        if (server == null) {
            return;
        }
        bindWorld(server.getWorldPath(LevelResource.ROOT));
    }

    /** 服务端停止：关闭 WAL 资源、清空世界路径与冷却缓存（WAL 防重索引不删除）。 */
    public synchronized void onServerStopping() {
        closeWal();
        lastTransferTick.clear();
    }

    private synchronized void closeWal() {
        if (wal != null) {
            wal.close();
        }
        wal = null;
        boundWorldPath = null;
    }

    /**
     * 绑定世界路径（纯逻辑，测试可调用）：路径变化/为 null → 关闭旧实例、重建/清空；
     * 最新路径未就绪时银行转账必须拒绝。
     */
    synchronized void bindWorld(Path worldPath) {
        if (worldPath == null) {
            closeWal();
            return;
        }
        if (boundWorldPath != null && boundWorldPath.equals(worldPath) && wal != null) {
            return; // 同一世界路径复用（同一 JVM 生命周期内）。
        }
        if (wal != null) {
            wal.close();
        }
        boundWorldPath = worldPath;
        Path walFile = worldPath.resolve(WAL_FILE_NAME);
        wal = new FileTransferWal(walFile);
        if (wal.globallyQuarantined()) {
            LOGGER.error("[StockMarket] WAL 全局隔离：银行转账已 fail closed（文件={}）", walFile);
        } else if (!wal.quarantinedKeys().isEmpty()) {
            LOGGER.warn("[StockMarket] WAL 存在 {} 个被隔离的转账键，需人工审计",
                    wal.quarantinedKeys().size());
        }
    }

    /** 确保 WAL 已绑定当前玩家所在服务器的世界路径；未就绪返回 null（调用方拒绝银行转账）。 */
    private FileTransferWal ensureWalFor(ServerPlayer player) {
        if (player == null || player.server == null) {
            return null;
        }
        synchronized (this) {
            Path world = player.server.getWorldPath(LevelResource.ROOT);
            bindWorld(world);
            return wal;
        }
    }

    /**
     * 玩家登录对账（第七轮）：<b>绝不无条件把 WAL latest 覆盖到附件</b>。只对经
     * {@link ReconciledBankTransferLedger#reconcileWriteBack} 裁决为 {@code USE_WAL}
     * （WAL 合法领先附件、无指纹冲突、任一侧都非 MANUAL_REVIEW）的键安全写回；
     * 冲突 / MANUAL_REVIEW / 只读 / 隔离一律保留附件原证据，由后续 find 持续 MANUAL_REVIEW。
     * 全程不调用 LC 或证券资金；日志仅受限字段。
     */
    public void onPlayerLogin(ServerPlayer player) {
        if (player == null) {
            return;
        }
        FileTransferWal w = ensureWalFor(player);
        if (w == null || w.globallyQuarantined()) {
            if (w != null) {
                LOGGER.error("[StockMarket] WAL 全局隔离：玩家登录不写回（文件={}）", w.path());
            }
            return;
        }
        UUID id = player.getUUID();
        ReconciledBankTransferLedger reconciled =
                new ReconciledBankTransferLedger(new PersistedBankTransferLedger(), w, w);
        for (TransferKey key : w.keys()) {
            if (!key.playerId().equals(id)) {
                continue;
            }
            try {
                boolean safe = reconciled.reconcileWriteBack(id, key.requestId());
                if (!safe && !w.quarantinedKeys().contains(key)) {
                    // 有 WAL 记录但裁决为不写回（冲突/保守/B 侧 MANUAL_REVIEW）→ 受限审计日志。
                    LOGGER.warn("[StockMarket] 登录对账保留附件证据 player={} requestIdHash={}",
                            id, shortHash(key.requestId()));
                }
            } catch (com.tanrunn.stockmarket.api.BankTransferBlockedException e) {
                LOGGER.error("[StockMarket] 登录对账被 WAL 隔离阻断 player={} requestIdHash={}",
                        id, shortHash(key.requestId()));
            } catch (RuntimeException e) {
                LOGGER.warn("[StockMarket] 登录对账失败 player={} requestIdHash={}",
                        id, shortHash(key.requestId()));
            }
        }
    }

    /** 测试钩子：当前绑定 WAL（纯逻辑路径切换测试用）。 */
    synchronized FileTransferWal currentWalForTest() {
        return wal;
    }

    /** 玩家退出：只清理冷却等临时状态，不删除 WAL 防重索引。 */
    public void onPlayerLoggedOut(UUID playerId) {
        if (playerId != null) {
            lastTransferTick.remove(playerId);
        }
    }

    // ---------------------------------------------------------------- execute

    public BankTransferResult execute(ServerPlayer player, BankTransferRequest request,
                                      boolean sendSnapshotOnResult) {
        if (player == null || player.server == null || !player.server.isSameThread()) {
            return BankTransferResult.failure(BankTransferStatus.WRONG_THREAD,
                    "必须在服务端主线程操作", 0, 0,
                    request == null ? 0 : request.requestedCopper(),
                    request == null ? 0 : request.requestedSecuritiesCents(),
                    0, 0, request == null ? "" : request.requestId());
        }
        if (request == null) {
            return BankTransferResult.failure(BankTransferStatus.INVALID_REQUEST,
                    "请求无效", bankBalance(player), securitiesBalance(player), 0, 0, 0, 0, "");
        }

        // 1) WAL 必须是当前世界路径且已加载；全局隔离 → 银行转账 fail closed（零资金，普通证券不受影响）。
        FileTransferWal wal = ensureWalFor(player);
        if (wal == null || wal.globallyQuarantined()) {
            return BankTransferResult.failure(BankTransferStatus.UNAVAILABLE,
                    "银行转账系统隔离中（资金记录完整性未知），请联系管理员",
                    bankBalance(player), securitiesBalance(player),
                    request.requestedCopper(), request.requestedSecuritiesCents(), 0, 0,
                    request.requestId());
        }

        // 2) 组合账本：WAL keyed 隔离 → WAL 最新 → 附件详细 → 附件墓碑。
        PersistedBankTransferLedger persistedLedger = new PersistedBankTransferLedger();
        ReconciledBankTransferLedger reconciled =
                new ReconciledBankTransferLedger(persistedLedger, wal, wal);
        BankTransferService service = new BankTransferService(
                bridge(), new StockMarketSecuritiesAccount(), reconciled, wal,
                Config.MAX_BANK_DEPOSIT_COPPER.get(), Config.MAX_BANK_WITHDRAW_CENTS.get(),
                RuntimeEpoch.current());

        // 3) 冷却：only 对“附件+WAL 都无该 requestId”的全新请求生效（known 判断含 WAL）。
        boolean known;
        try {
            known = reconciled.find(player.getUUID(), request.requestId()) != null;
        } catch (BankTransferBlockedException e) {
            BankTransferStatus s = e.global()
                    ? BankTransferStatus.UNAVAILABLE : BankTransferStatus.MANUAL_REVIEW;
            return BankTransferResult.failure(s,
                    e.global() ? "银行转账系统隔离中（资金记录完整性未知），请联系管理员"
                            : "该转账被隔离，需人工审计",
                    bankBalance(player), securitiesBalance(player),
                    request.requestedCopper(), request.requestedSecuritiesCents(), 0, 0,
                    request.requestId());
        }
        if (!known) {
            long now = player.serverLevel().getGameTime();
            Long last = lastTransferTick.get(player.getUUID());
            if (last != null && now - last < Config.BANK_TRANSFER_COOLDOWN_TICKS.get()) {
                BankTransferResult limited = BankTransferResult.failure(BankTransferStatus.RATE_LIMITED,
                        "操作过于频繁，请稍候", bankBalance(player), securitiesBalance(player),
                        request.requestedCopper(), request.requestedSecuritiesCents(), 0, 0,
                        request.requestId());
                if (sendSnapshotOnResult) {
                    sendSnapshot(player, limited.message());
                }
                return limited;
            }
            lastTransferTick.put(player.getUUID(), now);
        }

        BankTransferResult result = service.transfer(player.getUUID(), request);
        if (result.status() == BankTransferStatus.COMPENSATION_FAILED
                || result.status() == BankTransferStatus.MANUAL_REVIEW
                || result.status() == BankTransferStatus.RECOVERY_REQUIRED
                || result.status() == BankTransferStatus.RATE_LIMITED) {
            // 人工审计错误点：真实服务端日志（仅受限字段，不记录无界输入/异常堆栈）。
            LOGGER.error("[StockMarket] bank transfer {}: player={} requestIdHash={} direction={} "
                            + "copper={} reqCents={} status={}",
                    result.status(),
                    player.getGameProfile().getName(), shortHash(request.requestId()),
                    request.direction(), request.requestedCopper(),
                    request.requestedSecuritiesCents(), result.status());
        }
        if (sendSnapshotOnResult) {
            sendSnapshot(player, result.message());
        }
        return result;
    }

    private static String shortHash(String value) {
        if (value == null || value.isEmpty()) {
            return "?";
        }
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(Character.forDigit((d[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(d[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "?";
        }
    }

    private CurrencyBridge bridge() {
        try {
            return StockMarketApi.currencyBridge(Config.BANK_BRIDGE_ID.get()).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private long bankBalance(ServerPlayer player) {
        CurrencyBridge b = bridge();
        if (b == null || !b.isAvailable()) {
            return 0;
        }
        try {
            return b.balanceCopper(player.getUUID());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private long securitiesBalance(ServerPlayer player) {
        try {
            return StockMarketApi.isAvailable() ? StockMarketApi.balanceCents(player) : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static void sendSnapshot(ServerPlayer player, String message) {
        if (MarketService.get() != null) {
            MarketService.get().sendSnapshot(player, false, message);
        }
    }
}
