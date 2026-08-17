package tw.skeleton.chunkpatch;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A region scan runs on a background executor and can outlive the world
 * session that started it (the player can leave the world, or start another
 * scan, before the old one finishes). Every async scan carries a session
 * token and the server instance it was started against; a result or
 * progress update that no longer matches the controller's current session/
 * server must be dropped rather than corrupting a newer session's state.
 *
 * {@code updateScanProgress}/{@code completeScan} are driven directly here
 * (they are package-private for this reason) with fabricated session
 * numbers instead of racing the real background scan thread, so these tests
 * are deterministic.
 */
class ChunkFillControllerScanLifecycleTest {
	@BeforeAll
	static void bootstrap() {
		MinecraftTestBootstrap.ensureBootstrapped();
	}

	@TempDir
	Path worldDir;

	private static MinecraftServer mockServer(Path root) {
		MinecraftServer server = mock(MinecraftServer.class);
		when(server.getWorldPath(LevelResource.ROOT)).thenReturn(root);
		return server;
	}

	private static ServerLevel mockLevel() {
		ServerLevel level = mock(ServerLevel.class);
		when(level.dimension()).thenReturn(Level.OVERWORLD);
		return level;
	}

	private static ChunkScanResult fakeResult(String dimensionId) {
		return new ChunkScanResult(
			Path.of("test-world"),
			dimensionId,
			new LongOpenHashSet(),
			new LongOpenHashSet(),
			new LongOpenHashSet(),
			new LongOpenHashSet(),
			null,
			0,
			0,
			0,
			0
		);
	}

	@Test
	void staleScanProgressIsIgnoredButCurrentSessionApplies() {
		ChunkFillController controller = new ChunkFillController();
		controller.requestScan(mockServer(worldDir), mockLevel());
		long current = controller.scanSessionId();
		assertEquals(0, controller.snapshot().scannedRegionFiles());

		controller.updateScanProgress(current - 1, 7, 20);
		assertEquals(0, controller.snapshot().scannedRegionFiles(), "a stale session token must not update progress");

		controller.updateScanProgress(current, 7, 20);
		assertEquals(7, controller.snapshot().scannedRegionFiles());
		assertEquals(20, controller.snapshot().totalRegionFiles());
	}

	@Test
	void staleScanResultIsIgnoredButCurrentSessionApplies() {
		ChunkFillController controller = new ChunkFillController();
		MinecraftServer server = mockServer(worldDir);
		controller.requestScan(server, mockLevel());
		long current = controller.scanSessionId();

		controller.completeScan(current - 1, server, fakeResult("minecraft:overworld"), null);
		assertEquals(ChunkFillController.State.SCANNING, controller.snapshot().state(), "a stale session token must not commit a scan result");

		controller.completeScan(current, server, fakeResult("minecraft:overworld"), null);
		assertEquals(ChunkFillController.State.READY, controller.snapshot().state());
	}

	@Test
	void scanResultFromANoLongerActiveServerIsIgnored() {
		ChunkFillController controller = new ChunkFillController();
		MinecraftServer server = mockServer(worldDir);
		controller.requestScan(server, mockLevel());
		long current = controller.scanSessionId();

		MinecraftServer differentServer = mock(MinecraftServer.class);
		controller.completeScan(current, differentServer, fakeResult("minecraft:overworld"), null);

		assertEquals(ChunkFillController.State.SCANNING, controller.snapshot().state(), "a result whose server no longer matches activeServer must be ignored");
	}

	@Test
	void serverShutdownInvalidatesTheInFlightScanSession() {
		ChunkFillController controller = new ChunkFillController();
		MinecraftServer server = mockServer(worldDir);
		controller.requestScan(server, mockLevel());
		long staleSession = controller.scanSessionId();

		controller.onServerStopping();
		controller.completeScan(staleSession, server, fakeResult("minecraft:overworld"), null);

		assertEquals(ChunkFillController.State.IDLE, controller.snapshot().state());
		assertNull(controller.snapshot().dimensionId());
	}

	@Test
	void worldAScanCannotUpdateStateAfterWorldBScanStarts() {
		ChunkFillController controller = new ChunkFillController();
		MinecraftServer serverA = mockServer(worldDir);
		controller.requestScan(serverA, mockLevel());
		long sessionA = controller.scanSessionId();

		// World A's integrated server stops (player left/closed the world)
		// before its scan finished, then World B opens and starts a new scan.
		controller.onServerStopping();
		Path worldBDir = worldDir.resolve("world-b");
		MinecraftServer serverB = mockServer(worldBDir);
		controller.requestScan(serverB, mockLevel());
		long sessionB = controller.scanSessionId();
		assertTrue(sessionB > sessionA, "opening world B must not reuse world A's scan session");

		// World A's scan finishes late, after B has already taken over.
		controller.completeScan(sessionA, serverA, fakeResult("minecraft:overworld"), null);
		assertEquals(ChunkFillController.State.SCANNING, controller.snapshot().state(), "world A's stale result must not affect world B's session");

		controller.completeScan(sessionB, serverB, fakeResult("minecraft:overworld"), null);
		assertEquals(ChunkFillController.State.READY, controller.snapshot().state());
	}
}
