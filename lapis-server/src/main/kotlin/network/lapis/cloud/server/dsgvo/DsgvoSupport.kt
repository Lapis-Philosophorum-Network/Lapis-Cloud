package network.lapis.cloud.server.dsgvo

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * Matches the `Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())` pattern
 * inlined in every other `rpc`/`routes` file (see e.g. `ContributionService`) — pulled out once
 * here because the DSGVO package has several call sites (contributors, service, routes) that all
 * need "now" for the exact same reason (timestamping an export/erasure/audit event).
 */
internal fun nowUtc(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
