package tw.skeleton.chunkpatch;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the single-in-flight invariant that pause/stop draining and
 * stale-callback safety are both built on: a real ChunkFillController/pause
 * test would need a running Minecraft server, but as long as the scheduler
 * never allows a second submission before the first completes, "stop calling
 * tick() while one is outstanding" is sufficient for the controller to drain
 * safely — there is nothing left for it to accidentally start.
 */
class ChunkGenerationSchedulerTest {
	private static ChunkScanResult scanWithPartial(ChunkBounds bounds, LongOpenHashSet partial, LongOpenHashSet corrupt) {
		return new ChunkScanResult(
			Path.of("test-world"),
			"minecraft:overworld",
			new LongOpenHashSet(),
			new LongOpenHashSet(),
			partial,
			corrupt,
			bounds,
			0,
			0,
			0,
			0
		);
	}

	private static final class RecordingListener implements GenerationScheduler.Listener {
		final List<String> events = new ArrayList<>();
		boolean exhausted;

		@Override
		public void marshalToOwnerThread(Runnable task) {
			task.run(); // tests run single-threaded; act as an inline "server thread"
		}

		@Override
		public void onTargetCompleted(int x, int z, boolean wasPartial) {
			events.add("completed:" + x + "," + z + ":" + wasPartial);
		}

		@Override
		public void onTargetFailed(int x, int z, Throwable error) {
			events.add("failed:" + x + "," + z);
		}

		@Override
		public void onExhausted() {
			exhausted = true;
		}
	}

	@Test
	void neverAllowsASecondSubmissionBeforeTheFirstCompletes() {
		ChunkBounds bounds = new ChunkBounds(0, 3, 0, 3);
		FakeChunkGenerationGateway gateway = new FakeChunkGenerationGateway();
		RecordingListener listener = new RecordingListener();
		GenerationScheduler scheduler = new GenerationScheduler(gateway, scanWithPartial(bounds, new LongOpenHashSet(), new LongOpenHashSet()), bounds, listener);

		scheduler.tick(1000);
		assertTrue(scheduler.hasInFlight());
		assertEquals(1, gateway.requestCount());

		for (int i = 0; i < 100; i++) {
			scheduler.tick(1000);
		}
		assertEquals(1, gateway.requestCount());
	}

	@Test
	void submitsTheNextTargetOnlyAfterTheCurrentOneCompletes() {
		ChunkBounds bounds = new ChunkBounds(0, 3, 0, 3);
		FakeChunkGenerationGateway gateway = new FakeChunkGenerationGateway();
		RecordingListener listener = new RecordingListener();
		GenerationScheduler scheduler = new GenerationScheduler(gateway, scanWithPartial(bounds, new LongOpenHashSet(), new LongOpenHashSet()), bounds, listener);

		scheduler.tick(1000);
		assertEquals(1, gateway.requestCount());
		assertTrue(gateway.hasPending(0, 0));

		gateway.complete(0, 0);
		assertFalse(scheduler.hasInFlight());
		assertEquals(List.of("completed:0,0:false"), listener.events);

		scheduler.tick(1000);
		assertEquals(2, gateway.requestCount());
		assertTrue(gateway.hasPending(1, 0));
	}

	@Test
	void futureFailureClearsInFlightWithoutResubmitting() {
		ChunkBounds bounds = new ChunkBounds(0, 3, 0, 3);
		FakeChunkGenerationGateway gateway = new FakeChunkGenerationGateway();
		RecordingListener listener = new RecordingListener();
		GenerationScheduler scheduler = new GenerationScheduler(gateway, scanWithPartial(bounds, new LongOpenHashSet(), new LongOpenHashSet()), bounds, listener);

		scheduler.tick(1000);
		gateway.fail(0, 0, new IllegalStateException("boom"));

		assertFalse(scheduler.hasInFlight());
		assertEquals(List.of("failed:0,0"), listener.events);
	}

	@Test
	void staleCallbackAfterSessionInvalidationDoesNotMutateState() {
		ChunkBounds bounds = new ChunkBounds(0, 3, 0, 3);
		FakeChunkGenerationGateway gateway = new FakeChunkGenerationGateway();
		RecordingListener listener = new RecordingListener();
		GenerationScheduler scheduler = new GenerationScheduler(gateway, scanWithPartial(bounds, new LongOpenHashSet(), new LongOpenHashSet()), bounds, listener);

		scheduler.tick(1000);
		assertTrue(gateway.hasPending(0, 0));

		// Simulate stop()/dimension-loss/server-shutdown while the request is
		// still outstanding: the session is invalidated, but Minecraft's own
		// future is left to complete naturally.
		scheduler.invalidateSession();
		assertFalse(scheduler.hasInFlight());

		gateway.complete(0, 0);

		assertTrue(listener.events.isEmpty(), "a stale completion must not fire listener callbacks");
	}

