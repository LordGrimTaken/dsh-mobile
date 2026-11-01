package dev.dsh.mobile.data

import dev.dsh.mobile.net.DshJson
import dev.dsh.mobile.net.JobsResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GET /sessions/{id}/jobs contract + the pure background-jobs sheet helpers. */
class JobsSheetTest {

    private fun job(
        id: String,
        status: JobStatus,
        kind: String = "shell",
        label: String = "job $id",
        duration: Long? = 1_500L,
        finished: String? = null,
        started: String? = "2026-11-01T10:00:00Z",
    ) = JobEntry(
        id = id,
        kindLabel = jobKindLabel(kind),
        label = sanitizedJobLabel(label),
        status = status,
        startedAt = started,
        finishedAt = finished,
        durationMs = duration,
        detail = null,
    )

    @Test
    fun `jobs response decodes supported counts and failure detail`() {
        val response = DshJson.decodeFromString(
            JobsResponse.serializer(),
            """{"supported":true,
               "counts":{"running":1,"completed":2,"failed":1},
               "jobs":[{"id":"j1","kind":"shell","label":"npm test","status":"running",
                        "startedAt":"2026-11-01T10:00:00Z","durationMs":1200},
                       {"id":"j2","kind":"python","label":"build","status":"failed",
                        "finishedAt":"2026-11-01T10:01:00Z","durationMs":5000,
                        "detail":"exit code 1"}]}""",
        )
        assertTrue(response.supported)
        assertEquals(1, response.counts?.running)
        assertEquals(2, response.counts?.completed)
        assertEquals(2, response.jobs.size)
        assertEquals("exit code 1", response.jobs[1].detail)
        assertEquals(5000L, response.jobs[1].durationMs)
    }

    @Test
    fun `old hosts and missing counts default safely`() {
        val unsupported = DshJson.decodeFromString(
            JobsResponse.serializer(),
            """{"supported":false}""",
        )
        assertFalse(unsupported.supported)
        assertNull(unsupported.counts)
        assertTrue(unsupported.jobs.isEmpty())

        val defaults = DshJson.decodeFromString(JobsResponse.serializer(), """{}""")
        assertFalse(defaults.supported)
        assertNull(defaults.counts)
        assertEquals(0, defaults.jobs.size)
    }

    @Test
    fun `statuses normalize and only running is active`() {
        assertEquals(JobStatus.Running, normalizedJobStatus("running"))
        assertEquals(JobStatus.Running, normalizedJobStatus("in_progress"))
        assertEquals(JobStatus.Running, normalizedJobStatus("started"))
        assertEquals(JobStatus.Pending, normalizedJobStatus("queued"))
        assertEquals(JobStatus.Completed, normalizedJobStatus("done"))
        assertEquals(JobStatus.Completed, normalizedJobStatus("succeeded"))
        assertEquals(JobStatus.Failed, normalizedJobStatus("error"))
        assertEquals(JobStatus.Failed, normalizedJobStatus("timeout"))
        assertEquals(JobStatus.Stopped, normalizedJobStatus("stopped"))
        assertEquals(JobStatus.Stopped, normalizedJobStatus("cancelled"))
        assertEquals(JobStatus.Stopped, normalizedJobStatus("canceled"))
        assertEquals(JobStatus.Stopped, normalizedJobStatus("KILLED"))
        assertEquals("Running", JobStatus.Running.label)
        assertEquals("Stopped", JobStatus.Stopped.label)
        assertEquals("Failed", JobStatus.Failed.label)
        assertTrue(JobStatus.Running.active)
        assertFalse(JobStatus.Pending.active)
        assertFalse(JobStatus.Completed.active)
        assertFalse(JobStatus.Failed.active)
        assertFalse(JobStatus.Stopped.active)
    }

