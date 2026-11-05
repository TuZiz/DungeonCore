package dev.dungeon.persistence

import dev.dungeon.model.RunSnapshot
import java.util.UUID
import java.util.concurrent.CompletableFuture

interface ProgressStore : AutoCloseable {
    fun save(player: UUID, dungeon: String, snapshot: RunSnapshot): CompletableFuture<Unit>
    fun progress(player: UUID, dungeon: String): CompletableFuture<Pair<Int,Int>?>
    fun reserveReward(player: UUID, dungeon: String, run: UUID): CompletableFuture<Boolean>
    fun unlocked(player: UUID, campaign: String): CompletableFuture<Int>
    fun complete(player: UUID, run: UUID, dungeon: String, difficulty: String, elapsed: Long, campaign: String, chapter: Int): CompletableFuture<Boolean?>
    fun rememberReturn(id: UUID, point: ReturnPoint): CompletableFuture<Unit>
    fun returnPoint(id: UUID): CompletableFuture<ReturnPoint?>
    fun forgetReturn(id: UUID): CompletableFuture<Unit>
    fun delivered(player: UUID, run: UUID): CompletableFuture<Unit>
}
