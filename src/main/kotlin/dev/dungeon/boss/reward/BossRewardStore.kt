package dev.dungeon.boss.reward

import dev.dungeon.boss.BossRewardsSettings
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom

data class BossGrant(val receipt: String,val item: String,val state: String)
data class BossRoll(val fresh: Boolean,val material: Boolean,val generator: Boolean)
data class BossSpoilsPlan(val material: String,val sword: String?,val group: String?)

/** Dedicated SQLite file; every transaction, including migration/close, belongs to this executor. */
class BossRewardStore(private val path: Path,private val random: ()->Double={ ThreadLocalRandom.current().nextDouble() }) {
    private val executor=Executors.newSingleThreadExecutor { r -> Thread(r,"Dungeon-Boss-SQLite").apply { isDaemon=false } }
    private var connection: Connection?=null
    private var closing: CompletableFuture<Unit>?=null
    private fun db(): Connection {
        connection?.let { return it }
        Class.forName("org.sqlite.JDBC")
        val c=DriverManager.getConnection("jdbc:sqlite:$path")
        try {
            c.createStatement().use { s ->
                s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA busy_timeout=5000");s.execute("PRAGMA synchronous=FULL")
                val version=s.executeQuery("PRAGMA user_version").use { it.next();it.getInt(1) }
                require(version in 0..2) { "$path: unsupported boss reward schema $version" }
                s.execute("CREATE TABLE IF NOT EXISTS kills(battle TEXT,player TEXT,day TEXT,material INTEGER,generator INTEGER,PRIMARY KEY(battle,player))")
                s.execute("CREATE TABLE IF NOT EXISTS daily(player TEXT,day TEXT,attempts INTEGER,wins INTEGER,PRIMARY KEY(player,day))")
                s.execute("CREATE TABLE IF NOT EXISTS grants(receipt TEXT PRIMARY KEY,battle TEXT,player TEXT,item TEXT,state TEXT)")
                s.execute("CREATE INDEX IF NOT EXISTS grants_player ON grants(player,state)")
                s.execute("CREATE TABLE IF NOT EXISTS schedule(id TEXT PRIMARY KEY,next_at INTEGER)")
                // Ambiguous physical deliveries are held for review, never automatically duplicated.
                s.execute("UPDATE grants SET state='REVIEW' WHERE state='DELIVERING'")
                s.execute("CREATE TABLE IF NOT EXISTS opened_spoils(receipt TEXT PRIMARY KEY)")
                s.execute("CREATE TABLE IF NOT EXISTS spoils_plans(receipt TEXT PRIMARY KEY,material TEXT NOT NULL,sword TEXT,quality_group TEXT)")
                s.execute("PRAGMA user_version=2")
            }
            connection=c;return c
        } catch(e: Exception) { c.close();throw e }
    }
    @Synchronized private fun <T> submit(block: (Connection)->T): CompletableFuture<T> {
        if(closing!=null) return CompletableFuture.failedFuture(IllegalStateException("Boss database closing: $path"))
        return CompletableFuture.supplyAsync({ block(db()) },executor)
    }
    private fun <T> transaction(c: Connection,block: ()->T): T {
        c.autoCommit=false
        return try { val result=block();c.commit();result } catch(e: Throwable) { c.rollback();throw e } finally { c.autoCommit=true }
    }
    fun initialize()=submit { Unit }
    fun nextSpawn(id: String,initialAt: Long)=submit { c ->
        c.prepareStatement("INSERT OR IGNORE INTO schedule VALUES(?,?)").use { it.setString(1,id);it.setLong(2,initialAt);it.executeUpdate() }
        c.prepareStatement("SELECT next_at FROM schedule WHERE id=?").use { it.setString(1,id);it.executeQuery().use { r -> check(r.next());r.getLong(1) } }
    }
    fun schedule(id: String,at: Long)=submit { c ->
        c.prepareStatement("INSERT INTO schedule VALUES(?,?) ON CONFLICT(id) DO UPDATE SET next_at=excluded.next_at").use { it.setString(1,id);it.setLong(2,at);it.executeUpdate() };Unit
    }
    /** Deduplication, both independent rolls, daily limits and pending grants commit together. */
    fun roll(battle: UUID,player: UUID,at: Long,s: BossRewardsSettings,packageItem: String?=null)=submit { c -> transaction(c) {
        val b=battle.toString();val p=player.toString();val day=Instant.ofEpochMilli(at).atZone(s.zone).toLocalDate().toString()
        val existing=c.prepareStatement("SELECT material,generator FROM kills WHERE battle=? AND player=?").use {
            it.setString(1,b);it.setString(2,p);it.executeQuery().use { r -> if(r.next()) BossRoll(false,r.getInt(1)==1,r.getInt(2)==1) else null }
        }
        if(existing!=null) return@transaction existing
        val daily=c.prepareStatement("SELECT attempts,wins FROM daily WHERE player=? AND day=?").use {
            it.setString(1,p);it.setString(2,day);it.executeQuery().use { r -> if(r.next()) r.getInt(1) to r.getInt(2) else 0 to 0 }
        }
        val material=random()<s.materialChance
        val canTry=daily.first<s.dailyAttempts && daily.second<s.dailyWins
        val generator=canTry && random()<s.generatorChance
        c.prepareStatement("INSERT INTO daily VALUES(?,?,?,?) ON CONFLICT(player,day) DO UPDATE SET attempts=excluded.attempts,wins=excluded.wins").use {
            it.setString(1,p);it.setString(2,day);it.setInt(3,daily.first+if(canTry) 1 else 0);it.setInt(4,daily.second+if(generator) 1 else 0);it.executeUpdate()
        }
        c.prepareStatement("INSERT INTO kills VALUES(?,?,?,?,?)").use {
            it.setString(1,b);it.setString(2,p);it.setString(3,day);it.setInt(4,if(material) 1 else 0);it.setInt(5,if(generator) 1 else 0);it.executeUpdate()
        }
        val items=if(packageItem!=null) listOf(packageItem)
            else listOf(s.materialId to material,s.generatorId to generator).filter { it.second }.map { it.first }
        items.forEach { grant(c,b,p,it) }
        BossRoll(true,material,generator)
    } }
    enum class SpoilsStatus { UNOPENED, OPENED, UNKNOWN }
    private fun spoils(c: Connection,player: UUID,receipt: String,packageItem: String): Pair<SpoilsStatus,String?> =
        c.prepareStatement("""SELECT g.battle,o.receipt FROM grants g
            LEFT JOIN opened_spoils o ON o.receipt=g.receipt
            WHERE g.receipt=? AND g.player=? AND g.item=? AND g.state<>'PENDING'""").use {
            it.setString(1,receipt);it.setString(2,player.toString());it.setString(3,packageItem)
            it.executeQuery().use { rows ->
                if(!rows.next()) SpoilsStatus.UNKNOWN to null
                else (if(rows.getString(2)==null) SpoilsStatus.UNOPENED else SpoilsStatus.OPENED) to rows.getString(1)
            }
        }
    fun spoilsStatus(player: UUID,receipt: String,packageItem: String)=submit { spoils(it,player,receipt,packageItem).first }
    fun planSpoils(player: UUID,receipt: String,packageItem: String,proposed: BossSpoilsPlan)=submit { c -> transaction(c) {
        if(spoils(c,player,receipt,packageItem).first!=SpoilsStatus.UNOPENED) return@transaction null
        c.prepareStatement("INSERT OR IGNORE INTO spoils_plans VALUES(?,?,?,?)").use {
            it.setString(1,receipt);it.setString(2,proposed.material);it.setString(3,proposed.sword)
            it.setString(4,proposed.group);it.executeUpdate()
        }
        c.prepareStatement("SELECT material,sword,quality_group FROM spoils_plans WHERE receipt=?").use {
            it.setString(1,receipt)
            it.executeQuery().use { rows -> check(rows.next());BossSpoilsPlan(rows.getString(1),rows.getString(2),rows.getString(3)) }
        }
    } }
    fun openSpoils(player: UUID,receipt: String,packageItem: String,loot: List<String>,settings: BossRewardsSettings)=submit { c -> transaction(c) {
        val (status,battle)=spoils(c,player,receipt,packageItem)
        if(status!=SpoilsStatus.UNOPENED) return@transaction status
        require(loot.isNotEmpty())
        val p=player.toString()
        c.prepareStatement("SELECT material,generator FROM kills WHERE battle=? AND player=?").use {
            it.setString(1,battle);it.setString(2,p)
            it.executeQuery().use { rows ->
                check(rows.next()) { "Missing kill for boss spoils $receipt" }
                if(rows.getBoolean(1)) grant(c,battle!!,p,settings.materialId)
                if(rows.getBoolean(2)) grant(c,battle!!,p,settings.generatorId)
            }
        }
        loot.forEach { grant(c,battle!!,p,it) }
        c.prepareStatement("INSERT INTO opened_spoils VALUES(?)").use { it.setString(1,receipt);it.executeUpdate() }
        // Possession of the original receipt proves delivery even after a crash between inventory and SQLite.
        c.prepareStatement("UPDATE grants SET state='DELIVERED' WHERE receipt=?").use { it.setString(1,receipt);it.executeUpdate() }
        SpoilsStatus.OPENED
    } }
    private fun grant(c: Connection,battle: String,player: String,item: String) {
        c.prepareStatement("INSERT INTO grants VALUES(?,?,?,?,'PENDING')").use {
            it.setString(1,UUID.randomUUID().toString());it.setString(2,battle);it.setString(3,player);it.setString(4,item);it.executeUpdate()
        }
    }
    fun pending(player: UUID)=submit { c ->
        c.prepareStatement("SELECT receipt,item,state FROM grants WHERE player=? AND state IN ('PENDING','REVIEW','DELIVERING') ORDER BY rowid LIMIT 100").use {
            it.setString(1,player.toString());it.executeQuery().use { r -> buildList { while(r.next()) add(BossGrant(r.getString(1),r.getString(2),r.getString(3))) } }
        }
    }
    fun reserve(player: UUID,receipt: String)=change(player,receipt,"PENDING","DELIVERING")
    fun release(player: UUID,receipt: String)=change(player,receipt,"DELIVERING","PENDING")
    fun delivered(player: UUID,receipt: String)=change(player,receipt,"DELIVERING","DELIVERED")
    fun reconcile(player: UUID,receipt: String)=change(player,receipt,"REVIEW","DELIVERED")
    private fun change(player: UUID,receipt: String,from: String,to: String)=submit { c ->
        c.prepareStatement("UPDATE grants SET state=? WHERE player=? AND receipt=? AND state=?").use {
            it.setString(1,to);it.setString(2,player.toString());it.setString(3,receipt);it.setString(4,from);it.executeUpdate()==1
        }
    }
    @Synchronized fun closeAsync(): CompletableFuture<Unit> {
        closing?.let { return it }
        val f=CompletableFuture<Unit>();closing=f
        executor.execute { try { connection?.close();connection=null;f.complete(Unit) } catch(e: Exception) { f.completeExceptionally(e) } }
        executor.shutdown();return f
    }
}
