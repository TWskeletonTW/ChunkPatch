package tw.skeleton.chunkpatch;

import com.mojang.datafixers.util.Either;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code ExecutorService.shutdown()} lets already-queued tasks run to
 * completion; a request queued on the coordinator just before {@code close()}
 * would otherwise still call {@code getChunkFuture()} on a ServerChunkCache
 * belonging to a world being torn down. These tests verify the closed guard
 * checked both at the API entry and again inside the queued coordinator task.
 */
class MinecraftChunkGenerationGatewayTest {
	@BeforeAll
	static void bootstrap() {
		MinecraftTestBootstrap.ensureBootstrapped();
	}

	@Test
	void requestAfterCloseFailsImmediatelyWithoutTouchingSource() {
		ServerChunkCache source = mock(ServerChunkCache.class);
		MinecraftChunkGenerationGateway gateway = new MinecraftChunkGenerationGateway(source);
		gateway.close();

		CompletableFuture<Void> future = gateway.requestFeatures(5, 5);

		assertTrue(future.isCompletedExceptionally());
		verify(source, never()).getChunkFuture(anyInt(), anyInt(), any(), anyBoolean());
	}

	@Test
	void queuedTaskObservesClosedStateAndNeverCallsSource() throws Exception {
		ServerChunkCache source = mock(ServerChunkCache.class);
		CountDownLatch firstStarted = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		when(source.getChunkFuture(eq(0), eq(0), eq(ChunkStatus.FEATURES), eq(true))).thenAnswer(invocation -> {
			firstStarted.countDown();
			releaseFirst.await();
			return CompletableFuture.completedFuture(Either.<ChunkAccess, ChunkHolder.ChunkLoadingFailure>left(mock(ChunkAccess.class)));
		});

		MinecraftChunkGenerationGateway gateway = new MinecraftChunkGenerationGateway(source);
		try {
			gateway.requestFeatures(0, 0); // occupies the single coordinator thread
			assertTrue(firstStarted.await(2, TimeUnit.SECONDS), "the first request never reached the coordinator thread");

			CompletableFuture<Void> second = gateway.requestFeatures(1, 1); // queues behind the first
			gateway.close(); // closed=true; shutdown() still lets both queued tasks run
			releaseFirst.countDown(); // let the first task finish so the coordinator can reach the queued one

			try {
				second.get(2, TimeUnit.SECONDS);
				fail("expected the queued task to fail once it observed the closed gateway");
			} catch (ExecutionException expected) {
				// expected: the queued task must see closed=true and never touch source
			}
			verify(source, never()).getChunkFuture(eq(1), eq(1), any(), anyBoolean());
		} finally {
			releaseFirst.countDown();
		}
	}
}
