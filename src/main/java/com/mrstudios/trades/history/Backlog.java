package com.mrstudios.trades.history;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Trade records that couldn't be written to the database, one JSON object per line in
 * {@code history-backlog.jsonl} (DESIGN.md 6.3). They're imported on the next start, so no
 * record is ever dropped.
 */
public final class Backlog {

    public static final String FILE_NAME = "history-backlog.jsonl";

    /** A line of the file: the record, or null if the line can't be read. */
    public record Line(String text, TradeRecord record) {
    }

    private final Path file;

    public Backlog(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public synchronized void append(List<TradeRecord> records) throws IOException {
        if (records.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (TradeRecord record : records) {
            text.append(toJson(record)).append('\n');
        }
        Path folder = file.getParent();
        if (folder != null) {
            Files.createDirectories(folder);
        }
        Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public synchronized List<Line> read() throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<Line> lines = new ArrayList<>();
        for (String text : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (text.isBlank()) {
                continue;
            }
            TradeRecord record;
            try {
                record = fromJson(text);
            } catch (RuntimeException e) {
                record = null;
            }
            lines.add(new Line(text, record));
        }
        return lines;
    }

    /** Replaces the file with {@code remaining}, or deletes it when nothing remains. */
    public synchronized void rewrite(List<String> remaining) throws IOException {
        if (remaining.isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        Files.writeString(file, String.join("\n", remaining) + "\n", StandardCharsets.UTF_8);
    }

    static String toJson(TradeRecord r) {
        Base64.Encoder base64 = Base64.getEncoder();
        JsonObject json = new JsonObject();
        json.addProperty("id", r.id().toString());
        json.addProperty("completed_at", r.completedAt());
        json.addProperty("server", r.server());
        json.addProperty("world", r.world());
        json.addProperty("x", r.x());
        json.addProperty("y", r.y());
        json.addProperty("z", r.z());
        json.addProperty("a_uuid", r.aId().toString());
        json.addProperty("a_name", r.aName());
        json.addProperty("b_uuid", r.bId().toString());
        json.addProperty("b_name", r.bName());
        json.addProperty("a_items", base64.encodeToString(r.aItems()));
        json.addProperty("b_items", base64.encodeToString(r.bItems()));
        json.addProperty("a_summary", r.aSummary());
        json.addProperty("b_summary", r.bSummary());
        json.addProperty("a_money", r.aMoney().toPlainString());
        json.addProperty("b_money", r.bMoney().toPlainString());
        json.addProperty("a_xp", r.aXp());
        json.addProperty("b_xp", r.bXp());
        json.addProperty("economy_error", r.economyError());
        json.addProperty("data_version", r.dataVersion());
        return json.toString();
    }

    static TradeRecord fromJson(String text) {
        Base64.Decoder base64 = Base64.getDecoder();
        JsonObject j = JsonParser.parseString(text).getAsJsonObject();
        return new TradeRecord(UUID.fromString(j.get("id").getAsString()), j.get("completed_at").getAsLong(),
                j.get("server").getAsString(), j.get("world").getAsString(),
                j.get("x").getAsInt(), j.get("y").getAsInt(), j.get("z").getAsInt(),
                UUID.fromString(j.get("a_uuid").getAsString()), j.get("a_name").getAsString(),
                UUID.fromString(j.get("b_uuid").getAsString()), j.get("b_name").getAsString(),
                base64.decode(j.get("a_items").getAsString()), base64.decode(j.get("b_items").getAsString()),
                j.get("a_summary").getAsString(), j.get("b_summary").getAsString(),
                new BigDecimal(j.get("a_money").getAsString()), new BigDecimal(j.get("b_money").getAsString()),
                j.get("a_xp").getAsInt(), j.get("b_xp").getAsInt(), j.get("economy_error").getAsBoolean(),
                j.get("data_version").getAsInt());
    }
}
