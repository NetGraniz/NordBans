package com.nordfjell.nordbans;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Indexed SQLite persistence with an immutable in-memory read snapshot. */
final class BanStore {
    private static final int MAX_RECORDS=50_000;
    private static final class State {
        final Map<String,BanRecord> bans;
        State(Map<String,BanRecord> bans){this.bans=Map.copyOf(bans);}
    }
    private final Path database; private volatile State state=new State(Map.of());
    BanStore(Path database){this.database=database.toAbsolutePath().normalize();}

    synchronized void load()throws IOException{
        try(Connection c=open()){initialize(c);state=new State(readAll(c));}
        catch(SQLException|RuntimeException e){throw new IOException("Invalid or unreadable ban database",e);}
    }
    Optional<BanRecord> active(String name){BanRecord r=state.bans.get(normalize(name));return r!=null&&r.active(System.currentTimeMillis())?Optional.of(r):Optional.empty();}
    synchronized void put(BanRecord r)throws IOException{
        validate(r);String key=normalize(r.playerName());
        try(Connection c=open();PreparedStatement s=c.prepareStatement("INSERT INTO bans(normalized_name,player_name,expires_at,reason,actor) VALUES (?,?,?,?,?) ON CONFLICT(normalized_name) DO UPDATE SET player_name=excluded.player_name,expires_at=excluded.expires_at,reason=excluded.reason,actor=excluded.actor")){
            s.setString(1,key);s.setString(2,r.playerName());s.setLong(3,r.expiresAtMillis());s.setString(4,r.reason());s.setString(5,r.actor());s.executeUpdate();
            Map<String,BanRecord> next=new HashMap<>(state.bans);next.put(key,r);state=new State(next);
        }catch(SQLException e){throw new IOException("Could not persist ban",e);}
    }
    synchronized Optional<BanRecord> remove(String name)throws IOException{
        String key=normalize(name);BanRecord old=state.bans.get(key);if(old==null)return Optional.empty();
        try(Connection c=open();PreparedStatement s=c.prepareStatement("DELETE FROM bans WHERE normalized_name=?")){
            s.setString(1,key);s.executeUpdate();Map<String,BanRecord> next=new HashMap<>(state.bans);next.remove(key);state=new State(next);return Optional.of(old);
        }catch(SQLException e){throw new IOException("Could not remove ban",e);}
    }
    synchronized void removeExpired()throws IOException{
        long now=System.currentTimeMillis();if(state.bans.values().stream().noneMatch(r->!r.active(now)))return;
        try(Connection c=open();PreparedStatement s=c.prepareStatement("DELETE FROM bans WHERE expires_at<=?")){
            s.setLong(1,now);s.executeUpdate();Map<String,BanRecord> next=new HashMap<>(state.bans);next.values().removeIf(r->!r.active(now));state=new State(next);
        }catch(SQLException e){throw new IOException("Could not remove expired bans",e);}
    }
    Collection<BanRecord> activeRecords(){long now=System.currentTimeMillis();return state.bans.values().stream().filter(r->r.active(now)).toList();}
    Collection<String> activeNames(){return activeRecords().stream().map(BanRecord::playerName).toList();}

    private Connection open()throws SQLException,IOException{
        Files.createDirectories(database.getParent());Connection c=DriverManager.getConnection("jdbc:sqlite:"+database);
        try(Statement s=c.createStatement()){s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA synchronous=NORMAL");s.execute("PRAGMA busy_timeout=5000");}return c;
    }
    private static void initialize(Connection c)throws SQLException{try(Statement s=c.createStatement()){
        s.executeUpdate("CREATE TABLE IF NOT EXISTS bans (normalized_name TEXT PRIMARY KEY, player_name TEXT NOT NULL, expires_at INTEGER NOT NULL, reason TEXT NOT NULL, actor TEXT NOT NULL)");
        s.executeUpdate("CREATE INDEX IF NOT EXISTS bans_expiry ON bans(expires_at)");
    }}
    private Map<String,BanRecord> readAll(Connection c)throws SQLException,IOException{
        Map<String,BanRecord> loaded=new HashMap<>();try(Statement s=c.createStatement();ResultSet rows=s.executeQuery("SELECT normalized_name,player_name,expires_at,reason,actor FROM bans")){
            while(rows.next()){BanRecord r=new BanRecord(rows.getString(2),rows.getLong(3),rows.getString(4),rows.getString(5));validate(r);String key=rows.getString(1);
                if(!normalize(r.playerName()).equals(key)||loaded.putIfAbsent(key,r)!=null)throw new IOException("Invalid normalized ban identity");
                if(loaded.size()>MAX_RECORDS)throw new IOException("Too many ban records");}
        }return loaded;
    }
    static void validate(BanRecord r){if(r==null||!validName(r.playerName())||r.expiresAtMillis()<=0||r.reason()==null||r.reason().isBlank()||r.reason().length()>256||r.reason().chars().anyMatch(Character::isISOControl)||r.actor()==null||r.actor().isBlank()||r.actor().length()>128||r.actor().chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid ban fields");}
    static boolean validName(String name){return name!=null&&name.length()<=32&&name.matches("[A-Za-z0-9_.-]+");}
    static String normalize(String name){return name.toLowerCase(Locale.ROOT);}
}
