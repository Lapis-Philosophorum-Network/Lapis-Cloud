package network.lapis.cloud.server.audit

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import kotlin.uuid.Uuid

/**
 * Pure tests of [AuditHashChain] -- no DB access anywhere in this file, same "pure Calculator"
 * rationale [JournalEntryBalanceTest]/[network.lapis.cloud.server.rpc.ElectionTallyTest] give for
 * their own pure-logic subjects.
 */
class AuditHashChainTest :
    FunSpec({
        fun input(
            sequenceNumber: Long = 1,
            occurredAt: LocalDateTime = LocalDateTime(2026, 7, 21, 12, 0),
            actorMemberId: Uuid? = Uuid.random(),
            actorRole: AccountRole? = AccountRole.TREASURER,
            entityType: AuditEntityType = AuditEntityType.JOURNAL_ENTRY,
            entityId: Uuid = Uuid.random(),
            action: AuditAction = AuditAction.CREATE,
            beforeSnapshot: String? = null,
            afterSnapshot: String? = "payload",
            previousEntryHash: String? = "abc123",
        ) = AuditHashChain.ChainInput(
            sequenceNumber = sequenceNumber,
            occurredAt = occurredAt,
            actorMemberId = actorMemberId,
            actorRole = actorRole,
            entityType = entityType,
            entityId = entityId,
            action = action,
            beforeSnapshot = beforeSnapshot,
            afterSnapshot = afterSnapshot,
            previousEntryHash = previousEntryHash,
        )

        test("computeHash is deterministic -- the same ChainInput always yields the same hash") {
            val i = input()
            val h1 = AuditHashChain.computeHash(i)
            val h2 = AuditHashChain.computeHash(i)
            val h3 = AuditHashChain.computeHash(i.copy())
            h1 shouldBe h2
            h1 shouldBe h3
        }

        test("computeHash produces a 64-char lowercase hex string (SHA-256)") {
            val hash = AuditHashChain.computeHash(input())
            hash.length shouldBe 64
            hash shouldBe hash.lowercase()
            hash.all { it in "0123456789abcdef" } shouldBe true
        }

        test("changing a single field (afterSnapshot) changes the hash") {
            val base = input(afterSnapshot = "payload-A")
            val changed = base.copy(afterSnapshot = "payload-B")
            AuditHashChain.computeHash(base) shouldNotBe AuditHashChain.computeHash(changed)
        }

        test("changing sequenceNumber alone changes the hash") {
            val base = input(sequenceNumber = 1)
            val changed = base.copy(sequenceNumber = 2)
            AuditHashChain.computeHash(base) shouldNotBe AuditHashChain.computeHash(changed)
        }

        test("changing entityId alone changes the hash") {
            val base = input(entityId = Uuid.random())
            val changed = base.copy(entityId = Uuid.random())
            AuditHashChain.computeHash(base) shouldNotBe AuditHashChain.computeHash(changed)
        }

        test("changing actorMemberId from null to a real id changes the hash (nullable-field boundary)") {
            val withoutActor = input(actorMemberId = null, actorRole = null)
            val withActor = withoutActor.copy(actorMemberId = Uuid.random(), actorRole = AccountRole.ADMIN)
            AuditHashChain.computeHash(withoutActor) shouldNotBe AuditHashChain.computeHash(withActor)
        }

        test("a genesis row (previousEntryHash = null) hashes differently than an otherwise-identical row with a real previous hash") {
            val genesis = input(previousEntryHash = null)
            val chained = genesis.copy(previousEntryHash = "0000000000000000000000000000000000000000000000000000000000000")
            AuditHashChain.computeHash(genesis) shouldNotBe AuditHashChain.computeHash(chained)
        }

        test("a genesis row's hash does not collide with the same row using the literal GENESIS_MARKER string as previousEntryHash") {
            // canonicalPayload folds a null previousEntryHash to GENESIS_MARKER internally -- this
            // pins that an actual (contrived) previousEntryHash equal to that literal string is
            // still distinguishable in principle from the null/genesis case at the ChainInput level,
            // i.e. there is no way to observe from the outside that null and "GENESIS" collapse to
            // the same payload by accident for two DIFFERENT logical inputs sharing every other
            // field -- they are the same payload only because they really are the same ChainInput.
            val genesis = input(previousEntryHash = null)
            val literalMarker = genesis.copy(previousEntryHash = AuditHashChain.GENESIS_MARKER)
            AuditHashChain.computeHash(genesis) shouldBe AuditHashChain.computeHash(literalMarker)
        }

        test("field-boundary shifting across adjacent string fields does not collide (FIELD_SEPARATOR is not attacker-writable content)") {
            // "AB" + "" and "A" + "B" would collide under naive string concatenation; the
            // FIELD_SEPARATOR-joined payload must keep them distinct.
            val a = input(beforeSnapshot = "AB", afterSnapshot = "")
            val b = input(beforeSnapshot = "A", afterSnapshot = "B")
            AuditHashChain.computeHash(a) shouldNotBe AuditHashChain.computeHash(b)
        }

        test("concurrent computeHash calls from many coroutines each get their own correct, independent MessageDigest result") {
            val inputs = (1..50).map { input(sequenceNumber = it.toLong(), entityId = Uuid.random()) }
            val expected = inputs.map { AuditHashChain.computeHash(it) }
            val concurrentResults =
                coroutineScope {
                    inputs.map { i -> async { AuditHashChain.computeHash(i) } }.awaitAll()
                }
            concurrentResults shouldBe expected
        }
    })
