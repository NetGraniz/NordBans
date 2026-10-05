package com.nordfjell.nordbansprobe;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;

/** LOCAL TEST ONLY: fault injection and state inspection, console-only. */
public final class BanTestProbe extends JavaPlugin {
    private Object bans,store,worker,originalPersister; private Field persister;
    private long ticks;
    @Override public void onEnable() {
        bans=Bukkit.getPluginManager().getPlugin("NordBans");
        // Default-false production permissions stay unchanged. This isolated test console
        // receives only the two nodes needed to exercise the normal command dispatcher.
        var permissions=Bukkit.getConsoleSender().addAttachment(this);
        permissions.setPermission("nordbans.tempban",true);
        permissions.setPermission("nordbans.unban",true);
        try {
            store=field(bans,"store"); worker=field(bans,"worker");
            persister=store.getClass().getDeclaredField("persister"); persister.setAccessible(true); originalPersister=persister.get(store);
        } catch(Exception e){throw new IllegalStateException(e);}
        Bukkit.getScheduler().runTaskTimer(this,()->ticks++,1,1);
        getCommand("nbtest").setExecutor((sender,command,label,args)->{
            if(sender instanceof org.bukkit.entity.Player || args.length==0)return true;
            try {
                switch(args[0]) {
                    case "state" -> {
                        Collection<?> active=(Collection<?>)call(store,"activeNames");
                        Object pending=call(worker,"pending");
                        var syncs=(Collection<?>)call(store,"syncRecords"); List<String> releases=new ArrayList<>();
                        for(Object sync:syncs) if(call(sync,"action").equals("UNBAN")) releases.add((String)call(call(sync,"record"),"playerName"));
                        // Names are fixture-only; no real player/database data is read.
                        getLogger().info("NBSTATE "+args[1]+" {\"active\":"+json(active)+",\"releases\":"+json(releases)+",\"pending\":"+pending+",\"ticks\":"+ticks+"}");
                    }
                    case "fault" -> {
                        boolean fail=Boolean.parseBoolean(args[1]);
                        if(fail) {
                            Class<?> type=persister.getType();
                            Object replacement=Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{throw new java.io.IOException("Local fixture write failure");});
                            persister.set(store,replacement);
                        } else persister.set(store,originalPersister);
                        getLogger().info("NBFAULT "+fail);
                    }
                    case "hold" -> submit(()->{Thread.sleep(Long.parseLong(args[1]));return true;});
                    case "expire" -> {
                        Class<?> type=Class.forName("com.nordfjell.nordbans.BanRecord",true,bans.getClass().getClassLoader());
                        var ctor=type.getDeclaredConstructor(String.class,long.class,String.class,String.class);ctor.setAccessible(true);
                        Object record=ctor.newInstance(args[1],System.currentTimeMillis()+Long.parseLong(args[2])*1000,"Local expiry","Console");
                        var put=store.getClass().getDeclaredMethod("put",type);put.setAccessible(true);
                        submit(()->{put.invoke(store,record);return true;});
                    }
                    case "disable" -> Bukkit.getPluginManager().disablePlugin((org.bukkit.plugin.Plugin)bans);
                    default -> { }
                }
            } catch(Exception e){throw new IllegalStateException(e);}
            return true;
        });
        getLogger().info("LOCAL_NORDBANS_PROBE_READY");
    }
    private void submit(Callable<?> task)throws Exception {
        var method=worker.getClass().getDeclaredMethod("submit",Callable.class,BiConsumer.class);method.setAccessible(true);
        if(!(boolean)method.invoke(worker,task,(BiConsumer<Object,Exception>)(v,e)->{if(e!=null)throw new IllegalStateException(e);}))throw new IllegalStateException("Probe task rejected");
    }
    private static Object field(Object object,String name)throws Exception{var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static Object call(Object object,String name)throws Exception{var m=object.getClass().getDeclaredMethod(name);m.setAccessible(true);return m.invoke(object);}
    private static String json(Collection<?> values){return values.stream().map(v->"\""+v+"\"").collect(java.util.stream.Collectors.joining(",","[","]"));}
}
