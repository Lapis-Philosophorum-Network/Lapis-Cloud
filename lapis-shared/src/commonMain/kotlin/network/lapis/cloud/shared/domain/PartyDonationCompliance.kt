package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * §25 PartG Spendenannahmeverbot (donation-acceptance rules, V0.5.1) -- only relevant when
 * [OrganizationSettingsDto.isPoliticalParty] is `true`; a complete no-op for a plain
 * gemeinnuetziger Verein, see `network.lapis.cloud.server.rpc.AccountingService` KDoc.
 *
 * **This is a starting point for research, not a legal specification.** Every category-to-Absatz
 * mapping and every threshold figure used by
 * `network.lapis.cloud.server.rpc.PartyDonationComplianceCalculator` (which consumes
 * [DonorCategory] and is the actual verdict engine) must be verified against the current §25/25a/
 * 25c/25d PartG text and, ideally, a lawyer before being relied on for a real party -- see that
 * object's KDoc for the full disclaimer and the exact named constants.
 *
 * [DonorCategory] is the classification a treasurer *explicitly* assigns per donation -- there is
 * deliberately NO automatic inference of a donor's legal category from any other data (citizenship,
 * ownership structure etc. cannot be verified by this system). Literal order is load-bearing:
 * `AccountingSchemaDriftTest` asserts the generated enum-column values in exactly this order,
 * matching `10-accounting.kuml.kts`'s `donorCategory` enum.
 *
 * - [GERMAN_NATURAL_PERSON]/[EU_NATURAL_PERSON]: amount/aggregate-checked only, never
 *   category-prohibited (also covers the "German citizen abroad"/"EU citizen or entity" carve-outs
 *   -- a human picks one of these literals for such a donor rather than [NON_EU_FOREIGN_NATURAL_PERSON]).
 * - [NON_EU_FOREIGN_NATURAL_PERSON]: capped by a per-donor annual aggregate (Abs.2 Nr.3-ish).
 * - [GERMAN_COMPANY_OR_ORGANIZATION]: amount/aggregate-checked only, same as the natural-person
 *   categories.
 * - [PUBLIC_LAW_CORPORATION]/[OVER_25_PERCENT_STATE_OWNED_COMPANY]: structurally always
 *   PROHIBITED, amount-independent (Abs.2 Nr.1/Nr.2-ish).
 * - [OTHER_PARTY_OR_PARLIAMENTARY_GROUP_ENTITY]/[PROFESSIONAL_OR_TRADE_ASSOCIATION]: also
 *   structurally always PROHIBITED here -- conservatively, even though the underlying law's
 *   "designed to circumvent transparency/funnel disguised individual contributions" condition
 *   (Abs.2 Nr.4-ish) is narrower than "any donation from such an entity". A treasurer who knows a
 *   specific donation from such an entity is in fact lawful must reclassify it (e.g. to
 *   [GERMAN_COMPANY_OR_ORGANIZATION]) rather than being silently allowed by this system.
 * - [ANONYMOUS]: not category-prohibited, but triggers a per-donation (not aggregate)
 *   forwarding-to-the-Bundestag-administration duty above a threshold.
 *
 * Deliberately NOT modelled anywhere in this system: Abs.2 Nr.6/7-ish "donation clearly given in
 * expectation of a concrete economic or political advantage" -- this cannot be inferred from any
 * data this system holds; flagged as a permanently deferred gap, not a bug.
 */
@Serializable
enum class DonorCategory {
    GERMAN_NATURAL_PERSON,
    EU_NATURAL_PERSON,
    NON_EU_FOREIGN_NATURAL_PERSON,
    GERMAN_COMPANY_OR_ORGANIZATION,
    PUBLIC_LAW_CORPORATION,
    OVER_25_PERCENT_STATE_OWNED_COMPANY,
    OTHER_PARTY_OR_PARLIAMENTARY_GROUP_ENTITY,
    PROFESSIONAL_OR_TRADE_ASSOCIATION,
    ANONYMOUS,
}

/** Whether a donation's identified donor is a [JournalEntryDto.donorMemberId] member or an [ExternalDonorDto]. */
@Serializable
enum class DonorType { MEMBER, EXTERNAL }

