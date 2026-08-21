package com.tanrunn.stockmarket.server.transfer;

import com.tanrunn.stockmarket.api.BankTransferPhase;
import com.tanrunn.stockmarket.api.BankTransferRecord;
import com.tanrunn.stockmarket.api.BankTransferRequest;
import com.tanrunn.stockmarket.api.BankTransferStatus;
import com.tanrunn.stockmarket.api.OperationIds;
import com.tanrunn.stockmarket.api.TransferKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实持久介质上的 WAL 恢复索引测试（临时目录；测试后清理，不触碰真实世界）。 */
class FileTransferWalTest {

    @TempDir
    Path dir;

    private static final UUID PLAYER_A = UUID.randomUUID();
    private static final UUID PLAYER_B = UUID.randomUUID();
    private static final String PROVIDER = "server_menu:lc_bank_main";
    private static final long EPOCH = 424242L;

    private Path path(String name) {
        return dir.resolve(name);
    }

    private static BankTransferRecord record(String requestId, BankTransferPhase phase) {
        return new BankTransferRecord(requestId, BankTransferRequest.Direction.DEPOSIT_TO_SECURITIES,
                phase, BankTransferStatus.INCOMPLETE_TRANSFER, "状态", 10, 1000, 1000, 10,
                "sm:bd:o", "sm:bc:o", "sm:sd:o", "sm:sc:o", "sm:rb:o", 4990, 1000,
                PROVIDER, OperationIds.VERSION, BankTransferRecord.STATE_MACHINE_VERSION, EPOCH);
    }

    // ---------------------------------------------------------------- 恢复索引

    @Test
    void latestKeepsOnlyNewestPhasePerKeyAndSequenceAdvances() {
        Path p = path("a.wal");
        FileTransferWal wal = new FileTransferWal(p);
        assertTrue(wal.writeIntent(PLAYER_A, record("r1", BankTransferPhase.PREPARED)));
        assertTrue(wal.writeIntent(PLAYER_A, record("r1", BankTransferPhase.SOURCE_DEBITED)));
        assertTrue(wal.writeIntent(PLAYER_A, record("r1", BankTransferPhase.DESTINATION_CREDIT_PENDING)));
        FileTransferWal reparsed = new FileTransferWal(p);
        Optional<BankTransferRecord> latest = reparsed.latest(TransferKey.of(PLAYER_A, "r1"));
        assertTrue(latest.isPresent());
        assertEquals(BankTransferPhase.DESTINATION_CREDIT_PENDING, latest.get().phase());
        assertEquals(3, reparsed.lastSequence());
    }

    @Test
    void twoPlayersSameRequestIdAreFullyIsolated() {
        Path p = path("b.wal");
        FileTransferWal wal = new FileTransferWal(p);
        wal.writeIntent(PLAYER_A, record("same", BankTransferPhase.COMPENSATION_PENDING));
        wal.writeIntent(PLAYER_B, record("same", BankTransferPhase.DESTINATION_CREDIT_PENDING));
        FileTransferWal reparsed = new FileTransferWal(p);
        assertEquals(BankTransferPhase.COMPENSATION_PENDING,
                reparsed.latest(TransferKey.of(PLAYER_A, "same")).get().phase());
        assertEquals(BankTransferPhase.DESTINATION_CREDIT_PENDING,
                reparsed.latest(TransferKey.of(PLAYER_B, "same")).get().phase());
        assertTrue(reparsed.compact());
        assertTrue(reparsed.latest(TransferKey.of(PLAYER_B, "same")).isPresent(), "A 压缩不删 B 最新");
        assertFalse(reparsed.quarantinedKeys().contains(TransferKey.of(PLAYER_B, "same")));
    }

    @Test
    void pendingPhasesSurviveObjectDestruction() {
        Path p = path("c.wal");
        FileTransferWal wal = new FileTransferWal(p);
        wal.writeIntent(PLAYER_A, record("x", BankTransferPhase.PREPARED));
        wal.writeIntent(PLAYER_A, record("x", BankTransferPhase.SOURCE_DEBITED));
        wal.writeIntent(PLAYER_A, record("x", BankTransferPhase.DESTINATION_CREDIT_PENDING));
        wal.writeIntent(PLAYER_A, record("x", BankTransferPhase.COMPENSATION_PENDING));
        FileTransferWal reparsed = new FileTransferWal(p);
        assertEquals(BankTransferPhase.COMPENSATION_PENDING,
                reparsed.latest(TransferKey.of(PLAYER_A, "x")).get().phase());
    }

    // ---------------------------------------------------------------- 损坏处理

