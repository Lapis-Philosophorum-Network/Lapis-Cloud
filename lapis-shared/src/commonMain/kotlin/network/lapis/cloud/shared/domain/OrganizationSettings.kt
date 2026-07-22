package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * The issuing association's own letterhead (V0.4.1 Serienbrief/PDF engine) -- name/address for
 * every letter template, bank details ([bankIban]/[bankBic]) for a Beitragsrechnung's payment
 * instructions, and the Gemeinnuetzigkeit tax-exemption reference ([taxExemptionAuthority]/
 * [taxExemptionDate] -- the issuing Finanzamt and date of the Freistellungsbescheid) required for
 * a legally complete Spendenbescheinigung.
 *
 * Exactly one row exists in this codebase, enforced by convention only (see
 * `network.lapis.cloud.server.db.generated.OrganizationSettingsTable` KDoc and
 * `lapis-server/src/main/kuml/11-organization-settings.kuml.kts` file header) -- there is no
 * create/delete RPC, only [network.lapis.cloud.shared.rpc.IOrganizationSettingsService.getOrganizationSettings]/
 * [network.lapis.cloud.shared.rpc.IOrganizationSettingsService.updateOrganizationSettings], both
 * always targeting that single seeded row. Every field except [id]/[name] is nullable -- a fresh
 * deployment is seeded with a placeholder [name] only, an ADMIN must configure the rest before a
 * legally complete Spendenbescheinigung/Beitragsrechnung can be generated (see
 * `network.lapis.cloud.server.routes.registerMailmergeRoutes` KDoc for the completeness guards
 * this enforces at generation time).
 *
 * [isPoliticalParty] (V0.4.1 fix wave) selects the Spendenbescheinigung's legal basis --
 * `false` (default, gemeinnuetziger Verein, § 10b EStG deduction) or `true` (political party,
 * § 34g EStG tax credit) -- see `network.lapis.cloud.server.pdf.SpendenbescheinigungPdfGenerator`
 * KDoc for why this branch exists and what remains an unverified simplification.
 *
 * [postalMailEnabled] (V0.4.2 Letterxpress postal-mail dispatch) is the explicit opt-in gate for
 * the whole postal-dispatch feature -- **defaults to `false`/off**. Postal dispatch sends a
 * member's postal address (PII) to Letterxpress, a third-party data processor: enabling this in
 * real operation requires the association/party to have a Data Processing Agreement
 * (Auftragsverarbeitungsvertrag/AVV) with Letterxpress **in place first** -- an organizational/
 * legal precondition this codebase cannot verify or enforce. ADMIN-only to set (same tier as every
 * other field, via [network.lapis.cloud.shared.rpc.IOrganizationSettingsService.updateOrganizationSettings]).
 * See `network.lapis.cloud.server.rpc.PostalMailService` KDoc for the runtime gate this backs.
 *
 * [politicianRankingEnabled] (V0.6.4 Politiker-Profile und Politiker-Ranking) is the explicit
 * opt-in gate for the whole feature -- **defaults to `false`/off**, same "independent flag, not
 * folded into [isPoliticalParty]" reasoning [postalMailEnabled] already established. ADMIN-only to
 * set, same tier as every other field. See `network.lapis.cloud.server.rpc.PoliticianService`
 * KDoc for the runtime gate this backs.
 */
@Serializable
data class OrganizationSettingsDto(
    val id: String,
    val name: String,
    val street: String?,
    val postalCode: String?,
    val city: String?,
    val country: String?,
    val bankIban: String?,
    val bankBic: String?,
    val taxExemptionAuthority: String?,
    val taxExemptionDate: LocalDate?,
    val isPoliticalParty: Boolean = false,
    val postalMailEnabled: Boolean = false,
    val politicianRankingEnabled: Boolean = false,
)

/** Replaces every field of the single [OrganizationSettingsDto] row wholesale (no partial update). */
@Serializable
data class OrganizationSettingsInput(
    val name: String,
    val street: String? = null,
    val postalCode: String? = null,
    val city: String? = null,
    val country: String? = null,
    val bankIban: String? = null,
    val bankBic: String? = null,
    val taxExemptionAuthority: String? = null,
    val taxExemptionDate: LocalDate? = null,
    val isPoliticalParty: Boolean = false,
    /** See [OrganizationSettingsDto.postalMailEnabled] KDoc -- AVV requirement applies here too. */
    val postalMailEnabled: Boolean = false,
    /** See [OrganizationSettingsDto.politicianRankingEnabled] KDoc. */
    val politicianRankingEnabled: Boolean = false,
)
