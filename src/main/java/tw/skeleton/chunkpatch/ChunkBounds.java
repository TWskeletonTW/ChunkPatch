package tw.skeleton.chunkpatch;

/** A normalized, inclusive chunk-coordinate rectangle. */
public record ChunkBounds(int minX, int maxX, int minZ, int maxZ) {
	public ChunkBounds {
		if (minX > maxX || minZ > maxZ) {
			throw new IllegalArgumentException("Chunk bounds must be normalized");
		}
	}

	public static ChunkBounds normalized(int x1, int x2, int z1, int z2) {
		return new ChunkBounds(Math.min(x1, x2), Math.max(x1, x2), Math.min(z1, z2), Math.max(z1, z2));
	}

	public static ChunkBounds fromBlocks(int x1, int x2, int z1, int z2) {
		return normalized(Math.floorDiv(x1, 16), Math.floorDiv(x2, 16), Math.floorDiv(z1, 16), Math.floorDiv(z2, 16));
	}

	public long area() {
		return ((long)maxX - minX + 1L) * ((long)maxZ - minZ + 1L);
	}

	public boolean contains(int chunkX, int chunkZ) {
		return chunkX >= minX && chunkX <= maxX && chunkZ >= minZ && chunkZ <= maxZ;
	}

	public int minBlockX() {
		return minX * 16;
	}

	public int maxBlockX() {
		return maxX * 16 + 15;
	}

	public int minBlockZ() {
		return minZ * 16;
	}

	public int maxBlockZ() {
		return maxZ * 16 + 15;
	}
}
