package tw.skeleton.chunkpatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionScannerTest {
	@TempDir
	Path temporary;

	@Test
	void findsValidAndInvalidLocationEntriesWithoutReadingChunkPayloads() throws Exception {
		Path region = Files.createDirectories(temporary.resolve("region"));
		byte[] validFile = new byte[3 * 4096];
		ByteBuffer validHeader = ByteBuffer.wrap(validFile).order(ByteOrder.BIG_ENDIAN);
		validHeader.putInt((2 * 32 + 1) * 4, (2 << 8) | 1);
		Files.write(region.resolve("r.0.0.mca"), validFile);

		byte[] invalidFile = new byte[2 * 4096];
		ByteBuffer invalidHeader = ByteBuffer.wrap(invalidFile).order(ByteOrder.BIG_ENDIAN);
		invalidHeader.putInt((3 * 32 + 4) * 4, (9 << 8) | 1);
		Files.write(region.resolve("r.1.0.mca"), invalidFile);
		Files.createFile(region.resolve("r.2.0.mca"));
		Files.write(region.resolve("r.3.0.mca"), new byte[] {1});

		ChunkScanResult result = RegionScanner.scan(temporary, "minecraft:overworld");
		assertEquals(1L, result.generatedCount());
		assertEquals(1L, result.corruptCount());
		assertEquals(4, result.regionFileCount());
		assertEquals(1, result.unreadableRegionFileCount());
		assertTrue(result.isGenerated(1, 2));
		assertTrue(result.isCorrupt(36, 3));
		assertNotNull(result.detectedBounds());
		assertEquals(1, result.detectedBounds().minX());
		assertEquals(36, result.detectedBounds().maxX());
	}
}
