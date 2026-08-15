package tw.skeleton.chunkpatch;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.ChunkPos;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/** Reads Anvil location tables and each stored chunk's generation Status without writing to the world. */
public final class RegionScanner {
	private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
	private static final int LOCATION_TABLE_BYTES = 4096;
	private static final int SECTOR_BYTES = 4096;
	private static final int MAX_NBT_DEPTH = 512;
	static final byte STATE_EMPTY = 0;
	static final byte STATE_FULL = 1;
	static final byte STATE_PARTIAL = 2;
	static final byte STATE_CORRUPT = 3;
	static final byte STATE_RENDERABLE = 4;
	private static final ScanProgressListener NO_PROGRESS = (completed, total) -> { };

	private RegionScanner() {
	}

	public static ChunkScanResult scan(Path dimensionPath, String dimensionId) throws IOException {
		return scan(dimensionPath, dimensionId, RegionScanCache.open(dimensionPath), NO_PROGRESS);
	}

	public static ChunkScanResult scan(
		Path dimensionPath,
		String dimensionId,
		ScanProgressListener progress
	) throws IOException {
		return scan(dimensionPath, dimensionId, RegionScanCache.open(dimensionPath), progress);
	}

	static ChunkScanResult scan(Path dimensionPath, String dimensionId, Path cacheDirectory) throws IOException {
		return scan(dimensionPath, dimensionId, RegionScanCache.open(cacheDirectory, dimensionPath), NO_PROGRESS);
	}

