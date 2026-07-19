package network.lapis.cloud.server.rpc

import network.lapis.cloud.shared.domain.DonationDuty
import network.lapis.cloud.shared.domain.DonorCategory
import java.math.BigDecimal

/**
 * Pure §25 PartG Spendenannahmeverbot (donation-acceptance rules) verdict engine, extracted so it
 * is unit-testable without a database -- same "pure logic extracted to a sibling file" idiom as
 * [JournalEntryBalance]/[GeneralLedgerCalculator]/[UseOfFundsCalculator]/[FinancialStatementCalculator].
 * Only ever invoked by [AccountingService] when
 * `network.lapis.cloud.shared.domain.OrganizationSettingsDto.isPoliticalParty` is `true` -- a
 * complete no-op for a plain gemeinnuetziger Verein.
 *
 * **This is a starting point for research, not a legal specification.** Every threshold constant
 * below and every category-to-Absatz mapping in [check] is the current *understanding* of §25/25a/
 * 25c/25d PartG at the time this wave was written -- **verify against the current law text and,
 * ideally, a lawyer before relying on this for a real party.** The exact figures are deliberately
 * named constants (never scattered magic numbers) so they are trivial to find and correct once
 * verified. This mirrors the same disclaimer class already established by
 * [ReserveType.FREIE_RUECKLAGE][network.lapis.cloud.shared.domain.ReserveType] (the §62 Abs.1 Nr.3
 * percentage cap) and [UseOfFundsCalculator.TIMELY_USE_YEARS] (the §55 AO timely-use window).
 *
 * **Verdict/duty model**: [check] takes one donation's [amount], its [category], and that same
 * donor's already-`POSTED` donation total for the current calendar year *excluding* this donation
 * ([priorPostedTotalThisYear] -- `ZERO` for [DonorCategory.ANONYMOUS], which is a per-donation, not
 * an aggregate, rule) and returns a [DonationComplianceResult]:
 *  - The four structural categories ([DonorCategory.PUBLIC_LAW_CORPORATION],
 *    [DonorCategory.OVER_25_PERCENT_STATE_OWNED_COMPANY],
 *    [DonorCategory.OTHER_PARTY_OR_PARLIAMENTARY_GROUP_ENTITY],
 *    [DonorCategory.PROFESSIONAL_OR_TRADE_ASSOCIATION]) are [DonationVerdict.PROHIBITED] by
 *    category alone, amount-independent, with no [DonationDuty].
 *  - [DonorCategory.NON_EU_FOREIGN_NATURAL_PERSON] is [DonationVerdict.PROHIBITED] once
 *    `priorPostedTotalThisYear + amount` strictly exceeds [FOREIGN_DONOR_ANNUAL_CAP_EUR]; within
 *    the cap it is [DonationVerdict.ALLOWED] (still subject to the additive duties below).
 *  - [DonorCategory.ANONYMOUS] is never [DonationVerdict.PROHIBITED] here -- above
 *    [ANONYMOUS_FORWARDING_THRESHOLD_EUR] it is [DonationVerdict.ALLOWED] plus
 *    [DonationDuty.ANONYMOUS_FORWARDING_REQUIRED] (the party may book it but must forward it to the
 *    Bundestag administration -- a duty, not a block).
 *  - Every other [DonationVerdict.ALLOWED] identified donor ([DonorCategory.GERMAN_NATURAL_PERSON],
 *    [DonorCategory.EU_NATURAL_PERSON], [DonorCategory.NON_EU_FOREIGN_NATURAL_PERSON] within cap,
 *    [DonorCategory.GERMAN_COMPANY_OR_ORGANIZATION]) additionally accrues
 *    [DonationDuty.PROMPT_BUNDESTAG_REPORT_REQUIRED] once `priorPostedTotalThisYear + amount`
 *    strictly exceeds [PROMPT_REPORT_THRESHOLD_EUR], and
 *    [DonationDuty.ANNUAL_DISCLOSURE_REQUIRED] once it strictly exceeds
 *    [ANNUAL_DISCLOSURE_THRESHOLD_EUR] -- both additive, both can apply to the same donation at
 *    once. **Duties never change [DonationVerdict.ALLOWED] to [DonationVerdict.PROHIBITED]** --
 *    only the checks above ever produce [DonationVerdict.PROHIBITED].
 *
 * Flagged deviation from a stricter reading of the law: current law arguably treats the
 * prompt-report trigger as a single-donation event, but this calculator computes both
 * [DonationDuty.PROMPT_BUNDESTAG_REPORT_REQUIRED] and [DonationDuty.ANNUAL_DISCLOSURE_REQUIRED] on
 * the aggregate-per-donor-per-calendar-year figure (per the wave's scope decision) -- the
 * single-vs-aggregate boundary is exactly the kind of figure a lawyer must confirm.
 *
 * Deliberately NOT modelled: Abs.2 Nr.6/7-ish "donation clearly given in expectation of a concrete
 * economic or political advantage" -- cannot be inferred from any data this system holds.
 *
 * **BigDecimal pitfall, deliberately guarded against** (same class of bug as
 * [JournalEntryBalance]/[UseOfFundsCalculator]'s KDoc): every comparison is via
 * `BigDecimal.compareTo`, never `equals`/`==`.
 */
