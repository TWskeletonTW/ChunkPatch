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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Minecraft keeps running (and can autosave) while ChunkPatch's background
 * scan thread reads .mca files. A region whose mtime keeps changing during
 * the read must not be cached and must not be misclassified as corrupt —
 * both would be wrong, since the file may be perfectly valid and simply
 * being actively written to.
 */
class RegionScannerConcurrentMutationTest {
	private static final int SECTOR_BYTES = 4096;

	@TempDir
	Path temporary;

	@Test
	void regionThatKeepsChangingDuringTheScanIsNeitherCachedNorMarkedCorrupt() throws Exception {
		Path world = Files.createDirectories(temporary.resolve("world"));
		Path region = Files.createDirectories(world.resolve("region"));
		Path cache = temporary.resolve("cache");
		Path regionFile = region.resolve("r.0.0.mca");
		Files.write(regionFile, buildRegion("full"));

		AtomicBoolean keepTouching = new AtomicBoolean(true);
		Thread mutator = new Thread(() -> {
			long offset = 0L;
			while (keepTouching.get()) {
				try {
					Files.setLastModifiedTime(regionFile, FileTime.fromMillis(System.currentTimeMillis() + offset));
					offset += 1L;
					Thread.sleep(0, 200_000); // 0.2ms: fast enough to very likely land inside a scan
				} catch (Exception ignored) {
					// best-effort mutator; a failed touch just means one fewer collision attempt
				}
			}
		}, "region-mutator");
		mutator.setDaemon(true);
		mutator.start();
		try {
			ChunkScanResult result = RegionScanner.scan(world, "minecraft:overworld", cache);

			// The scan must finish without crashing and must not fabricate a
			// corrupt chunk out of a torn read.
			assertEquals(0L, result.corruptCount());
		} finally {
			keepTouching.set(false);
			mutator.join(1000L);
		}

		// Once the file stops changing, a follow-up scan must succeed and
		// classify the region normally — proving the earlier instability
		// didn't poison the cache with a torn read.
		Thread.sleep(5L);
		ChunkScanResult stable = RegionScanner.scan(world, "minecraft:overworld", cache);
		assertTrue(stable.isMapReady(1, 2));
		assertFalse(stable.isCorrupt(1, 2));
	}

	private static byte[] buildRegion(String status) throws Exception {
		byte[] regionFile = new byte[3 * SECTOR_BYTES];
		writeChunk(regionFile, 2 * 32 + 1, 2, status);
		return regionFile;
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
