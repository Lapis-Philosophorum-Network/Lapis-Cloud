package network.lapis.cloud.server.db.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * Placeholder for the full LTR ledger that V0.6 (LTR-Wirtschaft/Auktion/Price-Oracle) will
 * introduce — just a balance snapshot per member, no debit/credit/reserve columns yet. Read-only
 * from `network.lapis.cloud.server.rpc.GovernanceService`'s perspective in this wave: Meritokratische
 * Abstimmungen (V0.2.3) validates a member's stake against this balance via
 * `network.lapis.cloud.server.economy.LtrBalanceProvider`, but does not debit it — there is no
 * writable ledger to debit yet. V0.6 replaces this table with the real ledger and swaps in a
 * ledger-backed [network.lapis.cloud.server.economy.LtrBalanceProvider] that also performs the
 * actual settlement debit. `DECIMAL(18,2)`, wider than [ContributionTable.amountDue]'s `(12,2)`,
 * because a balance accumulates over time rather than reflecting a single period's due amount;
 * scale 2 matches the 0.01 LTR minimum stake enforced in `GovernanceService.castStimme`.
 */
object LtrBalanceTable : Table("ltr_balance") {
    val memberId = uuid("member_id").references(MemberTable.id)
    val balanceLtr = decimal("balance_ltr", 18, 2)
    val updatedAt = datetime("updated_at")

    override val primaryKey = PrimaryKey(memberId)
}
