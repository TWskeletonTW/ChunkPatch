package tw.skeleton.chunkpatch;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.ChunkPos;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/** Persistent, per-dimension cache for the 1024 chunk states in each Anvil region file. */
final class RegionScanCache {
	private static final int MAGIC = 0x43504348; // CPCH
	private static final int VERSION = 2;
	private static final int CHUNKS_PER_REGION = 1024;
	private static final int MAX_REGION_ENTRIES = 1_000_000;

	private RegionScanCache() {
	}

	static Session open(Path dimensionPath) {
		Path cacheDirectory = FabricLoader.getInstance().getConfigDir().resolve("chunkpatch-scan-cache");
		return open(cacheDirectory, dimensionPath);
	}

	static Session open(Path cacheDirectory, Path dimensionPath) {
		String dimensionKey = dimensionPath.toAbsolutePath().normalize().toString();
		Path cacheFile = cacheDirectory.resolve(hash(dimensionKey) + ".bin");
		Map<Long, Entry> stored = new HashMap<>();
		if (Files.isRegularFile(cacheFile)) {
			try {
				stored.putAll(read(cacheFile, dimensionKey));
			} catch (IOException | RuntimeException exception) {
				ChunkPatchMod.LOGGER.warn("Ignoring invalid region scan cache {} ({})", cacheFile, exception.getMessage());
			}
		}
		return new Session(cacheFile, dimensionKey, stored);
	}

	private static Map<Long, Entry> read(Path cacheFile, String expectedDimensionKey) throws IOException {
		try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(cacheFile)))) {
			if (input.readInt() != MAGIC) throw new IOException("Wrong cache signature");
			if (input.readInt() != VERSION) throw new IOException("Unsupported cache version");
			if (!expectedDimensionKey.equals(input.readUTF())) throw new IOException("Cache belongs to another dimension");
			int count = input.readInt();
			if (count < 0 || count > MAX_REGION_ENTRIES) throw new IOException("Invalid cache entry count");

			Map<Long, Entry> entries = new HashMap<>(Math.max(16, count * 4 / 3));
			for (int i = 0; i < count; i++) {
				int regionX = input.readInt();
				int regionZ = input.readInt();
				long size = input.readLong();
				long modifiedMillis = input.readLong();
				byte[] states = input.readNBytes(CHUNKS_PER_REGION);
				if (states.length != CHUNKS_PER_REGION) throw new EOFException("Truncated cache entry");
				for (byte state : states) {
					if (state < RegionScanner.STATE_EMPTY || state > RegionScanner.STATE_RENDERABLE) {
						throw new IOException("Invalid cached chunk state");
					}
				}
				entries.put(ChunkPos.asLong(regionX, regionZ), new Entry(size, modifiedMillis, states));
			}
			return entries;
		}
	}

	private static String hash(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	static final class Session {
		private final Path cacheFile;
		private final String dimensionKey;
		private final Map<Long, Entry> stored;
		private final Map<Long, Entry> current = new HashMap<>();

		private Session(Path cacheFile, String dimensionKey, Map<Long, Entry> stored) {
			this.cacheFile = cacheFile;
			this.dimensionKey = dimensionKey;
			this.stored = stored;
		}

		byte[] find(int regionX, int regionZ, long size, long modifiedMillis) {
			long key = ChunkPos.asLong(regionX, regionZ);
			Entry entry = stored.get(key);
			if (entry == null || entry.size != size || entry.modifiedMillis != modifiedMillis) return null;
			current.put(key, entry);
			return entry.states;
		}

		void put(int regionX, int regionZ, long size, long modifiedMillis, byte[] states) {
			if (states.length != CHUNKS_PER_REGION) throw new IllegalArgumentException("A region must contain 1024 chunk states");
			current.put(ChunkPos.asLong(regionX, regionZ), new Entry(size, modifiedMillis, states.clone()));
		}

		void save() {
			try {
				writeAtomically();
			} catch (IOException | RuntimeException exception) {
				ChunkPatchMod.LOGGER.warn("Unable to save region scan cache {} ({})", cacheFile, exception.getMessage());
			}
		}

		private void writeAtomically() throws IOException {
			synchronized (RegionScanCache.class) {
				Files.createDirectories(cacheFile.getParent());
				Path temporary = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
				try {
					try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
						output.writeInt(MAGIC);
						output.writeInt(VERSION);
						output.writeUTF(dimensionKey);
						output.writeInt(current.size());
						for (Map.Entry<Long, Entry> cached : current.entrySet()) {
							long packed = cached.getKey();
							Entry entry = cached.getValue();
							output.writeInt(ChunkPos.getX(packed));
							output.writeInt(ChunkPos.getZ(packed));
							output.writeLong(entry.size);
							output.writeLong(entry.modifiedMillis);
							output.write(entry.states);
						}
					}
					try {
						Files.move(temporary, cacheFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
					} catch (AtomicMoveNotSupportedException exception) {
						Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING);
					}
				} finally {
					Files.deleteIfExists(temporary);
				}
			}
		}
	}

	private record Entry(long size, long modifiedMillis, byte[] states) {
	}
}
