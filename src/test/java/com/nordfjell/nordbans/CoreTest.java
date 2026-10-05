package com.nordfjell.nordbans;

import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CoreTest {
    private CoreTest() {
    }

    public static void main(String[] args) throws Exception {
        assert DurationParser.parse("1m").orElseThrow().equals(Duration.ofMinutes(1));
        assert DurationParser.parse("5h").orElseThrow().equals(Duration.ofHours(5));
        assert DurationParser.parse("1d").orElseThrow().equals(Duration.ofDays(1));
        assert DurationParser.parse("0m").isEmpty();
        assert DurationParser.parse("1w").isEmpty();
        assert DurationParser.parse("forever").isEmpty();
        String kick = BanProtocol.kickMessage(123456789L, "Chat spam | repeated");
        assert kick.startsWith("NORD_BAN_V1|123456789|");
        assert !kick.contains("Chat spam");

        Path directory = Files.createTempDirectory("nordbans-store-");
        Path file = directory.resolve("bans.properties");
        BanStore store = new BanStore(file);
        store.load();
        long future = System.currentTimeMillis() + 60_000L;
        store.put(new BanRecord("Player", future, "Spam", "Console"));
        BanStore reloaded = new BanStore(file);
        reloaded.load();
        assert reloaded.active("player").orElseThrow().reason().equals("Spam");
        assert reloaded.remove("PLAYER").isPresent();
        assert reloaded.active("Player").isEmpty();
        Files.deleteIfExists(file);
        Files.deleteIfExists(directory);
    }
}
