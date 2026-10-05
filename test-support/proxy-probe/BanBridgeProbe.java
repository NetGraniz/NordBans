package com.nordfjell.nordbansprobe;

import com.google.inject.Inject;
import com.google.gson.Gson;
import com.nordfjell.nordqueue.NordQueuePlugin;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.*;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.*;
import org.slf4j.Logger;
import java.util.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;

/** LOCAL TEST ONLY: count main-backend BAN/UNBAN messages and inspect synthetic queue states. */
public final class BanBridgeProbe {
    private final ProxyServer proxy;private final Logger logger; private NordQueuePlugin queue;
    private final AtomicInteger bans=new AtomicInteger(),unbans=new AtomicInteger();
    private final Gson gson=new Gson();
    @Inject public BanBridgeProbe(ProxyServer proxy,Logger logger){this.proxy=proxy;this.logger=logger;}
    @Subscribe public void initialize(ProxyInitializeEvent event){
        queue=(NordQueuePlugin)proxy.getPluginManager().getPlugin("nordqueue").orElseThrow().getInstance().orElseThrow();
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("banbridge").plugin(this).build(),(SimpleCommand)invocation->{
            if(invocation.source() instanceof Player || invocation.arguments().length<2)return;
            if(!invocation.arguments()[0].equals("state"))return;
            try{
                var field=queue.getClass().getDeclaredField("suspendedBanStore");field.setAccessible(true);Object store=field.get(queue);
                var recordsField=store.getClass().getDeclaredField("records");recordsField.setAccessible(true);
                var records=(Map<?,?>)recordsField.get(store);List<String> active=new ArrayList<>();
                for(Object ban:records.values()){
                    var isActive=ban.getClass().getDeclaredMethod("active",long.class);isActive.setAccessible(true);
                    if((boolean)isActive.invoke(ban,System.currentTimeMillis())){
                        var name=ban.getClass().getDeclaredMethod("playerName");name.setAccessible(true);active.add((String)name.invoke(ban));
                    }
                }
                active.sort(String.CASE_INSENSITIVE_ORDER);
                var snapshot=queue.snapshot();
                var main=proxy.getServer("main").orElseThrow().getPlayersConnected().stream().map(Player::getUsername).sorted().toList();
                var limbo=proxy.getServer("queue").orElseThrow().getPlayersConnected().stream().map(Player::getUsername).sorted().toList();
                var waiting=snapshot.orderedIds().stream().map(id->proxy.getPlayer(id).orElseThrow().getUsername()).toList();
                logger.info("BRSTATE {} {}",invocation.arguments()[1],gson.toJson(Map.of("main",main,"limbo",limbo,"waiting",waiting,
                    "banned",active,"banMessages",bans.get(),"unbanMessages",unbans.get())));
            }catch(Exception e){throw new IllegalStateException(e);}
        });
        logger.info("LOCAL_BAN_BRIDGE_READY");
    }
    @Subscribe(order=PostOrder.FIRST) public void message(PluginMessageEvent event){
        if(!event.getIdentifier().getId().equals("nordfjell:bans") || !(event.getSource() instanceof ServerConnection s)
            || !s.getServerInfo().getName().equals("main"))return;
        try(var input=new DataInputStream(new ByteArrayInputStream(event.getData()))){
            String action=input.readUTF();if(action.equals("BAN"))bans.incrementAndGet();else if(action.equals("UNBAN"))unbans.incrementAndGet();
        }catch(IOException e){throw new IllegalStateException(e);}
    }
}
