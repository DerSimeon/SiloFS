package app.silofs.server

import java.util.concurrent.atomic.AtomicReference

/**
 * Failpoint hook for crash-injection testing (gap #8).
 *
 * When the `S3_FAILPOINT` environment variable matches [point], the server
 * crashes immediately via `Runtime.halt(1)`. This allows integration tests
 * to kill the server at controlled failure points (after temp write, after
 * fsync, after rename, before response, etc.) and verify durability.
 *
 * In production, `S3_FAILPOINT` is never set, so all calls are no-ops.
 *
 * The failpoint can also be set at runtime via [set], which is useful for
 * tests that want to arm the failpoint only for a specific request.
 */
object Failpoint {

    private val runtimeOverride = AtomicReference<String?>(null)

    /**
     * Crash the process if the configured failpoint matches [point].
     * The env var is read on every call so tests can set it between requests.
     */
    fun crashIf(point: String) {
        val configured = runtimeOverride.get() ?: System.getenv("S3_FAILPOINT") ?: return
        if (configured == point) {
            // Use Runtime.halt instead of System.exit so shutdown hooks don't
            // run — we want an immediate, ungraceful death to simulate a crash.
            Runtime.getRuntime().halt(1)
        }
    }

    /** Set a runtime failpoint override (for testing). */
    fun set(point: String?) {
        runtimeOverride.set(point)
    }
}
