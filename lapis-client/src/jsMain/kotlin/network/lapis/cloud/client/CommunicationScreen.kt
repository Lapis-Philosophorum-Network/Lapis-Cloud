package network.lapis.cloud.client

import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.rpc.IDirectMessageService
import network.lapis.cloud.shared.rpc.IMailingService

/**
 * Carries forward the Mailinglisten/Postfach functionality the pre-V0.7.3 demo already exercised
 * (`listMailingLists`/`subscribe`/`unsubscribe`/`unreadCount`) -- exactly the same calls, just
 * re-hosted under real session auth instead of the removed "acting as" switcher. No new capability
 * (no compose/send UI) -- see V0.7.3 plan "Open Question 3": dropping this screen entirely would be
 * a silent functional regression versus what was reachable in the demo before this wave, so it is
 * carried forward as-is rather than either expanded or removed.
 */
fun renderCommunicationScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 640.px
            marginTop = 24.px
        }
    root.h1("Kommunikation")

    renderMailingLists(root)
    renderInbox(root)
}

private fun renderMailingLists(root: SimplePanel) {
    root.h2("Mailinglisten")
    val panel = root.vPanel(spacing = 4)

    fun refresh() {
        panel.removeAll()
        AppScope.launch {
            val lists = guarded { rpcService<IMailingService>().listMailingLists() } ?: return@launch
            if (lists.isEmpty()) {
                panel.p("Noch keine Mailinglisten.")
                return@launch
            }
            lists.forEach { list ->
                val row = panel.hPanel(spacing = 8) { addCssClass("align-items-center") }
                row.div("${list.name} (${list.subscriberCount} Abonnenten)") { addCssClass("flex-grow-1") }
                val toggleButton = row.button(if (list.isSubscribedByCurrentMember) "Abbestellen" else "Abonnieren")
                toggleButton.onClick {
                    AppScope.launch {
                        val result =
                            guarded {
                                if (list.isSubscribedByCurrentMember) {
                                    rpcService<IMailingService>().unsubscribe(list.id)
                                } else {
                                    rpcService<IMailingService>().subscribe(list.id)
                                }
                            }
                        if (result != null) refresh()
                    }
                }
            }
        }
    }
    refresh()
}

private fun renderInbox(root: SimplePanel) {
    root.h2("Postfach")
    val panel = root.vPanel(spacing = 4)
    AppScope.launch {
        val unread = guarded { rpcService<IDirectMessageService>().unreadCount() } ?: return@launch
        panel.div("Ungelesene Nachrichten: $unread")
    }
}
