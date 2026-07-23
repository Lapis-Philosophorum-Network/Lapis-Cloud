package network.lapis.cloud.client

import io.kvision.form.select.select
import io.kvision.form.text.password
import io.kvision.form.text.text
import io.kvision.form.text.textArea
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.InputType
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AdminCreateMemberInput
import network.lapis.cloud.shared.domain.MemberSummaryDto
import network.lapis.cloud.shared.rpc.IMemberService
import network.lapis.cloud.shared.rpc.IRegistrationService

/**
 * Screen 4 of the V0.7.3 plan -- BOARD/ADMIN only, route-guarded in `Routing.kt` (never even
 * rendered for a plain MEMBER, per the plan). Three sub-sections in this one file, mirroring the
 * existing `renderXSection` grouping convention the old `App.kt` already used:
 * pending applications (approve/reject), the active-member directory/search, and direct member
 * creation.
 */
fun renderMemberAdministrationScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 16) {
            addCssClass("mx-auto")
            width = 720.px
            marginTop = 24.px
        }
    root.h1("Mitgliederverwaltung")

    renderPendingApplications(root)
    renderMemberDirectory(root)
    renderDirectMemberCreation(root)
}

private fun renderPendingApplications(root: SimplePanel) {
    root.h2("Offene Anträge")
    val pendingPanel = root.vPanel(spacing = 6)

    fun refresh() {
        pendingPanel.removeAll()
        AppScope.launch {
            val applications = guarded { rpcService<IRegistrationService>().listPendingApplications() } ?: return@launch
            if (applications.isEmpty()) {
                pendingPanel.p("Keine offenen Anträge.")
                return@launch
            }
            applications.forEach { application ->
                val row = pendingPanel.hPanel(spacing = 8) { addCssClasses("border rounded p-2 align-items-center") }
                row.div("${application.displayName} (${application.email}) -- eingereicht am ${application.joinedAt}") {
                    addCssClass("flex-grow-1")
                }
                val approveButton = row.button("Annehmen", style = ButtonStyle.SUCCESS)
                approveButton.onClick {
                    AppScope.launch {
                        val result = guarded { rpcService<IRegistrationService>().approveApplication(application.id) }
                        if (result != null) {
                            notifySuccess("${application.displayName} wurde aufgenommen.")
                            refresh()
                        }
                    }
                }
                val rejectButton = row.button("Ablehnen", style = ButtonStyle.OUTLINEDANGER)
                rejectButton.onClick {
                    rejectApplicationDialog(application.displayName) { reason ->
                        AppScope.launch {
                            val result = guarded { rpcService<IRegistrationService>().rejectApplication(application.id, reason) }
                            if (result != null) {
                                notifyInfo("${application.displayName} wurde abgelehnt.")
                                refresh()
                            }
                        }
                    }
                }
            }
        }
    }
    refresh()
}

/** Reject requires a non-blank reason -- see `IRegistrationService.rejectApplication` KDoc, a real
 * modal input rather than a bare confirm, since [confirmDialog] has no input field of its own. */
