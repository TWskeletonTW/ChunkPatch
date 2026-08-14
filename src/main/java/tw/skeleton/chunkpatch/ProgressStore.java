package tw.skeleton.chunkpatch;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/** Crash-safe progress persistence in the instance config directory. */
public final class ProgressStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("chunkpatch-progress.json");

	private ProgressStore() {
	}

	public static Optional<ProgressData> load() {
		if (!Files.isRegularFile(FILE)) return Optional.empty();
		try {
			return Optional.ofNullable(GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), ProgressData.class));
		} catch (Exception exception) {
			ChunkPatchMod.LOGGER.warn("Unable to load generation progress", exception);
			return Optional.empty();
		}
	}

	public static void save(ProgressData data) {
		Path temporary = FILE.resolveSibling(FILE.getFileName() + ".tmp");
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(temporary, GSON.toJson(data), StandardCharsets.UTF_8);
			try {
				Files.move(temporary, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicMoveUnavailable) {
				Files.move(temporary, FILE, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException exception) {
			ChunkPatchMod.LOGGER.warn("Unable to save generation progress", exception);
		}
	}

	public static void clear() {
		try {
			Files.deleteIfExists(FILE);
		} catch (IOException exception) {
			ChunkPatchMod.LOGGER.warn("Unable to remove completed progress", exception);
		}
	}

	public static final class ProgressData {
		public String dimensionPath;
		public String dimensionId;
		public int minX;
		public int maxX;
		public int minZ;
		public int maxZ;
		public int nextX;
		public int nextZ;
		public long planned;
		public long completed;
		public int chunksPerTick;
	}
}
