package com.nordfjell.nordbans;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/** A single writer commits the complete file before publishing an immutable reader snapshot. */
final class BanStore {
    private static final String UNBAN = "!unban.";
    private static final long MAX_FILE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_RECORDS = 50000;
    record Sync(String action, BanRecord record) {}
    private static final class State {
        final Map<String,BanRecord> bans,releases;
        final List<Sync> sync;
        final long firstExpiry;
        State(Map<String,BanRecord> bans, Map<String,BanRecord> releases) {
            this.bans=Map.copyOf(bans); this.releases=Map.copyOf(releases);
            long now=System.currentTimeMillis(); List<Sync> entries=new ArrayList<>();
            bans.values().stream().filter(r->r.active(now)).forEach(r->entries.add(new Sync("BAN",r)));
            releases.values().stream().filter(r->r.active(now)).forEach(r->entries.add(new Sync("UNBAN",r)));
            entries.sort(Comparator.comparing(item->normalize(item.record.playerName())));
            sync=List.copyOf(entries);
            firstExpiry=entries.stream().mapToLong(item->item.record.expiresAtMillis()).min().orElse(Long.MAX_VALUE);
        }
    }
    @FunctionalInterface interface Persister { void save(Properties properties) throws IOException; }
    private final Path file;
    private final Persister persister;
    private volatile State state = new State(Map.of(), Map.of());

