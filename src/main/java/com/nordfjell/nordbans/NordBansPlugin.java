package com.nordfjell.nordbans;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

public final class NordBansPlugin extends JavaPlugin implements Listener, TabExecutor {
    private final MiniMessage mini = MiniMessage.miniMessage();
    private final SyncPlanner sync = new SyncPlanner();
    private record Carrier(Player player, String name, long readyAt) {}
    private final Map<UUID,Carrier> carriers = new ConcurrentHashMap<>();
    private final AtomicBoolean syncRunning = new AtomicBoolean();
    private Map<String,String> messages = Map.of();
    private BanStore store;
    private BoundedTasks worker;
    private volatile boolean ready;
    private volatile boolean closing;
    private boolean cleanupPending;
    private long maximumDurationMillis;
    private int syncPerTick;

    @Override public void onEnable() {
        try {
            // Register the gate before initialization; failed initialization must not just remove bans.
            getServer().getPluginManager().registerEvents(this,this);
            saveDefaultConfig();
            Map<String,String> templates = new HashMap<>();
            var section = getConfig().getConfigurationSection("messages");
            if (section != null) section.getValues(true).forEach((key,value) -> {
                if (value instanceof String text) templates.put("messages." + key,text);
            });
            messages = Map.copyOf(templates);
            maximumDurationMillis = Duration.ofDays(Math.clamp(getConfig().getLong("maximum-duration-days",365),1,3650)).toMillis();
            int pending = getConfig().getInt("storage.maximum-pending-operations",128);
            long wait = getConfig().getLong("storage.maximum-queue-wait-millis",10000);
            syncPerTick = getConfig().getInt("sync.messages-per-tick",10);
            if (pending < 1 || pending > 4096 || wait < 1 || wait > 300000 || syncPerTick < 1 || syncPerTick > 100)
                throw new IllegalArgumentException("Invalid storage/sync limits");
            store = new BanStore(getDataFolder().toPath().resolve("bans.properties"));
            store.load();
            worker = new BoundedTasks(pending,wait);
            register("tempban"); register("unban");
            getServer().getMessenger().registerOutgoingPluginChannel(this,BanProtocol.CHANNEL);
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(this,ignored -> tick(),20,1);
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(this,ignored -> cleanupExpired(),1200,1200);
            ready = true;
            getLogger().info("NordBans " + getPluginMeta().getVersion() + " enabled with " + store.activeRecords().size() + " active temporary bans.");
        } catch (Exception exception) {
            ready=false;
            getLogger().severe("Ban initialization failed (" + exception.getClass().getSimpleName()
                + "); refusing logins and requesting safe server shutdown. Check configuration/storage.");
            getServer().shutdown();
        }
    }
    @Override public void onDisable() {
        ready=false; closing=true;
        if (worker != null) worker.close();
        sync.unavailable();
        carriers.clear();
        if (!getServer().isStopping()) {
            getLogger().severe("NordBans disabled on a running server; requesting safe shutdown rather than bypassing bans.");
            getServer().shutdown();
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void preLogin(AsyncPlayerPreLoginEvent event) {
        if (!ready) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,Component.text("Ban checks are temporarily unavailable.")); return;
        }
        store.active(event.getName()).ifPresent(record -> event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
            Component.text(BanProtocol.kickMessage(record.expiresAtMillis(),record.reason()))));
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) {
        if (!ready) { event.getPlayer().kick(Component.text("Ban checks are temporarily unavailable.")); return; }
        // Cover a committed ban between the async pre-login check and join.
        store.active(event.getPlayer().getName()).ifPresent(record -> kick(event.getPlayer(),record));
        // Paper's JoinEvent precedes completion of Velocity's backend switch. Messages sent
        // immediately can be dropped before the proxy installs its ServerConnection handler.
        Player player = event.getPlayer();
        carriers.put(player.getUniqueId(),new Carrier(player,player.getName(),System.nanoTime()+1_000_000_000L));
        // No per-join full synchronization; the single tick task handles carrier recovery.
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        carriers.computeIfPresent(player.getUniqueId(),(id,carrier) -> carrier.player() == player ? null : carrier);
    }
    private void tick() {
        if (!ready || closing) return;
        try { worker.drain(32); }
        catch (RuntimeException exception) { getLogger().severe("Ban completion failed: " + exception.getClass().getSimpleName()); }
        long now=System.nanoTime();
        // At most one outstanding entity task: a slow/retiring region cannot grow a queue.
        if (!syncRunning.compareAndSet(false,true)) return;
        Carrier carrier=carriers.values().stream().filter(entry -> now-entry.readyAt() >= 0 && store.active(entry.name()).isEmpty())
            .findFirst().orElse(null);
        if (carrier == null) { sync.unavailable(); syncRunning.set(false); return; }
        Runnable retired = () -> { carriers.values().remove(carrier); sync.unavailable(); syncRunning.set(false); };
        try {
            if (!carrier.player().getScheduler().execute(this,() -> {
                try {
                    if (!ready || closing) return;
                    sync.tick(carrier.player().isOnline() && store.active(carrier.name()).isEmpty(),store::syncRecords,store::syncFor,
                        message -> transmit(carrier.player(),message),syncPerTick);
                } finally { syncRunning.set(false); }
            },retired,1L)) retired.run();
        } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) { retired.run(); }
    }
    private void cleanupExpired() {
        if (!ready || closing || cleanupPending) return;
        cleanupPending=true;
        if (!worker.submit(() -> { store.removeExpired(); return true; },(ok,error) -> {
            cleanupPending=false;
            if (error != null) getLogger().warning("Expired-ban cleanup failed; will retry: " + error.getClass().getSimpleName());
        })) cleanupPending=false;
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name=command.getName().toLowerCase(Locale.ROOT);
        if (!name.equals("tempban") && !name.equals("unban")) return false;
        if (!sender.hasPermission("nordbans."+name)) { sender.sendMessage(Component.text("No permission.")); return true; }
        if (!ready || closing) { send(sender,"messages.storage-error"); return true; }
        return name.equals("tempban") ? tempBan(sender,args) : unban(sender,args);
    }
    private boolean tempBan(CommandSender sender,String[] args) {
        if (args.length < 3) { send(sender,"messages.tempban-usage"); return true; }
        String name=args[0];
        if (!BanStore.validName(name)) { send(sender,"messages.invalid-player"); return true; }
        var duration=DurationParser.parse(args[1]);
        long millis,expires;
        try {
            millis=duration.orElseThrow().toMillis(); expires=Math.addExact(System.currentTimeMillis(),millis);
        } catch (ArithmeticException | NoSuchElementException exception) { send(sender,"messages.invalid-duration"); return true; }
        if (millis > maximumDurationMillis) {
            send(sender,"messages.duration-too-long","<days>",Long.toString(Duration.ofMillis(maximumDurationMillis).toDays())); return true;
        }
        String reason=String.join(" ",Arrays.copyOfRange(args,2,args.length)).trim();
        BanRecord record=new BanRecord(name,expires,reason,sender.getName());
        try { BanStore.validate(record); }
        catch (IllegalArgumentException exception) { send(sender,"messages.reason-too-long"); return true; }
        String durationText=args[1];
        submit(sender,() -> { store.put(record); return record; },(committed,error) -> {
            if (error != null) { storageFailure(sender,error); return; }
            store.syncFor(name).ifPresent(sync::changed);
            // Result is account-based, but must not kick after a later UNBAN or replacement ban.
            if (store.active(name).filter(record::equals).isPresent()) {
                carriers.values().stream().filter(carrier -> carrier.name().equalsIgnoreCase(name))
                    .findFirst().ifPresent(carrier -> onOwner(carrier.player(),() -> {
                        if (carrier.player().isOnline() && store.active(name).filter(record::equals).isPresent())
                            kick(carrier.player(),record);
                    }));
            }
            getLogger().info(record.actor() + " temporarily banned " + name + " for " + durationText + ": " + reason);
            reply(sender,"messages.banned","<player>",name,"<duration>",durationText);
        });
        return true;
    }
    private boolean unban(CommandSender sender,String[] args) {
        if (args.length != 1 || !BanStore.validName(args[0])) { send(sender,"messages.unban-usage"); return true; }
        String name=args[0],actor=sender.getName();
        submit(sender,() -> store.remove(name),(removed,error) -> {
            if (error != null) { storageFailure(sender,error); return; }
            if (removed.isEmpty()) { reply(sender,"messages.not-banned","<player>",name); return; }
            store.syncFor(name).ifPresent(sync::changed);
            getLogger().info(actor + " removed the temporary ban for " + name + ".");
            reply(sender,"messages.unbanned","<player>",name);
        });
        return true;
    }
    private <T> void submit(CommandSender sender,java.util.concurrent.Callable<T> operation,BiConsumer<T,Exception> callback) {
        send(sender,worker.submit(operation,callback) ? "messages.storage-pending" : "messages.storage-busy");
    }
    private void storageFailure(CommandSender sender,Exception error) {
        getLogger().warning("Ban change failed before publication: " + error.getClass().getSimpleName());
        reply(sender,error instanceof TimeoutException ? "messages.storage-busy" : "messages.storage-error");
    }
    private boolean transmit(Player carrier,BanStore.Sync message) {
        if (carrier == null || !carrier.isOnline()) return false;
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try (DataOutputStream output=new DataOutputStream(bytes)) {
                output.writeUTF(message.action()); output.writeUTF(message.record().playerName());
                output.writeLong(message.record().expiresAtMillis()); output.writeUTF(message.record().reason());
            }
            carrier.sendPluginMessage(this,BanProtocol.CHANNEL,bytes.toByteArray()); return true;
        } catch (IOException | RuntimeException exception) {
            getLogger().warning("Ban synchronization deferred: " + exception.getClass().getSimpleName()); return false;
        }
    }
    private void kick(Player player,BanRecord record) {
        player.kick(Component.text(BanProtocol.kickMessage(record.expiresAtMillis(),record.reason())));
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        if (!ready || args.length != 1 || !sender.hasPermission("nordbans."+command.getName().toLowerCase(Locale.ROOT))) return List.of();
        String prefix=args[0].toLowerCase(Locale.ROOT);
        Collection<String> names=command.getName().equalsIgnoreCase("unban") ? store.activeNames()
            : carriers.values().stream().map(Carrier::name).toList();
        return names.stream().filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted(String.CASE_INSENSITIVE_ORDER).limit(50).toList();
    }
    private void register(String name) {
        PluginCommand command=Objects.requireNonNull(getCommand(name),"Missing command " + name);
        command.setExecutor(this); command.setTabCompleter(this);
    }
    private void reply(CommandSender sender,String path,String... replacements) {
        // Feedback to an old disconnected actor must not reach their replacement session.
        onOwner(sender,() -> {
            if (sender instanceof Player player && !player.isOnline()) return;
            send(sender,path,replacements);
        });
    }
    private void onOwner(CommandSender sender,Runnable action) {
        if (!ready || closing) return;
        Runnable guarded = () -> { if (ready && !closing) action.run(); };
        try {
            if (sender instanceof Player player) player.getScheduler().execute(this,guarded,null,1L);
            else Bukkit.getGlobalRegionScheduler().execute(this,guarded);
        } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
            // Shutdown can disable the plugin between the check and scheduling.
        }
    }
    private void send(CommandSender sender,String path,String... replacements) {
        String fallback=switch(path) {
            case "messages.storage-busy" -> "<red>Ban storage is busy; please retry.</red>";
            case "messages.storage-pending" -> "<yellow>Ban update queued; wait for confirmation.</yellow>";
            default -> "<red>Ban storage update failed.</red>";
        };
        String raw=messages.getOrDefault(path,fallback);
        List<TagResolver> tags=new ArrayList<>();
        for (int i=0;i+1<replacements.length;i+=2) {
            String key=replacements[i]; tags.add(Placeholder.unparsed(key.substring(1,key.length()-1),replacements[i+1]));
        }
        sender.sendMessage(mini.deserialize(raw,tags.toArray(TagResolver[]::new)));
    }
}
