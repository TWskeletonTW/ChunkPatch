package tw.skeleton.chunkpatch;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * Verifies that a published PreviewSnapshot is a true point-in-time,
 * immutable copy: once handed to a reader (the client render thread), later
 * server-thread mutations to the scan must never be visible through it. This
 * is the property the old {@code volatile PreviewData} record did not
 * actually provide, since its record accessors returned live, mutable
 * arrays.
 */
class PreviewSnapshotTest {
	private static ChunkScanResult newScan(ChunkBounds bounds, LongOpenHashSet partial) {
		return new ChunkScanResult(
			Path.of("test-world"),
			"minecraft:overworld",
			new LongOpenHashSet(),
			new LongOpenHashSet(),
			partial,
			new LongOpenHashSet(),
			bounds,
			0,
			0,
			0,
			0
		);
	}

	@Test
	void previouslyPublishedSnapshotIsUnaffectedByLaterMutation() {
		ChunkBounds bounds = new ChunkBounds(0, 9, 0, 9);
		ChunkScanResult scan = newScan(bounds, new LongOpenHashSet());

		ChunkScanResult.PreviewSnapshot before = scan.preview();
		assertEquals(0, before.savedCount(0));

		scan.markMapReady(0, 0);
		ChunkScanResult.PreviewSnapshot after = scan.preview();

		assertNotSame(before, after);
		assertEquals(0, before.savedCount(0), "a snapshot already handed to a reader must not change");
		assertEquals(1, after.savedCount(0));
	}

	@Test
	void markMapReadyPromotesPartialCountToSavedCount() {
		ChunkBounds bounds = new ChunkBounds(0, 9, 0, 9);
		LongOpenHashSet partial = new LongOpenHashSet();
		partial.add(ChunkPos.asLong(0, 0));
		ChunkScanResult scan = newScan(bounds, partial);
		scan.rebuildPreview();
		assertEquals(1, scan.preview().partialCount(0));

		scan.markMapReady(0, 0);

		ChunkScanResult.PreviewSnapshot snapshot = scan.preview();
		assertEquals(1, snapshot.savedCount(0));
		assertEquals(0, snapshot.partialCount(0));
	}

	@Test
	void rebuildPreviewRecomputesFromCurrentSets() {
		ChunkBounds bounds = new ChunkBounds(0, 9, 0, 9);
		ChunkScanResult scan = newScan(bounds, new LongOpenHashSet());
		scan.markMapReady(0, 0);
		scan.markMapReady(0, 0); // idempotent: already in the renderable set the second time

		scan.rebuildPreview();

		assertEquals(1, scan.preview().savedCount(0));
	}
}