internal object PartyDonationComplianceCalculator {
    private val ZERO = BigDecimal.ZERO

    /**
     * Per-donor annual aggregate cap for a [DonorCategory.NON_EU_FOREIGN_NATURAL_PERSON] donation
     * (Abs.2 Nr.3-ish) -- current understanding, verify against the current §25/25a/25c/25d PartG
     * text and a lawyer.
     */
    val FOREIGN_DONOR_ANNUAL_CAP_EUR: BigDecimal = BigDecimal("1000")

    /**
     * Per-donation (NOT aggregate) threshold above which an anonymous donation must be forwarded to
     * the Bundestag administration -- current understanding, verify against the current §25/25a/
     * 25c/25d PartG text and a lawyer.
     */
    val ANONYMOUS_FORWARDING_THRESHOLD_EUR: BigDecimal = BigDecimal("500")

    /**
     * Per-donor annual aggregate threshold above which a donation must be individually named
     * (donor + amount) in the party's annual Rechenschaftsbericht -- current understanding, verify
     * against the current §25/25a/25c/25d PartG text and a lawyer.
     */
    val ANNUAL_DISCLOSURE_THRESHOLD_EUR: BigDecimal = BigDecimal("10000")

    /**
     * Per-donor annual aggregate threshold above which a donation must be reported to the
     * Bundestag administration promptly ("unverzueglich") -- current understanding, verify against
     * the current §25/25a/25c/25d PartG text and a lawyer. See class KDoc for the flagged
     * single-donation-vs-aggregate deviation.
     */
    val PROMPT_REPORT_THRESHOLD_EUR: BigDecimal = BigDecimal("35000")

    /**
     * [DonorCategory] literals that are PROHIBITED by category alone, amount-independent -- see
     * class KDoc for the conservative rationale behind the last two literals.
     */
    private val STRUCTURALLY_PROHIBITED_CATEGORIES =
        setOf(
            DonorCategory.PUBLIC_LAW_CORPORATION,
            DonorCategory.OVER_25_PERCENT_STATE_OWNED_COMPANY,
            DonorCategory.OTHER_PARTY_OR_PARLIAMENTARY_GROUP_ENTITY,
            DonorCategory.PROFESSIONAL_OR_TRADE_ASSOCIATION,
        )

