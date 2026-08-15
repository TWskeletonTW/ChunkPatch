package tw.skeleton.chunkpatch;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XaeroMapCoverageScannerTest {
	@TempDir
	Path temporary;

	@Test
	void marksStoredAndUnstoredChunksCoveredByPopulatedXaeroLeaves() throws Exception {
		Path cacheDirectory = Files.createDirectories(temporary.resolve("cache_1"));
		writeCache(cacheDirectory.resolve("-2_5.xwmc"), (2 << 4) | 3);

		LongOpenHashSet full = new LongOpenHashSet();
		LongOpenHashSet partial = new LongOpenHashSet();
		LongOpenHashSet renderable = new LongOpenHashSet();
		LongOpenHashSet corrupt = new LongOpenHashSet();
		for (int z = 172; z <= 175; z++) {
			for (int x = -56; x <= -53; x++) partial.add(ChunkPos.asLong(x, z));
		}
		long alreadyFull = ChunkPos.asLong(-56, 172);
		long invalid = ChunkPos.asLong(-55, 172);
		long unstored = ChunkPos.asLong(-54, 172);
		partial.remove(alreadyFull);
		partial.remove(invalid);
		partial.remove(unstored);
		full.add(alreadyFull);
		corrupt.add(invalid);
		long outside = ChunkPos.asLong(-52, 172);
		partial.add(outside);

		XaeroMapCoverageScanner.CoverageStats stats = XaeroMapCoverageScanner.applyFromCacheDirectories(
			List.of(cacheDirectory),
			full,
			renderable,
			partial,
			corrupt,
			new ChunkBounds(-60, -50, 170, 180)
		);

		assertEquals(1, stats.cacheFiles());
		assertEquals(0, stats.unreadableFiles());
		assertEquals(1L, stats.populatedLeaves());
		assertEquals(13L, stats.promotedPartialChunks());
		assertEquals(1L, stats.addedUnstoredChunks());
		assertEquals(1L, partial.size());
		assertTrue(partial.contains(outside));
		assertEquals(14L, renderable.size());
		assertTrue(renderable.contains(unstored));
		assertTrue(renderable.contains(ChunkPos.asLong(-53, 175)));
		assertFalse(renderable.contains(alreadyFull));
		assertFalse(renderable.contains(invalid));
		assertFalse(renderable.contains(outside));
	}

	private static void writeCache(Path file, int leafKey) throws Exception {
		try (
			ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(file)));
			DataOutputStream output = new DataOutputStream(zip)
		) {
			zip.putNextEntry(new ZipEntry("cache.xaero"));
			output.writeInt((1 << 16) | 24);
			output.writeInt(123); // cache hash
			output.writeInt(2); // reload version
			output.writeInt(3); // highlights hash
			output.writeInt(Integer.MAX_VALUE); // cave start
			output.writeInt(30); // cave depth
			output.writeByte(leafKey);
			output.writeInt(99); // texture version
			output.writeByte(0xFF);
			zip.closeEntry();
		}
	}
}
