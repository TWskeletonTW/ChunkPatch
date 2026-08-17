package tw.skeleton.chunkpatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionScannerTest {
	private static final int SECTOR_BYTES = 4096;

	@TempDir
	Path temporary;

	@Test
	void classifiesFullPartialAndInvalidChunksFromPayloadStatus() throws Exception {
		Path region = Files.createDirectories(temporary.resolve("region"));
		byte[] validFile = new byte[5 * SECTOR_BYTES];
		writeChunk(validFile, 2 * 32 + 1, 2, "full");
		writeChunk(validFile, 2 * 32 + 2, 3, "features");
		writeChunk(validFile, 2 * 32 + 3, 4, "surface");
		Files.write(region.resolve("r.0.0.mca"), validFile);

		byte[] invalidFile = new byte[2 * SECTOR_BYTES];
		ByteBuffer invalidHeader = ByteBuffer.wrap(invalidFile).order(ByteOrder.BIG_ENDIAN);
		invalidHeader.putInt((3 * 32 + 4) * 4, (9 << 8) | 1);
		Files.write(region.resolve("r.1.0.mca"), invalidFile);
		Files.createFile(region.resolve("r.2.0.mca"));
		Files.write(region.resolve("r.3.0.mca"), new byte[] {1});

		ChunkScanResult result = RegionScanner.scan(temporary, "minecraft:overworld", temporary.resolve("cache"));
		assertEquals(1L, result.fullCount());
		assertEquals(1L, result.renderableCount());
		assertEquals(2L, result.mapReadyCount());
		assertEquals(1L, result.partialCount());
		assertEquals(1L, result.corruptCount());
		assertEquals(4, result.regionFileCount());
		assertEquals(1, result.unreadableRegionFileCount());
		assertEquals(0, result.cachedRegionFileCount());
		// Only files that were actually read successfully count as "rescanned":
		// r.0.0.mca and r.1.0.mca. r.3.0.mca throws before a state array ever
		// exists, so it is unreadable but not also counted as a rescan.
		assertEquals(2, result.rescannedRegionFileCount());
		assertTrue(result.isMapReady(1, 2));
		assertFalse(result.isPartial(1, 2));
		assertTrue(result.isMapReady(2, 2));
		assertTrue(result.isPartial(3, 2));
		assertTrue(result.isCorrupt(36, 3));
		assertNotNull(result.detectedBounds());
		assertEquals(1, result.detectedBounds().minX());
		assertEquals(36, result.detectedBounds().maxX());
	}

	@Test
	void reusesUnchangedRegionsAndInvalidatesChangedFiles() throws Exception {
		Path world = Files.createDirectories(temporary.resolve("world"));
		Path region = Files.createDirectories(world.resolve("region"));
		Path cache = temporary.resolve("cache");
		Path regionFile = region.resolve("r.-1.2.mca");
		byte[] original = new byte[3 * SECTOR_BYTES];
		writeChunk(original, 4 * 32 + 5, 2, "full");
		Files.write(regionFile, original);

		ChunkScanResult first = RegionScanner.scan(world, "minecraft:overworld", cache);
		assertEquals(0, first.cachedRegionFileCount());
		assertEquals(1, first.rescannedRegionFileCount());
		assertTrue(first.isMapReady(-27, 68));

		ChunkScanResult second = RegionScanner.scan(world, "minecraft:overworld", cache);
		assertEquals(1, second.cachedRegionFileCount());
		assertEquals(0, second.rescannedRegionFileCount());
		assertTrue(second.isMapReady(-27, 68));

		long previousModified = Files.getLastModifiedTime(regionFile).toMillis();
		byte[] changed = new byte[3 * SECTOR_BYTES];
		writeChunk(changed, 4 * 32 + 5, 2, "surface");
		Files.write(regionFile, changed);
		Files.setLastModifiedTime(regionFile, FileTime.fromMillis(previousModified + 2_000L));

		ChunkScanResult third = RegionScanner.scan(world, "minecraft:overworld", cache);
		assertEquals(0, third.cachedRegionFileCount());
		assertEquals(1, third.rescannedRegionFileCount());
		assertFalse(third.isMapReady(-27, 68));
		assertTrue(third.isPartial(-27, 68));
	}

	private static void writeChunk(byte[] regionFile, int index, int sector, String status) throws Exception {
		byte[] nbt = chunkNbt(status);
		ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
		try (DeflaterOutputStream compressed = new DeflaterOutputStream(compressedBytes)) {
			compressed.write(nbt);
		}
		byte[] payload = compressedBytes.toByteArray();
		if (payload.length + 5 > SECTOR_BYTES) throw new IllegalStateException("Test payload exceeds one sector");

		ByteBuffer buffer = ByteBuffer.wrap(regionFile).order(ByteOrder.BIG_ENDIAN);
		buffer.putInt(index * 4, (sector << 8) | 1);
		buffer.position(sector * SECTOR_BYTES);
		buffer.putInt(payload.length + 1);
		buffer.put((byte)2);
		buffer.put(payload);
	}

	private static byte[] chunkNbt(String status) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream output = new DataOutputStream(bytes)) {
			output.writeByte(10);
			output.writeUTF("");
			output.writeByte(3);
			output.writeUTF("DataVersion");
			output.writeInt(3337);
			output.writeByte(8);
			output.writeUTF("Status");
			output.writeUTF(status);
			output.writeByte(0);
		}
		return bytes.toByteArray();
	}
}
