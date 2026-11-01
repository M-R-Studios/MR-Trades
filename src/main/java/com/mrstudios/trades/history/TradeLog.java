package com.mrstudios.trades.history;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Writes completed trades to the {@link Database} and answers history queries, all on one
 * background thread named {@code MR-Trades-DB} (DESIGN.md 6.3). Nothing here ever blocks the main
 * thread or fails a trade: a record that can't be written goes to the {@link Backlog}, which is
 * imported on the next start.
 */
public final class TradeLog {

    public static final String THREAD_NAME = "MR-Trades-DB";
    /** Waits before each retry of a failed insert: three retries, then the backlog. */
    static final long[] RETRY_DELAYS_MS = {250, 1000, 3000};
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    /** Opens the database; called on the log's thread. */
    @FunctionalInterface
    public interface Opener {
        Database open() throws SQLException;
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public enum Status {
        OPENING, READY, FAILED, CLOSED
    }

    private final Logger logger;
    private final Backlog backlog;
    private final Sleeper sleeper;
    private final LongSupplier clock;
    private final ThreadPoolExecutor executor;
    private volatile Database database;
    private volatile Status status = Status.OPENING;

    public TradeLog(Logger logger, Backlog backlog, Sleeper sleeper, LongSupplier clock) {
        this.logger = logger;
        this.backlog = backlog;
        this.sleeper = sleeper;
        this.clock = clock;
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), task -> {
            Thread thread = new Thread(task, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Opens the database, creates or upgrades its tables, imports the backlog and purges old
     * rows, in that order, before anything else queued. A failure disables history, never trading.
     */
    public void open(Opener opener, int retentionDays) {
        executor.execute(() -> {
            try {
                Database opened = opener.open();
                try {
                    opened.migrate();
                } catch (SQLException | RuntimeException e) {
                    opened.close();
                    throw e;
                }
                database = opened;
                status = Status.READY;
                logger.info("Trade history: " + opened.describe() + ".");
            } catch (SQLException | RuntimeException e) {
                status = Status.FAILED;
                logger.log(Level.SEVERE, "Couldn't open the trade history storage; /trade history is unavailable"
                        + " until it works again (trading isn't affected). Completed trades are kept in "
                        + Backlog.FILE_NAME + " until then: " + e.getMessage(), e);
                return;
            }
            importBacklog();
            purgeNow(retentionDays);
        });
    }

    public Status status() {
        return status;
    }

    /** {@code SQLite (history.db)}, or what went wrong, for {@code /mrtrades version}. */
    public String describe() {
        Database db = database;
        return switch (status) {
            case READY -> db == null ? "ready" : db.describe();
            case OPENING -> "opening";
            case FAILED -> "unavailable (see the console)";
            case CLOSED -> "closed";
        };
    }

    /**
     * Queues {@code record}. Returns at once and never throws: if the log is closed or full of
     * trouble, the record goes to the backlog instead.
     */
    public void record(TradeRecord record) {
        if (status == Status.CLOSED) {
            toBacklog(List.of(record), "the trade log is closed");
            return;
        }
        try {
            executor.execute(new Write(record));
        } catch (RejectedExecutionException e) {
            toBacklog(List.of(record), "the trade log is closed");
        }
    }

    /** A page of {@code query.player()}'s trades; a page past the end gives the last page. */
    public CompletableFuture<HistoryQuery.Result> page(HistoryQuery query) {
        return submit(db -> {
            int total = db.count(query.player());
            int pages = Math.max(1, (total + HistoryQuery.PAGE_SIZE - 1) / HistoryQuery.PAGE_SIZE);
            HistoryQuery shown = new HistoryQuery(query.player(), query.name(), Math.min(query.page(), pages));
            List<TradeRecord> rows = db.page(query.player(), (shown.page() - 1) * HistoryQuery.PAGE_SIZE,
                    HistoryQuery.PAGE_SIZE);
            return new HistoryQuery.Result(shown, rows, total);
        });
    }

    public CompletableFuture<Optional<Database.NameMatch>> findName(String name) {
        return submit(db -> db.findName(name));
    }

    /** Deletes rows older than {@code retentionDays} on the log's thread; 0 keeps everything. */
    public void purge(int retentionDays) {
        try {
            executor.execute(() -> purgeNow(retentionDays));
        } catch (RejectedExecutionException ignored) {
            // Closed: the next start purges.
        }
    }

    /** Waits until everything queued so far is done (tests, and the restart-safe shutdown). */
    public void flush(long timeoutMs) {
        try {
            executor.submit(() -> { }).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RejectedExecutionException ignored) {
            // Best effort.
        }
    }

    /**
     * Stops accepting work, waits up to {@code waitMs} for queued writes, then puts whatever is
     * still queued into the backlog and closes the database.
     */
    public void close(long waitMs) {
        status = Status.CLOSED;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(waitMs, TimeUnit.MILLISECONDS)) {
                List<TradeRecord> left = new ArrayList<>();
                for (Runnable task : executor.shutdownNow()) {
                    if (task instanceof Write write) {
                        left.add(write.record);
                    }
                }
                toBacklog(left, "the trade log didn't finish writing in time");
                executor.awaitTermination(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Database db = database;
        database = null;
        if (db != null) {
            db.close();
        }
    }

    @FunctionalInterface
    private interface Query<T> {
        T run(Database db) throws SQLException;
    }

    private <T> CompletableFuture<T> submit(Query<T> query) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                Database db = database;
                if (db == null) {
                    result.completeExceptionally(new IllegalStateException("The trade history storage isn't available"));
                    return;
                }
                try {
                    result.complete(query.run(db));
                } catch (SQLException | RuntimeException e) {
                    result.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(new IllegalStateException("The trade log is closed"));
        }
        return result;
    }

    private void purgeNow(int retentionDays) {
        Database db = database;
        if (retentionDays <= 0 || db == null) {
            return;
        }
        try {
            int deleted = db.deleteBefore(clock.getAsLong() - retentionDays * DAY_MS);
            if (deleted > 0) {
                logger.info("Trade history: deleted " + deleted + " trade(s) older than " + retentionDays + " days.");
            }
        } catch (SQLException | RuntimeException e) {
            logger.log(Level.WARNING, "Couldn't delete old trade history: " + e.getMessage(), e);
        }
    }

    private void importBacklog() {
        List<Backlog.Line> lines;
        try {
            lines = backlog.read();
        } catch (IOException e) {
            logger.log(Level.WARNING, "Couldn't read " + Backlog.FILE_NAME + "; it's kept for the next start.", e);
            return;
        }
        if (lines.isEmpty()) {
            return;
        }
        List<String> remaining = new ArrayList<>();
        int imported = 0;
        for (Backlog.Line line : lines) {
            if (line.record() == null) {
                logger.warning(Backlog.FILE_NAME + " has a line that can't be read; it's kept in the file: " + line.text());
                remaining.add(line.text());
                continue;
            }
            try {
                database.insert(line.record());
                imported++;
            } catch (SQLException | RuntimeException e) {
                remaining.add(line.text());
            }
        }
        try {
            backlog.rewrite(remaining);
        } catch (IOException e) {
            // The imported rows would be imported again next time; inserts skip known ids.
            logger.log(Level.WARNING, "Couldn't update " + Backlog.FILE_NAME, e);
        }
        logger.info("Trade history: imported " + imported + " trade(s) from " + Backlog.FILE_NAME
                + (remaining.isEmpty() ? "." : "; " + remaining.size() + " are kept there for the next start."));
    }

    private void toBacklog(List<TradeRecord> records, String why) {
        if (records.isEmpty()) {
            return;
        }
        try {
            backlog.append(records);
            logger.warning(records.size() + " trade record(s) went to " + Backlog.FILE_NAME + " (" + why
                    + "); they're imported on the next start.");
        } catch (IOException | RuntimeException e) {
            // The last resort: the console has everything needed to restore them by hand.
            for (TradeRecord record : records) {
                logger.severe("Couldn't save a trade record anywhere (" + e.getMessage() + "): " + Backlog.toJson(record));
            }
        }
    }

    /** Inserts one record, retrying with backoff, then falls back to the backlog. */
    private final class Write implements Runnable {

        private final TradeRecord record;

        Write(TradeRecord record) {
            this.record = record;
        }

        @Override
        public void run() {
            Database db = database;
            if (db == null) {
                toBacklog(List.of(record), "the trade history storage isn't available");
                return;
            }
            Exception last = null;
            for (int attempt = 0; attempt <= RETRY_DELAYS_MS.length; attempt++) {
                try {
                    if (attempt > 0) {
                        sleeper.sleep(RETRY_DELAYS_MS[attempt - 1]);
                    }
                    db.insert(record);
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    last = e;
                    break;
                } catch (SQLException | RuntimeException e) {
                    last = e;
                }
            }
            logger.log(Level.WARNING, "Couldn't write trade " + record.id() + " to the trade history", last);
            toBacklog(List.of(record), "the database refused it");
        }
    }
}
