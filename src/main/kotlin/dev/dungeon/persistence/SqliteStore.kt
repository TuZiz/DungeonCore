package dev.dungeon.persistence

import dev.dungeon.model.RunSnapshot
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/** Connection belongs exclusively to database executor, including open/close. */
class SqliteStore(private val path: Path) : ProgressStore {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r,"Dungeon-SQLite").apply { isDaemon = false } }
    private var connection: Connection? = null
    private fun db(): Connection {
        connection?.let { return it }
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection("jdbc:sqlite:$path").also { c ->
            try { c.createStatement().use { s ->
                s.execute("PRAGMA journal_mode=WAL"); s.execute("PRAGMA busy_timeout=5000")
                val v = s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
                require(v in 0..1) { "Unsupported database schema $v: $path" }
                s.execute("CREATE TABLE IF NOT EXISTS progress(player TEXT,dungeon TEXT,stage INTEGER,count INTEGER,PRIMARY KEY(player,dungeon))")
                s.execute("CREATE TABLE IF NOT EXISTS rewards(player TEXT,run TEXT,dungeon TEXT,status TEXT,created INTEGER,PRIMARY KEY(player,run))")
                s.execute("CREATE TABLE IF NOT EXISTS completions(player TEXT,run TEXT,dungeon TEXT,difficulty TEXT,elapsed INTEGER,completed INTEGER,PRIMARY KEY(player,run))")
                s.execute("CREATE TABLE IF NOT EXISTS unlocks(player TEXT,campaign TEXT,chapter INTEGER,PRIMARY KEY(player,campaign))")
                s.execute("CREATE TABLE IF NOT EXISTS returns(player TEXT PRIMARY KEY,world TEXT,x REAL,y REAL,z REAL,yaw REAL,pitch REAL)")
                s.execute("PRAGMA user_version=1")
            }; connection = c } catch(e: Exception) { c.close();throw e }
        }
    }
    @Synchronized private fun <T> submit(block: (Connection) -> T): CompletableFuture<T> {
        if(closing != null) return CompletableFuture.failedFuture(IllegalStateException("Database closing: $path"))
        return CompletableFuture.supplyAsync({ block(db()) },executor)
    }
    fun initialize() = submit { Unit }
    override fun save(player: UUID, dungeon: String, snapshot: RunSnapshot) = submit { c ->
        c.prepareStatement("INSERT INTO progress VALUES(?,?,?,?) ON CONFLICT(player,dungeon) DO UPDATE SET stage=excluded.stage,count=excluded.count").use {
            it.setString(1,player.toString());it.setString(2,dungeon);it.setInt(3,snapshot.stage);it.setInt(4,snapshot.count);it.executeUpdate()
        }; Unit
    }
    override fun progress(player: UUID, dungeon: String) = submit { c ->
        c.prepareStatement("SELECT stage,count FROM progress WHERE player=? AND dungeon=?").use {
            it.setString(1,player.toString());it.setString(2,dungeon)
            it.executeQuery().use { rows -> if(rows.next()) rows.getInt(1) to rows.getInt(2) else null }
        }
    }
    override fun reserveReward(player: UUID, dungeon: String, run: UUID) = submit { c ->
        c.prepareStatement("INSERT OR IGNORE INTO rewards VALUES(?,?,?,'RESERVED',?)").use {
            it.setString(1,player.toString());it.setString(2,run.toString());it.setString(3,dungeon);it.setLong(4,System.currentTimeMillis());it.executeUpdate() == 1
        }
    }
    override fun delivered(player: UUID, run: UUID) = submit { c ->
        c.prepareStatement("UPDATE rewards SET status='DELIVERED' WHERE player=? AND run=?").use {
            it.setString(1,player.toString());it.setString(2,run.toString());it.executeUpdate()
        }; Unit
    }
    override fun unlocked(player: UUID, campaign: String) = submit { c ->
        c.prepareStatement("SELECT chapter FROM unlocks WHERE player=? AND campaign=?").use {
            it.setString(1,player.toString());it.setString(2,campaign)
            it.executeQuery().use { r -> if(r.next()) r.getInt(1) else 1 }
        }
    }
    /** Record, next chapter, and reward reservation commit together. Null means already reserved. */
    override fun complete(player: UUID, run: UUID, dungeon: String, difficulty: String, elapsed: Long, campaign: String, chapter: Int) = submit { c ->
        c.autoCommit = false
        try {
            val first = c.prepareStatement("SELECT 1 FROM completions WHERE player=? AND dungeon=? LIMIT 1").use {
                it.setString(1,player.toString());it.setString(2,dungeon);it.executeQuery().use { r -> !r.next() }
            }
            val inserted = c.prepareStatement("INSERT OR IGNORE INTO rewards VALUES(?,?,?,'RESERVED',?)").use {
                it.setString(1,player.toString());it.setString(2,run.toString());it.setString(3,dungeon);it.setLong(4,System.currentTimeMillis());it.executeUpdate() == 1
            }
            if(inserted) {
                c.prepareStatement("INSERT INTO completions VALUES(?,?,?,?,?,?)").use {
                    it.setString(1,player.toString());it.setString(2,run.toString());it.setString(3,dungeon)
                    it.setString(4,difficulty);it.setLong(5,elapsed);it.setLong(6,System.currentTimeMillis());it.executeUpdate()
                }
                c.prepareStatement("INSERT INTO unlocks VALUES(?,?,?) ON CONFLICT(player,campaign) DO UPDATE SET chapter=MAX(chapter,excluded.chapter)").use {
                    it.setString(1,player.toString());it.setString(2,campaign);it.setInt(3,chapter+1);it.executeUpdate()
                }
            }
            c.commit();if(inserted) first else null
        } catch(e: Exception) { c.rollback();throw e } finally { c.autoCommit = true }
    }
    override fun rememberReturn(id: UUID, point: ReturnPoint) = submit { c ->
        c.prepareStatement("INSERT OR REPLACE INTO returns VALUES(?,?,?,?,?,?,?)").use {
            it.setString(1,id.toString());it.setString(2,point.world);it.setDouble(3,point.x);it.setDouble(4,point.y);it.setDouble(5,point.z)
            it.setFloat(6,point.yaw);it.setFloat(7,point.pitch);it.executeUpdate()
        }; Unit
    }
    override fun returnPoint(id: UUID) = submit { c ->
        c.prepareStatement("SELECT world,x,y,z,yaw,pitch FROM returns WHERE player=?").use {
            it.setString(1,id.toString());it.executeQuery().use { r ->
                if(r.next()) ReturnPoint(r.getString(1),r.getDouble(2),r.getDouble(3),r.getDouble(4),r.getFloat(5),r.getFloat(6)) else null
            }
        }
    }
    override fun forgetReturn(id: UUID) = submit { c ->
        c.prepareStatement("DELETE FROM returns WHERE player=?").use { it.setString(1,id.toString());it.executeUpdate() };Unit
    }
    private var closing: CompletableFuture<Unit>? = null
    @Synchronized fun closeAsync(): CompletableFuture<Unit> {
        closing?.let { return it }
        val future = CompletableFuture<Unit>()
        closing = future
        executor.execute {
            try { connection?.close();connection = null;future.complete(Unit) }
            catch(e: Exception) { future.completeExceptionally(e) }
        }
        executor.shutdown()
        return future
    }
    override fun close() { closeAsync() }
}
