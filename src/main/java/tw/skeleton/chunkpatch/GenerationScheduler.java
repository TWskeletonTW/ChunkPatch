package tw.skeleton.chunkpatch;

/**
 * Single-in-flight, future-driven backpressure scheduler for FEATURES chunk
 * generation. At most one generation request is ever outstanding; a new
 * target is only submitted once the previous one's completion callback has
 * run. This replaces synchronous per-tick {@code getChunk()} calls (which
 * block the server thread and can cascade into generating a large
 * neighborhood of chunks in one call) with non-blocking future submission, so
 * backpressure happens before an expensive worldgen burst starts rather than
 * as a cooldown after it already happened. See ChunkPatch_REFACTOR_PLAN.md.
 *
 * This class holds no reference to any Minecraft server/world object and
 * performs no I/O; it only tracks cursor position, in-flight state, and a
 * session token, and delegates actual generation requests to a
 * {@link ChunkGenerationGateway}. That makes it directly unit-testable with a
 * fake gateway.
 *
 * Not internally synchronized: callers (ChunkFillController) are already
 * synchronized and must only drive this from their own owning thread.
 */
final class GenerationScheduler {
	/** Invoked for scheduler-driven side effects; all callbacks are expected to run on the owning thread. */
	interface Listener {
		/**
		 * Marshals a completion callback onto the thread that owns generation
		 * state (the server thread in production; direct/inline execution in
		 * tests, since a fake gateway completes futures synchronously).
		 */
		void marshalToOwnerThread(Runnable task);

		/** A target finished generating successfully. */
		void onTargetCompleted(int x, int z, boolean wasPartial);

		/** A target's generation future completed exceptionally. */
		void onTargetFailed(int x, int z, Throwable error);

		/** The cursor walked past the end of the selection with nothing left in flight. */
		void onExhausted();
	}

	private final ChunkGenerationGateway gateway;
	private final ChunkScanResult scan;
	private final ChunkBounds bounds;
	private final Listener listener;

	private long sessionId;
	private Target inFlight;
	private int cursorX;
	private int cursorZ;

	GenerationScheduler(ChunkGenerationGateway gateway, ChunkScanResult scan, ChunkBounds bounds, Listener listener) {
		this.gateway = gateway;
		this.scan = scan;
		this.bounds = bounds;
		this.listener = listener;
		this.cursorX = bounds.minX();
		this.cursorZ = bounds.minZ();
	}

	long sessionId() {
		return sessionId;
	}

	boolean hasInFlight() {
		return inFlight != null;
	}

	int cursorX() {
		return cursorX;
	}

	int cursorZ() {
		return cursorZ;
	}

	/**
	 * Bumps the session token and clears in-flight tracking, so any
	 * completion callback still arriving from the previous session is
	 * ignored. Used when stopping, when the dimension disappears, or when the
	 * server is shutting down. Does not cancel the underlying Minecraft
	 * worldgen future (that future is allowed to finish naturally; only its
	 * effect on ChunkPatch state is suppressed).
	 */
	void invalidateSession() {
		sessionId++;
		inFlight = null;
	}

	/**
	 * Looks for and submits the next eligible target, unless one is already
	 * in flight. Walks at most {@code maxLookups} candidate coordinates
	 * looking for a chunk that is neither already map-ready nor corrupt, so a
	 * single tick spent skipping long runs of already-done chunks stays
	 * bounded. Returns true once the cursor has walked past the selection
	 * with nothing left in flight (the caller should treat this as
	 * "finished"); {@link Listener#onExhausted()} is invoked in that case.
	 */
	boolean tick(int maxLookups) {
		if (inFlight != null) return false;
		int lookups = 0;
		while (lookups++ < maxLookups) {
			if (cursorZ > bounds.maxZ()) {
				listener.onExhausted();
				return true;
			}
			int x = cursorX;
			int z = cursorZ;
			advanceCursor();
			if (scan.isMapReady(x, z) || scan.isCorrupt(x, z)) continue;
			submit(x, z);
			return false;
		}
		return false;
	}

	private void submit(int x, int z) {
		boolean wasPartial = scan.isPartial(x, z);
		long submittedSession = sessionId;
		inFlight = new Target(x, z);
		gateway.requestFeatures(x, z).whenComplete((ignored, error) ->
			listener.marshalToOwnerThread(() -> handleCompletion(submittedSession, x, z, wasPartial, error))
		);
	}

	private void handleCompletion(long submittedSession, int x, int z, boolean wasPartial, Throwable error) {
		// Stale: the session was reset (stop, dimension change, server
		// shutdown) while this request was in flight. Minecraft's own future
		// is allowed to complete naturally; we just must not act on it.
		if (submittedSession != sessionId) return;
		if (inFlight == null || inFlight.x != x || inFlight.z != z) return;
		inFlight = null;
		if (error != null) {
			listener.onTargetFailed(x, z, error);
			return;
		}
		scan.markMapReady(x, z);
		listener.onTargetCompleted(x, z, wasPartial);
	}

	private void advanceCursor() {
		if (cursorX >= bounds.maxX()) {
			cursorX = bounds.minX();
			cursorZ++;
		} else {
			cursorX++;
		}
	}

	private record Target(int x, int z) {
	}
}
