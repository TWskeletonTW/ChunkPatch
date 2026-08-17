package tw.skeleton.chunkpatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The stable-read check in RegionScanner compares full FileTime precision;
 * the on-disk cache must use the same precision, or a same-size region file
 * modified twice within one millisecond (some filesystems report finer
 * resolution) could produce a false cache hit against stale states.
 */
class RegionScanCacheTest {
	@TempDir
	Path temporary;

	@Test
	void sameSizeButDifferentNanosecondIsACacheMiss() {
		Path dimensionPath = temporary.resolve("world");
		Path cacheDir = temporary.resolve("cache");
		byte[] states = new byte[1024];
		states[0] = RegionScanner.STATE_FULL;

		RegionScanCache.Session writer = RegionScanCache.open(cacheDir, dimensionPath);
		FileTime original = FileTime.from(Instant.ofEpochSecond(1_700_000_000L, 123_100_000L));
		writer.put(0, 0, 4096L, original, states);
		writer.save();

		RegionScanCache.Session reader = RegionScanCache.open(cacheDir, dimensionPath);
		FileTime differentNano = FileTime.from(Instant.ofEpochSecond(1_700_000_000L, 123_800_000L));

		assertNull(reader.find(0, 0, 4096L, differentNano), "a different nanosecond-precision mtime must not hit the cache");
	}

	@Test
	void sameSizeAndExactTimestampIsACacheHit() {
		Path dimensionPath = temporary.resolve("world");
		Path cacheDir = temporary.resolve("cache");
		byte[] states = new byte[1024];
		states[5] = RegionScanner.STATE_RENDERABLE;

		RegionScanCache.Session writer = RegionScanCache.open(cacheDir, dimensionPath);
		FileTime modified = FileTime.from(Instant.ofEpochSecond(1_700_000_000L, 42L));
		writer.put(0, 0, 4096L, modified, states);
		writer.save();

		RegionScanCache.Session reader = RegionScanCache.open(cacheDir, dimensionPath);
		byte[] found = reader.find(0, 0, 4096L, FileTime.from(Instant.ofEpochSecond(1_700_000_000L, 42L)));

		assertNotNull(found);
		assertArrayEquals(states, found);
	}

	@Test
	void cacheFileWithAnUnsupportedVersionIsIgnored() throws Exception {
		Path dimensionPath = temporary.resolve("world");
		Path cacheDir = Files.createDirectories(temporary.resolve("cache"));
		String dimensionKey = dimensionPath.toAbsolutePath().normalize().toString();
		Path cacheFile = cacheDir.resolve(sha256Hex(dimensionKey) + ".bin");

		try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(cacheFile)))) {
			output.writeInt(0x43504348); // MAGIC
			output.writeInt(3); // an old/unsupported version
			output.writeUTF(dimensionKey);
			output.writeInt(0); // no entries
		}

		RegionScanCache.Session reader = RegionScanCache.open(cacheDir, dimensionPath);

		assertNull(reader.find(0, 0, 4096L, FileTime.fromMillis(0L)), "a cache written with an unsupported version must be ignored, not partially trusted");
	}

	private static String sha256Hex(String value) throws Exception {
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		return HexFormat.of().formatHex(digest);
	}
}
