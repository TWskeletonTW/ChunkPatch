package tw.skeleton.chunkpatch;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.ChunkPos;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Reads Xaero's leaf texture cache headers without loading the texture pixels. */
final class XaeroMapCoverageScanner {
	private static final Pattern CACHE_FILE_NAME = Pattern.compile("(-?\\d+)_(-?\\d+)\\.xwmc");
	private static final int XAERO_REGION_CHUNKS = 32;
	private static final int LEAF_CHUNKS = 4;
	private static final int LEAVES_PER_AXIS = 8;
	private static final int CACHE_END = 0xFF;

	private XaeroMapCoverageScanner() {
	}

	static CoverageStats apply(
		Path dimensionPath,
		String dimensionId,
		LongOpenHashSet partial,
		LongOpenHashSet renderable
	) {
		Path dimensionFolder = findDimensionFolder(dimensionPath, dimensionId);
		if (dimensionFolder == null || !Files.isDirectory(dimensionFolder)) return CoverageStats.EMPTY;

		List<Path> cacheDirectories = new ArrayList<>();
		addCacheDirectory(dimensionFolder.resolve("cache_1"), cacheDirectories);
		try (DirectoryStream<Path> children = Files.newDirectoryStream(dimensionFolder)) {
			for (Path child : children) {
				if (Files.isDirectory(child) && child.getFileName().toString().startsWith("mw$")) {
					addCacheDirectory(child.resolve("cache_1"), cacheDirectories);
				}
			}
		} catch (IOException exception) {
			ChunkPatchMod.LOGGER.warn("Unable to list Xaero map folders in {} ({})", dimensionFolder, exception.getMessage());
		}
		return applyFromCacheDirectories(cacheDirectories, partial, renderable);
	}

	static CoverageStats applyFromCacheDirectories(
		List<Path> cacheDirectories,
		LongOpenHashSet partial,
		LongOpenHashSet renderable
	) {
		int files = 0;
		int unreadable = 0;
		long leaves = 0L;
		long promoted = 0L;
		Path firstUnreadable = null;
		String firstUnreadableReason = null;

		for (Path cacheDirectory : cacheDirectories) {
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(cacheDirectory, "*.xwmc")) {
				for (Path cacheFile : stream) {
					Matcher matcher = CACHE_FILE_NAME.matcher(cacheFile.getFileName().toString());
					if (!matcher.matches()) continue;
					files++;
					try {
						int regionX = Integer.parseInt(matcher.group(1));
						int regionZ = Integer.parseInt(matcher.group(2));
						LeafMask mask = readLeafMask(cacheFile);
						leaves += mask.count();
						promoted += promotePartialChunks(regionX, regionZ, mask.bits(), partial, renderable);
					} catch (IOException | RuntimeException exception) {
						unreadable++;
						if (firstUnreadable == null) {
							firstUnreadable = cacheFile;
							firstUnreadableReason = exception.getMessage();
						}
					}
				}
			} catch (IOException exception) {
				unreadable++;
				if (firstUnreadable == null) {
					firstUnreadable = cacheDirectory;
					firstUnreadableReason = exception.getMessage();
				}
			}
		}

