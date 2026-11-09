package dev.dsh.mobile.data

import java.util.UUID

/**
 * One reachable endpoint for a paired host. A single host often has several routes to the same
 * machine: LAN, Tailscale Serve, a named Cloudflare tunnel, or any custom URL. All routes share
 * the host's device token; only the base URL differs.
 */
data class HostRoute(
    val id: String,
    val label: String,
    val baseUrl: String,
    val kind: String = KIND_CUSTOM,
) {
    companion object {
        const val KIND_LAN = "lan"
        const val KIND_TAILSCALE = "tailscale"
        const val KIND_CLOUDFLARE = "cloudflare"
        const val KIND_CUSTOM = "custom"

        val KINDS = listOf(KIND_LAN, KIND_TAILSCALE, KIND_CLOUDFLARE, KIND_CUSTOM)

        fun newId(): String = "route-" + UUID.randomUUID().toString()

        fun defaultFor(baseUrl: String, label: String = ""): HostRoute = HostRoute(
            id = "route-default",
            label = label.ifBlank { "Direct" },
            baseUrl = baseUrl,
            kind = KIND_CUSTOM,
        )

        fun labelForKind(kind: String): String = when (kind) {
            KIND_LAN -> "LAN"
            KIND_TAILSCALE -> "Tailscale"
            KIND_CLOUDFLARE -> "Cloudflare"
            else -> "Custom"
        }

        /** Best-effort kind from a URL so the add form can guess a sensible label. */
        fun kindForUrl(baseUrl: String): String {
            val lower = baseUrl.lowercase()
            return when {
                lower.contains(".ts.net") -> KIND_TAILSCALE
                lower.contains("cloudflare") || lower.contains("trycloudflare") -> KIND_CLOUDFLARE
                lower.startsWith("http://10.") ||
                    lower.startsWith("http://192.168.") ||
                    lower.startsWith("http://172.") -> KIND_LAN
                else -> KIND_CUSTOM
            }
        }
    }
}

/** One probe outcome for a route (produced by the connection manager, rendered by the UI). */
data class RouteProbe(
    val routeId: String,
    val reachable: Boolean,
    val latencyMs: Long? = null,
    val error: String? = null,
    val hostVersion: String? = null,
) {
    /** "12 ms" / "unreachable" / "not probed" display text. */
    fun summary(): String = when {
        reachable && latencyMs != null -> "reachable · ${latencyMs}ms"
        reachable -> "reachable"
        error != null -> "unreachable · ${error.take(60)}"
        else -> "unreachable"
    }
}

/**
 * Pure route-selection policy for the connection manager. Runtime probing lives in AppState;
 * ordering/tie-breaking rules live here so they can be unit tested.
 */
object RouteSelector {
    /** Reachable probes ordered fastest first; ties keep the host's configured route order. */
    fun rank(probes: List<RouteProbe>, routes: List<HostRoute>): List<RouteProbe> {
        val configuredOrder = routes.mapIndexed { index, route -> route.id to index }.toMap()
        return probes
            .filter { it.reachable }
            .sortedWith(
                compareBy(
                    { it.latencyMs == null },
                    { it.latencyMs ?: Long.MAX_VALUE },
                    { configuredOrder[it.routeId] ?: Int.MAX_VALUE },
                ),
            )
    }

    /** Fastest reachable probe, or null when every route failed. */
    fun best(probes: List<RouteProbe>, routes: List<HostRoute>): RouteProbe? =
        rank(probes, routes).firstOrNull()

    /** Route id that should be active after a probe round, or null when nothing is reachable. */
    fun bestRouteId(probes: List<RouteProbe>, routes: List<HostRoute>): String? =
        best(probes, routes)?.routeId
}

/** Result of validating/adding a route through the pure planner. */
sealed interface RouteAddResult {
    data class Added(val route: HostRoute, val routes: List<HostRoute>) : RouteAddResult
    data class Invalid(val message: String) : RouteAddResult
}

/** Result of removing a route. */
sealed interface RouteRemoveResult {
    data class Removed(
        val routes: List<HostRoute>,
        val activeRouteId: String,
        val activeChanged: Boolean,
    ) : RouteRemoveResult

    data class Invalid(val message: String) : RouteRemoveResult
}

