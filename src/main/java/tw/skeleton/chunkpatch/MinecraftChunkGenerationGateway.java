package tw.skeleton.chunkpatch;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkStatus;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Requests FEATURES generation through {@link ServerChunkCache#getChunkFuture}
 * from a dedicated coordinator thread.
 *
 * Minecraft 1.19.4's {@code getChunkFuture} only avoids blocking when called
 * off the server main thread: in that branch it queues the (cheap,
 * ticket-registering) setup call onto Minecraft's own main-thread executor
 * without ever calling {@code managedBlock}, and the returned future
 * completes asynchronously as Minecraft's worldgen scheduler finishes the
 * work. Calling it directly from the main thread, or wrapping the blocking
 * {@code getChunk()} in {@code supplyAsync}, both still end up inside
 * {@code managedBlock()} on the main thread and defeat the point — see
 * ChunkPatch_REFACTOR_PLAN.md section 9.
 *
 * The coordinator thread only ever calls this one thread-aware API method. It
 * must never touch {@code ServerLevel}, controller state, or scan state.
 */
final class MinecraftChunkGenerationGateway implements ChunkGenerationGateway, AutoCloseable {
	private final ServerChunkCache source;
	private final ExecutorService coordinator;
	// ExecutorService.shutdown() lets already-queued tasks run to completion;
	// a request() call can queue a task on the coordinator just before close()
	// runs, and that task would otherwise still call getChunkFuture() on a
	// ServerChunkCache belonging to a world that is being torn down. The
	// stale completion is already harmless (GenerationScheduler's session
	// token discards it), but the queued task itself should never touch the
	// old ServerChunkCache in the first place. Checked both at the API entry
	// and again inside the queued task, since close() can race between them.
	private final AtomicBoolean closed = new AtomicBoolean(false);

	MinecraftChunkGenerationGateway(ServerChunkCache source) {
		this.source = source;
		this.coordinator = Executors.newSingleThreadExecutor(task -> {
			Thread thread = new Thread(task, "ChunkPatch Generation Coordinator");
			thread.setDaemon(true);
			return thread;
		});
	}

	@Override
	public CompletableFuture<Void> requestFeatures(int x, int z) {
		if (closed.get()) {
			return CompletableFuture.failedFuture(new IllegalStateException("Generation gateway is closed"));
		}
		return CompletableFuture
			.supplyAsync(() -> {
				if (closed.get()) {
					throw new CompletionException(new IllegalStateException("Generation gateway is closed"));
				}
				return source.getChunkFuture(x, z, ChunkStatus.FEATURES, true);
			}, coordinator)
			.thenCompose(Function.identity())
			.thenApply(result -> result.map(
				chunk -> null,
				failure -> {
					throw new CompletionException(
						new IllegalStateException("Chunk generation failed for " + x + "," + z + ": " + failure)
					);
				}
			));
	}

	@Override
	public void close() {
		closed.set(true);
		coordinator.shutdown();
	}
}
