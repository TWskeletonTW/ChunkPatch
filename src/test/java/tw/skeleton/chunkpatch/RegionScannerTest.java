package tw.skeleton.chunkpatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
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
		byte[] validFile = new byte[4 * SECTOR_BYTES];
		writeChunk(validFile, 2 * 32 + 1, 2, "full");
		writeChunk(validFile, 2 * 32 + 2, 3, "structure_starts");
		Files.write(region.resolve("r.0.0.mca"), validFile);

		byte[] invalidFile = new byte[2 * SECTOR_BYTES];
		ByteBuffer invalidHeader = ByteBuffer.wrap(invalidFile).order(ByteOrder.BIG_ENDIAN);
		invalidHeader.putInt((3 * 32 + 4) * 4, (9 << 8) | 1);
		Files.write(region.resolve("r.1.0.mca"), invalidFile);
		Files.createFile(region.resolve("r.2.0.mca"));
		Files.write(region.resolve("r.3.0.mca"), new byte[] {1});

		ChunkScanResult result = RegionScanner.scan(temporary, "minecraft:overworld");
		assertEquals(1L, result.generatedCount());
		assertEquals(1L, result.partialCount());
		assertEquals(1L, result.corruptCount());
		assertEquals(4, result.regionFileCount());
		assertEquals(1, result.unreadableRegionFileCount());
		assertTrue(result.isGenerated(1, 2));
		assertFalse(result.isPartial(1, 2));
		assertTrue(result.isPartial(2, 2));
		assertTrue(result.isCorrupt(36, 3));
		assertNotNull(result.detectedBounds());
		assertEquals(1, result.detectedBounds().minX());
		assertEquals(36, result.detectedBounds().maxX());
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