private fun rejectApplicationDialog(
    applicantName: String,
    onConfirm: (String) -> Unit,
) {
    val modal = Modal(caption = "Antrag von $applicantName ablehnen")
    modal.p("Bitte geben Sie einen Ablehnungsgrund an (wird beim Mitglied gespeichert).")
    val reasonInput = modal.textArea(rows = 3)
    val errorBox =
        modal.div().apply {
            addCssClass("text-danger")
            hide()
        }
    modal.addButton(Button("Abbrechen", style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button("Ablehnen", style = ButtonStyle.DANGER).apply {
            onClick {
                val reason = reasonInput.value.orEmpty().trim()
                if (reason.isBlank()) {
                    errorBox.content = "Bitte einen Grund angeben."
                    errorBox.show()
                    return@onClick
                }
                modal.hide()
                onConfirm(reason)
            }
        },
    )
    modal.show()
}

/**
 * `IMemberService.listMembers()` returns id+displayName only, deliberately -- it is still
 * reachable unauthenticated (the historical "picker" bootstrap endpoint, AKTIV-filtered since
 * V0.7.2), and there is no privileged read RPC for another member's email/role/address. This
 * directly bounds what this directory can show -- see V0.7.3 plan "Open Question 2". A small
 * follow-up wave adding a BOARD/ADMIN-gated detailed read would improve this; not added here
 * without being asked for, to avoid adding new backend surface as a side effect of a UI wave.
 */
private fun renderMemberDirectory(root: SimplePanel) {
    root.h2("Mitgliederverzeichnis")
    root.p(
        "Aktive Mitglieder nach Name. E-Mail/Rolle/Adresse sind hier aus Datenschutzgründen nicht " +
            "einsehbar -- dafür existiert aktuell keine privilegierte Leseschnittstelle.",
    )
    val searchRow = root.hPanel(spacing = 8)
    val searchInput = searchRow.text(label = "Suche nach Name")
    val directoryPanel = root.vPanel(spacing = 2)

    var allMembers: List<MemberSummaryDto> = emptyList()

    fun renderDirectory(filter: String) {
        directoryPanel.removeAll()
        val filtered =
            if (filter.isBlank()) {
                allMembers
            } else {
                allMembers.filter { it.displayName.contains(filter, ignoreCase = true) }
            }
        if (filtered.isEmpty()) {
            directoryPanel.p("Keine Treffer.")
        } else {
            filtered.forEach { directoryPanel.div(it.displayName) { addCssClasses("border-bottom py-1") } }
        }
    }

    val searchButton = searchRow.button("Suchen", style = ButtonStyle.OUTLINESECONDARY)
    searchButton.onClick { renderDirectory(searchInput.value.orEmpty()) }

    AppScope.launch {
        allMembers = guarded { rpcService<IMemberService>().listMembers() } ?: emptyList()
        renderDirectory("")
    }
}

private fun renderDirectMemberCreation(root: SimplePanel) {
    root.h2("Mitglied direkt anlegen")
    root.p(
        "Legt ein Mitglied ohne Antrags-/Freigabeschritt an (z. B. für Beitritte auf Papier oder " +
            "Datenmigration) -- Status sofort AKTIV.",
    )

    val callerRole = AppState.session?.role ?: AccountRole.MEMBER
    val roleOptions = selectableRolesFor(callerRole).map { it.name to it.name }

    val nameInput = root.text(label = "Name")
    val emailInput = root.text(type = InputType.EMAIL, label = "E-Mail")
    val passwordInput = root.password(label = "Vorläufiges Passwort (mind. ${Validation.PASSWORD_MIN_LENGTH} Zeichen)")
    val roleSelect = root.select(options = roleOptions, value = roleOptions.firstOrNull()?.first, label = "Rolle")
    if (roleOptions.size == 1) {
        root.p("Als Vorstand können Sie hier nur reguläre Mitglieder anlegen -- Vorstand/Schatzmeister/Admin ist Admin vorbehalten.")
    }
    val errorBox =
        root.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val createButton = root.button("Mitglied anlegen", style = ButtonStyle.PRIMARY)
    createButton.onClick {
        errorBox.hide()
        val name = nameInput.value.orEmpty().trim()
        val email = emailInput.value.orEmpty().trim()
        val temporaryPassword = passwordInput.value.orEmpty()
        val roleValue = roleSelect.value

        if (!Validation.isNonBlank(name) || !Validation.looksLikeEmail(email) || roleValue == null) {
            errorBox.content = "Bitte Name, eine gültige E-Mail-Adresse und eine Rolle angeben."
            errorBox.show()
            return@onClick
        }
        val passwordHint = Validation.passwordHint(temporaryPassword, email)
        if (passwordHint != null) {
            errorBox.content = passwordHint
            errorBox.show()
            return@onClick
        }

        createButton.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    rpcService<IRegistrationService>().createMemberDirect(
                        AdminCreateMemberInput(
                            displayName = name,
                            email = email,
                            role = AccountRole.valueOf(roleValue),
                            temporaryPassword = temporaryPassword,
                        ),
                    )
                }
            createButton.disabled = false
            if (result != null) {
                notifySuccess("$name wurde angelegt.")
                nameInput.value = null
                emailInput.value = null
                passwordInput.value = null
            }
        }
    }
}