		if (files > 0) {
			ChunkPatchMod.LOGGER.info(
				"Xaero map coverage: {} cache files, {} populated 4x4 leaves, {} partial chunks already drawable",
				files,
				leaves,
				promoted
			);
		}
		if (unreadable > 0) {
			ChunkPatchMod.LOGGER.warn(
				"Skipped {} unreadable Xaero map caches. First: {} ({})",
				unreadable,
				firstUnreadable,
				firstUnreadableReason == null ? "unknown error" : firstUnreadableReason
			);
		}
		return new CoverageStats(files, unreadable, leaves, promoted);
	}

	private static Path findDimensionFolder(Path dimensionPath, String dimensionId) {
		Path normalized = dimensionPath.toAbsolutePath().normalize();
		Path worldRoot;
		String xaeroDimension;
		switch (dimensionId) {
			case "minecraft:overworld" -> {
				worldRoot = normalized;
				xaeroDimension = "null";
			}
			case "minecraft:the_nether" -> {
				worldRoot = normalized.getParent();
				xaeroDimension = "DIM-1";
			}
			case "minecraft:the_end" -> {
				worldRoot = normalized.getParent();
				xaeroDimension = "DIM1";
			}
			default -> {
				return null;
			}
		}
		if (worldRoot == null || worldRoot.getFileName() == null) return null;
		Path gameDirectory;
		try {
			gameDirectory = FabricLoader.getInstance().getGameDir();
		} catch (IllegalStateException exception) {
			// Unit tests and data tools can call the region scanner before Fabric
			// Loader has established a game directory. World status scanning must
			// continue normally in that environment.
			return null;
		}
		return gameDirectory
			.resolve("xaero")
			.resolve("world-map")
			.resolve(worldRoot.getFileName().toString())
			.resolve(xaeroDimension);
	}

	private static void addCacheDirectory(Path path, List<Path> output) {
		if (Files.isDirectory(path)) output.add(path);
	}

	private static LeafMask readLeafMask(Path cacheFile) throws IOException {
		try (
			ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(cacheFile), 4096));
			DataInputStream input = new DataInputStream(zip)
		) {
			ZipEntry entry = zip.getNextEntry();
			if (entry == null || !"cache.xaero".equals(entry.getName())) {
				throw new IOException("Xaero cache has no cache.xaero entry");
			}
			int packedVersion = input.readInt();
			int major = packedVersion & 0xFFFF;
			if (major != 8 && (major < 12 || major > 24)) {
				throw new IOException("Unsupported Xaero cache version " + major);
			}

			if (major >= 9) input.readInt(); // cache hash
			if (major >= 11) input.readInt(); // reload version
			if (major >= 18) input.readInt(); // highlights hash
			if (major >= 23) input.readInt(); // cave start
			if (major >= 24) input.readInt(); // cave depth

			long bits = 0L;
			int count = 0;
			while (true) {
				int key = input.read();
				if (key < 0) throw new EOFException("Truncated Xaero cache metadata");
				if (key == CACHE_END) break;
				int leafX = key >>> 4;
				int leafZ = key & 0x0F;
				if (leafX >= LEAVES_PER_AXIS || leafZ >= LEAVES_PER_AXIS) {
					throw new IOException("Invalid Xaero leaf key " + key);
				}
				input.readInt(); // cached texture version
				long bit = 1L << (leafZ * LEAVES_PER_AXIS + leafX);
				if ((bits & bit) == 0L) {
					bits |= bit;
					count++;
				}
			}
			return new LeafMask(bits, count);
		}
	}

	private static long promotePartialChunks(
		int regionX,
		int regionZ,
		long leafBits,
		LongOpenHashSet partial,
		LongOpenHashSet renderable
	) {
		long promoted = 0L;
		for (int leafZ = 0; leafZ < LEAVES_PER_AXIS; leafZ++) {
			for (int leafX = 0; leafX < LEAVES_PER_AXIS; leafX++) {
				long bit = 1L << (leafZ * LEAVES_PER_AXIS + leafX);
				if ((leafBits & bit) == 0L) continue;
				int baseX = regionX * XAERO_REGION_CHUNKS + leafX * LEAF_CHUNKS;
				int baseZ = regionZ * XAERO_REGION_CHUNKS + leafZ * LEAF_CHUNKS;
				for (int dz = 0; dz < LEAF_CHUNKS; dz++) {
					for (int dx = 0; dx < LEAF_CHUNKS; dx++) {
						long packed = ChunkPos.asLong(baseX + dx, baseZ + dz);
						if (partial.remove(packed)) {
							renderable.add(packed);
							promoted++;
						}
					}
				}
			}
		}
		return promoted;
	}

	record CoverageStats(int cacheFiles, int unreadableFiles, long populatedLeaves, long promotedChunks) {
		private static final CoverageStats EMPTY = new CoverageStats(0, 0, 0L, 0L);
	}

	private record LeafMask(long bits, int count) {
	}
}
