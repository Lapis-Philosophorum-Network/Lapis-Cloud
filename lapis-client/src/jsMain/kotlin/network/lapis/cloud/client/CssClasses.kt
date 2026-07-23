package network.lapis.cloud.client

import io.kvision.core.Widget

/**
 * Round-2-review fix (2026-07-23): [Widget.addCssClass] adds its single `String` argument as one
 * literal CSS class -- KVision hands the whole `classes` set straight to snabbdom's `class` module,
 * which calls the real DOM's `Element.classList.add(token)` once per set entry. `classList.add`
 * throws `InvalidCharacterError` ("contains HTML space characters") if a single token itself
 * contains a space, per the `DOMTokenList` spec -- it is not a shorthand for "add every
 * space-separated class in this string" the way setting `element.className` directly would be.
 *
 * Nine call sites across this wave's screens (`DashboardScreen.kt`, `ContributionsScreen.kt`,
 * `DocumentsScreen.kt`, `MemberAdministrationScreen.kt`, `RegistrationScreen.kt`) passed a
 * space-separated Bootstrap utility-class string (e.g. `"btn btn-outline-primary text-start"`)
 * to a single `addCssClass(...)` call, exactly this mistake. Concretely observed impact for
 * `DashboardScreen.navTile`'s `"btn btn-outline-primary text-start"` call: the exception is thrown
 * synchronously from inside KVision's own render/patch cycle, which is invoked from Navigo's
 * `callHandler` route-resolution step with no surrounding `try`/`catch` anywhere in that call
 * chain (KVision, Navigo, and this app's own pre-fix `Routing.show()` alike) -- so the exception
 * unwound the entire route handler silently: everything in `renderDashboardScreen` after the
 * first broken `navTile()` call (the remaining nav tiles, the "Konto" heading, the
 * change-password form, and the logout/Austritt buttons) never executed, with nothing logged to
 * the console. The very same exception, thrown while running inside `callHandler` at exactly the
 * line before `context.instance.updatePageLinks()` (Navigo's own re-scan of `[data-navigo]`
 * elements after every successful route handler -- see `navigo/lib/navigo.js`), also explains why
 * the top navbar's own links looked unclickable after landing on a broken screen: the re-scan that
 * would have hooked them into Navigo's click-hijacking never ran, because the handler above it on
 * the same call stack had already thrown.
 *
 * Use this instead of [Widget.addCssClass] for any multi-class Bootstrap utility string; keep
 * using plain [Widget.addCssClass] directly for a genuine single class name (most call sites in
 * this codebase, e.g. `addCssClass("mx-auto")`, `addCssClass("text-danger")`, already do this
 * correctly and are left untouched).
 */
fun Widget.addCssClasses(css: String) {
    css
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .forEach { addCssClass(it) }
}
