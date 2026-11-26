package dev.dungeon

import dev.dungeon.boss.BossRewardsSettings
import dev.dungeon.boss.battle.ContributionLedger
import dev.dungeon.boss.reward.BossRewardStore
import dev.dungeon.boss.reward.BossSpoilsPlan
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BossRewardTest {
    @TempDir lateinit var folder: Path
    private val settings=BossRewardsSettings(.05,60,64.0,"luckblock:material_lucky_block",.25,"luckblock:generator_lucky_block",.03,3,1,ZoneId.of("Asia/Shanghai"))
    private val at=Instant.parse("2026-11-17T12:00:00Z").toEpochMilli()
    private fun <T> java.util.concurrent.CompletableFuture<T>.result()=get(5,TimeUnit.SECONDS)
    @Test fun `personal spoils preserve plan across restart and open exactly once`() {
        val path=folder.resolve("spoils.db")
        val owner=UUID.randomUUID()
        val battle=UUID.randomUUID()
        val packageId="dungeon:warden_spoils"
        val plan=BossSpoilsPlan("genlite:gear","星芽:短剑","EXCELLENT")
        val first=BossRewardStore(path) { 0.0 }
        val receipt=try {
            assertTrue(first.roll(battle,owner,at,settings,packageId).result().fresh)
            assertFalse(first.roll(battle,owner,at,settings,packageId).result().fresh)
            val grant=first.pending(owner).result().single()
            assertEquals(packageId,grant.item)
            assertNull(first.planSpoils(owner,grant.receipt,packageId,plan).result())
            assertTrue(first.reserve(owner,grant.receipt).result())
            assertNull(first.planSpoils(UUID.randomUUID(),grant.receipt,packageId,plan).result())
            assertEquals(plan,first.planSpoils(owner,grant.receipt,packageId,plan).result())
            grant.receipt
        } finally { first.closeAsync().result() }
        val second=BossRewardStore(path)
        try {
            assertEquals(plan,second.planSpoils(owner,receipt,packageId,BossSpoilsPlan("other",null,null)).result())
            repeat(2) {
                assertEquals(BossRewardStore.SpoilsStatus.OPENED,
                    second.openSpoils(owner,receipt,packageId,listOf(plan.material,"stack:serialized"),settings).result())
            }
            assertEquals(listOf(plan.material,"stack:serialized",settings.materialId,settings.generatorId).sorted(),
                second.pending(owner).result().map { it.item }.sorted())
        } finally { second.closeAsync().result() }
    }
    @Test fun `only confirmed HP loss counts and overkill is capped`() {
        val p=UUID.randomUUID();val ledger=ContributionLedger(100.0)
        ledger.beforeDamage(p,100.0,90.0,1000);ledger.confirm(98.0)
        assertEquals(2.0,ledger.snapshot()[p]!!.damage)
        ledger.beforeDamage(p,98.0,50.0,1010);ledger.confirm(98.0)
        assertEquals(2.0,ledger.snapshot()[p]!!.damage,"cancelled after monitor or immune damage must not count")
        ledger.beforeDamage(p,3.0,99999.0,1020);ledger.confirm(0.0)
        assertEquals(5.0,ledger.snapshot()[p]!!.damage)
        assertEquals(setOf(p),ledger.eligible(61020,.05,60000))
        assertTrue(ledger.eligible(61021,.05,60000).isEmpty())
        ledger.reset();assertTrue(ledger.snapshot().isEmpty())
    }
    @Test fun `unattributed loss and healing never create player contribution`() {
        val p=UUID.randomUUID();val ledger=ContributionLedger(100.0)
        ledger.beforeDamage(p,100.0,6.0,1000)
        ledger.beforeDamage(null,94.0,80.0,1001)
        ledger.confirm(14.0)
        assertEquals(6.0,ledger.snapshot()[p]!!.damage)
        ledger.beforeDamage(p,14.0,7.0,1002);ledger.confirm(20.0)
        assertEquals(6.0,ledger.snapshot()[p]!!.damage)
    }
    @Test fun `same battle is never rerolled and daily wins include pending rewards`() {
        val rolls=AtomicInteger();val db=BossRewardStore(folder.resolve("boss.db")) { rolls.incrementAndGet();0.0 }
        try {
            val p=UUID.randomUUID();val b=UUID.randomUUID()
            val first=db.roll(b,p,at,settings).result();assertTrue(first.material && first.generator && first.fresh)
            repeat(8) { assertFalse(db.roll(b,p,at,settings).result().fresh) }
            assertEquals(2,rolls.get())
            val next=db.roll(UUID.randomUUID(),p,at,settings).result();assertTrue(next.material);assertFalse(next.generator)
            assertEquals(3,db.pending(p).result().size)
            val tomorrow=db.roll(UUID.randomUUID(),p,at+86400000,settings).result();assertTrue(tomorrow.generator)
        } finally { db.closeAsync().result() }
    }
    @Test fun `only first three qualified daily kills roll generator independently`() {
        val n=AtomicInteger();val db=BossRewardStore(folder.resolve("tries.db")) { if(n.incrementAndGet()%2==1) .1 else .5 }
        try {
            val p=UUID.randomUUID()
            repeat(3) { val r=db.roll(UUID.randomUUID(),p,at,settings).result();assertTrue(r.material);assertFalse(r.generator) }
            val fourth=db.roll(UUID.randomUUID(),p,at,settings).result();assertTrue(fourth.material);assertFalse(fourth.generator)
            assertEquals(7,n.get(),"fourth qualified kill may roll material only")
        } finally { db.closeAsync().result() }
    }
    @Test fun `concurrent callbacks share one roll and one claim reservation`() {
        val db=BossRewardStore(folder.resolve("concurrent.db")) { 0.0 }
        try {
            val p=UUID.randomUUID();val b=UUID.randomUUID()
            val futures=(1..20).map { db.roll(b,p,at,settings) }
            assertEquals(1,futures.count { it.result().fresh })
            val grant=db.pending(p).result().first()
            val claims=(1..20).map { db.reserve(p,grant.receipt) }
            assertEquals(1,claims.count { it.result() })
            assertTrue(db.release(p,grant.receipt).result());assertTrue(db.reserve(p,grant.receipt).result())
            assertTrue(db.delivered(p,grant.receipt).result());assertFalse(db.reserve(p,grant.receipt).result())
        } finally { db.closeAsync().result() }
    }
    @Test fun `restart retains pending rewards and holds uncertain deliveries for review`() {
        val p=UUID.randomUUID();val path=folder.resolve("recovery.db");val first=BossRewardStore(path) { 0.0 }
        first.roll(UUID.randomUUID(),p,at,settings).result()
        val grant=first.pending(p).result().first();assertTrue(first.reserve(p,grant.receipt).result());first.closeAsync().result()
        val reopened=BossRewardStore(path)
        try {
            val pending=reopened.pending(p).result()
            assertEquals(setOf("REVIEW","PENDING"),pending.map { it.state }.toSet())
            assertFalse(reopened.reserve(p,grant.receipt).result())
            assertTrue(reopened.reconcile(p,grant.receipt).result())
            assertEquals(1,reopened.pending(p).result().size)
        } finally { reopened.closeAsync().result() }
    }
}
