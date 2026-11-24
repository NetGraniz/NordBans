package com.nordfjell.nordbans;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.DriverManager;
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
}