    @Test
    void truncatedTailQuarantinesOnlyThatKeyNotGlobal() throws Exception {
        Path p = path("d.wal");
        FileTransferWal wal = new FileTransferWal(p);
        wal.writeIntent(PLAYER_A, record("good", BankTransferPhase.PREPARED));
        wal.writeIntent(PLAYER_A, record("cut", BankTransferPhase.DESTINATION_CREDIT_PENDING));
        byte[] bytes = Files.readAllBytes(p);
        // 截断：去掉最后 20 字节（落在校验和区），|p= 与 |req= 完好 → 可归属该 key。
        Files.write(p, java.util.Arrays.copyOf(bytes, bytes.length - 20));
        FileTransferWal reparsed = new FileTransferWal(p);
        assertFalse(reparsed.globallyQuarantined(), "能解析 key 的半条行只隔离该 key");
        assertTrue(reparsed.quarantinedKeys().contains(TransferKey.of(PLAYER_A, "cut")));
        assertTrue(reparsed.latest(TransferKey.of(PLAYER_A, "good")).isPresent());
    }

    @Test
    void unparseableCorruptLineGloballyQuarantines() throws Exception {
        Path p = path("e.wal");
        Files.write(p, "this is not a wal line; no key fields\n".getBytes(StandardCharsets.UTF_8));
        FileTransferWal reparsed = new FileTransferWal(p);
        assertTrue(reparsed.globallyQuarantined(), "无法归属的损坏行必须全局隔离");
    }

    @Test
    void checksumTamperQuarantinesOnlyThatKey() throws Exception {
        Path p = path("f.wal");
        FileTransferWal wal = new FileTransferWal(p);
        wal.writeIntent(PLAYER_A, record("t1", BankTransferPhase.DESTINATION_CREDIT_PENDING));
        wal.writeIntent(PLAYER_A, record("ok", BankTransferPhase.PREPARED));
        byte[] bytes = Files.readAllBytes(p);
        int firstNl = indexOfByte(bytes, (byte) '\n');
        bytes[firstNl - 5] ^= 0x01; // 篡改第一条（t1）的校验和
        Files.write(p, bytes);
        FileTransferWal reparsed = new FileTransferWal(p);
        assertFalse(reparsed.globallyQuarantined());
        assertTrue(reparsed.quarantinedKeys().contains(TransferKey.of(PLAYER_A, "t1")));
        assertFalse(reparsed.latest(TransferKey.of(PLAYER_A, "t1")).isPresent());
        assertTrue(reparsed.latest(TransferKey.of(PLAYER_A, "ok")).isPresent());
    }

    @Test
    void duplicateSeqQuarantinesKey() throws Exception {
        Path p = path("g.wal");
        FileTransferWal wal = new FileTransferWal(p);
        wal.writeIntent(PLAYER_A, record("dup", BankTransferPhase.PREPARED));
        byte[] bytes = Files.readAllBytes(p);
        Files.write(p, concat(bytes, bytes));
        FileTransferWal reparsed = new FileTransferWal(p);
        assertFalse(reparsed.globallyQuarantined());
        assertTrue(reparsed.quarantinedKeys().contains(TransferKey.of(PLAYER_A, "dup")));
    }

    @Test
    void seqRegressionQuarantinesKey() throws Exception {
        Path p = path("h.wal");
        Files.write(p, (craftLine(3, PLAYER_A, "rg", "DESTINATION_CREDIT_PENDING")
                + craftLine(1, PLAYER_A, "rg", "PREPARED")).getBytes(StandardCharsets.UTF_8));
        FileTransferWal reparsed = new FileTransferWal(p);
        assertFalse(reparsed.globallyQuarantined());
        assertTrue(reparsed.quarantinedKeys().contains(TransferKey.of(PLAYER_A, "rg")));
    }

    @Test
    void nonPositiveSeqQuarantinesKey() throws Exception {
        Path p = path("i.wal");
        Files.write(p, (craftLine(0, PLAYER_A, "bad0", "PREPARED")
                + craftLine(-1, PLAYER_B, "bad1", "PREPARED")).getBytes(StandardCharsets.UTF_8));
        FileTransferWal reparsed = new FileTransferWal(p);
        assertFalse(reparsed.globallyQuarantined());
        assertTrue(reparsed.quarantinedKeys().contains(TransferKey.of(PLAYER_A, "bad0")));
        assertTrue(reparsed.quarantinedKeys().contains(TransferKey.of(PLAYER_B, "bad1")));
    }

    @Test
    void oversizedLineGloballyQuarantines() throws Exception {
        Path p = path("j.wal");
        StringBuilder huge = new StringBuilder("WALV=1|seq=1|p=");
        for (int i = 0; i < 200_000; i++) {
            huge.append('a');
        }
        Files.write(p, huge.toString().getBytes(StandardCharsets.UTF_8));
        FileTransferWal reparsed = new FileTransferWal(p);
        assertTrue(reparsed.globallyQuarantined(), "超长行无法可靠归属 → 全局隔离");
    }

