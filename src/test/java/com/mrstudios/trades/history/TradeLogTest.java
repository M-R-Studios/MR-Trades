package com.mrstudios.trades.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The trade log on a real SQLite file (DESIGN.md 6, 8.1 TradeLogTest). */
class TradeLogTest {

    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long NOW = 1_800_000_000_000L;
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID SAM = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID KIM = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @TempDir
    Path folder;

    private final List<TradeLog> logs = new ArrayList<>();
    private final Logger logger = Logger.getLogger("TradeLogTest");

    @AfterEach
    void closeLogs() {
        logs.forEach(log -> log.close(1000));
    }

    private Backlog backlog() {
        return new Backlog(folder.resolve(Backlog.FILE_NAME));
    }

    private File dbFile() {
        return folder.resolve("history.db").toFile();
    }

    /** A log on the SQLite file, opened and ready. Retries don't wait. */
    private TradeLog sqlite(int retentionDays) {
        return started(() -> new SqliteDatabase(dbFile()), retentionDays);
    }

    private TradeLog started(TradeLog.Opener opener, int retentionDays) {
        TradeLog log = new TradeLog(logger, backlog(), millis -> { }, () -> NOW);
        logs.add(log);
        log.open(opener, retentionDays);
        log.flush(5000);
        return log;
    }

    static TradeRecord record(UUID a, String aName, UUID b, String bName, long at) {
        return new TradeRecord(UUID.randomUUID(), at, "default", "world", 1, 64, -3, a, aName, b, bName,
                new byte[] {1, 2, 3}, new byte[0], "64x Diamond", "", new BigDecimal("12.50"), BigDecimal.ZERO,
                5, 0, false, 4440);
    }

    private static HistoryQuery.Result page(TradeLog log, UUID player, int page) {
        return log.page(new HistoryQuery(player, "x", page)).join();
    }

    @Test
    void insertAndPageThroughHistoryNewestFirst() {
        TradeLog log = sqlite(0);
        List<TradeRecord> written = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            TradeRecord r = i % 2 == 0 ? record(ALEX, "Alex", SAM, "Sam", NOW - i * 1000L)
                    : record(SAM, "Sam", ALEX, "Alex", NOW - i * 1000L);
            written.add(r);
            log.record(r);
        }
        log.record(record(SAM, "Sam", KIM, "Kim", NOW));
        log.flush(5000);

        HistoryQuery.Result first = page(log, ALEX, 1);
        assertEquals(50, first.total());
        assertEquals(2, first.pages());
        assertEquals(45, first.rows().size());
        assertEquals(written.subList(0, 45), first.rows(), "newest first, every column read back");
        assertTrue(first.hasNext());
        assertFalse(first.hasPrevious());

        HistoryQuery.Result second = page(log, ALEX, 2);
        assertEquals(written.subList(45, 50), second.rows());
        assertFalse(second.hasNext());

