package com.tanrunn.stockmarket.server.transfer;

import com.tanrunn.stockmarket.api.BankTransferPhase;
import com.tanrunn.stockmarket.api.BankTransferRecord;
import com.tanrunn.stockmarket.api.BankTransferRecordValidator;
import com.tanrunn.stockmarket.api.BankTransferRequest;
import com.tanrunn.stockmarket.api.BankTransferStatus;
import com.tanrunn.stockmarket.api.OperationIds;
import com.tanrunn.stockmarket.api.QuarantineReason;
import com.tanrunn.stockmarket.api.TransferKey;
import com.tanrunn.stockmarket.api.TransferPhases;
import com.tanrunn.stockmarket.api.Wal;
import com.tanrunn.stockmarket.api.WalRecoveryView;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 服务端资金意图 WAL（第八轮：持久隔离证据 + 水位 + poison）。
 *
 * <p><b>写入顺序</b>：编码（阶段意图或 quarantine marker）→ append → flush →
 * {@code FileChannel.force(true)} → 成功后才更新内存索引/隔离索引。任何 append/force 异常后
 * 本实例进入 <b>poisoned</b>（global quarantine、不可继续写）：本次、后续 writeIntent /
 * quarantineKey 一律 false，生产入口后续银行转账 UNAVAILABLE；普通股票交易不受影响。</p>
 *
 * <p><b>持久隔离 marker</b>：对账产生的 MR_BLOCK 写入带校验和、seq、复合键、有界原因枚举的
 * marker 行；marker 是吸收态（重启/压缩/登录写回都不得解除），仅管理员离线清除。marker 自身
 * 校验和损坏：能归属复合键 → 只隔离该键，不能 → 全局隔离。</p>
 *
 * <p><b>水位</b>：加载时对<b>每一行</b>先结构化提取 seq——即使校验和/记录损坏，能可靠提取的
 * 正数 seq 也计入历史最大水位（并登记占用），下一次追加必须用 max+1；{@link Math#addExact}
 * 溢出 → 全局隔离（不得回绕）。损坏高位 seq 的原始证据不因压缩消失（存在隔离即拒绝压缩）。</p>
 *
 * <p><b>有界读取</b>：不使用无界 readAllLines；超长行只消费当前行、触发全局隔离。</p>
 *
 * <p><b>状态图</b>：阶段转换统一委托 {@link TransferPhases}（唯一权威实现）。</p>
 */
public final class FileTransferWal implements Wal, WalRecoveryView {

    private static final int WAL_VERSION = 1;
    public static final int MAX_LINE_CHARS = 64 * 1024;

    private static final String PREFIX = "WALV=" + WAL_VERSION + "|seq=";
    private static final String CHECKSUM_HEADER = "|ck=";
    private static final String MARKER_FIELD = "|Q=1|";

    private final Path file;
    private final long maxTriggerBytes;
    private final long maxTriggerLines;

    private long sequence;
    private boolean globallyQuarantined;
    private boolean poisoned;
    private boolean closed;
    private final Map<TransferKey, BankTransferRecord> latestByKey = new LinkedHashMap<>();
    private final Set<TransferKey> quarantined = new LinkedHashSet<>();
    private final Map<Long, TransferKey> seqOwner = new LinkedHashMap<>();

    // 收尾：内存行计数（加载数一次，append O(1)+1）与压缩退避，避免每次写全文件扫描。
    private long lineCount;
    private int writesSinceCompact;
    private int compactBackoffWrites;
    private int rescanCount;

    /** 压缩被拒/失败后的写次数退避：此窗口内不重复尝试压缩（避免每次写都重试）。 */
    public static final int COMPACT_BACKOFF_WRITES = 16;

    // ---- 测试接缝 ----
    volatile int failLoadAfterEntries = -1;  // compact 读取中途失败
    volatile int failWriteBeforeBytes = -1;  // append 写首字节前失败
    volatile int failWriteAfterBytes = -1;   // append 写若干字节后失败（模拟半行）
    volatile boolean failForce;               // 完整写入后 force 失败

    public FileTransferWal(Path file, long maxTriggerBytes, long maxTriggerLines) {
        this.file = file;
        this.maxTriggerBytes = maxTriggerBytes;
        this.maxTriggerLines = maxTriggerLines;
        loadAll();
    }

    public FileTransferWal(Path file) {
        this(file, 4L << 20, 8192L);
    }

    // ---------------------------------------------------------------- Wal (write + force)

    @Override
    public synchronized boolean writeIntent(UUID playerId, BankTransferRecord record) {
        if (record == null || closed || globallyQuarantined || poisoned) {
            return false;
        }
        final long seq;
        try {
            seq = Math.addExact(sequence, 1);
        } catch (ArithmeticException e) {
            poison(CorruptReason.SEQ_OVERFLOW, -1);
            return false;
        }
        try {
            appendOrFail(encode(playerId, record, seq));
            latestByKey.put(TransferKey.of(playerId, record.requestId()), record);
            sequence = seq;
            afterAppend();
            return true;
        } catch (IOException | RuntimeException e) {
            poison(CorruptReason.WRITE_FAILED, seq);
            return false;
        }
    }

    @Override
    public synchronized boolean quarantineKey(TransferKey key, QuarantineReason reason) {
        if (key == null || closed || globallyQuarantined || poisoned) {
            return false;
        }
        final long seq;
        try {
            seq = Math.addExact(sequence, 1);
        } catch (ArithmeticException e) {
            poison(CorruptReason.SEQ_OVERFLOW, -1);
            return false;
        }
        try {
            appendOrFail(encodeMarker(key, reason, seq));
            quarantined.add(key);
            sequence = seq;
            afterAppend();
            return true;
        } catch (IOException | RuntimeException e) {
            // marker 持久化失败：当前实例进入 fail-closed（不得依赖临时值继续使用原 pending）。
            poison(CorruptReason.MARKER_WRITE_FAILED, seq);
            return false;
        }
    }

    private void appendOrFail(byte[] line) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(line);
        try (FileChannel ch = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            if (failWriteBeforeBytes >= 0) {
                throw new IOException("simulated failure before first byte");
            }
            int written = 0;
            while (buf.hasRemaining()) {
                ch.write(buf);
                written += buf.position();
                if (failWriteAfterBytes >= 0 && written >= failWriteAfterBytes) {
                    throw new IOException("simulated mid-line failure after " + written + " bytes");
                }
            }
            if (failForce) {
                throw new IOException("simulated force failure");
            }
            ch.force(true);
        }
    }

    /** append/force 异常后：实例进入 poisoned（global quarantine + 不可写）。 */
    private void poison(CorruptReason reason, long seq) {
        poisoned = true;
        globallyQuarantined = true;
        log("wal-poisoned", null, reason, seq, null);
    }

    // ---------------------------------------------------------------- WalRecoveryView

    @Override
    public synchronized Optional<BankTransferRecord> latest(TransferKey key) {
        return Optional.ofNullable(latestByKey.get(key));
    }

    @Override
    public synchronized Set<TransferKey> quarantinedKeys() {
        return new LinkedHashSet<>(quarantined);
    }

    @Override
    public synchronized boolean globallyQuarantined() {
        return globallyQuarantined;
    }

    @Override
    public synchronized long lastSequence() {
        return sequence;
    }

    public synchronized boolean isPoisoned() {
        return poisoned;
    }

    public enum CorruptReason {
        PARSE_OR_CHECKSUM, BAD_SEQ, DUPLICATE_SEQ, SEQ_REGRESSION, PHASE_REGRESSION,
        OVERSIZED_LINE, UNREADABLE, WRITE_FAILED, MARKER_WRITE_FAILED, SEQ_OVERFLOW, UNKNOWN_VERSION
    }

    public record WalCorruption(TransferKey key, CorruptReason reason, long seq, boolean global) {
    }

    // ---------------------------------------------------------------- lifecycle

    public synchronized void close() {
        this.closed = true;
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    public Path path() {
        return file;
    }

    // ---- 测试接缝的公开访问器（仅测试用；生产不调用） ----

    public void injectLoadFailureAfterEntries(int n) {
        this.failLoadAfterEntries = n;
    }

    public void injectWriteFailureBeforeBytes(int n) {
        this.failWriteBeforeBytes = n;
    }

    public void injectWriteFailureAfterBytes(int n) {
        this.failWriteAfterBytes = n;
    }

    public void injectForceFailure(boolean fail) {
        this.failForce = fail;
    }

    /** 测试接缝：发生过的“全文件扫描 / 重读”次数（正常转账写入不得增加）。 */
    public synchronized int rescanCount() {
        return rescanCount;
    }

    public synchronized Set<TransferKey> keys() {
        return new LinkedHashSet<>(latestByKey.keySet());
    }

    /** 测试钩子：验证两个不同世界路径不共用同一个 WAL 实例。 */
    public static boolean isForPath(FileTransferWal wal, Path path) {
        return wal != null && wal.file.toAbsolutePath().equals(path.toAbsolutePath());
    }

    // ---------------------------------------------------------------- load (streaming bounded)

    private void loadAll() {
        if (!Files.exists(file)) {
            return;
        }
        Set<Long> seenSeqs = new LinkedHashSet<>();
        seqOwner.clear();
        long longMaxObserved = 0;
        Map<TransferKey, BankTransferPhase> keyLastPhase = new LinkedHashMap<>();

        long loadedLines = 0;
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = readBoundedLine(br)) != null) {
                loadedLines++;
                if (line.equals("__OVERSIZED__")) {
                    globalQuarantine(CorruptReason.OVERSIZED_LINE, -1);
                    continue;
                }
                if (line.isBlank()) {
                    continue;
                }
                // 结构分层：任何行（含损坏）先提取可用的正数 seq 计入水位与占用。
                long rawSeq = extractSeqNumber(line);
                if (rawSeq > 0) {
                    // 收尾：全局（跨 key）seq 倒退必须 fail closed——不能因为两侧阶段各自
                    // 合法就放行；能可靠归属则 keyed 隔离，否则全局隔离。
                    if (rawSeq < longMaxObserved) {
                        long[] regBuf = {-1};
                        TransferKey regKey = extractKey(line, regBuf);
                        if (regKey != null) {
                            quarantine(regKey, CorruptReason.SEQ_REGRESSION, rawSeq);
                        } else {
                            globalQuarantine(CorruptReason.SEQ_REGRESSION, rawSeq);
                        }
                        seenSeqs.add(rawSeq);
                        continue;
                    }
                    longMaxObserved = Math.max(longMaxObserved, rawSeq);
                    if (isMarkerLine(line)) {
                        seenSeqs.add(rawSeq);
                        handleMarker(line, rawSeq);
                        continue;
                    }
                    WalEntry parsed = tryParse(line);
                    if (parsed == null) {
                        seenSeqs.add(rawSeq); // 损坏行也登记 seq 占用（不得复用）
                        handleCorrupt(line);
                        continue;
                    }
                    // 合法行：seq 检查
                    TransferKey key = TransferKey.of(parsed.playerId(), parsed.record().requestId());
                    long s = parsed.seq();
                    if (s <= 0) {
                        quarantine(key, CorruptReason.BAD_SEQ, s);
                        continue;
                    }
                    if (!seenSeqs.add(s)) {
                        TransferKey owner = seqOwner.get(s);
                        if (owner != null && !owner.equals(key)) {
                            quarantine(owner, CorruptReason.DUPLICATE_SEQ, s);
                        }
                        quarantine(key, CorruptReason.DUPLICATE_SEQ, s);
                        continue;
                    }
                    seqOwner.put(s, key);
                    if (keyLastPhase.containsKey(key)
                            && !TransferPhases.isDirectEdge(keyLastPhase.get(key), parsed.record().phase())) {
                        quarantine(key, CorruptReason.PHASE_REGRESSION, s);
                        continue;
                    }
                    keyLastPhase.put(key, parsed.record().phase());
                    latestByKey.put(key, parsed.record());
                } else {
                    // 无法提取 seq：若为 marker → 按损坏处理；否则按损坏行处理。
                    if (isMarkerLine(line)) {
                        handleMarker(line, -1);
                    } else {
                        WalEntry parsed = tryParse(line);
                        if (parsed == null) {
                            handleCorrupt(line);
                        } else {
                            long s = parsed.seq();
                            if (s <= 0) {
                                quarantine(TransferKey.of(parsed.playerId(), parsed.record().requestId()),
                                        CorruptReason.BAD_SEQ, s);
                            }
                        }
                    }
                }
            }
            sequence = longMaxObserved;
            lineCount = loadedLines; // 加载时统计一次，之后 append 用内存 O(1) 计数
            writesSinceCompact = 0;
            compactBackoffWrites = 0;
        } catch (IOException | RuntimeException e) {
            globalQuarantine(CorruptReason.UNREADABLE, -1);
            return;
        }
        if (!globallyQuarantined) {
            maybeCompactIfNeeded();
        }
    }

    /** 结构化提取行内 |seq= 的数值（不校验 checksum；用于水位/占用登记）。 */
    private static long extractSeqNumber(String line) {
        int idx = line.indexOf("|seq=");
        if (idx < 0) {
            return -1;
        }
        int begin = idx + 5;
        int end = line.indexOf('|', begin);
        String token = end < 0 ? line.substring(begin) : line.substring(begin, end);
        try {
            return Long.parseLong(token);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static boolean isMarkerLine(String line) {
        return line.contains(MARKER_FIELD);
    }

    /** 解析并处理 marker：校验和通过 → 持久 isolation(key)；损坏 → keyed/global。 */
    private void handleMarker(String line, long rawSeq) {
        int ck = line.lastIndexOf(CHECKSUM_HEADER);
        if (ck < 0) {
            handleCorrupt(line);
            return;
        }
        String payload = line.substring(0, ck);
        String expected = line.substring(ck + CHECKSUM_HEADER.length());
        if (!constantTimeEquals(sha256Hex(payload), expected)) {
            handleCorrupt(line);
            return;
        }
        TransferKey key = parseMarkerKey(payload);
        if (key == null) {
            globalQuarantine(CorruptReason.PARSE_OR_CHECKSUM, rawSeq);
            return;
        }
        quarantined.add(key);
        log("wal-marker", key, CorruptReason.PARSE_OR_CHECKSUM, rawSeq, null);
    }

    private static TransferKey parseMarkerKey(String payload) {
        try {
            int p = payload.indexOf("|p=");
            int r = payload.indexOf("|req=");
            if (p < 0 || r < 0) {
                return null;
            }
            String uuidHex = fieldValue(payload, p, "|p=");
            String reqHex = fieldValue(payload, r, "|req=");
            UUID player = UUID.fromString(unhex(uuidHex));
            String requestId = unhex(reqHex);
            if (requestId.isBlank()) {
                return null;
            }
            return TransferKey.of(player, requestId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String readBoundedLine(BufferedReader br) throws IOException {
        StringBuilder sb = new StringBuilder(256);
        int c;
        boolean oversized = false;
        while ((c = br.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (sb.length() < MAX_LINE_CHARS) {
                sb.append((char) c);
            } else {
                oversized = true;
            }
        }
        if (oversized) {
            return "__OVERSIZED__"; // 只消费当前行，不额外读下一行
        }
        if (sb.length() == 0 && c == -1) {
            return null;
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- corruption

    private void quarantine(TransferKey key, CorruptReason reason, long seq) {
        if (key == null) {
            globalQuarantine(reason, seq);
            return;
        }
        quarantined.add(key);
        log("wal-corrupt", key, reason, seq, null);
    }

    private void globalQuarantine(CorruptReason reason, long seq) {
        globallyQuarantined = true;
        log("wal-global-quarantine", null, reason, seq, null);
    }

    private TransferKey extractKey(String line, long[] seqOut) {
        try {
            int p = line.indexOf("|p=");
            int r = line.indexOf("|req=");
            int s = line.indexOf("|seq=");
            if (p < 0 || r < 0) {
                return null;
            }
            String uuidHex = fieldValue(line, p, "|p=");
            String reqHex = fieldValue(line, r, "|req=");
            String seqStr = s < 0 ? "" : fieldValue(line, s, "|seq=");
            if (uuidHex == null || uuidHex.isEmpty() || reqHex == null || reqHex.isEmpty()) {
                return null;
            }
            UUID player = UUID.fromString(unhex(uuidHex));
            String requestId = unhex(reqHex);
            if (requestId.isBlank()) {
                return null;
            }
            if (seqStr != null && !seqStr.isEmpty()) {
                seqOut[0] = Long.parseLong(seqStr);
            }
            return TransferKey.of(player, requestId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void handleCorrupt(String line) {
        long[] seqBuf = {-1};
        TransferKey key = extractKey(line, seqBuf);
        quarantine(key, CorruptReason.PARSE_OR_CHECKSUM, seqBuf[0]);
        if (key != null && seqBuf[0] > 0) {
            TransferKey owner = seqOwner.get(seqBuf[0]);
            if (owner != null && !owner.equals(key)) {
                quarantine(owner, CorruptReason.PARSE_OR_CHECKSUM, seqBuf[0]);
            }
        }
    }

    private static String fieldValue(String line, int start, String field) {
        int begin = start + field.length();
        int end = line.indexOf('|', begin);
        return end < 0 ? line.substring(begin) : line.substring(begin, end);
    }

    // ---------------------------------------------------------------- line parse

    public record WalEntry(UUID playerId, BankTransferRecord record, long seq) {
    }

    private WalEntry tryParse(String line) {
        int ck = line.lastIndexOf(CHECKSUM_HEADER);
        if (ck < 0) {
            return null;
        }
        String payload = line.substring(0, ck);
        String expected = line.substring(ck + CHECKSUM_HEADER.length());
        if (!constantTimeEquals(sha256Hex(payload), expected)) {
            return null;
        }
        if (!payload.startsWith("WALV=")) {
            return null;
        }
        try {
            int verEnd = payload.indexOf('|');
            if (verEnd < 5) {
                return null;
            }
            String versionToken = payload.substring(5, verEnd);
            if (!String.valueOf(WAL_VERSION).equals(versionToken)) {
                return null;
            }
        } catch (RuntimeException e) {
            return null;
        }
        Map<String, String> kv = new LinkedHashMap<>();
        try {
            String[] fields = payload.split("\\|");
            for (String f : fields) {
                if (f.isEmpty()) {
                    continue;
                }
                int eq = f.indexOf('=');
                if (eq < 0) {
                    return null;
                }
                kv.put(f.substring(0, eq), f.substring(eq + 1));
            }
            if (!kv.containsKey("seq") || !kv.containsKey("req") || !kv.containsKey("dir")
                    || !kv.containsKey("ph") || !kv.containsKey("st") || !kv.containsKey("p")) {
                return null;
            }
            long seq = Long.parseLong(kv.get("seq"));
            if (seq <= 0) {
                return null;
            }
            UUID playerId = UUID.fromString(unhex(kv.get("p")));
            String requestId = unhex(kv.get("req"));
            if (requestId.isBlank() || requestId.length() > BankTransferRequest.MAX_REQUEST_ID_LENGTH) {
                return null;
            }
            BankTransferRecord record = new BankTransferRecord(
                    requestId,
                    parseDirection(kv.get("dir")),
                    parsePhase(kv.get("ph")),
                    parseStatus(kv.get("st")),
                    unhex(kv.getOrDefault("msg", "")),
                    Long.parseLong(kv.getOrDefault("reqCu", "0")),
                    Long.parseLong(kv.getOrDefault("reqCt", "0")),
                    Long.parseLong(kv.getOrDefault("db", "0")),
                    Long.parseLong(kv.getOrDefault("co", "0")),
                    unhex(kv.getOrDefault("b0", "")),
                    unhex(kv.getOrDefault("b1", "")),
                    unhex(kv.getOrDefault("s0", "")),
                    unhex(kv.getOrDefault("s1", "")),
                    unhex(kv.getOrDefault("rb", "")),
                    Long.parseLong(kv.getOrDefault("bk", "0")),
                    Long.parseLong(kv.getOrDefault("se", "0")),
                    unhex(kv.getOrDefault("prv", "")),
                    (int) Long.parseLong(kv.getOrDefault("opv", "0")),
                    (int) Long.parseLong(kv.getOrDefault("smv", "0")),
                    Long.parseLong(kv.getOrDefault("ep", "0")));
            if (record.direction() == null || record.phase() == null || record.status() == null) {
                return null;
            }
            if (!BankTransferRecordValidator.isWellFormed(record)) {
                return null;
            }
            return new WalEntry(playerId, record, seq);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static BankTransferRequest.Direction parseDirection(String name) {
        try {
            return BankTransferRequest.Direction.valueOf(name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static BankTransferPhase parsePhase(String name) {
        try {
            return BankTransferPhase.valueOf(name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static BankTransferStatus parseStatus(String name) {
        try {
            return BankTransferStatus.valueOf(name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- encode

    private static byte[] encode(UUID playerId, BankTransferRecord r, long seq) {
        String payload = PREFIX + seq
                + "|p=" + (playerId == null ? "" : hex(playerId.toString()))
                + "|req=" + hex(r.requestId() != null ? r.requestId() : "")
                + "|dir=" + (r.direction() == null ? "" : r.direction().name())
                + "|reqCu=" + r.requestedCopper()
                + "|reqCt=" + r.requestedSecuritiesCents()
                + "|db=" + r.actualDebitCents()
                + "|co=" + r.copperAmount()
                + "|ph=" + (r.phase() == null ? "" : r.phase().name())
                + "|st=" + (r.status() == null ? "" : r.status().name())
                + "|prv=" + hex(r.providerId())
                + "|opv=" + r.operationIdVersion()
                + "|smv=" + r.stateMachineVersion()
                + "|ep=" + r.runtimeEpoch()
                + "|b0=" + hex(r.opBankDebit())
                + "|b1=" + hex(r.opBankCredit())
                + "|s0=" + hex(r.opSecuritiesDebit())
                + "|s1=" + hex(r.opSecuritiesCredit())
                + "|rb=" + hex(r.opRollback())
                + "|bk=" + r.bankBalanceCopper()
                + "|se=" + r.securitiesBalanceCents()
                + "|msg=" + hex(r.message());
        return (payload + CHECKSUM_HEADER + sha256Hex(payload) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] encodeMarker(TransferKey key, QuarantineReason reason, long seq) {
        String payload = "WALV=" + WAL_VERSION + MARKER_FIELD + "seq=" + seq
                + "|p=" + hex(key.playerId().toString())
                + "|req=" + hex(key.requestId())
                + "|rsn=" + (reason == null ? "?" : reason.name());
        return (payload + CHECKSUM_HEADER + sha256Hex(payload) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- safe compaction (keyed)

    public synchronized boolean compact() {
        if (globallyQuarantined || closed || !quarantined.isEmpty()) {
            return false;
        }
        final List<WalEntry> keep;
        try {
            keep = loadEntriesStrict();
        } catch (WalReadFailure e) {
            log("wal-compact-read-failed", null, e.reason, -1, e.cause);
            return false;
        }
        Map<TransferKey, WalEntry> latest = new LinkedHashMap<>();
        long maxSeq = 0;
        for (WalEntry e : keep) {
            TransferKey k = TransferKey.of(e.playerId(), e.record().requestId());
            WalEntry prev = latest.get(k);
            if (prev == null || e.seq() > prev.seq()) {
                latest.put(k, e);
            }
            maxSeq = Math.max(maxSeq, e.seq());
        }
        List<WalEntry> ordered = new ArrayList<>(latest.values());
        ordered.sort(java.util.Comparator.comparingLong(WalEntry::seq));
        Path tmp = file.resolveSibling(file.getFileName() + ".compact");
        try {
            try (FileChannel ch = FileChannel.open(tmp,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (WalEntry e : ordered) {
                    ByteBuffer buf = ByteBuffer.wrap(encode(e.playerId(), e.record(), e.seq()));
                    while (buf.hasRemaining()) {
                        ch.write(buf);
                    }
                }
                ch.force(true);
            }
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            forceParentDirectory();
            latestByKey.clear();
            for (Map.Entry<TransferKey, WalEntry> e : latest.entrySet()) {
                latestByKey.put(e.getKey(), e.getValue().record());
            }
            sequence = maxSeq;
            lineCount = ordered.size(); // 压缩后文件行数 = 已写条数
            writesSinceCompact = 0;
            return true;
        } catch (IOException | RuntimeException e) {
            log("wal-compact-failed", null, CorruptReason.WRITE_FAILED, -1, e);
            deleteQuietly(tmp);
            return false;
        }
    }

    private static final class WalReadFailure extends RuntimeException {
        final CorruptReason reason;
        final Throwable cause;

        WalReadFailure(CorruptReason reason, Throwable cause) {
            this.reason = reason;
            this.cause = cause;
        }
    }

    private List<WalEntry> loadEntriesStrict() {
        rescanCount++;
        List<WalEntry> out = new ArrayList<>();
        long loadedLines = 0;
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = readBoundedLine(br)) != null) {
                loadedLines++;
                if (line.equals("__OVERSIZED__")) {
                    throw new WalReadFailure(CorruptReason.OVERSIZED_LINE, null);
                }
                if (line.isBlank()) {
                    continue;
                }
                if (isMarkerLine(line) || extractSeqNumber(line) < 0) {
                    // marker 或无法归属 seq 的行在压缩时视为不一致 → 整体失败。
                    throw new WalReadFailure(CorruptReason.PARSE_OR_CHECKSUM, null);
                }
                WalEntry parsed = tryParse(line);
                if (parsed == null) {
                    throw new WalReadFailure(CorruptReason.PARSE_OR_CHECKSUM, null);
                }
                out.add(parsed);
                if (failLoadAfterEntries >= 0 && out.size() >= failLoadAfterEntries) {
                    throw new WalReadFailure(CorruptReason.UNREADABLE,
                            new IOException("simulated mid-read failure"));
                }
            }
        } catch (WalReadFailure e) {
            throw e;
        } catch (IOException e) {
            throw new WalReadFailure(CorruptReason.UNREADABLE, e);
        }
        return out;
    }

    /** 每次成功 append 后：内存 O(1) 行计数 + 退避衰减，再视阈值处理压缩。 */
    private void afterAppend() {
        lineCount++;
        writesSinceCompact++;
        maybeCompactIfNeeded();
    }

    private void maybeCompactIfNeeded() {
        // 退避窗口内不重复尝试（压缩被拒/失败后避免每次写都重试）。
        if (compactBackoffWrites > 0) {
            compactBackoffWrites--;
            return;
        }
        try {
            if (!Files.exists(file)) {
                return;
            }
            long bytes = Files.size(file); // O(1) stat；行数用内存计数，不重扫全文件
            if (bytes <= maxTriggerBytes && lineCount <= maxTriggerLines) {
                return;
            }
            boolean ok = compact();
            if (ok) {
                writesSinceCompact = 0;
            } else {
                compactBackoffWrites = COMPACT_BACKOFF_WRITES; // 被拒/失败 → 退避
            }
        } catch (IOException | RuntimeException e) {
            compactBackoffWrites = COMPACT_BACKOFF_WRITES;
            log("wal-threshold-failed", null, CorruptReason.WRITE_FAILED, -1, e);
        }
    }

    private void forceParentDirectory() {
        Path parent = file.toAbsolutePath().getParent();
        if (parent == null) {
            return;
        }
        try (FileChannel ch = FileChannel.open(parent, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException e) {
            // best effort
        }
    }

    // ---------------------------------------------------------------- bounded logging

    private void log(String event, TransferKey key, CorruptReason reason, long seq, Throwable t) {
        org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(FileTransferWal.class);
        String player = key == null ? "?" : key.playerId().toString();
        String reqHash = key == null ? "?" : shortHash(key.requestId());
        String reasonText = reason == null ? "?" : reason.name();
        logger.error("[StockMarket] WAL {} [-manual-review-required] player={} requestIdHash={} "
                        + "reason={} seq={}", event, player, reqHash, reasonText, seq);
    }

    private static String shortHash(String value) {
        return sha256Hex(value == null ? "" : value).substring(0, 16);
    }

    // ---------------------------------------------------------------- helpers

    private static String hex(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() * 2);
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String unhex(String hex) {
        if (hex == null || hex.isEmpty()) {
            return "";
        }
        if ((hex.length() & 1) != 0) {
            throw new IllegalArgumentException("odd hex length");
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int hi = Character.digit(hex.charAt(2 * i), 16);
            int lo = Character.digit(hex.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("bad hex");
            }
            bytes[i] = (byte) ((hi << 4) | lo);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String sha256Hex(String payload) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    public static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