    BanStore(Path file) { this(file, null); }
    BanStore(Path file, Persister persister) {
        this.file = file.toAbsolutePath().normalize();
        this.persister = persister == null ? this::writeAtomic : persister;
    }
    synchronized void load() throws IOException {
        Files.createDirectories(file.getParent());
        if (!Files.exists(file)) { state = new State(Map.of(), Map.of()); return; }
        if (Files.size(file) > MAX_FILE_BYTES) throw new IOException("Ban file exceeds safe size limit");
        Properties properties = new Properties() {
            @Override public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) throw new IllegalArgumentException("Duplicate ban property");
                return super.put(key, value);
            }
        };
        try (InputStream input = Files.newInputStream(file)) { properties.load(input); }
        catch (IllegalArgumentException exception) { throw new IOException("Invalid ban properties", exception); }
        if (properties.size() > MAX_RECORDS) throw new IOException("Too many ban records");
        Map<String,BanRecord> bans = new HashMap<>(), releases = new HashMap<>();
        long now = System.currentTimeMillis();
        for (String key : properties.stringPropertyNames()) {
            boolean release = key.startsWith(UNBAN);
            String nameKey = normalize(release ? key.substring(UNBAN.length()) : key);
            String value = properties.getProperty(key);
            if (release && !value.startsWith("UNBAN|")) throw new IOException("Invalid release tombstone");
            BanRecord record = decode(release ? value.substring(6) : value);
            if (!nameKey.equals(normalize(record.playerName()))) throw new IOException("Ban name/key mismatch");
            Map<String,BanRecord> target = release ? releases : bans;
            if (target.containsKey(nameKey)) throw new IOException("Duplicate normalized ban name");
            // Validate even expired entries: corrupt data must not silently remove a ban.
            target.put(nameKey, record);
        }
        for (String key : releases.keySet()) if (bans.containsKey(key)) throw new IOException("Conflicting ban and release");
        bans.values().removeIf(record -> !record.active(now));
        releases.values().removeIf(record -> !record.active(now));
        state = new State(bans, releases); // No partial state can escape a failed load.
    }
    Optional<BanRecord> active(String name) {
        BanRecord record = state.bans.get(normalize(name));
        return record != null && record.active(System.currentTimeMillis()) ? Optional.of(record) : Optional.empty();
    }
    synchronized void put(BanRecord record) throws IOException {
        validate(record);
        Map<String,BanRecord> bans = new HashMap<>(state.bans), releases = new HashMap<>(state.releases);
        String name = normalize(record.playerName()); bans.put(name, record); releases.remove(name);
        commit(new State(bans, releases));
    }
    synchronized Optional<BanRecord> remove(String name) throws IOException {
        String key = normalize(name); BanRecord old = state.bans.get(key);
        if (old == null) return Optional.empty();
        Map<String,BanRecord> bans = new HashMap<>(state.bans), releases = new HashMap<>(state.releases);
        bans.remove(key);
        // Durable tombstone survives no carrier/restart, until the old ban would have expired.
        if (old.active(System.currentTimeMillis())) releases.put(key, old);
        commit(new State(bans, releases)); return Optional.of(old);
    }
    synchronized void removeExpired() throws IOException {
        long now = System.currentTimeMillis();
        Map<String,BanRecord> bans = new HashMap<>(state.bans), releases = new HashMap<>(state.releases);
        boolean changed = bans.values().removeIf(record -> !record.active(now));
        changed |= releases.values().removeIf(record -> !record.active(now));
        if (changed) commit(new State(bans, releases));
    }
    Collection<BanRecord> activeRecords() {
        long now = System.currentTimeMillis();
        return state.bans.values().stream().filter(record -> record.active(now)).toList();
    }
    Collection<String> activeNames() { return activeRecords().stream().map(BanRecord::playerName).toList(); }
    Optional<Sync> syncFor(String name) {
        State snapshot = state; String key = normalize(name); long now = System.currentTimeMillis();
        BanRecord ban = snapshot.bans.get(key);
        if (ban != null && ban.active(now)) return Optional.of(new Sync("BAN", ban));
        BanRecord release = snapshot.releases.get(key);
        return release != null && release.active(now) ? Optional.of(new Sync("UNBAN", release)) : Optional.empty();
    }
    List<Sync> syncRecords() {
        State snapshot=state; long now=System.currentTimeMillis();
        if(now < snapshot.firstExpiry) return snapshot.sync;
        return snapshot.sync.stream().filter(item->item.record.active(now)).toList();
    }
    private void commit(State next) throws IOException {
        if (next.bans.size() + next.releases.size() > MAX_RECORDS) throw new IOException("Too many ban records");
        Properties p = new Properties();
        next.bans.forEach((name, record) -> p.setProperty(name, encode(record)));
        // Five fields make old NordBans 1.0.0 ignore metadata instead of resending it as a BAN.
        next.releases.forEach((name, record) -> p.setProperty(UNBAN + name, "UNBAN|" + encode(record)));
        persister.save(p);
        state = next;
    }
    private void writeAtomic(Properties properties) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        properties.store(bytes, "NordBans. !unban.* are durable release tombstones; do not edit live.");
        if (bytes.size() > MAX_FILE_BYTES) throw new IOException("Ban file exceeds safe size limit");
        Path temporary = Files.createTempFile(file.getParent(), file.getFileName().toString() + ".", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes.toByteArray());
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            // No non-atomic fallback: unsupported replacement fails without publishing memory.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private static String encode(BanRecord record) {
        return record.expiresAtMillis() + "|" + text(record.playerName()) + "|" + text(record.reason()) + "|" + text(record.actor());
    }
    private static BanRecord decode(String value) throws IOException {
        try {
            String[] parts = value.split("\\|", -1);
            if (parts.length != 4) throw new IllegalArgumentException("Invalid field count");
            BanRecord record = new BanRecord(untext(parts[1]), Long.parseLong(parts[0]), untext(parts[2]), untext(parts[3]));
            validate(record); return record;
        } catch (RuntimeException | CharacterCodingException exception) { throw new IOException("Invalid ban record", exception); }
    }
    static void validate(BanRecord record) {
        if (record == null || !validName(record.playerName()) || record.expiresAtMillis() <= 0 ||
            record.reason() == null || record.reason().isBlank() || record.reason().length() > 256 ||
            record.reason().chars().anyMatch(Character::isISOControl) || record.actor() == null ||
            record.actor().isBlank() || record.actor().length() > 128 || record.actor().chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid ban fields");
    }
    static boolean validName(String name) { return name != null && name.length() <= 32 && name.matches("[A-Za-z0-9_.-]+"); }
    private static String text(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String untext(String value) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(value))).toString();
    }
    static String normalize(String name) { return name.toLowerCase(Locale.ROOT); }
}
