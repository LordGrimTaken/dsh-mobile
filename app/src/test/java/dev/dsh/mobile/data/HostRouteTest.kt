package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostRouteTest {

    private fun route(id: String, url: String, label: String = id) =
        HostRoute(id = id, label = label, baseUrl = url)

    private fun probe(id: String, ms: Long?, reachable: Boolean = true, error: String? = null) =
        RouteProbe(routeId = id, reachable = reachable, latencyMs = ms, error = error)

    // region selection policy

    @Test
    fun `rank returns reachable routes fastest first`() {
        val routes = listOf(route("lan", "http://10.0.0.2:3543"), route("ts", "https://pc.ts.net"))
        val probes = listOf(probe("lan", 42), probe("ts", 12))
        val ranked = RouteSelector.rank(probes, routes)
        assertEquals(listOf("ts", "lan"), ranked.map { it.routeId })
    }

    @Test
    fun `rank breaks latency ties by configured route order`() {
        val routes = listOf(route("a", "http://a:1"), route("b", "http://b:1"), route("c", "http://c:1"))
        val probes = listOf(probe("c", 20), probe("a", 20), probe("b", 20))
        assertEquals(listOf("a", "b", "c"), RouteSelector.rank(probes, routes).map { it.routeId })
    }

    @Test
    fun `rank ignores unreachable routes and puts unknown latency last`() {
        val routes = listOf(route("a", "http://a:1"), route("b", "http://b:1"), route("c", "http://c:1"))
        val probes = listOf(
            probe("a", null),
            probe("b", 30),
            probe("c", null, reachable = false, error = "timeout"),
        )
        assertEquals(listOf("b", "a"), RouteSelector.rank(probes, routes).map { it.routeId })
    }

    @Test
    fun `best is null when every route failed`() {
        val routes = listOf(route("a", "http://a:1"))
        val probes = listOf(probe("a", null, reachable = false, error = "refused"))
        assertNull(RouteSelector.best(probes, routes))
        assertNull(RouteSelector.bestRouteId(probes, routes))
    }

    @Test
    fun `best chooses the fastest reachable route`() {
        val routes = listOf(route("lan", "http://10.0.0.2:3543"), route("ts", "https://pc.ts.net"))
        val probes = listOf(probe("lan", 80), probe("ts", 15))
        assertEquals("ts", RouteSelector.bestRouteId(probes, routes))
    }

    // endregion

    // region route edits

    @Test
    fun `add validates, normalizes and auto-labels a route`() {
        val existing = RoutePlanner.defaultRoutes("https://desk.example.com")
        val added = RoutePlanner.add(existing, labelInput = "  ", baseUrlInput = "https://pc.tailnet.ts.net/")
        assertTrue(added is RouteAddResult.Added)
        val result = added as RouteAddResult.Added
        assertEquals("https://pc.tailnet.ts.net", result.route.baseUrl)
        assertEquals("Tailscale", result.route.label)
        assertEquals(2, result.routes.size)
        assertEquals(existing.first().id, result.routes.first().id)
    }

    @Test
    fun `add rejects invalid, duplicate and over-limit route lists`() {
        val existing = RoutePlanner.defaultRoutes("https://desk.example.com")
        val invalid = RoutePlanner.add(existing, "", "not a url")
        assertTrue(invalid is RouteAddResult.Invalid)

        val duplicate = RoutePlanner.add(existing, "again", "https://DESK.example.com/")
        assertTrue(duplicate is RouteAddResult.Invalid)
        assertTrue((duplicate as RouteAddResult.Invalid).message.contains("already"))

        var routes = existing
        repeat(RoutePlanner.MAX_ROUTES - 1) { index ->
            val added = RoutePlanner.add(routes, "r$index", "https://host$index.example.com")
            routes = (added as RouteAddResult.Added).routes
        }
        val overLimit = RoutePlanner.add(routes, "", "https://one-too-many.example.com")
        assertTrue(overLimit is RouteAddResult.Invalid)
        assertTrue((overLimit as RouteAddResult.Invalid).message.contains("at most"))
    }

    @Test
    fun `remove keeps at least one route and reports active changes`() {
        val routes = listOf(route("a", "http://a:1"), route("b", "http://b:1"))

        val untouched = RoutePlanner.remove(routes, "a", "b")
        assertTrue(untouched is RouteRemoveResult.Removed)
        assertEquals(false, (untouched as RouteRemoveResult.Removed).activeChanged)
        assertEquals("a", untouched.activeRouteId)

        val activeRemoved = RoutePlanner.remove(routes, "a", "a")
        assertTrue(activeRemoved is RouteRemoveResult.Removed)
        assertTrue((activeRemoved as RouteRemoveResult.Removed).activeChanged)
        assertEquals("b", activeRemoved.activeRouteId)

        val last = RoutePlanner.remove(listOf(route("a", "http://a:1")), "a", "a")
        assertTrue(last is RouteRemoveResult.Invalid)
    }

    @Test
    fun `activate only accepts known routes`() {
        val routes = listOf(route("a", "http://a:1"), route("b", "http://b:1"))
        assertNotNull(RoutePlanner.activate(routes, "b"))
        assertNull(RoutePlanner.activate(routes, "nope"))
    }

    // endregion

    // region profile helpers

    @Test
    fun `ensureRoutes synthesizes a direct route for legacy profiles`() {
        val legacy = HostProfile(
            id = "h",
            label = "Desktop",
            baseUrl = "https://desk.example.com",
            deviceId = "dev",
            token = "token",
            pairedAt = "2026-10-01T00:00:00Z",
            lastUsedAt = "2026-10-02T00:00:00Z",
        )
        val migrated = legacy.ensureRoutes()
        assertEquals(1, migrated.routes.size)
        assertEquals("https://desk.example.com", migrated.routes.first().baseUrl)
        assertEquals(migrated.routes.first().id, migrated.activeRouteId)
        assertEquals("https://desk.example.com", migrated.activeRoute.baseUrl)
        assertEquals(false, migrated.hasMultipleRoutes)
    }

    @Test
    fun `withActiveRoute mirrors the base url of the chosen route`() {
        val profile = HostProfile(
            id = "h",
            label = "Desktop",
            baseUrl = "http://192.168.1.20:3543",
            deviceId = "dev",
            token = "token",
            pairedAt = "2026-10-01T00:00:00Z",
            lastUsedAt = "2026-10-02T00:00:00Z",
            routes = listOf(
                route("lan", "http://192.168.1.20:3543"),
                route("ts", "https://pc.tailnet.ts.net"),
            ),
            activeRouteId = "lan",
        )
        val switched = profile.withActiveRoute("ts")!!
        assertEquals("https://pc.tailnet.ts.net", switched.baseUrl)
        assertEquals("ts", switched.activeRouteId)
        assertNull(profile.withActiveRoute("nope"))
    }

    // endregion
}
