package com.nordfjell.nordbans;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class RegressionTest {
    static int passed;
    static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    static void pass(String message) { passed++; System.out.println("PASS "+message); }
    static BanRecord ban(String name) { return new BanRecord(name,System.currentTimeMillis()+600000,"Spam | тест","Console"); }
    static void write(Path file,Properties p) throws IOException { try(var out=Files.newOutputStream(file)){p.store(out,"LOCAL TEST");} }
    static void failsIO(IoAction action) throws Exception { try {action.run(); throw new AssertionError("Expected IOException");} catch(IOException expected){} }
    @FunctionalInterface interface IoAction {void run() throws Exception;}
    static void until(java.util.function.BooleanSupplier condition) throws Exception {
        long start=System.nanoTime(); while(!condition.getAsBoolean()) {if(System.nanoTime()-start>TimeUnit.SECONDS.toNanos(5))throw new AssertionError("Timeout"); Thread.sleep(2);}
    }
    public static void main(String[] args) throws Exception {
        Path directory=Files.createTempDirectory("nordbans-regression-");
        try { storeTests(directory); taskTests(); syncTests(directory); }
        finally {
            try(var paths=Files.walk(directory)){ for(Path p:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); }
        }
        System.out.println("ALL "+passed+" REGRESSION SCENARIOS PASSED");
    }
    static void storeTests(Path dir) throws Exception {
        Path file=dir.resolve("bans.properties"); BanStore store=new BanStore(file); store.load();
        BanRecord record=ban("Player"); store.put(record);
        BanStore reloaded=new BanStore(file); reloaded.load(); check(reloaded.active("PLAYER").orElseThrow().equals(record),"Roundtrip");
        pass("legacy ban record format, Unicode reason and case-insensitive lookup roundtrip");
        check(store.syncRecords()==store.syncRecords(),"Stable state must reuse precomputed sync frame");
        pass("immutable sorted synchronization frame reused until state/expiry changes");
        byte[] original=Files.readAllBytes(file);
        BanStore failed=new BanStore(file,p->{throw new IOException("Injected disk failure");}); failed.load();
        failsIO(()->failed.put(ban("Other"))); check(failed.active("Other").isEmpty()&&Arrays.equals(original,Files.readAllBytes(file)),"Failed BAN must not publish");
        pass("failed BAN preserves file and memory");
        failsIO(()->failed.remove("Player")); check(failed.active("Player").isPresent(),"Failed UNBAN must not lift ban");
        pass("failed UNBAN preserves enforced ban");
        BanRecord replacement=new BanRecord("Player",record.expiresAtMillis()+1000,"Other reason","Console");
        failsIO(()->failed.put(replacement)); check(failed.active("Player").orElseThrow().equals(record),"Failed replacement must keep old ban");
        pass("failed replacement keeps original ban and expiry");
        Properties good=new Properties(); try(var input=Files.newInputStream(file)){good.load(input);}
        Properties broken=new Properties(); broken.putAll(good); broken.setProperty("bad","not-a-record"); write(file,broken);
        failsIO(reloaded::load); check(reloaded.active("Player").isPresent(),"Failed load must not partly replace current state");
        pass("malformed record fails entire load without partial publication");
        Properties mismatch=new Properties(); mismatch.setProperty("different",good.getProperty("player")); write(file,mismatch); failsIO(reloaded::load);
        pass("name/key mismatch rejected");
        Files.writeString(file,"player="+good.getProperty("player")+"\nplayer="+good.getProperty("player")+"\n"); failsIO(reloaded::load);
        pass("duplicate property keys rejected");
        Properties duplicate=new Properties(); duplicate.setProperty("PLAYER",good.getProperty("player")); duplicate.setProperty("player",good.getProperty("player")); write(file,duplicate); failsIO(reloaded::load);
        pass("duplicate case-normalized accounts rejected");
        Properties malformedUtf=new Properties(); malformedUtf.setProperty("player",record.expiresAtMillis()+"|UGxheWVy|_w|Q29uc29sZQ"); write(file,malformedUtf); failsIO(reloaded::load);
        pass("invalid UTF-8 cannot be silently replaced and accepted");
        Files.writeString(file,"player=\\uZZZZ\n"); failsIO(reloaded::load);
        pass("malformed Java-properties escape rejected");
        write(file,good); reloaded.load(); reloaded.remove("Player");
        check(reloaded.active("Player").isEmpty()&&reloaded.syncFor("Player").orElseThrow().action().equals("UNBAN"),"Release tombstone");
        BanStore afterRestart=new BanStore(file); afterRestart.load();
        check(afterRestart.syncFor("PLAYER").orElseThrow().action().equals("UNBAN"),"Release must survive restart");
        pass("UNBAN is durable without an online carrier and across restart");
        Properties released=new Properties(); try(var input=Files.newInputStream(file)){released.load(input);}
        check(released.getProperty("!unban.player").split("\\|",-1).length==5,"Old decoder must ignore release, not interpret BAN");
        pass("release metadata cannot be mistaken for a BAN by the old four-field decoder");
        afterRestart.put(replacement); check(afterRestart.syncFor("player").orElseThrow().action().equals("BAN"),"Replacement clears release");
        BanStore afterReban=new BanStore(file); afterReban.load(); check(afterReban.active("Player").orElseThrow().equals(replacement),"Reban persists");
        pass("later BAN replaces release tombstone atomically");
        Properties conflict=new Properties(); conflict.putAll(good); conflict.setProperty("!unban.player","UNBAN|"+good.getProperty("player")); write(file,conflict); failsIO(afterReban::load);
        pass("conflicting BAN and UNBAN records rejected");
        for(BanRecord invalid:List.of(new BanRecord("<click>",123,"Spam","Console"),new BanRecord("Player",-1,"Spam","Console"),
            new BanRecord("Player",123,"\rFORGED LOG","Console"),new BanRecord("Player",123," ","Console"))) {
            try {store.put(invalid);throw new AssertionError("Invalid record accepted");}catch(IllegalArgumentException expected){}
        }
        pass("invalid names, expiry and control-character log injection rejected");
        BanStore memory=new BanStore(dir.resolve("memory.properties"),p->{});
        memory.put(new BanRecord("Expired",System.currentTimeMillis()-10,"Expired","Console"));
        check(memory.active("Expired").isEmpty()&&memory.syncRecords().isEmpty(),"Expired record not enforced or transmitted");
        memory.removeExpired(); pass("expired records absent from enforcement and synchronization");
        AtomicBoolean fail=new AtomicBoolean(); BanStore expiring=new BanStore(dir.resolve("expiring.properties"),p->{if(fail.get())throw new IOException();});
        expiring.put(new BanRecord("Expired",System.currentTimeMillis()-10,"Expired","Console")); fail.set(true); failsIO(expiring::removeExpired);
        fail.set(false); expiring.removeExpired(); check(expiring.syncRecords().isEmpty(),"Cleanup retry");
        pass("failed expiration cleanup remains retryable without inconsistent publication");
        BanStore large=new BanStore(dir.resolve("large.properties"),p->{}); var reader=Executors.newSingleThreadExecutor();
        Future<?> reads=reader.submit(()->{for(int i=0;i<50000;i++) large.active("User"+(i%1000));});
        for(int i=0;i<1000;i++)large.put(ban("User"+i)); reads.get(); reader.shutdown();
        check(large.activeRecords().size()==1000&&large.syncRecords().size()==1000,"Concurrent large snapshot");
        pass("1000 synthetic bans publish immutable snapshots while reads continue");
        try(var files=Files.list(dir)){check(files.noneMatch(p->p.getFileName().toString().endsWith(".tmp")),"Temporary file leak");}
        pass("atomic replacement leaves no temporary files after successful commit");
    }
    static void taskTests() throws Exception {
        BoundedTasks tasks=new BoundedTasks(2,10000); CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicInteger callbacks=new AtomicInteger(); Thread main=Thread.currentThread();
        check(tasks.submit(()->{started.countDown();release.await();return 1;},(value,error)->{check(Thread.currentThread()==main,"Callback on wrong thread");callbacks.incrementAndGet();}),"Accept first"); started.await();
        check(tasks.submit(()->2,(value,error)->callbacks.incrementAndGet()),"Accept second");
        check(!tasks.submit(()->3,(value,error)->{}),"Queue cap"); pass("bounded queue rejects extra work without caller-thread execution");
        release.countDown(); until(()->tasks.pending()==2); Thread.sleep(20);
        check(callbacks.get()==0&&!tasks.submit(()->3,(value,error)->{}),"Completed results retain permits");
        tasks.drain(1); check(callbacks.get()==1,"Drain budget"); tasks.drain(32); check(callbacks.get()==2,"Main-thread delivery");
        pass("callbacks only run when main thread drains, with bounded completion backlog");
        check(tasks.submit(()->3,(value,error)->callbacks.incrementAndGet()),"Capacity recovers");
        until(()->{tasks.drain(32);return callbacks.get()==3;}); tasks.close();
        pass("draining results releases capacity for subsequent commands");
        BoundedTasks timeout=new BoundedTasks(2,100); CountDownLatch running=new CountDownLatch(1),unblock=new CountDownLatch(1);
        AtomicBoolean executed=new AtomicBoolean(); AtomicReference<Exception> failure=new AtomicReference<>();
        timeout.submit(()->{running.countDown();unblock.await();return 1;},(v,e)->{}); check(running.await(5,TimeUnit.SECONDS),"Worker did not start");
        timeout.submit(()->{executed.set(true);return 2;},(v,e)->failure.set(e)); Thread.sleep(150); unblock.countDown();
        until(()->{timeout.drain(32);return failure.get()!=null;});
        check(failure.get() instanceof TimeoutException&&!executed.get(),"Expired queued write executed"); timeout.close();
        pass("expired queued operation fails before executing storage mutation");
        BoundedTasks closed=new BoundedTasks(1,10000); AtomicBoolean delivered=new AtomicBoolean();
        closed.submit(()->1,(v,e)->delivered.set(true)); Thread.sleep(20); closed.close(); closed.drain(32);
        check(!delivered.get()&&!closed.submit(()->2,(v,e)->{}),"Late callback after shutdown");
        pass("shutdown suppresses late callbacks and rejects new work");
        BoundedTasks failureTasks=new BoundedTasks(1,10000); AtomicReference<Exception> expected=new AtomicReference<>();
        failureTasks.submit(()->{throw new IOException();},(v,e)->expected.set(e)); until(()->{failureTasks.drain(32);return expected.get()!=null;}); failureTasks.close();
        check(expected.get() instanceof IOException,"Storage exception not returned"); pass("storage failures delivered as results without success callbacks");
    }
    static void syncTests(Path dir) throws Exception {
        BanStore store=new BanStore(dir.resolve("sync.properties"),p->{}); for(int i=0;i<1000;i++)store.put(ban("User"+i));
        SyncPlanner planner=new SyncPlanner(); AtomicInteger baselines=new AtomicInteger(),sent=new AtomicInteger();
        var baseline=(java.util.function.Supplier<List<BanStore.Sync>>)()->{baselines.incrementAndGet();return store.syncRecords();};
        for(int i=0;i<100;i++)check(planner.tick(true,baseline,store::syncFor,message->{sent.incrementAndGet();return true;},10)<=10,"Tick budget");
        check(sent.get()==1000&&baselines.get()==1&&planner.pending()==0,"Baseline counts");
        pass("1000 bans synchronized once, at most ten messages per tick");
        for(int i=0;i<1000;i++)planner.tick(true,baseline,store::syncFor,message->{sent.incrementAndGet();return true;},10);
        check(sent.get()==1000&&baselines.get()==1,"Join amplification");
        pass("1000 further available-carrier ticks do not resend full ban list");
        planner.changed(store.syncFor("User0").orElseThrow()); store.remove("User0"); planner.changed(store.syncFor("User0").orElseThrow());
        List<String> actions=new ArrayList<>(); planner.tick(true,baseline,store::syncFor,message->{actions.add(message.action());return true;},10);
        check(actions.equals(List.of("UNBAN")),"Stale BAN after UNBAN"); pass("latest committed account state supersedes obsolete pending BAN");
        planner.tick(false,baseline,store::syncFor,message->true,10);
        int sentBefore=sent.get(); planner.tick(true,baseline,store::syncFor,message->{sent.incrementAndGet();return true;},10);
        check(baselines.get()==2&&sent.get()-sentBefore==10,"Recovery baseline"); pass("carrier disappearance triggers one rate-limited recovery baseline");
        planner.tick(true,baseline,store::syncFor,message->false,10); int before=baselines.get();
        planner.tick(true,baseline,store::syncFor,message->true,10); check(baselines.get()==before+1,"Failed transmission recovery");
        pass("failed transmission schedules recovery instead of dropping state");
    }
}
