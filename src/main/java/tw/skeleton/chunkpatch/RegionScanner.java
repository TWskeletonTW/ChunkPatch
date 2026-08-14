package tw.skeleton.chunkpatch;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads only Anvil location tables. Existing chunks are never opened for writing. */
public final class RegionScanner {
	private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
	private static final int LOCATION_TABLE_BYTES = 4096;
	private static final int SECTOR_BYTES = 4096;

	private RegionScanner() {
	}

	public static ChunkScanResult scan(Path dimensionPath, String dimensionId) throws IOException {
		Path regionDirectory = dimensionPath.resolve("region");
		LongOpenHashSet generated = new LongOpenHashSet();
		LongOpenHashSet corrupt = new LongOpenHashSet();
		int[] extrema = {Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE};
		int regionFiles = 0;
		int emptyFiles = 0;
		int unreadable = 0;
		Path firstUnreadable = null;
		String firstUnreadableReason = null;

		if (Files.isDirectory(regionDirectory)) {
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionDirectory, "r.*.*.mca")) {
				for (Path regionFile : stream) {
					Matcher matcher = REGION_NAME.matcher(regionFile.getFileName().toString());
					if (!matcher.matches()) continue;
					regionFiles++;
					try {
						if (Files.size(regionFile) == 0L) {
							emptyFiles++;
							continue;
						}
						scanRegion(regionFile, Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), generated, corrupt, extrema);
					} catch (IOException | RuntimeException exception) {
						unreadable++;
						if (firstUnreadable == null) {
							firstUnreadable = regionFile;
							firstUnreadableReason = exception.getMessage();
						}
					}
				}
			}
			if (emptyFiles > 0) {
				ChunkPatchMod.LOGGER.info("Ignored {} empty region files in {}", emptyFiles, regionDirectory);
			}
			if (unreadable > 0) {
				ChunkPatchMod.LOGGER.warn(
					"Skipped {} unreadable region files in {}. First: {} ({})",
					unreadable,
					regionDirectory,
					firstUnreadable,
					firstUnreadableReason == null ? "unknown error" : firstUnreadableReason
				);
			}
		}

		ChunkBounds bounds = extrema[0] == Integer.MAX_VALUE
			? null
			: new ChunkBounds(extrema[0], extrema[1], extrema[2], extrema[3]);
		return new ChunkScanResult(dimensionPath.toAbsolutePath().normalize(), dimensionId, generated, corrupt, bounds, regionFiles, unreadable);
	}

	private static void scanRegion(Path file, int regionX, int regionZ, LongOpenHashSet generated, LongOpenHashSet corrupt, int[] extrema) throws IOException {
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
			long fileSize = channel.size();
			if (fileSize < LOCATION_TABLE_BYTES) throw new IOException("Region header is truncated");
			long fileSectors = (fileSize + SECTOR_BYTES - 1L) / SECTOR_BYTES;
			ByteBuffer header = ByteBuffer.allocate(LOCATION_TABLE_BYTES).order(ByteOrder.BIG_ENDIAN);
			while (header.hasRemaining() && channel.read(header) >= 0) {
				// Keep reading until the location table is complete.
			}
			if (header.position() != LOCATION_TABLE_BYTES) throw new IOException("Region location table is truncated");
			header.flip();

			for (int index = 0; index < 1024; index++) {
				int location = header.getInt();
				if (location == 0) continue;
				int chunkX = regionX * 32 + (index & 31);
				int chunkZ = regionZ * 32 + (index >>> 5);
				int offset = location >>> 8;
				int sectors = location & 0xFF;
				long packed = ChunkPos.asLong(chunkX, chunkZ);
				if (offset < 2 || sectors == 0 || (long)offset + sectors > fileSectors) {
					corrupt.add(packed);
				} else {
					generated.add(packed);
				}
				extrema[0] = Math.min(extrema[0], chunkX);
				extrema[1] = Math.max(extrema[1], chunkX);
				extrema[2] = Math.min(extrema[2], chunkZ);
				extrema[3] = Math.max(extrema[3], chunkZ);
			}
		}
	}
}
