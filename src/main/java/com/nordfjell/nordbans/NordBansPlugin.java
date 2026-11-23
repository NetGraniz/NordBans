package com.nordfjell.nordbans;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

public final class NordBansPlugin extends JavaPlugin implements Listener,TabExecutor {
    private final MiniMessage mini=MiniMessage.miniMessage();
    private volatile Map<String,String> messages=Map.of();private BanStore store;private BoundedTasks worker;
    private volatile boolean ready,closing,reloadPending;private boolean cleanupPending;private volatile long maximumDurationMillis;
    private record RuntimeSettings(Map<String,String> messages,long maximumDurationMillis,int maximumPending,long maximumWaitMillis){}

    @Override public void onEnable(){
        try{
            getServer().getPluginManager().registerEvents(this,this);saveDefaultConfig();
            RuntimeSettings settings=loadSettings(new File(getDataFolder(),"config.yml"));apply(settings);
            store=new BanStore(getDataFolder().toPath().resolve("bans.db"));store.load();worker=new BoundedTasks(settings.maximumPending(),settings.maximumWaitMillis());
            register("tempban");register("unban");register("nordbans");
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(this,ignored->tick(),20,1);
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(this,ignored->cleanupExpired(),1200,1200);
            ready=true;getLogger().info("NordBans "+getPluginMeta().getVersion()+" enabled with "+store.activeRecords().size()+" active temporary bans in SQLite.");
        }catch(Exception e){ready=false;getLogger().severe("Ban initialization failed ("+e.getClass().getSimpleName()+"); refusing logins and requesting safe server shutdown.");getServer().shutdown();}
    }
    @Override public void onDisable(){ready=false;closing=true;if(worker!=null)worker.close();if(!getServer().isStopping()){getLogger().severe("NordBans disabled on a running server; requesting safe shutdown.");getServer().shutdown();}}
    @EventHandler(priority=EventPriority.HIGHEST)public void preLogin(AsyncPlayerPreLoginEvent event){
        if(!ready){event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,Component.text("Ban checks are temporarily unavailable."));return;}
        store.active(event.getName()).ifPresent(r->event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,banMessage(r)));
    }
    @EventHandler(priority=EventPriority.MONITOR)public void join(PlayerJoinEvent event){if(!ready){event.getPlayer().kick(Component.text("Ban checks are temporarily unavailable."));return;}store.active(event.getPlayer().getName()).ifPresent(r->kick(event.getPlayer(),r));}
    private void tick(){if(!ready||closing)return;try{worker.drain(32);}catch(RuntimeException e){getLogger().severe("Ban completion failed: "+e.getClass().getSimpleName());}}
    private void cleanupExpired(){if(!ready||closing||cleanupPending)return;cleanupPending=true;if(!worker.submit(()->{store.removeExpired();return true;},(ok,error)->{cleanupPending=false;if(error!=null)getLogger().warning("Expired-ban cleanup failed; will retry: "+error.getClass().getSimpleName());}))cleanupPending=false;}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){String name=command.getName().toLowerCase(Locale.ROOT);if(name.equals("nordbans"))return admin(sender,args);if(!name.equals("tempban")&&!name.equals("unban"))return false;if(!sender.hasPermission("nordbans."+name)){sender.sendMessage(Component.text("No permission."));return true;}if(!ready||closing){send(sender,"messages.storage-error");return true;}return name.equals("tempban")?tempBan(sender,args):unban(sender,args);}
    private boolean admin(CommandSender sender,String[] args){
        if(!sender.hasPermission("nordbans.admin")){sender.sendMessage(Component.text("No permission."));return true;}
        if(args.length!=1||!args[0].equalsIgnoreCase("reload")){sender.sendMessage(Component.text("Usage: /nordbans reload"));return true;}
        if(!ready||closing||reloadPending){sender.sendMessage(Component.text(reloadPending?"NordBans reload is already pending.":"NordBans is unavailable."));return true;}
        reloadPending=true;File file=new File(getDataFolder(),"config.yml");
        Bukkit.getAsyncScheduler().runNow(this,ignored->{RuntimeSettings next=null;Exception problem=null;try{next=loadSettings(file);}catch(Exception e){problem=e;}RuntimeSettings result=next;Exception error=problem;
            Bukkit.getGlobalRegionScheduler().execute(this,()->{reloadPending=false;if(!ready||closing)return;if(error!=null){getLogger().warning("NordBans reload rejected; previous settings retained: "+error.getClass().getSimpleName());reply(sender,"messages.reload-failed");return;}apply(result);reply(sender,"messages.reload-success");});});
        sender.sendMessage(Component.text("NordBans reload queued."));return true;
    }
    private RuntimeSettings loadSettings(File file){
        YamlConfiguration yaml=YamlConfiguration.loadConfiguration(file);Map<String,String> templates=new HashMap<>();var section=yaml.getConfigurationSection("messages");
        if(section==null)throw new IllegalArgumentException("Missing messages section");section.getValues(true).forEach((key,value)->{if(value instanceof String text)templates.put("messages."+key,text);});
        long days=yaml.getLong("maximum-duration-days",365);int pending=yaml.getInt("storage.maximum-pending-operations",128);long wait=yaml.getLong("storage.maximum-queue-wait-millis",10000);
        if(days<1||days>3650||pending<1||pending>4096||wait<1||wait>300000)throw new IllegalArgumentException("Invalid NordBans settings");
        for(String value:templates.values()){if(value.isBlank())throw new IllegalArgumentException("Blank NordBans message");mini.deserialize(value);}
        return new RuntimeSettings(Map.copyOf(templates),Duration.ofDays(days).toMillis(),pending,wait);
    }
    private void apply(RuntimeSettings settings){messages=settings.messages();maximumDurationMillis=settings.maximumDurationMillis();}
    private boolean tempBan(CommandSender sender,String[] args){
        if(args.length<3){send(sender,"messages.tempban-usage");return true;}String name=args[0];if(!BanStore.validName(name)){send(sender,"messages.invalid-player");return true;}
        var duration=DurationParser.parse(args[1]);long millis,expires;try{millis=duration.orElseThrow().toMillis();expires=Math.addExact(System.currentTimeMillis(),millis);}catch(ArithmeticException|NoSuchElementException e){send(sender,"messages.invalid-duration");return true;}
        if(millis>maximumDurationMillis){send(sender,"messages.duration-too-long","<days>",Long.toString(Duration.ofMillis(maximumDurationMillis).toDays()));return true;}
        String reason=String.join(" ",Arrays.copyOfRange(args,2,args.length)).trim();BanRecord record=new BanRecord(name,expires,reason,sender.getName());
        try{BanStore.validate(record);}catch(IllegalArgumentException e){send(sender,"messages.reason-too-long");return true;}
        String durationText=args[1];submit(sender,()->{store.put(record);return record;},(committed,error)->{
            if(error!=null){storageFailure(sender,error);return;}Player online=Bukkit.getPlayerExact(name);if(online!=null)onOwner(online,()->{if(online.isOnline()&&store.active(name).filter(record::equals).isPresent())kick(online,record);});
            getLogger().info(record.actor()+" temporarily banned "+name+" for "+durationText+": "+reason);reply(sender,"messages.banned","<player>",name,"<duration>",durationText);
        });return true;
    }
    private boolean unban(CommandSender sender,String[] args){if(args.length!=1||!BanStore.validName(args[0])){send(sender,"messages.unban-usage");return true;}String name=args[0],actor=sender.getName();submit(sender,()->store.remove(name),(removed,error)->{if(error!=null){storageFailure(sender,error);return;}if(removed.isEmpty()){reply(sender,"messages.not-banned","<player>",name);return;}getLogger().info(actor+" removed the temporary ban for "+name+".");reply(sender,"messages.unbanned","<player>",name);});return true;}
    private <T>void submit(CommandSender sender,java.util.concurrent.Callable<T> operation,BiConsumer<T,Exception> callback){send(sender,worker.submit(operation,callback)?"messages.storage-pending":"messages.storage-busy");}
    private void storageFailure(CommandSender sender,Exception error){getLogger().warning("Ban change failed before publication: "+error.getClass().getSimpleName());reply(sender,error instanceof TimeoutException?"messages.storage-busy":"messages.storage-error");}
    private Component banMessage(BanRecord r){long minutes=Math.max(1,(r.expiresAtMillis()-System.currentTimeMillis()+59_999)/60_000);return Component.text("Temporarily banned for "+minutes+" minute(s): "+r.reason());}
    private void kick(Player player,BanRecord record){player.kick(banMessage(record));}
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args){if(command.getName().equalsIgnoreCase("nordbans"))return args.length==1&&sender.hasPermission("nordbans.admin")&&"reload".startsWith(args[0].toLowerCase(Locale.ROOT))?List.of("reload"):List.of();if(!ready||args.length!=1||!sender.hasPermission("nordbans."+command.getName().toLowerCase(Locale.ROOT)))return List.of();String prefix=args[0].toLowerCase(Locale.ROOT);Collection<String> names=command.getName().equalsIgnoreCase("unban")?store.activeNames():Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();return names.stream().filter(n->n.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted(String.CASE_INSENSITIVE_ORDER).limit(50).toList();}
    private void register(String name){PluginCommand command=Objects.requireNonNull(getCommand(name));command.setExecutor(this);command.setTabCompleter(this);}
    private void reply(CommandSender sender,String path,String... replacements){onOwner(sender,()->{if(sender instanceof Player p&&!p.isOnline())return;send(sender,path,replacements);});}
    private void onOwner(CommandSender sender,Runnable action){if(!ready||closing)return;Runnable guarded=()->{if(ready&&!closing)action.run();};try{if(sender instanceof Player p)p.getScheduler().execute(this,guarded,null,1);else Bukkit.getGlobalRegionScheduler().execute(this,guarded);}catch(org.bukkit.plugin.IllegalPluginAccessException ignored){}}
    private void send(CommandSender sender,String path,String... replacements){String fallback=switch(path){case"messages.storage-busy"->"<red>Ban storage is busy; please retry.</red>";case"messages.storage-pending"->"<yellow>Ban update queued; wait for confirmation.</yellow>";case"messages.reload-success"->"<green>NordBans configuration reloaded.</green>";case"messages.reload-failed"->"<red>Reload rejected; previous settings retained.</red>";default->"<red>Ban storage update failed.</red>";};String raw=messages.getOrDefault(path,fallback);List<TagResolver> tags=new ArrayList<>();for(int i=0;i+1<replacements.length;i+=2){String key=replacements[i];tags.add(Placeholder.unparsed(key.substring(1,key.length()-1),replacements[i+1]));}sender.sendMessage(mini.deserialize(raw,tags.toArray(TagResolver[]::new)));}
}