    /**
     * Human-readable reason cited when a [STRUCTURALLY_PROHIBITED_CATEGORIES] category is
     * PROHIBITED. Keyed by category so every structural literal carries its own Absatz citation.
     */
    private val STRUCTURAL_PROHIBITION_REASON: Map<DonorCategory, String> =
        mapOf(
            DonorCategory.PUBLIC_LAW_CORPORATION to
                "§25 Abs.2 PartG (current understanding, verify): donations from a public-law corporation " +
                "are prohibited except ordinary membership-type contributions, which this system does not model.",
            DonorCategory.OVER_25_PERCENT_STATE_OWNED_COMPANY to
                "§25 Abs.2 PartG (current understanding, verify): donations from a company with more than " +
                "25% direct/indirect public-sector ownership or comparable state influence are prohibited.",
            DonorCategory.OTHER_PARTY_OR_PARLIAMENTARY_GROUP_ENTITY to
                "§25 Abs.2 PartG (current understanding, verify): donations from another party's " +
                "parliamentary-group funds/foundations/associations are conservatively prohibited outright " +
                "here -- reclassify if a specific donation is known to be lawful.",
            DonorCategory.PROFESSIONAL_OR_TRADE_ASSOCIATION to
                "§25 Abs.2 PartG (current understanding, verify): donations from a professional/trade " +
                "association are conservatively prohibited outright here (the law's narrower " +
                "circumvention-of-transparency condition is not evaluated) -- reclassify if a specific " +
                "donation is known to be lawful.",
        )

    /**
     * Evaluates one donation. [amount] is this donation's own income amount (strictly positive);
     * [category] is the effective/snapshotted [DonorCategory]; [priorPostedTotalThisYear] is the
     * same donor's other already-`POSTED` donation total in the same calendar year (`ZERO` for
     * [DonorCategory.ANONYMOUS] -- see class KDoc). Never throws.
     */
    fun check(
        amount: BigDecimal,
        category: DonorCategory,
        priorPostedTotalThisYear: BigDecimal,
    ): DonationComplianceResult {
        if (category in STRUCTURALLY_PROHIBITED_CATEGORIES) {
            return DonationComplianceResult(
                verdict = DonationVerdict.PROHIBITED,
                reason = STRUCTURAL_PROHIBITION_REASON.getValue(category),
                duties = emptySet(),
            )
        }

        val annualTotal = priorPostedTotalThisYear + amount

        if (category == DonorCategory.NON_EU_FOREIGN_NATURAL_PERSON && annualTotal.compareTo(FOREIGN_DONOR_ANNUAL_CAP_EUR) > 0) {
            return DonationComplianceResult(
                verdict = DonationVerdict.PROHIBITED,
                reason =
                    "§25 Abs.2 PartG (current understanding, verify): donations from a non-EU foreign " +
                        "natural person are capped at an aggregate of $FOREIGN_DONOR_ANNUAL_CAP_EUR EUR per " +
                        "calendar year; this donor's annual total would be $annualTotal EUR.",
                duties = emptySet(),
            )
        }

        val duties = mutableSetOf<DonationDuty>()
        if (category == DonorCategory.ANONYMOUS) {
            if (amount.compareTo(ANONYMOUS_FORWARDING_THRESHOLD_EUR) > 0) {
                duties += DonationDuty.ANONYMOUS_FORWARDING_REQUIRED
            }
        } else {
            if (annualTotal.compareTo(PROMPT_REPORT_THRESHOLD_EUR) > 0) {
                duties += DonationDuty.PROMPT_BUNDESTAG_REPORT_REQUIRED
            }
            if (annualTotal.compareTo(ANNUAL_DISCLOSURE_THRESHOLD_EUR) > 0) {
                duties += DonationDuty.ANNUAL_DISCLOSURE_REQUIRED
            }
        }

        return DonationComplianceResult(verdict = DonationVerdict.ALLOWED, reason = null, duties = duties)
    }
}

/** Outcome of [PartyDonationComplianceCalculator.check]. [DonationVerdict.PROHIBITED] hard-blocks posting; duties never do. */
internal enum class DonationVerdict { ALLOWED, PROHIBITED }

/** [reason] is non-null iff [verdict] is [DonationVerdict.PROHIBITED]; [duties] may be non-empty regardless of [verdict]. */
internal data class DonationComplianceResult(
    val verdict: DonationVerdict,
    val reason: String?,
    val duties: Set<DonationDuty>,
)