	@Test
	void partialChunkIsPromotedOnCompletion() {
		ChunkBounds bounds = new ChunkBounds(0, 0, 0, 0);
		LongOpenHashSet partial = new LongOpenHashSet();
		partial.add(ChunkPos.asLong(0, 0));
		FakeChunkGenerationGateway gateway = new FakeChunkGenerationGateway();
		RecordingListener listener = new RecordingListener();
		ChunkScanResult scan = scanWithPartial(bounds, partial, new LongOpenHashSet());
		GenerationScheduler scheduler = new GenerationScheduler(gateway, scan, bounds, listener);

		scheduler.tick(1000);
		gateway.complete(0, 0);

		assertEquals(List.of("completed:0,0:true"), listener.events);
		assertTrue(scan.isMapReady(0, 0));
		assertFalse(scan.isPartial(0, 0));
	}

	@Test
	void corruptChunksAreNeverSubmitted() {
		ChunkBounds bounds = new ChunkBounds(0, 0, 0, 0);
		LongOpenHashSet corrupt = new LongOpenHashSet();
		corrupt.add(ChunkPos.asLong(0, 0));
		FakeChunkGenerationGateway gateway = new FakeChunkGenerationGateway();
		RecordingListener listener = new RecordingListener();
		GenerationScheduler scheduler = new GenerationScheduler(gateway, scanWithPartial(bounds, new LongOpenHashSet(), corrupt), bounds, listener);

		boolean finished = scheduler.tick(1000);

		assertTrue(finished);
		assertTrue(listener.exhausted);
		assertEquals(0, gateway.requestCount());
	}

	@Test
	void existingMapReadyChunksAreNeverSubmitted() {
		ChunkBounds bounds = new ChunkBounds(0, 0, 0, 0);
		FakeChunkGenerationGateway gateway = new FakeChunkGenerationGateway();
		RecordingListener listener = new RecordingListener();
		ChunkScanResult scan = scanWithPartial(bounds, new LongOpenHashSet(), new LongOpenHashSet());
		scan.markMapReady(0, 0);
		GenerationScheduler scheduler = new GenerationScheduler(gateway, scan, bounds, listener);

		boolean finished = scheduler.tick(1000);

		assertTrue(finished);
		assertTrue(listener.exhausted);
		assertEquals(0, gateway.requestCount());
	}

	@Test
	void exhaustionFiresOnceCursorPassesTheSelectionWithNothingInFlight() {
		ChunkBounds bounds = new ChunkBounds(0, 1, 0, 0);
		FakeChunkGenerationGateway gateway = new FakeChunkGenerationGateway();
		RecordingListener listener = new RecordingListener();
		GenerationScheduler scheduler = new GenerationScheduler(gateway, scanWithPartial(bounds, new LongOpenHashSet(), new LongOpenHashSet()), bounds, listener);

		scheduler.tick(1000);
		gateway.complete(0, 0);
		scheduler.tick(1000);
		gateway.complete(1, 0);

		assertFalse(listener.exhausted);
		boolean finished = scheduler.tick(1000);

		assertTrue(finished);
		assertTrue(listener.exhausted);
	}

	@Test
	void cursorScanBudgetStopsWithinATickWithoutFindingATarget() {
		ChunkBounds bounds = new ChunkBounds(0, 9, 0, 9);
		FakeChunkGenerationGateway gateway = new FakeChunkGenerationGateway();
		RecordingListener listener = new RecordingListener();
		ChunkScanResult scan = scanWithPartial(bounds, new LongOpenHashSet(), new LongOpenHashSet());
		for (int z = 0; z <= 9; z++) {
			for (int x = 0; x <= 9; x++) {
				scan.markMapReady(x, z);
			}
		}
		GenerationScheduler scheduler = new GenerationScheduler(gateway, scan, bounds, listener);

		boolean finished = scheduler.tick(5);

		assertFalse(finished);
		assertFalse(listener.exhausted);
		assertEquals(0, gateway.requestCount());
	}
}
