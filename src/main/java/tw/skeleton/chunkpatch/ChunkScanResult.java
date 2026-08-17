package tw.skeleton.chunkpatch;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.ChunkPos;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * Scan metadata with a generated-set owned and mutated only by the server
 * controller. The preview is server-owned mutable counters internally;
 * readers (the client render thread) only ever see a cloned, immutable
 * {@link PreviewSnapshot} published after each mutation, so a render frame
 * can never observe a half-updated array while the server thread is
 * concurrently incrementing counts for a just-completed target.
 */
public final class ChunkScanResult {
	public static final int PREVIEW_WIDTH = 180;
	public static final int PREVIEW_HEIGHT = 96;

	private final Path dimensionPath;
	private final String dimensionId;
	private final LongOpenHashSet full;
	private final LongOpenHashSet renderable;
	private final LongOpenHashSet partial;
	private final LongOpenHashSet corrupt;
	private final ChunkBounds detectedBounds;
	private final int regionFileCount;
	private final int unreadableRegionFileCount;
	private final int cachedRegionFileCount;
	private final int rescannedRegionFileCount;

	// Server-thread-only mutable preview state. Never handed out directly.
	private final int[] savedCounts = new int[PREVIEW_WIDTH * PREVIEW_HEIGHT];
	private final int[] partialCounts = new int[savedCounts.length];
	private final int[] possibleCounts = new int[savedCounts.length];
	private final boolean[] invalid = new boolean[savedCounts.length];
	private volatile PreviewSnapshot publishedPreview;

	public ChunkScanResult(
		Path dimensionPath,
		String dimensionId,
		LongOpenHashSet full,
		LongOpenHashSet renderable,
		LongOpenHashSet partial,
		LongOpenHashSet corrupt,
		ChunkBounds detectedBounds,
		int regionFileCount,
		int unreadableRegionFileCount,
		int cachedRegionFileCount,
		int rescannedRegionFileCount
	) {
		this.dimensionPath = dimensionPath;
		this.dimensionId = dimensionId;
		this.full = full;
		this.renderable = renderable;
		this.partial = partial;
		this.corrupt = corrupt;
		this.detectedBounds = detectedBounds;
		this.regionFileCount = regionFileCount;
		this.unreadableRegionFileCount = unreadableRegionFileCount;
		this.cachedRegionFileCount = cachedRegionFileCount;
		this.rescannedRegionFileCount = rescannedRegionFileCount;
		rebuildPreview();
	}

	public Path dimensionPath() { return dimensionPath; }
	public String dimensionId() { return dimensionId; }
	public long fullCount() { return full.size(); }
	public long renderableCount() { return renderable.size(); }
	public long mapReadyCount() { return full.size() + renderable.size(); }
	public long partialCount() { return partial.size(); }
	public long corruptCount() { return corrupt.size(); }
	public ChunkBounds detectedBounds() { return detectedBounds; }
	public int regionFileCount() { return regionFileCount; }
	public int unreadableRegionFileCount() { return unreadableRegionFileCount; }
	public int cachedRegionFileCount() { return cachedRegionFileCount; }
	public int rescannedRegionFileCount() { return rescannedRegionFileCount; }
	public boolean isMapReady(int x, int z) {
		long packed = ChunkPos.asLong(x, z);
		return full.contains(packed) || renderable.contains(packed);
	}
	public boolean isPartial(int x, int z) { return partial.contains(ChunkPos.asLong(x, z)); }
	public boolean isCorrupt(int x, int z) { return corrupt.contains(ChunkPos.asLong(x, z)); }

	/** Server-thread only. Publishes a fresh immutable snapshot afterward. */
	public void markMapReady(int x, int z) {
		long packed = ChunkPos.asLong(x, z);
		boolean wasPartial = partial.remove(packed);
		if (!renderable.add(packed) || detectedBounds == null || !detectedBounds.contains(x, z)) return;
		int index = previewIndex(x, z, PREVIEW_WIDTH, PREVIEW_HEIGHT);
		savedCounts[index]++;
		if (wasPartial && partialCounts[index] > 0) partialCounts[index]--;
		publishPreview();
	}

	public LongIterator fullIterator() { return full.iterator(); }
	public LongIterator renderableIterator() { return renderable.iterator(); }
	public LongIterator partialIterator() { return partial.iterator(); }
	public LongIterator corruptIterator() { return corrupt.iterator(); }

	/** Returns the most recently published immutable snapshot. Safe to call from any thread. */
	public PreviewSnapshot preview() { return publishedPreview; }

	/** Server-thread only. Fully recomputes counts from the current sets and publishes. */
	public void rebuildPreview() {
		recomputeCounts();
		publishPreview();
	}

	private void publishPreview() {
		publishedPreview = new PreviewSnapshot(
			PREVIEW_WIDTH,
			PREVIEW_HEIGHT,
			savedCounts.clone(),
			partialCounts.clone(),
			possibleCounts.clone(),
			invalid.clone()
		);
	}

	private int previewIndex(int chunkX, int chunkZ, int width, int height) {
		long rangeX = (long)detectedBounds.maxX() - detectedBounds.minX() + 1L;
		long rangeZ = (long)detectedBounds.maxZ() - detectedBounds.minZ() + 1L;
		int px = Math.max(0, Math.min(width - 1, (int)(((long)chunkX - detectedBounds.minX()) * width / rangeX)));
		int pz = Math.max(0, Math.min(height - 1, (int)(((long)chunkZ - detectedBounds.minZ()) * height / rangeZ)));
		return pz * width + px;
	}