    @Test
    fun `J1 status matrix keeps settled and unknown statuses inactive`() {
        val matrix = listOf(
            "completed",
            "failed",
            "stopped",
            "cancelled",
            "canceled",
            "killed",
            "unknown",
            "mystery",
        )
        matrix.forEach { raw ->
            val status = normalizedJobStatus(raw)
            assertFalse("$raw must not be active", status.active)
        }
        "running".let { assertTrue(normalizedJobStatus(it).active) }
        // Host-provided counts are authoritative and survive clamping; a stopped job must not bump
        // the running hint or change completed/failed counts.
        val jobs = matrix.mapIndexed { index, raw ->
            job("j$index", normalizedJobStatus(raw), kind = "shell")
        }
        val hostCounts = JobCounts(running = 0, completed = 5, failed = 1)
        assertEquals(hostCounts, clampedJobCounts(hostCounts, jobs))
        assertEquals(0, clampedJobCounts(hostCounts, jobs).running)
        assertNull(jobRunningHint(clampedJobCounts(hostCounts, jobs)))
        // Without host counts the client derives: stopped/unknown terminals count as completed.
        val derived = clampedJobCounts(null, jobs)
        assertEquals(0, derived.running)
        assertEquals(1, derived.failed)
        assertEquals(7, derived.completed)
        assertNull(jobRunningHint(derived))
        // A pending row is not active either.
        val pending = job("p", JobStatus.Pending)
        assertFalse(pending.status.active)
        assertEquals(0, clampedJobCounts(null, listOf(pending)).running)
    }

    @Test
    fun `kind labels friendly bounded and never blank`() {
        assertEquals("Shell", jobKindLabel("bash"))
        assertEquals("Shell", jobKindLabel("exec"))
        assertEquals("Python", jobKindLabel("python"))
        assertEquals("Node", jobKindLabel("nodejs"))
        assertEquals("Browser", jobKindLabel("playwright"))
        assertEquals("HTTP", jobKindLabel("curl"))
        assertEquals("Custom kind", jobKindLabel("custom_kind"))
        assertEquals("Weird kind", jobKindLabel("weird-kind"))
        assertEquals("Job", jobKindLabel(null))
        assertEquals("Job", jobKindLabel("  "))
        assertEquals(40, jobKindLabel("x".repeat(100)).length)
    }

    @Test
    fun `labels and details sanitize cap and redact credentials`() {
        assertEquals("npm test", sanitizedJobLabel("  npm\ntest  "))
        assertEquals("(untitled job)", sanitizedJobLabel("   "))
        assertEquals(JOBS_MAX_LABEL_CHARS, sanitizedJobLabel("x".repeat(500)).length)
        assertEquals("exit code 1", sanitizedJobDetail(" exit\ncode 1 "))
        assertNull(sanitizedJobDetail("   "))
        val redacted = sanitizedJobDetail("failed with api_key=supersecret123")!!
        assertFalse(redacted.contains("supersecret123"))
        assertTrue(redacted.contains("<redacted>"))
        assertEquals(JOBS_MAX_DETAIL_CHARS, sanitizedJobDetail("d".repeat(900))!!.length)
        assertNull(jobFailureDetail(job("j", JobStatus.Completed)))
        assertNull(jobFailureDetail(job("j", JobStatus.Failed)))
        val failed = job("f", JobStatus.Failed).copy(detail = "boom")
        assertEquals("boom", jobFailureDetail(failed))
    }

    @Test
    fun `durations format deterministically`() {
        assertEquals("", formatJobDuration(null))
        assertEquals("", formatJobDuration(-1))
        assertEquals("0 ms", formatJobDuration(0))
        assertEquals("999 ms", formatJobDuration(999))
        assertEquals("1.2 s", formatJobDuration(1_200))
        assertEquals("1.5 s", formatJobDuration(1_500))
        assertEquals("1m 05s", formatJobDuration(65_000))
        assertEquals("1h 05m", formatJobDuration(3_900_000))
        assertEquals("2026-11-01T10:00:00Z", jobStartedLabel(" 2026-11-01T10:00:00Z "))
        assertEquals("", jobStartedLabel(null))
        assertEquals(32, jobStartedLabel("t".repeat(100)).length)
    }

