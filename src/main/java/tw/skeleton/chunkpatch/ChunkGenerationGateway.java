package tw.skeleton.chunkpatch;

import java.util.concurrent.CompletableFuture;

/**
 * Thread-aware, non-blocking access to Minecraft's FEATURES chunk generation
 * pipeline. Implementations must never block the calling thread: submitting a
 * request only registers work with Minecraft's own generation scheduler and
 * returns a future that completes once that work is done. The returned
 * future may complete on an arbitrary thread; callers must not mutate
 * controller, scan, or world state directly from it.
 */
interface ChunkGenerationGateway {
	CompletableFuture<Void> requestFeatures(int x, int z);
}
