package com.nordfjell.nordbans;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BanStoreTest {
    @TempDir Path directory;

    @Test
    void persistsCaseInsensitiveBansInSqlite() throws Exception {
        Path database = directory.resolve("bans.db");
        long expires = System.currentTimeMillis() + 60_000;
        BanStore store = new BanStore(database);
        store.load();
        store.put(new BanRecord("PlayerOne", expires, "Spam", "Console"));

        BanStore reloaded = new BanStore(database);
        reloaded.load();
        assertEquals("Spam", reloaded.active("PLAYERONE").orElseThrow().reason());
        assertTrue(reloaded.remove("playerone").isPresent());
        BanStore removed = new BanStore(database);
        removed.load();
        assertTrue(removed.active("PlayerOne").isEmpty());
    }

    @Test
    void importsLegacyPropertiesOnlyOnce() throws Exception {
        Path database = directory.resolve("bans.db");
        Path legacy = directory.resolve("bans.properties");
        long expires = System.currentTimeMillis() + 60_000;
        String row = expires + "|" + encoded("LegacyUser") + "|" + encoded("Legacy reason") + "|" + encoded("Console");
        Files.writeString(legacy, "legacyuser=" + row + System.lineSeparator(), StandardCharsets.ISO_8859_1);

        BanStore first = new BanStore(database, legacy);
        first.load();
        assertEquals("Legacy reason", first.active("legacyuser").orElseThrow().reason());
        first.remove("LegacyUser");

        BanStore second = new BanStore(database, legacy);
        second.load();
        assertTrue(second.active("LegacyUser").isEmpty(), "migration marker must prevent re-import");
    }

    @Test
    void expiredRowsAreRemovedFromDatabase() throws Exception {
        Path database = directory.resolve("bans.db");
        BanStore store = new BanStore(database);
        store.load();
        store.put(new BanRecord("Expired", 1, "Old", "Console"));
        assertTrue(store.active("Expired").isEmpty());
        store.removeExpired();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM bans")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
    }

    @Test
    void durationParserKeepsSupportedContract() {
        assertEquals(60_000, DurationParser.parse("1m").orElseThrow().toMillis());
        assertTrue(DurationParser.parse("0m").isEmpty());
        assertTrue(DurationParser.parse("1w").isEmpty());
    }

    private static String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
