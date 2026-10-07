package com.nordfjell.nordbans;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

final class SyncConcurrencyTest {
    @Test void globalChangesAndEntityDeliveryDoNotLoseUpdates() throws Exception {
        SyncPlanner planner = new SyncPlanner();
        var latest = new ConcurrentHashMap<String,BanStore.Sync>();
        var delivered = ConcurrentHashMap.<String>newKeySet();
        planner.tick(true,List::of,key -> Optional.empty(),message -> true,10);
        try (var threads = Executors.newFixedThreadPool(3)) {
            var writers = new java.util.ArrayList<Future<?>>();
            for (int writer=0;writer<2;writer++) {
                final int prefix=writer;
                writers.add(threads.submit(() -> {
                    for (int index=0;index<1000;index++) {
                        var record=new BanRecord("Race"+prefix+"_"+index,System.currentTimeMillis()+600000,"Synthetic","Console");
                        var message=new BanStore.Sync("BAN",record);
                        latest.put(BanStore.normalize(record.playerName()),message);
                        planner.changed(message);
                    }
                }));
            }
            var reader=threads.submit(() -> {
                while (writers.stream().anyMatch(task -> !task.isDone()) || planner.pending()>0) {
                    int sent=planner.tick(true,List::of,key -> Optional.ofNullable(latest.get(key)),
                        message -> { delivered.add(message.record().playerName()); return true; },10);
                    assertTrue(sent<=10);
                    Thread.yield();
                }
            });
            for (var writer:writers) writer.get(10,TimeUnit.SECONDS);
            reader.get(10,TimeUnit.SECONDS);
        }
        assertEquals(2000,delivered.size());
        assertEquals(0,planner.pending());
    }
}