/**
 * An additive follow-up obligation a treasurer must act on manually -- surfaced read-only via
 * [DonationDutyReportDto], never a posting blocker (see
 * `network.lapis.cloud.server.rpc.PartyDonationComplianceCalculator` KDoc: only
 * `DonationVerdict.PROHIBITED` blocks posting, duties never do). Zero or more of these may apply to
 * the same donation simultaneously -- they are additive, not alternatives.
 */
@Serializable
enum class DonationDuty {
    /** The donation must be forwarded to the Bundestag administration rather than kept. */
    ANONYMOUS_FORWARDING_REQUIRED,

    /** Must be reported to the Bundestag administration promptly ("unverzueglich"). */
    PROMPT_BUNDESTAG_REPORT_REQUIRED,

    /** Must be individually named (donor + amount) in the party's annual Rechenschaftsbericht. */
    ANNUAL_DISCLOSURE_REQUIRED,
}

/**
 * V0.5.1 §25 PartG donor identity for a non-member donor -- see `10-accounting.kuml.kts` file
 * header for why a free-text name alone could not reliably identify "the same donor" across
 * bookings for the per-donor-per-calendar-year aggregate thresholds. Also useful to a plain
 * gemeinnuetziger Verein (attributing a non-member donation for a §10b EStG receipt) -- unlike the
 * compliance CHECK itself, this entity's CRUD is NOT gated on
 * [OrganizationSettingsDto.isPoliticalParty]. Lifecycle is create/deactivate/list/get only
 * (deactivate never delete, mirrors [CostCenterDto] exactly) so historical postings referencing a
 * donor are never invalidated. [displayName] is deliberately NOT unique -- two real donors may
 * share a name; identity is [id].
 */
@Serializable
data class ExternalDonorDto(
    val id: String,
    val displayName: String,
    val donorCategory: DonorCategory,
    val street: String?,
    val postalCode: String?,
    val city: String?,
    val country: String?,
    val active: Boolean,
)

/** [active] defaults to `true` for the common "create it active" call shape, same as [CostCenterInput]. */
@Serializable
data class ExternalDonorInput(
    val displayName: String,
    val donorCategory: DonorCategory,
    val street: String? = null,
    val postalCode: String? = null,
    val city: String? = null,
    val country: String? = null,
    val active: Boolean = true,
)

/**
 * One donor's open follow-up duties for a [DonationDutyReportDto.calendarYear] -- [annualTotal] is
 * that donor's summed [JournalEntryDto] donation amounts POSTED in that year (see
 * `network.lapis.cloud.server.rpc.AccountingService` KDoc for the exact aggregation). At least one
 * of [promptReportRequired]/[annualDisclosureRequired] is `true` for every row this report includes
 * -- both can be `true` simultaneously (additive duties, not alternatives).
 */
@Serializable
data class DonorDutyDto(
    val donorType: DonorType,
    val donorId: String,
    val donorDisplayName: String,
    val donorCategory: DonorCategory,
    val annualTotal: Decimal,
    val promptReportRequired: Boolean,
    val annualDisclosureRequired: Boolean,
)

/** One individual [DonorCategory.ANONYMOUS] donation whose amount exceeded the forwarding threshold -- see class KDoc. */
@Serializable
data class AnonymousDonationDutyDto(
    val journalEntryId: String,
    val entryDate: LocalDate,
    val amount: Decimal,
)

/**
 * §25 PartG follow-up-duty report (V0.5.1) for [calendarYear] -- read-only, lists currently open
 * prompt-report/annual-disclosure/anonymous-forwarding duties so a treasurer can act on them
 * manually. Does NOT compute prohibited donations (those are hard-blocked at post time, see
 * `network.lapis.cloud.server.rpc.AccountingService.requirePartyDonationAllowed`, so a `PROHIBITED`
 * donation can never appear in `POSTED` data). When [partyRulesApply] is `false` (a plain
 * gemeinnuetziger Verein, `OrganizationSettingsDto.isPoliticalParty == false`), [donorDuties] and
 * [anonymousForwarding] are always empty -- the whole §25 PartG mechanism is a no-op for such an
 * organization. Deliberately out of scope this wave: automated Bundestag-administration submission/
 * filing and full Rechenschaftsbericht document generation -- this report only makes the open
 * duties visible/queryable.
 */
@Serializable
data class DonationDutyReportDto(
    val calendarYear: Int,
    val partyRulesApply: Boolean,
    val donorDuties: List<DonorDutyDto>,
    val anonymousForwarding: List<AnonymousDonationDutyDto>,
)