    @Test
    void unknownVersionQuarantinesKey() throws Exception {
        Path p = path("k.wal");
        Files.write(p, (craftLineVersion(99, PLAYER_A, "uv", "PREPARED"))
                .getBytes(StandardCharsets.UTF_8));
        FileTransferWal reparsed = new FileTransferWal(p);
        assertFalse(reparsed.globallyQuarantined());
        assertTrue(reparsed.quarantinedKeys().contains(TransferKey.of(PLAYER_A, "uv")));
        assertFalse(reparsed.latest(TransferKey.of(PLAYER_A, "uv")).isPresent());
    }

    // ---------------------------------------------------------------- 压缩 / 阶段倒退 / close

    @Test
    void compactionKeepsLatestPerKeyAndPreservesSequence() {
        Path p = path("l.wal");
        FileTransferWal wal = new FileTransferWal(p);
        wal.writeIntent(PLAYER_A, record("ka", BankTransferPhase.PREPARED));
        wal.writeIntent(PLAYER_A, record("ka", BankTransferPhase.SOURCE_DEBITED));
        wal.writeIntent(PLAYER_A, record("ka", BankTransferPhase.DESTINATION_CREDIT_PENDING));
        wal.writeIntent(PLAYER_B, record("kb", BankTransferPhase.PREPARED));
        assertTrue(wal.compact());
        assertEquals(BankTransferPhase.DESTINATION_CREDIT_PENDING,
                wal.latest(TransferKey.of(PLAYER_A, "ka")).get().phase());
        assertEquals(4, wal.lastSequence(), "压缩不重编号，保留原最大 seq");
        assertTrue(wal.writeIntent(PLAYER_A, record("kc", BankTransferPhase.PREPARED)));
        assertEquals(5, wal.lastSequence(), "下一次追加使用历史最大 seq+1");
        FileTransferWal reparsed = new FileTransferWal(p);
        assertEquals(BankTransferPhase.DESTINATION_CREDIT_PENDING,
                reparsed.latest(TransferKey.of(PLAYER_A, "ka")).get().phase());
        assertEquals(5, reparsed.lastSequence());
    }

    @Test
    void compactionKeepsTerminalLatesstNotDeleted() {
        Path p = path("m.wal");
        FileTransferWal wal = new FileTransferWal(p);
        wal.writeIntent(PLAYER_A, record("t", BankTransferPhase.PREPARED));
        BankTransferRecord terminal = new BankTransferRecord("t",
                BankTransferRequest.Direction.DEPOSIT_TO_SECURITIES,
                BankTransferPhase.COMPLETED, BankTransferStatus.SUCCESS, "完成",
                10, 1000, 1000, 10, "sm:bd:o", "sm:bc:o", "sm:sd:o", "sm:sc:o", "sm:rb:o",
                4990, 2000, PROVIDER, OperationIds.VERSION,
                BankTransferRecord.STATE_MACHINE_VERSION, EPOCH);
        wal.writeIntent(PLAYER_A, terminal);
        assertTrue(wal.compact());
        assertTrue(wal.latest(TransferKey.of(PLAYER_A, "t")).isPresent(), "终态保留最新 WAL 墓碑");
        assertEquals(BankTransferPhase.COMPLETED, wal.latest(TransferKey.of(PLAYER_A, "t")).get().phase());
    }

    @Test
    void compactionDoesNotDropOtherPlayerSameRequestId() {
        Path p = path("m2.wal");
        FileTransferWal wal = new FileTransferWal(p);
        wal.writeIntent(PLAYER_A, record("same", BankTransferPhase.DESTINATION_CREDIT_PENDING));
        wal.writeIntent(PLAYER_B, record("same", BankTransferPhase.COMPENSATION_PENDING));
        wal.writeIntent(PLAYER_A, record("same", BankTransferPhase.DESTINATION_CREDITED));
        assertTrue(wal.compact());
        assertTrue(wal.latest(TransferKey.of(PLAYER_A, "same")).isPresent());
        assertTrue(wal.latest(TransferKey.of(PLAYER_B, "same")).isPresent());
        assertEquals(BankTransferPhase.COMPENSATION_PENDING,
                wal.latest(TransferKey.of(PLAYER_B, "same")).get().phase());
    }

    @Test
    void phaseRegressionQuarantinesKeyAndDoesNotAdoptOptimistic() throws Exception {
        Path p = path("n.wal");
        Files.write(p, (craftLine(1, PLAYER_A, "pr", "DESTINATION_CREDITED")
                + craftLine(2, PLAYER_A, "pr", "PREPARED")).getBytes(StandardCharsets.UTF_8));
        FileTransferWal reparsed = new FileTransferWal(p);
        assertTrue(reparsed.quarantinedKeys().contains(TransferKey.of(PLAYER_A, "pr")));
        // 不采用较乐观的记录（seq2 PREPARED）：latest 保留更保守的 seq1 DESTINATION_CREDITED。
        assertTrue(reparsed.latest(TransferKey.of(PLAYER_A, "pr")).isPresent());
        assertEquals(BankTransferPhase.DESTINATION_CREDITED,
                reparsed.latest(TransferKey.of(PLAYER_A, "pr")).get().phase(),
                "阶段倒退：保留保守记录，不得采纳乐观记录");
        assertFalse(reparsed.globallyQuarantined());
    }