        HistoryQuery.Result past = page(log, ALEX, 9);
        assertEquals(2, past.query().page(), "past the end shows the last page");
        assertEquals(1, page(log, KIM, 1).total(), "only the player's own trades");
    }

    @Test
    void viewOfSwapsGaveAndGot() {
        TradeRecord r = record(ALEX, "Alex", SAM, "Sam", NOW);
        assertEquals("64x Diamond", r.viewOf(ALEX).gaveSummary());
        assertEquals("Sam", r.viewOf(ALEX).partnerName());
        assertEquals("64x Diamond", r.viewOf(SAM).gotSummary());
        assertEquals(0, new BigDecimal("12.5").compareTo(r.viewOf(SAM).gotMoney()));
        assertEquals("Alex", r.viewOf(SAM).partnerName());
    }

    @Test
    void namesAreFoundIgnoringCaseNewestFirst() {
        TradeLog log = sqlite(0);
        log.record(record(ALEX, "OldName", SAM, "Sam", NOW - 5000));
        log.record(record(KIM, "Kim", ALEX, "Alex", NOW));
        log.flush(5000);

        assertEquals(Optional.of(new Database.NameMatch(ALEX, "Alex")), log.findName("ALEX").join());
        assertEquals(Optional.of(new Database.NameMatch(ALEX, "OldName")), log.findName("oldname").join());
        assertEquals(Optional.empty(), log.findName("nobody").join());
    }

    @Test
    void retentionDeletesOnlyOldRows() {
        TradeLog first = sqlite(0);
        TradeRecord old = record(ALEX, "Alex", SAM, "Sam", NOW - 2 * DAY);
        TradeRecord recent = record(ALEX, "Alex", SAM, "Sam", NOW - DAY / 2);
        first.record(old);
        first.record(recent);
        first.flush(5000);
        assertEquals(2, page(first, ALEX, 1).total(), "0 keeps everything");

        first.purge(1);
        first.flush(5000);
        assertEquals(List.of(recent), page(first, ALEX, 1).rows());
    }

    @Test
    void retentionAlsoRunsAtStartup() {
        TradeLog first = sqlite(0);
        first.record(record(ALEX, "Alex", SAM, "Sam", NOW - 10 * DAY));
        first.flush(5000);
        first.close(1000);

        TradeLog second = sqlite(7);
        assertEquals(0, page(second, ALEX, 1).total());
    }

    @Test
    void theLogSurvivesARestart() {
        TradeLog first = sqlite(0);
        TradeRecord r = record(ALEX, "Alex", SAM, "Sam", NOW);
        first.record(r);
        first.close(5000);

        TradeLog second = sqlite(0);
        assertEquals(List.of(r), page(second, ALEX, 1).rows());
    }

    @Test
    void aFailingDatabaseWritesToTheBacklogAndItIsImportedOnTheNextStart() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        TradeLog broken = started(() -> new FailingDatabase(attempts), 0);
        TradeRecord a = record(ALEX, "Alex", SAM, "Sam", NOW - 1000);
        TradeRecord b = record(SAM, "Sam", ALEX, "Alex", NOW);
        broken.record(a);
        broken.record(b);
        broken.flush(5000);
        assertEquals(2 * (TradeLog.RETRY_DELAYS_MS.length + 1), attempts.get(), "three retries each");
        assertEquals(List.of(a, b), backlog().read().stream().map(Backlog.Line::record).toList());
        broken.close(1000);

        TradeLog fixed = sqlite(0);
        assertEquals(List.of(b, a), page(fixed, ALEX, 1).rows());
        assertFalse(Files.exists(backlog().file()), "deleted once every line was imported");
    }

    @Test
    void storageThatCantOpenDisablesHistoryButKeepsEveryRecord() throws Exception {
        TradeLog log = started(() -> {
            throw new SQLException("connection refused");
        }, 0);
        assertEquals(TradeLog.Status.FAILED, log.status());
        TradeRecord r = record(ALEX, "Alex", SAM, "Sam", NOW);
        log.record(r);
        log.flush(5000);
        assertEquals(List.of(r), backlog().read().stream().map(Backlog.Line::record).toList());
        CompletionException failed = null;
        try {
            page(log, ALEX, 1);
        } catch (CompletionException e) {
            failed = e;
        }
        assertTrue(failed != null, "queries fail instead of hanging");
    }

    @Test
    void writesStillQueuedAtShutdownGoToTheBacklog() throws Exception {
        TradeLog slow = started(() -> new SlowDatabase(new SqliteDatabase(dbFile())), 0);
        List<TradeRecord> records = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            TradeRecord r = record(ALEX, "Alex", SAM, "Sam", NOW + i);
            records.add(r);
            slow.record(r);
        }
        slow.close(100);
        slow.record(record(KIM, "Kim", SAM, "Sam", NOW)); // after close: straight to the backlog

        int backlogged = backlog().read().size();
        TradeLog next = sqlite(0);
        int stored = page(next, ALEX, 1).total() + page(next, KIM, 1).total();
        assertTrue(backlogged >= 1, "some were still queued");
        assertEquals(6, stored, "every record ends up stored, none lost or doubled");
    }

    @Test
    void importingTheSameRecordTwiceKeepsOneRow() throws Exception {
        TradeRecord r = record(ALEX, "Alex", SAM, "Sam", NOW);
        backlog().append(List.of(r, r));
        TradeLog log = sqlite(0);
        assertEquals(1, page(log, ALEX, 1).total());
    }

    @Test
    void unreadableBacklogLinesAreKept() throws Exception {
        TradeRecord r = record(ALEX, "Alex", SAM, "Sam", NOW);
        backlog().append(List.of(r));
        Files.writeString(backlog().file(), "not json\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);
        TradeLog log = sqlite(0);
        assertEquals(1, page(log, ALEX, 1).total());
        assertEquals(List.of("not json"), Files.readAllLines(backlog().file()));
    }

    @Test
    void backlogLinesRoundTrip() {
        TradeRecord r = new TradeRecord(UUID.randomUUID(), NOW, "survival", "world_nether", -5, 70, 12, ALEX, "Alex",
                SAM, ".Bedrock_Sam", new byte[] {9, 8, 7}, new byte[] {1}, "1x Diamond Sword \"Excalibur\"", "2x Stone",
                new BigDecimal("1234.5678"), new BigDecimal("0.01"), 3, 7, true, 4440);
        assertEquals(r, Backlog.fromJson(Backlog.toJson(r)));
    }

    @Test
    void aNewerSchemaIsRefused() throws Exception {
        sqlite(0).close(1000);
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbFile().getAbsolutePath());
                var statement = connection.createStatement()) {
            statement.execute("INSERT INTO \"schema\" (version) VALUES (99)");
        }
        TradeLog log = sqlite(0);
        assertEquals(TradeLog.Status.FAILED, log.status(), "never writes into a schema it doesn't know");
    }

    @Test
    void moneyComesBackWithoutTheColumnScale() {
        TradeLog log = sqlite(0);
        TradeRecord r = record(ALEX, "Alex", SAM, "Sam", NOW);
        log.record(r);
        log.flush(5000);
        assertEquals("12.5", page(log, ALEX, 1).rows().get(0).aMoney().toPlainString());
    }

    /** Opens, but every insert fails. */
    private static final class FailingDatabase implements Database {

        private final AtomicInteger attempts;

        FailingDatabase(AtomicInteger attempts) {
            this.attempts = attempts;
        }

        @Override
        public void migrate() {
        }

        @Override
        public void insert(TradeRecord record) throws SQLException {
            attempts.incrementAndGet();
            throw new SQLException("disk full");
        }

        @Override
        public List<TradeRecord> page(UUID player, int offset, int limit) throws SQLException {
            throw new SQLException("disk full");
        }

        @Override
        public int count(UUID player) throws SQLException {
            throw new SQLException("disk full");
        }

        @Override
        public Optional<NameMatch> findName(String name) throws SQLException {
            throw new SQLException("disk full");
        }

        @Override
        public int deleteBefore(long millis) {
            return 0;
        }

        @Override
        public String describe() {
            return "failing";
        }

        @Override
        public void close() {
        }
    }

    /** A real database whose inserts take a while, so writes are still queued at shutdown. */
    private static final class SlowDatabase implements Database {

        private final Database real;

        SlowDatabase(Database real) {
            this.real = real;
        }

        @Override
        public void migrate() throws SQLException {
            real.migrate();
        }

        @Override
        public void insert(TradeRecord record) throws SQLException {
            try {
                Thread.sleep(80);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("interrupted");
            }
            real.insert(record);
        }

        @Override
        public List<TradeRecord> page(UUID player, int offset, int limit) throws SQLException {
            return real.page(player, offset, limit);
        }

        @Override
        public int count(UUID player) throws SQLException {
            return real.count(player);
        }

        @Override
        public Optional<NameMatch> findName(String name) throws SQLException {
            return real.findName(name);
        }

        @Override
        public int deleteBefore(long millis) throws SQLException {
            return real.deleteBefore(millis);
        }

        @Override
        public String describe() {
            return "slow";
        }

        @Override
        public void close() {
            real.close();
        }
    }
}
