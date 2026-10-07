package com.nordfjell.nordbans;

import java.util.*;
import java.util.function.*;

/** Serialized between global completions and the carrier's entity task. No file I/O under this lock. */
final class SyncPlanner {
    private final LinkedHashMap<String,BanStore.Sync> pending = new LinkedHashMap<>();
    private boolean baselineRequired=true;
    synchronized void changed(BanStore.Sync sync) { pending.put(BanStore.normalize(sync.record().playerName()),sync); }
    synchronized void unavailable() { baselineRequired=true; pending.clear(); }
    synchronized int tick(boolean available, Supplier<List<BanStore.Sync>> baseline,
             Function<String,Optional<BanStore.Sync>> latest, Predicate<BanStore.Sync> send, int budget) {
        if (!available) { unavailable(); return 0; }
        if (baselineRequired) {
            pending.clear(); for (var sync : baseline.get()) changed(sync); baselineRequired=false;
        }
        int sent=0, inspected=0;
        while (!pending.isEmpty() && inspected++ < budget) {
            String key=pending.keySet().iterator().next();
            var current=latest.apply(key); // Do not send an obsolete BAN after a later committed UNBAN.
            if (current.isEmpty()) { pending.remove(key); continue; }
            if (!send.test(current.get())) { baselineRequired=true; return sent; }
            pending.remove(key); sent++;
        }
        return sent;
    }
    synchronized int pending() { return pending.size(); }
}
