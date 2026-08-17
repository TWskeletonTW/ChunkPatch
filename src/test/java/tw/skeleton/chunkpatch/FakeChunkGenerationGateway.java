package tw.skeleton.chunkpatch;

import net.minecraft.world.level.ChunkPos;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Test double that lets tests control exactly when each generation request completes. */
final class FakeChunkGenerationGateway implements ChunkGenerationGateway {
	private final Map<Long, CompletableFuture<Void>> pending = new LinkedHashMap<>();
	private int requestCount;

	@Override
	public CompletableFuture<Void> requestFeatures(int x, int z) {
		requestCount++;
		CompletableFuture<Void> future = new CompletableFuture<>();
		pending.put(key(x, z), future);
		return future;
	}

	int requestCount() {
		return requestCount;
	}

	boolean hasPending(int x, int z) {
		return pending.containsKey(key(x, z));
	}

	void complete(int x, int z) {
		CompletableFuture<Void> future = pending.remove(key(x, z));
		if (future == null) throw new IllegalStateException("No pending request for " + x + "," + z);
		future.complete(null);
	}

	void fail(int x, int z, Throwable error) {
		CompletableFuture<Void> future = pending.remove(key(x, z));
		if (future == null) throw new IllegalStateException("No pending request for " + x + "," + z);
		future.completeExceptionally(error);
	}

	private static long key(int x, int z) {
		return ChunkPos.asLong(x, z);
	}
}