	private static ChunkScanResult scan(
		Path dimensionPath,
		String dimensionId,
		RegionScanCache.Session cache,
		ScanProgressListener progress
	) throws IOException {
		Path regionDirectory = dimensionPath.resolve("region");
		LongOpenHashSet full = new LongOpenHashSet();
		LongOpenHashSet renderable = new LongOpenHashSet();
		LongOpenHashSet partial = new LongOpenHashSet();
		LongOpenHashSet corrupt = new LongOpenHashSet();
		int[] extrema = {Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE};
		int regionFiles = 0;
		int emptyFiles = 0;
		int unreadable = 0;
		int cacheHits = 0;
		int cacheMisses = 0;
		Path firstUnreadable = null;
		String firstUnreadableReason = null;

		if (Files.isDirectory(regionDirectory)) {
			List<Path> files = new ArrayList<>();
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionDirectory, "r.*.*.mca")) {
				for (Path regionFile : stream) {
					Matcher matcher = REGION_NAME.matcher(regionFile.getFileName().toString());
					if (matcher.matches()) files.add(regionFile);
				}
			}
			regionFiles = files.size();
			progress.update(0, regionFiles);
			int completedRegionFiles = 0;
			for (Path regionFile : files) {
				Matcher matcher = REGION_NAME.matcher(regionFile.getFileName().toString());
				try {
					if (!matcher.matches()) continue;
					long size = Files.size(regionFile);
					if (size == 0L) {
						emptyFiles++;
						continue;
					}
					int regionX = Integer.parseInt(matcher.group(1));
					int regionZ = Integer.parseInt(matcher.group(2));
					long modifiedMillis = Files.getLastModifiedTime(regionFile).toMillis();
					byte[] states = cache.find(regionX, regionZ, size, modifiedMillis);
					if (states == null) {
						cacheMisses++;
						RegionState scanned = scanRegion(regionFile, regionX, regionZ);
						states = scanned.states();
						long finalSize = Files.size(regionFile);
						long finalModifiedMillis = Files.getLastModifiedTime(regionFile).toMillis();
						if (scanned.cacheable() && finalSize == size && finalModifiedMillis == modifiedMillis) {
							cache.put(regionX, regionZ, size, modifiedMillis, states);
						}
					} else {
						cacheHits++;
					}
					applyStates(regionX, regionZ, states, full, renderable, partial, corrupt, extrema);
				} catch (IOException | RuntimeException exception) {
					unreadable++;
					if (firstUnreadable == null) {
						firstUnreadable = regionFile;
						firstUnreadableReason = exception.getMessage();
					}
				} finally {
					progress.update(++completedRegionFiles, regionFiles);
				}
			}
			cache.save();
			ChunkPatchMod.LOGGER.info(
				"Region scan cache for {}: {} reused, {} rescanned",
				regionDirectory,
				cacheHits,
				cacheMisses
			);
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
		return new ChunkScanResult(
			dimensionPath.toAbsolutePath().normalize(),
			dimensionId,
			full,
			renderable,
			partial,
			corrupt,
			bounds,
			regionFiles,
			unreadable,
			cacheHits,
			cacheMisses
		);
	}

	private static RegionState scanRegion(Path file, int regionX, int regionZ) throws IOException {
		byte[] states = new byte[1024];
		boolean cacheable = true;
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
			long fileSize = channel.size();
			if (fileSize < LOCATION_TABLE_BYTES) throw new IOException("Region header is truncated");
			long fileSectors = (fileSize + SECTOR_BYTES - 1L) / SECTOR_BYTES;
			ByteBuffer header = ByteBuffer.allocate(LOCATION_TABLE_BYTES).order(ByteOrder.BIG_ENDIAN);
			readFully(channel, header, 0L);
			header.flip();

			for (int index = 0; index < 1024; index++) {
				int location = header.getInt();
				if (location == 0) continue;
				int offset = location >>> 8;
				int sectors = location & 0xFF;
				if (offset < 2 || sectors == 0 || (long)offset + sectors > fileSectors) {
					states[index] = STATE_CORRUPT;
				} else {
					try {
						int chunkX = regionX * 32 + (index & 31);
						int chunkZ = regionZ * 32 + (index >>> 5);
						ChunkStatusResult chunk = readChunkStatus(channel, file, fileSize, offset, sectors, chunkX, chunkZ);
						if (chunk.external()) cacheable = false;
						states[index] = classifyStatus(chunk.status());
					} catch (IOException | RuntimeException exception) {
						states[index] = STATE_CORRUPT;
					}
				}
			}
		}
		return new RegionState(states, cacheable);
	}

	private static void applyStates(
		int regionX,
		int regionZ,
		byte[] states,
		LongOpenHashSet full,
		LongOpenHashSet renderable,
		LongOpenHashSet partial,
		LongOpenHashSet corrupt,
		int[] extrema
	) {
		for (int index = 0; index < states.length; index++) {
			byte state = states[index];
			if (state == STATE_EMPTY) continue;
			int chunkX = regionX * 32 + (index & 31);
			int chunkZ = regionZ * 32 + (index >>> 5);
			long packed = ChunkPos.asLong(chunkX, chunkZ);
			if (state == STATE_FULL) full.add(packed);
			else if (state == STATE_RENDERABLE) renderable.add(packed);
			else if (state == STATE_PARTIAL) partial.add(packed);
			else corrupt.add(packed);
			extrema[0] = Math.min(extrema[0], chunkX);
			extrema[1] = Math.max(extrema[1], chunkX);
			extrema[2] = Math.min(extrema[2], chunkZ);
			extrema[3] = Math.max(extrema[3], chunkZ);
		}
	}

	private static ChunkStatusResult readChunkStatus(
		FileChannel channel,
		Path regionFile,
		long fileSize,
		int offset,
		int sectors,
		int chunkX,
		int chunkZ
	) throws IOException {
		long chunkPosition = (long)offset * SECTOR_BYTES;
		ByteBuffer prefix = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN);
		readFully(channel, prefix, chunkPosition);
		prefix.flip();
		int length = prefix.getInt();
		int compressionByte = prefix.get() & 0xFF;
		boolean external = (compressionByte & 0x80) != 0;
		if (length < 1 || (!external && length == 1) || length > sectors * SECTOR_BYTES - 4 || chunkPosition + 4L + length > fileSize) {
			throw new IOException("Invalid chunk payload length");
		}

		int compressionType = compressionByte & 0x7F;
		InputStream payload;
		if (external) {
			Path externalFile = regionFile.resolveSibling("c." + chunkX + "." + chunkZ + ".mcc");
			payload = Files.newInputStream(externalFile);
		} else {
			payload = new ChannelRangeInputStream(channel, chunkPosition + 5L, length - 1L);
		}

		InputStream decoded;
		try {
			decoded = switch (compressionType) {
				case 1 -> new GZIPInputStream(payload, 512);
				case 2 -> new InflaterInputStream(payload, new Inflater(), 512);
				case 3 -> payload;
				default -> throw new IOException("Unsupported chunk compression type " + compressionType);
			};
		} catch (IOException | RuntimeException exception) {
			payload.close();
			throw exception;
		}
		try (DataInputStream input = new DataInputStream(new BufferedInputStream(decoded, 512))) {
			int rootType = input.readUnsignedByte();
			if (rootType != 10) throw new IOException("Chunk NBT root is not a compound");
			skipString(input);
			String status = findStatusInCompound(input, 0);
			if (status == null || status.isBlank()) throw new IOException("Chunk NBT has no Status");
			return new ChunkStatusResult(status, external);
		}
	}

	private static byte classifyStatus(String status) {
		String normalized = status.startsWith("minecraft:") ? status.substring("minecraft:".length()) : status;
		return switch (normalized) {
			case "full" -> STATE_FULL;
			case "features", "initialize_light", "light", "spawn" -> STATE_RENDERABLE;
			default -> STATE_PARTIAL;
		};
	}

	private static String findStatusInCompound(DataInputStream input, int depth) throws IOException {
		checkDepth(depth);
		while (true) {
			int type = input.readUnsignedByte();
			if (type == 0) return null;
			String name = input.readUTF();
			if (type == 8 && "Status".equals(name)) return input.readUTF();
			if (type == 10 && "Level".equals(name)) {
				String legacyStatus = findStatusInCompound(input, depth + 1);
				if (legacyStatus != null) return legacyStatus;
			} else {
				skipTagPayload(input, type, depth + 1);
			}
		}
	}

	private static void skipTagPayload(DataInputStream input, int type, int depth) throws IOException {
		checkDepth(depth);
		switch (type) {
			case 1 -> input.skipNBytes(1L);
			case 2 -> input.skipNBytes(2L);
			case 3, 5 -> input.skipNBytes(4L);
			case 4, 6 -> input.skipNBytes(8L);
			case 7 -> input.skipNBytes(arrayBytes(input.readInt(), 1));
			case 8 -> skipString(input);
			case 9 -> {
				int elementType = input.readUnsignedByte();
				int count = checkedLength(input.readInt());
				if (elementType == 0 && count != 0) throw new IOException("Invalid NBT list type");
				for (int i = 0; i < count; i++) skipTagPayload(input, elementType, depth + 1);
			}
			case 10 -> {
				while (true) {
					int childType = input.readUnsignedByte();
					if (childType == 0) break;
					skipString(input);
					skipTagPayload(input, childType, depth + 1);
				}
			}
			case 11 -> input.skipNBytes(arrayBytes(input.readInt(), 4));
			case 12 -> input.skipNBytes(arrayBytes(input.readInt(), 8));
			default -> throw new IOException("Invalid NBT tag type " + type);
		}
	}

	private static void skipString(DataInputStream input) throws IOException {
		input.skipNBytes(input.readUnsignedShort());
	}

	private static long arrayBytes(int count, int bytesPerElement) throws IOException {
		return Math.multiplyExact((long)checkedLength(count), bytesPerElement);
	}

	private static int checkedLength(int length) throws IOException {
		if (length < 0) throw new IOException("Negative NBT length");
		return length;
	}

	private static void checkDepth(int depth) throws IOException {
		if (depth > MAX_NBT_DEPTH) throw new IOException("NBT nesting is too deep");
	}

	private static void readFully(FileChannel channel, ByteBuffer target, long position) throws IOException {
		while (target.hasRemaining()) {
			int read = channel.read(target, position);
			if (read < 0) throw new EOFException("Unexpected end of region file");
			if (read == 0) continue;
			position += read;
		}
	}

	private static final class ChannelRangeInputStream extends InputStream {
		private final FileChannel channel;
		private long position;
		private long remaining;
		private final byte[] oneByte = new byte[1];

		private ChannelRangeInputStream(FileChannel channel, long position, long remaining) {
			this.channel = channel;
			this.position = position;
			this.remaining = remaining;
		}

		@Override
		public int read() throws IOException {
			int read = read(oneByte, 0, 1);
			return read < 0 ? -1 : oneByte[0] & 0xFF;
		}

		@Override
		public int read(byte[] bytes, int offset, int length) throws IOException {
			if (length == 0) return 0;
			if (remaining <= 0L) return -1;
			int requested = (int)Math.min(length, remaining);
			ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, requested);
			int read;
			do {
				read = channel.read(buffer, position);
			} while (read == 0);
			if (read < 0) throw new EOFException("Unexpected end of chunk payload");
			position += read;
			remaining -= read;
			return read;
		}
	}

	private record RegionState(byte[] states, boolean cacheable) {
	}

	private record ChunkStatusResult(String status, boolean external) {
	}

	@FunctionalInterface
	public interface ScanProgressListener {
		void update(int completedRegionFiles, int totalRegionFiles);
	}
}