    @Test
    fun `running jobs come first then finished newest first`() {
        val jobs = listOf(
            job("done-old", JobStatus.Completed, finished = "2026-11-01T09:00:00Z"),
            job("failed", JobStatus.Failed, finished = "2026-11-01T10:30:00Z"),
            job("run", JobStatus.Running, started = "2026-11-01T10:00:00Z"),
            job("done-new", JobStatus.Completed, finished = "2026-11-01T11:00:00Z"),
            job("pending", JobStatus.Pending, started = "2026-11-01T10:10:00Z"),
        )
        val ordered = orderedJobs(jobs)
        assertEquals(
            listOf("run", "pending", "done-new", "failed", "done-old"),
            ordered.map { it.id },
        )
    }

    @Test
    fun `counts clamp and derive from the listed jobs`() {
        val jobs = listOf(
            job("a", JobStatus.Running),
            job("b", JobStatus.Completed),
            job("c", JobStatus.Failed),
            job("d", JobStatus.Completed),
        )
        assertEquals(JobCounts(1, 2, 1), clampedJobCounts(null, jobs))
        val clamped = clampedJobCounts(JobCounts(-1, 99, -2), jobs)
        assertEquals(0, clamped.running)
        assertEquals(99, clamped.completed)
        assertEquals(0, clamped.failed)
        assertEquals(99, clamped.total)
    }

    @Test
    fun `running hint action description and counts summary`() {
        val counts = JobCounts(running = 2, completed = 5, failed = 1)
        assertEquals("2 running", jobRunningHint(counts))
        assertEquals("Open jobs, 2 running", jobsActionContentDescription(counts))
        assertNull(jobRunningHint(JobCounts(0, 5, 1)))
        assertEquals("Open jobs", jobsActionContentDescription(JobCounts(0, 5, 1)))
        assertEquals("Open jobs", jobsActionContentDescription(null))
        assertEquals(
            "Running 2 · Completed 5 · Failed 1",
            jobCountsSummary(counts),
        )
    }

    @Test
    fun `A1 jobs cache respects the thirty second TTL`() {
        val cached = HostJobs.Empty
        val now = 1_800_000_000_000L
        assertFalse(shouldLoadSessionJobs(cached, false, false, fetchedAtMs = now - 1_000, nowMs = now))
        assertTrue(shouldLoadSessionJobs(cached, false, true, fetchedAtMs = now - 1_000, nowMs = now))
        assertTrue(
            shouldLoadSessionJobs(
                cached,
                false,
                false,
                fetchedAtMs = now - JOBS_CACHE_TTL_MS,
                nowMs = now,
            ),
        )
        assertFalse(shouldLoadSessionJobs(cached, true, true, fetchedAtMs = now, nowMs = now))
        assertTrue(shouldLoadSessionJobs(null, false, false, nowMs = now))
        assertEquals(30_000L, JOBS_CACHE_TTL_MS)
    }

    @Test
    fun `copy models and read-only guarantee are distinct`() {
        assertEquals("Jobs", JOBS_ACTION_LABEL)
        assertEquals("Jobs", JOBS_SHEET_TITLE)
        assertEquals("Read-only view of background jobs.", JOBS_READ_ONLY_NOTE)
        assertEquals("No background jobs yet.", JOBS_EMPTY_COPY)
        assertEquals("This host does not expose jobs.", JOBS_UNSUPPORTED_COPY)
        assertEquals("Couldn't load jobs.", JOBS_LOAD_FAILED_COPY)
        assertEquals("Loading jobs…", JOBS_LOADING_COPY)
        assertEquals("Retry", JOBS_RETRY_LABEL)
        assertEquals("Refresh", JOBS_REFRESH_LABEL)
        assertFalse(HostJobs.Unsupported.supported)
        assertTrue(HostJobs.Empty.supported)
        // Client display caps are documented constants (J9/J18).
        assertEquals(160, JOBS_MAX_LABEL_CHARS)
        assertEquals(400, JOBS_MAX_DETAIL_CHARS)
    }
}
