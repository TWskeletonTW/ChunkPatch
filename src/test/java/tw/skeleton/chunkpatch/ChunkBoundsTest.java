package tw.skeleton.chunkpatch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkBoundsTest {
	@Test
	void convertsNegativeBlockCoordinatesWithFloorDivision() {
		ChunkBounds bounds = ChunkBounds.fromBlocks(-1, 31, -17, 0);
		assertEquals(-1, bounds.minX());
		assertEquals(1, bounds.maxX());
		assertEquals(-2, bounds.minZ());
		assertEquals(0, bounds.maxZ());
		assertEquals(9L, bounds.area());
	}

	@Test
	void normalizesReversedCorners() {
		ChunkBounds bounds = ChunkBounds.normalized(10, -10, 8, -4);
		assertTrue(bounds.contains(0, 0));
		assertEquals(-160, bounds.minBlockX());
		assertEquals(175, bounds.maxBlockX());
	}
}
