package tw.skeleton.chunkpatch;

import com.mojang.datafixers.util.Either;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The generation coordinator's getChunkFuture() call runs on a real
 * background thread (MinecraftChunkGenerationGateway's coordinator
 * executor), so a single-in-flight target's completion can arrive on a
 * different thread than the one that submitted it. These tests stub
 * ServerChunkCache#getChunkFuture to return a future under the test's direct
 * control, then poll (bounded, short timeout) for the server.execute()
 * marshal to observe the completion, instead of assuming any particular
 * thread interleaving.
 */
class ChunkFillControllerShutdownLifecycleTest {
	@BeforeAll
	static void bootstrap() {
		MinecraftTestBootstrap.ensureBootstrapped();
	}

	@TempDir
	Path worldDir;

	private final AtomicInteger executeCount = new AtomicInteger();
	private final AtomicReference<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> lastChunkFuture = new AtomicReference<>();

	private MinecraftServer server;
	private ServerLevel level;
	private ServerChunkCache chunkSource;

	@BeforeEach
	void setUp() {
		server = mock(MinecraftServer.class);
		level = mock(ServerLevel.class);
		chunkSource = mock(ServerChunkCache.class);

		when(level.getServer()).thenReturn(server);
		when(level.dimension()).thenReturn(Level.OVERWORLD);
		when(level.getChunkSource()).thenReturn(chunkSource);
		when(level.getWorldBorder()).thenReturn(new WorldBorder());
		when(server.getAllLevels()).thenReturn(List.of(level));
		when(server.getAverageTickTime()).thenReturn(10.0f); // well under the admission gate's pause threshold
		when(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)).thenReturn(worldDir);
		when(chunkSource.getChunkFuture(anyInt(), anyInt(), eq(ChunkStatus.FEATURES), anyBoolean())).thenAnswer(invocation -> {
			CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> future = new CompletableFuture<>();
			lastChunkFuture.set(future);
			return future;
		});
		doAnswerExecuteSynchronously();
	}

	private void doAnswerExecuteSynchronously() {
		org.mockito.Mockito.doAnswer(invocation -> {
			Runnable task = invocation.getArgument(0);
			task.run();
			executeCount.incrementAndGet();
			return null;
		}).when(server).execute(any(Runnable.class));
	}

	/** Gets the controller to RUNNING with exactly one target in flight, without racing a real background scan/executor. */
	private ChunkFillController startWithOneTargetInFlight() {
		ChunkFillController controller = new ChunkFillController();
		long session = controller.beginScanSessionForTest(server, "minecraft:overworld");
		ChunkBounds bounds = new ChunkBounds(0, 0, 0, 0);
		ChunkScanResult scan = new ChunkScanResult(
			worldDir,
			"minecraft:overworld",
			new LongOpenHashSet(),
			new LongOpenHashSet(),
			new LongOpenHashSet(),
			new LongOpenHashSet(),
			bounds,
			0, 0, 0, 0
		);
		controller.completeScan(session, server, scan, null);
		assertEquals(ChunkFillController.State.READY, controller.snapshot().state());

		assertTrue(controller.prepare(level, bounds));
		assertTrue(controller.start(level));
		assertEquals(ChunkFillController.State.RUNNING, controller.snapshot().state());

		lastChunkFuture.set(null); // a previous controller in this test may have left a completed future behind
		controller.tick(server); // submits the only target: (0,0)
		// gateway.requestFeatures() calls the (stubbed) getChunkFuture() on the
		// real coordinator executor thread, not synchronously within tick().
		awaitCondition(() -> lastChunkFuture.get() != null, 2000L);
		assertFalse(lastChunkFuture.get().isDone());
		return controller;
	}

	private void completeCurrentTargetSuccessfully() {
		ChunkAccess chunk = mock(ChunkAccess.class);
		int before = executeCount.get();
		lastChunkFuture.get().complete(Either.left(chunk));
		awaitCondition(() -> executeCount.get() > before, 2000L);
	}

	private void failCurrentTarget() {
		int before = executeCount.get();
		lastChunkFuture.get().complete(Either.right(ChunkHolder.ChunkLoadingFailure.UNLOADED));
		awaitCondition(() -> executeCount.get() > before, 2000L);
	}

	private static void awaitCondition(BooleanSupplier condition, long timeoutMillis) {
		long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) fail("Condition not met within " + timeoutMillis + "ms");
			try {
				Thread.sleep(1L);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				fail(interrupted);
			}
		}
	}

	@Test
	void doesNotSubmitTheNextTargetOnTheSameTickAsTheLastCompletion() {
		ChunkFillController controller = new ChunkFillController();
		long session = controller.beginScanSessionForTest(server, "minecraft:overworld");
		ChunkBounds bounds = new ChunkBounds(0, 1, 0, 0); // two targets: (0,0) then (1,0)
		ChunkScanResult scan = new ChunkScanResult(
			worldDir,
			"minecraft:overworld",
			new LongOpenHashSet(),
			new LongOpenHashSet(),
			new LongOpenHashSet(),
			new LongOpenHashSet(),
			bounds,
			0, 0, 0, 0
		);
		controller.completeScan(session, server, scan, null);
		assertTrue(controller.prepare(level, bounds));
		assertTrue(controller.start(level));

		when(server.getTickCount()).thenReturn(100);
		controller.tick(server); // submits (0,0) on tick 100
		awaitCondition(() -> lastChunkFuture.get() != null, 2000L);

		completeCurrentTargetSuccessfully(); // commits on tick 100 -> next eligible tick is 101

		lastChunkFuture.set(null);
		controller.tick(server); // still tick 100
		try {
			Thread.sleep(50L);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
		assertNull(lastChunkFuture.get(), "must not submit the next target within the same tick as the last completion");

		when(server.getTickCount()).thenReturn(101);
		controller.tick(server); // now a later tick: allowed
		awaitCondition(() -> lastChunkFuture.get() != null, 2000L);
		assertFalse(lastChunkFuture.get().isDone());
	}

	@Test
	void pauseWhileInFlightDrainsBeforeReachingPaused() {
		ChunkFillController controller = startWithOneTargetInFlight();

		controller.pause(server);
		assertEquals(ChunkFillController.State.PAUSING, controller.snapshot().state(), "pause must wait for the in-flight target instead of finalizing immediately");

		completeCurrentTargetSuccessfully();
		assertEquals(ChunkFillController.State.PAUSED, controller.snapshot().state());
		assertEquals(1L, controller.snapshot().completed());
	}

	@Test
	void stopWhileInFlightDrainsBeforeReachingReady() {
		ChunkFillController controller = startWithOneTargetInFlight();

		controller.stop(server);
		assertEquals(ChunkFillController.State.STOPPING, controller.snapshot().state(), "stop must wait for the in-flight target instead of cancelling it");

		completeCurrentTargetSuccessfully();
		assertEquals(ChunkFillController.State.READY, controller.snapshot().state());
	}

	@Test
	void failureWhileDrainingStillReachesTheRequestedTerminalState() {
		ChunkFillController controllerA = startWithOneTargetInFlight();
		controllerA.pause(server);
		failCurrentTarget();
		assertEquals(ChunkFillController.State.PAUSED, controllerA.snapshot().state(), "a failure during drain must still finish the requested pause, not escalate to ERROR");

		ChunkFillController controllerB = startWithOneTargetInFlight();
		controllerB.stop(server);
		failCurrentTarget();
		assertEquals(ChunkFillController.State.READY, controllerB.snapshot().state(), "a failure during drain must still finish the requested stop, not escalate to ERROR");
	}

	@Test
	void shutdownWhileTargetIsPendingIsIgnoredOnLateCompletion() {
		ChunkFillController controller = startWithOneTargetInFlight();

		controller.onServerStopping();
		assertEquals(ChunkFillController.State.IDLE, controller.snapshot().state());
		assertNull(controller.snapshot().dimensionId());

		// The Minecraft worldgen future is not cancelled; it can still
		// complete naturally later. That must have no effect at all now.
		int before = executeCount.get();
		lastChunkFuture.get().complete(Either.left(mock(ChunkAccess.class)));
		// No server.execute() marshal should happen: the production listener
		// drops the callback outright once activeServer is cleared.
		try {
			Thread.sleep(50L);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
		assertEquals(before, executeCount.get(), "a stale completion after shutdown must not be marshalled to the (now gone) owner thread at all");
		assertEquals(ChunkFillController.State.IDLE, controller.snapshot().state());
		assertEquals(0L, controller.snapshot().completed());
	}

	@Test
	void startingASecondWorldAfterShutdownOnlyUsesTheNewState() {
		ChunkFillController controller = startWithOneTargetInFlight();
		controller.onServerStopping();

		// A second, unrelated world/server starts.
		MinecraftServer serverB = mock(MinecraftServer.class);
		ServerLevel levelB = mock(ServerLevel.class);
		ServerChunkCache chunkSourceB = mock(ServerChunkCache.class);
		when(levelB.getServer()).thenReturn(serverB);
		when(levelB.dimension()).thenReturn(Level.OVERWORLD);
		when(levelB.getChunkSource()).thenReturn(chunkSourceB);
		when(levelB.getWorldBorder()).thenReturn(new WorldBorder());
		when(serverB.getAllLevels()).thenReturn(List.of(levelB));

		long sessionB = controller.beginScanSessionForTest(serverB, "minecraft:overworld");
		ChunkScanResult scanB = new ChunkScanResult(
			worldDir.resolve("world-b"),
			"minecraft:overworld",
			new LongOpenHashSet(),
			new LongOpenHashSet(),
			new LongOpenHashSet(),
			new LongOpenHashSet(),
			new ChunkBounds(0, 0, 0, 0),
			0, 0, 0, 0
		);
		controller.completeScan(sessionB, serverB, scanB, null);

		assertEquals(ChunkFillController.State.READY, controller.snapshot().state());
		assertFalse(controller.snapshot().dimensionId() == null);

		// World A's stale target completing must still be a no-op even though a new session now exists.
		lastChunkFuture.get().complete(Either.left(mock(ChunkAccess.class)));
		assertEquals(ChunkFillController.State.READY, controller.snapshot().state(), "world A's stale completion must not disturb world B's fresh session");
		assertEquals(0L, controller.snapshot().completed());
	}
}