/**
 * Pure edit operations on a host's route list. AppState persists the results and rebuilds the
 * live connection; keeping validation here makes it unit-testable without Android.
 */
object RoutePlanner {

    fun add(
        routes: List<HostRoute>,
        labelInput: String,
        baseUrlInput: String,
        kind: String = HostRoute.KIND_CUSTOM,
    ): RouteAddResult {
        val baseUrl = when (val parsed = HostUrl.parse(baseUrlInput)) {
            is HostUrlResult.Valid -> parsed.root
            is HostUrlResult.Invalid -> return RouteAddResult.Invalid(parsed.message)
        }
        if (routes.any { it.baseUrl.equals(baseUrl, ignoreCase = true) }) {
            return RouteAddResult.Invalid("That address is already a route for this host.")
        }
        if (routes.size >= MAX_ROUTES) {
            return RouteAddResult.Invalid("A host can have at most $MAX_ROUTES routes.")
        }
        // An explicit kind wins; the default "custom" lets the URL pattern pick a better label.
        val explicitKind = kind.takeIf { it in HostRoute.KINDS && it != HostRoute.KIND_CUSTOM }
        val effectiveKind = explicitKind ?: HostRoute.kindForUrl(baseUrl)
        val label = labelInput.trim()
            .ifBlank { HostRoute.labelForKind(effectiveKind) }
            .take(40)
        val route = HostRoute(
            id = HostRoute.newId(),
            label = label,
            baseUrl = baseUrl,
            kind = effectiveKind,
        )
        return RouteAddResult.Added(route = route, routes = routes + route)
    }

    fun remove(
        routes: List<HostRoute>,
        activeRouteId: String?,
        routeId: String,
    ): RouteRemoveResult {
        if (routes.none { it.id == routeId }) {
            return RouteRemoveResult.Invalid("That route is no longer saved.")
        }
        if (routes.size <= 1) {
            return RouteRemoveResult.Invalid("A host needs at least one route.")
        }
        val remaining = routes.filterNot { it.id == routeId }
        val previousActive = activeRouteId ?: routes.first().id
        val active = remaining.firstOrNull { it.id == previousActive } ?: remaining.first()
        return RouteRemoveResult.Removed(
            routes = remaining,
            activeRouteId = active.id,
            activeChanged = active.id != previousActive,
        )
    }

    /** Moves the active route; returns the route list's active id (falls back to the first). */
    fun activate(routes: List<HostRoute>, routeId: String): Pair<List<HostRoute>, String>? {
        if (routes.none { it.id == routeId }) return null
        return routes to routeId
    }

    /** Configured-order default route from a legacy profile (single base URL). */
    fun defaultRoutes(baseUrl: String, label: String = ""): List<HostRoute> =
        listOf(HostRoute.defaultFor(baseUrl, label))

    const val MAX_ROUTES = 8
}

/** The route currently used for network calls. Falls back to the first configured route. */
val HostProfile.activeRoute: HostRoute
    get() = routes.firstOrNull { it.id == activeRouteId }
        ?: routes.firstOrNull()
        ?: HostRoute.defaultFor(baseUrl, label)

/** True when the profile has (or can synthesize) more than one route. */
val HostProfile.hasMultipleRoutes: Boolean
    get() = routes.size > 1

/**
 * Puts the route list in a valid shape: at least one route, a valid active id, and the mirrored
 * [HostProfile.baseUrl] pointing at the active route (all existing code paths keep using baseUrl).
 */
fun HostProfile.ensureRoutes(): HostProfile {
    val safeRoutes = routes.ifEmpty { RoutePlanner.defaultRoutes(baseUrl, label) }
    val active = safeRoutes.firstOrNull { it.id == activeRouteId } ?: safeRoutes.first()
    return copy(
        routes = safeRoutes,
        activeRouteId = active.id,
        baseUrl = active.baseUrl,
    )
}

/** Profile with a different active route (base URL mirrors the active route). */
fun HostProfile.withActiveRoute(routeId: String): HostProfile? {
    val safeRoutes = routes.ifEmpty { RoutePlanner.defaultRoutes(baseUrl, label) }
    val target = safeRoutes.firstOrNull { it.id == routeId } ?: return null
    return copy(
        routes = safeRoutes,
        activeRouteId = target.id,
        baseUrl = target.baseUrl,
    )
}
