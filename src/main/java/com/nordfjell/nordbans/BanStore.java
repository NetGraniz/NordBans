package com.nordfjell.nordbans;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Indexed SQLite persistence with an immutable in-memory read snapshot. */
final class BanStore {
    private static final String MIGRATION_KEY="legacy_bans_properties_v1", UNBAN="!unban.";
    private static final long MAX_LEGACY_BYTES=16L*1024L*1024L;
    private static final int MAX_RECORDS=50_000;
    private static final class State {
        final Map<String,BanRecord> bans;
        State(Map<String,BanRecord> bans){this.bans=Map.copyOf(bans);}
    }
    private final Path database,legacy; private volatile State state=new State(Map.of());
    BanStore(Path database){this(database,database.resolveSibling("bans.properties"));}
    BanStore(Path database,Path legacy){this.database=database.toAbsolutePath().normalize();this.legacy=legacy==null?null:legacy.toAbsolutePath().normalize();}

    synchronized void load()throws IOException{
        try(Connection c=open()){initialize(c);migrate(c);state=new State(readAll(c));}
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
        s.executeUpdate("CREATE INDEX IF NOT EXISTS bans_expiry ON bans(expires_at)");s.executeUpdate("CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY,value TEXT NOT NULL)");
    }}
    private Map<String,BanRecord> readAll(Connection c)throws SQLException,IOException{
        Map<String,BanRecord> loaded=new HashMap<>();try(Statement s=c.createStatement();ResultSet rows=s.executeQuery("SELECT normalized_name,player_name,expires_at,reason,actor FROM bans")){
            while(rows.next()){BanRecord r=new BanRecord(rows.getString(2),rows.getLong(3),rows.getString(4),rows.getString(5));validate(r);String key=rows.getString(1);
                if(!normalize(r.playerName()).equals(key)||loaded.putIfAbsent(key,r)!=null)throw new IOException("Invalid normalized ban identity");
                if(loaded.size()>MAX_RECORDS)throw new IOException("Too many ban records");}
        }return loaded;
    }
    private void migrate(Connection c)throws SQLException,IOException{
        try(PreparedStatement check=c.prepareStatement("SELECT 1 FROM metadata WHERE key=?")){check.setString(1,MIGRATION_KEY);try(ResultSet r=check.executeQuery()){if(r.next())return;}}
        Map<String,BanRecord> records=readLegacy();c.setAutoCommit(false);
        try(PreparedStatement insert=c.prepareStatement("INSERT OR IGNORE INTO bans(normalized_name,player_name,expires_at,reason,actor) VALUES (?,?,?,?,?)");PreparedStatement marker=c.prepareStatement("INSERT INTO metadata(key,value) VALUES (?,?)")){
            for(var e:records.entrySet()){BanRecord r=e.getValue();insert.setString(1,e.getKey());insert.setString(2,r.playerName());insert.setLong(3,r.expiresAtMillis());insert.setString(4,r.reason());insert.setString(5,r.actor());insert.addBatch();}
            insert.executeBatch();marker.setString(1,MIGRATION_KEY);marker.setString(2,"complete");marker.executeUpdate();c.commit();
        }catch(SQLException|RuntimeException e){c.rollback();throw e;}finally{c.setAutoCommit(true);}
    }
    private Map<String,BanRecord> readLegacy()throws IOException{
        if(legacy==null||!Files.exists(legacy))return Map.of();if(Files.size(legacy)>MAX_LEGACY_BYTES)throw new IOException("Legacy ban file exceeds safe size limit");
        Properties p=new Properties(){@Override public synchronized Object put(Object k,Object v){if(containsKey(k))throw new IllegalArgumentException("Duplicate legacy ban property");return super.put(k,v);}};
        try(InputStream in=Files.newInputStream(legacy)){p.load(in);}catch(IllegalArgumentException e){throw new IOException("Invalid legacy ban properties",e);}
        if(p.size()>MAX_RECORDS)throw new IOException("Too many legacy ban records");Map<String,BanRecord> result=new HashMap<>();long now=System.currentTimeMillis();
        for(String property:p.stringPropertyNames()){if(property.startsWith(UNBAN))continue;BanRecord r=decode(p.getProperty(property));String key=normalize(r.playerName());
            if(!normalize(property).equals(key)||result.putIfAbsent(key,r)!=null)throw new IOException("Invalid legacy ban identity");if(!r.active(now))result.remove(key);}
        return result;
    }
    private static BanRecord decode(String value)throws IOException{try{String[] p=value.split("\\|",-1);if(p.length!=4)throw new IllegalArgumentException();BanRecord r=new BanRecord(untext(p[1]),Long.parseLong(p[0]),untext(p[2]),untext(p[3]));validate(r);return r;}catch(RuntimeException|CharacterCodingException e){throw new IOException("Invalid legacy ban record",e);}}
    private static String untext(String value)throws CharacterCodingException{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(Base64.getUrlDecoder().decode(value))).toString();}
    static void validate(BanRecord r){if(r==null||!validName(r.playerName())||r.expiresAtMillis()<=0||r.reason()==null||r.reason().isBlank()||r.reason().length()>256||r.reason().chars().anyMatch(Character::isISOControl)||r.actor()==null||r.actor().isBlank()||r.actor().length()>128||r.actor().chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid ban fields");}
    static boolean validName(String name){return name!=null&&name.length()<=32&&name.matches("[A-Za-z0-9_.-]+");}
    static String normalize(String name){return name.toLowerCase(Locale.ROOT);}
}
