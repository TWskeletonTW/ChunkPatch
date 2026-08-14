package tw.skeleton.chunkpatch;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.ChunkPos;

import java.nio.file.Path;

/** Scan metadata with a generated-set owned and mutated only by the server controller. */
public final class ChunkScanResult {
	public static final int PREVIEW_WIDTH = 180;
	public static final int PREVIEW_HEIGHT = 96;

	private final Path dimensionPath;
	private final String dimensionId;
	private final LongOpenHashSet generated;
	private final LongOpenHashSet corrupt;
	private final ChunkBounds detectedBounds;
	private final int regionFileCount;
	private final int unreadableRegionFileCount;
	private volatile PreviewData preview;

	public ChunkScanResult(
		Path dimensionPath,
		String dimensionId,
		LongOpenHashSet generated,
		LongOpenHashSet corrupt,
		ChunkBounds detectedBounds,
		int regionFileCount,
		int unreadableRegionFileCount
	) {
		this.dimensionPath = dimensionPath;
		this.dimensionId = dimensionId;
		this.generated = generated;
		this.corrupt = corrupt;
		this.detectedBounds = detectedBounds;
		this.regionFileCount = regionFileCount;
		this.unreadableRegionFileCount = unreadableRegionFileCount;
		this.preview = buildPreview();
	}

	public Path dimensionPath() { return dimensionPath; }
	public String dimensionId() { return dimensionId; }
	public long generatedCount() { return generated.size(); }
	public long corruptCount() { return corrupt.size(); }
	public ChunkBounds detectedBounds() { return detectedBounds; }
	public int regionFileCount() { return regionFileCount; }
	public int unreadableRegionFileCount() { return unreadableRegionFileCount; }
	public boolean isGenerated(int x, int z) { return generated.contains(ChunkPos.asLong(x, z)); }
	public boolean isCorrupt(int x, int z) { return corrupt.contains(ChunkPos.asLong(x, z)); }
	public void markGenerated(int x, int z) { generated.add(ChunkPos.asLong(x, z)); }
	public LongIterator generatedIterator() { return generated.iterator(); }
	public LongIterator corruptIterator() { return corrupt.iterator(); }
	public PreviewData preview() { return preview; }
	public void rebuildPreview() { this.preview = buildPreview(); }

	private PreviewData buildPreview() {
		int[] savedCounts = new int[PREVIEW_WIDTH * PREVIEW_HEIGHT];
		boolean[] invalid = new boolean[savedCounts.length];
		if (detectedBounds == null) {
			return new PreviewData(PREVIEW_WIDTH, PREVIEW_HEIGHT, savedCounts, new int[savedCounts.length], invalid);
		}

		long rangeX = (long)detectedBounds.maxX() - detectedBounds.minX() + 1L;
		long rangeZ = (long)detectedBounds.maxZ() - detectedBounds.minZ() + 1L;
		LongIterator iterator = generated.iterator();
		while (iterator.hasNext()) {
			long packed = iterator.nextLong();
			int x = ChunkPos.getX(packed);
			int z = ChunkPos.getZ(packed);
			int px = Math.min(PREVIEW_WIDTH - 1, (int)(((long)x - detectedBounds.minX()) * PREVIEW_WIDTH / rangeX));
			int pz = Math.min(PREVIEW_HEIGHT - 1, (int)(((long)z - detectedBounds.minZ()) * PREVIEW_HEIGHT / rangeZ));
			savedCounts[pz * PREVIEW_WIDTH + px]++;
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

		int[] possibleCounts = new int[savedCounts.length];
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
		return new PreviewData(PREVIEW_WIDTH, PREVIEW_HEIGHT, savedCounts, possibleCounts, invalid);
	}

	public record PreviewData(int width, int height, int[] savedCounts, int[] possibleCounts, boolean[] invalid) {
		public int density(int index) {
			int possible = possibleCounts[index];
			return possible <= 0 ? 0 : (int)Math.min(255L, savedCounts[index] * 255L / possible);
		}
	}
}