	private void recomputeCounts() {
		Arrays.fill(savedCounts, 0);
		Arrays.fill(partialCounts, 0);
		Arrays.fill(possibleCounts, 0);
		Arrays.fill(invalid, false);
		if (detectedBounds == null) return;

		long rangeX = (long)detectedBounds.maxX() - detectedBounds.minX() + 1L;
		long rangeZ = (long)detectedBounds.maxZ() - detectedBounds.minZ() + 1L;
		LongIterator iterator = full.iterator();
		while (iterator.hasNext()) {
			long packed = iterator.nextLong();
			int x = ChunkPos.getX(packed);
			int z = ChunkPos.getZ(packed);
			int px = Math.min(PREVIEW_WIDTH - 1, (int)(((long)x - detectedBounds.minX()) * PREVIEW_WIDTH / rangeX));
			int pz = Math.min(PREVIEW_HEIGHT - 1, (int)(((long)z - detectedBounds.minZ()) * PREVIEW_HEIGHT / rangeZ));
			savedCounts[pz * PREVIEW_WIDTH + px]++;
		}

		iterator = renderable.iterator();
		while (iterator.hasNext()) {
			long packed = iterator.nextLong();
			int x = ChunkPos.getX(packed);
			int z = ChunkPos.getZ(packed);
			int px = Math.min(PREVIEW_WIDTH - 1, (int)(((long)x - detectedBounds.minX()) * PREVIEW_WIDTH / rangeX));
			int pz = Math.min(PREVIEW_HEIGHT - 1, (int)(((long)z - detectedBounds.minZ()) * PREVIEW_HEIGHT / rangeZ));
			savedCounts[pz * PREVIEW_WIDTH + px]++;
		}

		iterator = partial.iterator();
		while (iterator.hasNext()) {
			long packed = iterator.nextLong();
			int x = ChunkPos.getX(packed);
			int z = ChunkPos.getZ(packed);
			int px = Math.min(PREVIEW_WIDTH - 1, (int)(((long)x - detectedBounds.minX()) * PREVIEW_WIDTH / rangeX));
			int pz = Math.min(PREVIEW_HEIGHT - 1, (int)(((long)z - detectedBounds.minZ()) * PREVIEW_HEIGHT / rangeZ));
			partialCounts[pz * PREVIEW_WIDTH + px]++;
		}

		iterator = corrupt.iterator();
		while (iterator.hasNext()) {
			long packed = iterator.nextLong();
			int x = ChunkPos.getX(packed);
			int z = ChunkPos.getZ(packed);
			int px = Math.min(PREVIEW_WIDTH - 1, (int)(((long)x - detectedBounds.minX()) * PREVIEW_WIDTH / rangeX));
			int pz = Math.min(PREVIEW_HEIGHT - 1, (int)(((long)z - detectedBounds.minZ()) * PREVIEW_HEIGHT / rangeZ));
			invalid[pz * PREVIEW_WIDTH + px] = true;
		}

		for (int pz = 0; pz < PREVIEW_HEIGHT; pz++) {
			long z0 = detectedBounds.minZ() + pz * rangeZ / PREVIEW_HEIGHT;
			long z1 = detectedBounds.minZ() + (pz + 1L) * rangeZ / PREVIEW_HEIGHT - 1L;
			if (z1 < z0) z1 = z0;
			for (int px = 0; px < PREVIEW_WIDTH; px++) {
				long x0 = detectedBounds.minX() + px * rangeX / PREVIEW_WIDTH;
				long x1 = detectedBounds.minX() + (px + 1L) * rangeX / PREVIEW_WIDTH - 1L;
				if (x1 < x0) x1 = x0;
				long count = (x1 - x0 + 1L) * (z1 - z0 + 1L);
				possibleCounts[pz * PREVIEW_WIDTH + px] = (int)Math.min(Integer.MAX_VALUE, count);
			}
		}
	}

	/** Immutable, defensively-cloned preview state safe to read from any thread without synchronization. */
	public static final class PreviewSnapshot {
		private final int width;
		private final int height;
		private final int[] savedCounts;
		private final int[] partialCounts;
		private final int[] possibleCounts;
		private final boolean[] invalid;

		private PreviewSnapshot(int width, int height, int[] savedCounts, int[] partialCounts, int[] possibleCounts, boolean[] invalid) {
			this.width = width;
			this.height = height;
			this.savedCounts = savedCounts;
			this.partialCounts = partialCounts;
			this.possibleCounts = possibleCounts;
			this.invalid = invalid;
		}

		public int width() { return width; }
		public int height() { return height; }
		public int savedCount(int index) { return savedCounts[index]; }
		public int partialCount(int index) { return partialCounts[index]; }
		public int possibleCount(int index) { return possibleCounts[index]; }
		public boolean invalid(int index) { return invalid[index]; }

		public int density(int index) {
			int possible = possibleCounts[index];
			return possible <= 0 ? 0 : (int)Math.min(255L, savedCounts[index] * 255L / possible);
		}
	}
}