    @Test
    void closeBlocksFurtherWrites() {
        Path p = path("o.wal");
        FileTransferWal wal = new FileTransferWal(p);
        wal.close();
        assertFalse(wal.writeIntent(PLAYER_A, record("x", BankTransferPhase.PREPARED)));
    }

    // ---------------------------------------------------------------- 世界生命周期（第六轮：切世界不复用 WAL）

    @Test
    void coordinatorWorldSwitchDoesNotReuseWalAcrossDifferentPaths() {
        BankTransferCoordinator coord = BankTransferCoordinator.INSTANCE;
        coord.onServerStopping();
        Path worldA = dir.resolve("world-a");
        Path worldB = dir.resolve("world-b");
        coord.bindWorld(worldA);
        FileTransferWal walA = coord.currentWalForTest();
        assertTrue(FileTransferWal.isForPath(walA, worldA.resolve(BankTransferCoordinator.WAL_FILE_NAME)));
        // 同世界路径复用同一实例。
        coord.bindWorld(worldA);
        assertTrue(walA == coord.currentWalForTest(), "同一世界路径应复用");
        // 切世界：关闭旧实例、重建新 WAL（绝不继续写旧路径）。
        coord.bindWorld(worldB);
        FileTransferWal walB = coord.currentWalForTest();
        assertTrue(walB != walA, "不同世界路径必须新建 WAL");
        assertTrue(FileTransferWal.isForPath(walB, worldB.resolve(BankTransferCoordinator.WAL_FILE_NAME)));
        assertTrue(walA.isClosed(), "旧世界 WAL 必须关闭");
        // 停止：全部清空。
        coord.onServerStopping();
        assertTrue(coord.currentWalForTest() == null);
    }

    // ---------------------------------------------------------------- 第七轮反例

