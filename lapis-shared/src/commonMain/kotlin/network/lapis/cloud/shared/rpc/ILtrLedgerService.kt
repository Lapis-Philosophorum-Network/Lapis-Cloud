package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.LtrLedgerBalanceDto
import network.lapis.cloud.shared.domain.LtrLedgerEntryDto
import network.lapis.cloud.shared.domain.MintLtrInput

/**
 * V0.6.1 (Internes Crowdfunding) LTR-Ledger read/mint access. Replaces the V0.2.3-era
 * `PlaceholderLtrBalanceProvider` read-only seam (see
 * `network.lapis.cloud.server.economy.LtrBalanceProvider` KDoc) with a real, writable ledger --
 * this service is the only RPC-facing way to mint LTR; binding a stake into a Crowdfunding
 * project happens internally within `ICrowdfundingService.submitProject`, not through this
 * service (see that service's KDoc).
 */
@RpcService
interface ILtrLedgerService {
    /** Any authenticated member -- always their own balance. */
    suspend fun getMyBalance(): LtrLedgerBalanceDto

    /** Own balance for any member; another member's balance requires TREASURER/BOARD/ADMIN. */
    suspend fun getMemberBalance(memberId: String): LtrLedgerBalanceDto

    /** Any authenticated member -- always their own ledger entries, newest first. */
    suspend fun listMyEntries(limit: Int = 200): List<LtrLedgerEntryDto>

    /** Own entries for any member; another member's entries require TREASURER/BOARD/ADMIN. */
    suspend fun listMemberEntries(
        memberId: String,
        limit: Int = 200,
    ): List<LtrLedgerEntryDto>

    /** Role: TREASURER/BOARD/ADMIN. Grants (credits) LTR to a member -- the only way new LTR enters circulation. */
    suspend fun mintLtr(input: MintLtrInput): LtrLedgerEntryDto
}