    @Test
    void keyedCorruptionSurvivesManualCompactionAndRestart() throws Exception {
        Path p = path("r7-1.wal");
        FileTransferWal wal = new FileTransferWal(p);
        wal.writeIntent(PLAYER_A, record("x", BankTransferPhase.DESTINATION_CREDIT_PENDING));
        wal.writeIntent(PLAYER_A, record("y", BankTransferPhase.PREPARED));
        // 篡改第一条校验和 → x 被隔离。
        byte[] bytes = Files.readAllBytes(p);
        int firstNl = indexOfByte(bytes, (byte) '\n');
        bytes[firstNl - 5] ^= 0x01;
        Files.write(p, bytes);
        FileTransferWal reparsed = new FileTransferWal(p);
        assertTrue(reparsed.quarantinedKeys().contains(TransferKey.of(PLAYER_A, "x")));
        byte[] before = Files.readAllBytes(p);
        // 存在隔离证据 → 拒绝压缩，保留完整证据。
        assertFalse(reparsed.compact(), "隔离存在不得压缩");
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(p)), "压缩拒绝后文件字节不得变化");
        // 销毁旧对象 + 重启加载 → 同一 key 仍被隔离，不会成为新请求。
        assertTrue(new FileTransferWal(p).quarantinedKeys().contains(TransferKey.of(PLAYER_A, "x")));
        assertFalse(new FileTransferWal(p).latest(TransferKey.of(PLAYER_A, "x")).isPresent());
    }

    @Test
    void keyedCorruptionSurvivesAutomaticThresholdCompactionAndRestart() throws Exception {
        Path p = path("r7-2.wal");
        FileTransferWal wal = new FileTransferWal(p, 16L << 20, 2); // 行数阈值 2
        wal.writeIntent(PLAYER_A, record("a", BankTransferPhase.PREPARED));
        wal.writeIntent(PLAYER_A, record("b", BankTransferPhase.PREPARED));
        // 篡改第二条校验和 → b 隔离。
        byte[] bytes = Files.readAllBytes(p);
        int firstNl = indexOfByte(bytes, (byte) '\n');
        int secondNl = indexOfByteAt(bytes, (byte) '\n', firstNl + 1);
        bytes[secondNl - 5] ^= 0x01;
        Files.write(p, bytes);
        // reload 时阈值 1 → loadAll 末尾想压缩 → 隔离存在 → 拒绝。
        FileTransferWal reloaded = new FileTransferWal(p, 16L << 20, 1);
        assertTrue(reloaded.quarantinedKeys().contains(TransferKey.of(PLAYER_A, "b")));
        // 重启后隔离仍在。
        assertTrue(new FileTransferWal(p).quarantinedKeys().contains(TransferKey.of(PLAYER_A, "b")));
    }

    @Test
    void corruptOnlyRecordNeverBecomesNewRequestAfterCompaction() throws Exception {
        Path p = path("r7-3.wal");
        // 文件中只有一条损坏记录（可解析 key "only"）。
        FileTransferWal wal = new FileTransferWal(p);
        wal.writeIntent(PLAYER_A, record("only", BankTransferPhase.PREPARED));
        byte[] bytes = Files.readAllBytes(p);
        bytes[bytes.length - 6] ^= 0x01;
        Files.write(p, bytes);
        FileTransferWal reparsed = new FileTransferWal(p);
        assertTrue(reparsed.quarantinedKeys().contains(TransferKey.of(PLAYER_A, "only")));
        assertFalse(reparsed.compact(), "唯一合法证据被损坏 → 不得压缩");
        assertTrue(new FileTransferWal(p).quarantinedKeys().contains(TransferKey.of(PLAYER_A, "only")),
                "损坏键没有任何合法历史也不能消失成为新请求");
        assertFalse(new FileTransferWal(p).latest(TransferKey.of(PLAYER_A, "only")).isPresent());
    }

    @Test
    void compactionWritesInterleavedLatestEntriesInAscendingSequence() {
        Path p = path("r7-4.wal");
        FileTransferWal wal = new FileTransferWal(p);
        wal.writeIntent(PLAYER_A, record("k1", BankTransferPhase.PREPARED));
        wal.writeIntent(PLAYER_B, record("k2", BankTransferPhase.PREPARED));
        wal.writeIntent(PLAYER_A, record("k3", BankTransferPhase.PREPARED));
        wal.writeIntent(PLAYER_A, record("k1", BankTransferPhase.SOURCE_DEBITED));   // seq4
        wal.writeIntent(PLAYER_B, record("k2", BankTransferPhase.DESTINATION_CREDIT_PENDING)); // seq5
        wal.writeIntent(PLAYER_A, record("k3", BankTransferPhase.DESTINATION_CREDIT_PENDING)); // seq6
        assertTrue(wal.compact());
        assertEquals(6, wal.lastSequence(), "压缩保留历史最大 seq");
        assertEquals(BankTransferPhase.SOURCE_DEBITED, wal.latest(TransferKey.of(PLAYER_A, "k1")).get().phase());
        assertEquals(BankTransferPhase.DESTINATION_CREDIT_PENDING,
                wal.latest(TransferKey.of(PLAYER_B, "k2")).get().phase());
        // 压缩后重启必须无虚假 quarantine（seq 升序写入，不倒退）。
        FileTransferWal reloaded = new FileTransferWal(p);
        assertTrue(reloaded.quarantinedKeys().isEmpty(), "压缩后重启不得出现虚假隔离");
        assertEquals(BankTransferPhase.SOURCE_DEBITED,
                reloaded.latest(TransferKey.of(PLAYER_A, "k1")).get().phase());
        assertEquals(6, reloaded.lastSequence());
        // 下一次追加使用 7。
        assertTrue(wal.writeIntent(PLAYER_A, record("k4", BankTransferPhase.PREPARED)));
        assertEquals(7, wal.lastSequence());
    }

    @Test
    void compactionReadFailureKeepsOriginalFileAndIndex() throws Exception {
        Path p = path("r7-5.wal");
        FileTransferWal wal = new FileTransferWal(p);
        wal.writeIntent(PLAYER_A, record("a", BankTransferPhase.PREPARED));
        wal.writeIntent(PLAYER_A, record("a", BankTransferPhase.SOURCE_DEBITED));
        byte[] before = Files.readAllBytes(p);
        long seqBefore = wal.lastSequence();
        // 读到第 1 条合法条目后强迫 IOException → 压缩必须整体失败。
        wal.failLoadAfterEntries = 1;
        assertFalse(wal.compact());
        wal.failLoadAfterEntries = -1;
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(p)),
                "压缩读取失败不得用部分结果覆盖原 WAL");
        assertEquals(seqBefore, wal.lastSequence());
        assertTrue(wal.latest(TransferKey.of(PLAYER_A, "a")).isPresent(), "latest 索引未破坏");
        assertTrue(wal.quarantinedKeys().isEmpty());
    }

    @Test
    void duplicateSequenceAcrossDifferentPlayersQuarantinesBoth() throws Exception {
        Path p = path("r7-6.wal");
        Files.write(p, (craftLine(5, PLAYER_A, "da", "PREPARED")
                + craftLine(5, PLAYER_B, "db", "PREPARED")).getBytes(StandardCharsets.UTF_8));
        FileTransferWal reparsed = new FileTransferWal(p);
        assertFalse(reparsed.globallyQuarantined());
        assertTrue(reparsed.quarantinedKeys().contains(TransferKey.of(PLAYER_A, "da")),
                "重复 seq：首次涉及也要隔离");
        assertTrue(reparsed.quarantinedKeys().contains(TransferKey.of(PLAYER_B, "db")),
                "重复 seq：本次涉及也要隔离");
        // 上一个测试的断言
        assertFalse(reparsed.globallyQuarantined());
    }

    @Test
    void oversizedLineDoesNotConsumeFollowingValidLine() throws Exception {
        Path p = path("r7-7.wal");
        StringBuilder huge = new StringBuilder("WALV=1|seq=100|p=");
        for (int i = 0; i < 200_000; i++) {
            huge.append('a');
        }
        huge.append('\n');
        // 紧跟一条合法记录（key "after"）。
        String valid = craftLine(101, PLAYER_A, "after", "PREPARED");
        Files.write(p, (huge + valid).getBytes(StandardCharsets.UTF_8));
        FileTransferWal reparsed = new FileTransferWal(p);
        assertTrue(reparsed.globallyQuarantined(), "超长行 → 全局隔离");
        assertTrue(reparsed.latest(TransferKey.of(PLAYER_A, "after")).isPresent(),
                "超长行不得吞掉紧随其后的合法行（审计证据保留）");
        assertEquals(101, reparsed.lastSequence());
    }

    // ---------------------------------------------------------------- 第八轮反例（水位 / poison / marker / 状态图）

    @Test
    void corruptHighSequenceAdvancesWatermark() throws Exception {
        Path p = path("r8-1.wal");
        FileTransferWal wal = new FileTransferWal(p);
        wal.writeIntent(PLAYER_A, record("ok", BankTransferPhase.PREPARED)); // seq1
        // 高 seq（100）但校验和损坏的行（字段完整可提取）。
        byte[] bytes = Files.readAllBytes(p);
        String high = craftLine(100, PLAYER_A, "hi", "PREPARED");
        Files.write(p, concat(bytes, high.getBytes(StandardCharsets.UTF_8)));
        int firstNl = indexOfByte(bytes, (byte) '\n');
        // 校验和坏：篡改第二行的 ck 区。
        byte[] all = Files.readAllBytes(p);
        int secondNl = indexOfByteAt(all, (byte) '\n', firstNl + 1);
        int secondStart = firstNl + 1;
        int secondLen = secondNl - secondStart;
        all[secondStart + secondLen - 5] ^= 0x01;
        Files.write(p, all);
        FileTransferWal reparsed = new FileTransferWal(p);
        assertTrue(reparsed.quarantinedKeys().contains(TransferKey.of(PLAYER_A, "hi")),
                "损坏高 seq 行仍按 keyed 隔离");
        assertFalse(reparsed.globallyQuarantined());
        assertEquals(100, reparsed.lastSequence(), "损坏行的可提取高位 seq 必须计入水位");
    }

    @Test
    void corruptHighSequenceIsNeverReusedByLaterWrites() throws Exception {
        Path p = path("r8-2.wal");
        FileTransferWal wal = new FileTransferWal(p);
        wal.writeIntent(PLAYER_A, record("ok", BankTransferPhase.PREPARED));
        byte[] bytes = Files.readAllBytes(p);
        String high = craftLine(100, PLAYER_A, "hi", "PREPARED");
        Files.write(p, concat(bytes, high.getBytes(StandardCharsets.UTF_8)));
        byte[] all = Files.readAllBytes(p);
        int firstNl = indexOfByte(all, (byte) '\n');
        int secondNl = indexOfByteAt(all, (byte) '\n', firstNl + 1);
        all[firstNl + 1 + (secondNl - firstNl - 1) - 5] ^= 0x01;
        Files.write(p, all);
        FileTransferWal reparsed = new FileTransferWal(p);
        assertEquals(100, reparsed.lastSequence());
        // 被隔离 key 之外的后续写入必须用 101，绝不再次用到 seq=100。
        assertTrue(reparsed.writeIntent(PLAYER_A, record("next", BankTransferPhase.PREPARED)));
        assertEquals(101, reparsed.lastSequence(), "不得复用损坏行占用的 seq");
    }

    @Test
    void maxSequenceOverflowPoisonsWal() throws Exception {
        Path p = path("r8-3.wal");
        Files.write(p, craftLine(Long.MAX_VALUE, PLAYER_A, "max", "PREPARED")
                .getBytes(StandardCharsets.UTF_8));
        FileTransferWal wal = new FileTransferWal(p);
        assertEquals(Long.MAX_VALUE, wal.lastSequence());
        assertFalse(wal.writeIntent(PLAYER_A, record("x", BankTransferPhase.PREPARED)),
                "seq 溢出必须 fail closed（不得回绕）");
        assertTrue(wal.isPoisoned());
        assertTrue(wal.globallyQuarantined());
    }

    @Test
    void appendFailureBeforeWritePoisonsCurrentWal() {
        Path p = path("r8-4.wal");
        FileTransferWal wal = new FileTransferWal(p);
        wal.failWriteBeforeBytes = 0;
        assertFalse(wal.writeIntent(PLAYER_A, record("x", BankTransferPhase.PREPARED)));
        assertTrue(wal.isPoisoned());
        assertFalse(wal.quarantineKey(TransferKey.of(PLAYER_A, "y"), com.tanrunn.stockmarket.api.QuarantineReason
                .RECONCILIATION_DIVERGENT), "poisoned 后 marker 写入也失败");
    }

    @Test
    void appendFailureAfterPartialLinePoisonsCurrentWal() {
        Path p = path("r8-5.wal");
        FileTransferWal wal = new FileTransferWal(p);
        wal.failWriteAfterBytes = 10; // 模拟半行写入后失败
        assertFalse(wal.writeIntent(PLAYER_A, record("x", BankTransferPhase.PREPARED)));
        assertTrue(wal.isPoisoned());
        assertTrue(wal.globallyQuarantined());
    }

    @Test
    void forceFailureAfterFullWritePoisonsCurrentWal() {
        Path p = path("r8-6.wal");
        FileTransferWal wal = new FileTransferWal(p);
        wal.failForce = true;
        assertFalse(wal.writeIntent(PLAYER_A, record("x", BankTransferPhase.PREPARED)));
        assertTrue(wal.isPoisoned());
        assertTrue(wal.globallyQuarantined(), "force 失败后不得允许后续写入");
    }

    @Test
    void quarantineMarkerSurvivesCompactionAndRestart() {
        Path p = path("r8-7.wal");
        FileTransferWal wal = new FileTransferWal(p);
        wal.writeIntent(PLAYER_A, record("mk", BankTransferPhase.DESTINATION_CREDIT_PENDING));
        assertTrue(wal.quarantineKey(TransferKey.of(PLAYER_A, "mk"),
                com.tanrunn.stockmarket.api.QuarantineReason.RECONCILIATION_FINGERPRINT_CONFLICT));
        // 存在隔离证据 → 拒绝压缩，marker 不丢。
        assertFalse(wal.compact());
        byte[] before;
        try { before = Files.readAllBytes(p); } catch (Exception e) { throw new AssertionError(e); }
        assertFalse(new FileTransferWal(p).compact());
        try {
            assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(p)));
        } catch (Exception e) { throw new AssertionError(e); }
        // 重启：marker 仍隔离该 key。
        assertTrue(new FileTransferWal(p).quarantinedKeys().contains(TransferKey.of(PLAYER_A, "mk")));
    }

    @Test
    void normalRecordCannotAdvanceOutOfQuarantineMarker() {
        Path p = path("r8-8.wal");
        FileTransferWal wal = new FileTransferWal(p);
        wal.writeIntent(PLAYER_A, record("mr", BankTransferPhase.DESTINATION_CREDIT_PENDING));
        wal.quarantineKey(TransferKey.of(PLAYER_A, "mr"),
                com.tanrunn.stockmarket.api.QuarantineReason.RECONCILIATION_MANUAL_ATTACHMENT);
        // marker 之后再写一个更进取的 COMPLETED → 不得解除隔离。
        BankTransferRecord done = new BankTransferRecord("mr",
                BankTransferRequest.Direction.DEPOSIT_TO_SECURITIES,
                BankTransferPhase.COMPLETED, BankTransferStatus.SUCCESS, "done",
                10, 1000, 1000, 10, "sm:bd:o", "sm:bc:o", "sm:sd:o", "sm:sc:o", "sm:rb:o",
                4990, 2000, PROVIDER, OperationIds.VERSION,
                BankTransferRecord.STATE_MACHINE_VERSION, EPOCH);
        wal.writeIntent(PLAYER_A, done);
        FileTransferWal reparsed = new FileTransferWal(p);
        assertTrue(reparsed.quarantinedKeys().contains(TransferKey.of(PLAYER_A, "mr")),
                "COMPLETED 普通记录不得解除 quarantine marker");
    }

    @Test
    void quarantineMarkerForPlayerADoesNotBlockPlayerBWithSameRequestId() {
        Path p = path("r8-9.wal");
        FileTransferWal wal = new FileTransferWal(p);
        wal.quarantineKey(TransferKey.of(PLAYER_A, "x"),
                com.tanrunn.stockmarket.api.QuarantineReason.RECONCILIATION_DIVERGENT);
        wal.writeIntent(PLAYER_B, record("x", BankTransferPhase.DESTINATION_CREDIT_PENDING));
        FileTransferWal reparsed = new FileTransferWal(p);
        assertTrue(reparsed.quarantinedKeys().contains(TransferKey.of(PLAYER_A, "x")));
        assertFalse(reparsed.quarantinedKeys().contains(TransferKey.of(PLAYER_B, "x")), "A 的 marker 不阻断 B");
        assertTrue(reparsed.latest(TransferKey.of(PLAYER_B, "x")).isPresent());
    }

    @Test
    void transferPhaseGraphHasSingleAuthoritativeImplementation() {
        for (BankTransferPhase a : BankTransferPhase.values()) {
            for (BankTransferPhase b : BankTransferPhase.values()) {
                boolean edge = com.tanrunn.stockmarket.api.TransferPhases.isDirectEdge(a, b);
                boolean reach = com.tanrunn.stockmarket.api.TransferPhases.canProgressTo(a, b);
                if (edge && !reach) {
                    throw new AssertionError("阶段图不一致: directEdge(" + a + "→" + b
                            + ") 但不可达");
                }
                if (a == b && b != BankTransferPhase.MANUAL_REVIEW && !reach) {
                    throw new AssertionError("自反可达缺失: " + a);
                }
            }
        }
        // 关键转换存在性。
        assertTrue(com.tanrunn.stockmarket.api.TransferPhases.canProgressTo(BankTransferPhase.PREPARED,
                BankTransferPhase.COMPLETED));
        assertFalse(com.tanrunn.stockmarket.api.TransferPhases.canProgressTo(BankTransferPhase.COMPLETED,
                BankTransferPhase.PREPARED));
        assertTrue(com.tanrunn.stockmarket.api.TransferPhases.canProgressTo(
                BankTransferPhase.DESTINATION_CREDIT_PENDING, BankTransferPhase.MANUAL_REVIEW));
    }

    // ---------------------------------------------------------------- 收尾：无每次写全扫描 + 压缩退避

    @Test
    void normalWalAppendDoesNotRescanWholeFile() {
        Path p = path("fin-1.wal");
        FileTransferWal wal = new FileTransferWal(p); // 大阈值，不触发压缩
        for (int i = 0; i < 40; i++) {
            assertTrue(wal.writeIntent(PLAYER_A, record("r" + i, BankTransferPhase.PREPARED)));
        }
        assertEquals(0, wal.rescanCount(), "正常转账写入不得重新从头读取整个 WAL");
    }

    @Test
    void failedOrRejectedCompactionUsesBackoff() {
        Path p = path("fin-2.wal");
        FileTransferWal wal = new FileTransferWal(p, 16L << 20, 2); // 行数阈值 2
        wal.writeIntent(PLAYER_A, record("a", BankTransferPhase.PREPARED));
        // 制造隔离 → 后续压缩被拒。
        assertTrue(wal.quarantineKey(TransferKey.of(PLAYER_A, "a"),
                com.tanrunn.stockmarket.api.QuarantineReason.RECONCILIATION_DIVERGENT));
        // 第 3 行触发阈值检查 → compact 被拒（隔离）→ 进入退避。
        assertTrue(wal.writeIntent(PLAYER_B, record("b", BankTransferPhase.PREPARED)));
        int rescanAfter = wal.rescanCount();
        // 退避窗口内连续写入数次：不得因每次写都重试压缩（不产生新全文件扫描）。
        for (int i = 0; i < 10; i++) {
            assertTrue(wal.writeIntent(PLAYER_B, record("c" + i, BankTransferPhase.PREPARED)));
        }
        assertEquals(rescanAfter, wal.rescanCount(),
                "压缩被拒后应退避，不每次写入都再次尝试（无新增全文件扫描）");
        assertTrue(wal.quarantinedKeys().contains(TransferKey.of(PLAYER_A, "a")));
    }

    // ---------------------------------------------------------------- helpers (mirror of encoder)

    private static int indexOfByteAt(byte[] arr, byte target, int from) {
        for (int i = Math.max(0, from); i < arr.length; i++) {
            if (arr[i] == target) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfByte(byte[] arr, byte target) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == target) {
                return i;
            }
        }
        return -1;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static String craftLine(long seq, UUID player, String req, String phase) {
        return craftLineVersion(1, seq, player, req, phase);
    }

    private static String craftLineVersion(int version, UUID player, String req, String phase) {
        return craftLineVersion(version, 1L, player, req, phase);
    }

    private static String craftLineVersion(int version, long seq, UUID player, String req, String phase) {
        String payload = "WALV=" + version + "|seq=" + seq
                + "|p=" + hex(player.toString())
                + "|req=" + hex(req)
                + "|dir=DEPOSIT_TO_SECURITIES"
                + "|reqCu=10|reqCt=1000|db=1000|co=10"
                + "|ph=" + phase + "|st=INCOMPLETE_TRANSFER"
                + "|prv=" + hex(PROVIDER)
                + "|opv=" + OperationIds.VERSION
                + "|smv=" + BankTransferRecord.STATE_MACHINE_VERSION
                + "|ep=" + EPOCH
                + "|b0=" + hex("sm:bd:o")
                + "|b1=" + hex("sm:bc:o")
                + "|s0=" + hex("sm:sd:o")
                + "|s1=" + hex("sm:sc:o")
                + "|rb=" + hex("sm:rb:o")
                + "|bk=4990|se=1000|msg=" + hex("状态");
        return payload + "|ck=" + sha256Hex(payload) + "\n";
    }

    private static String hex(String value) {
        StringBuilder sb = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String sha256Hex(String payload) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
