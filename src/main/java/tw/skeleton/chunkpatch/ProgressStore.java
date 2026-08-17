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

	private ProgressStore() {
	}

	// Resolved lazily (not as a static field) so merely loading this class
	// outside a running Fabric Loader environment (e.g. a unit test that
	// exercises a ChunkFillController code path that happens to touch
	// ProgressStore) does not permanently poison the class with a
	// NoClassDefFoundError from a failed static initializer.
	private static Path resolveFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("chunkpatch-progress.json");
	}

	public static Optional<ProgressData> load() {
		try {
			Path file = resolveFile();
			if (!Files.isRegularFile(file)) return Optional.empty();
			return Optional.ofNullable(GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ProgressData.class));
		} catch (Exception exception) {
			ChunkPatchMod.LOGGER.warn("Unable to load generation progress", exception);
			return Optional.empty();
		}
	}

	public static void save(ProgressData data) {
		try {
			Path file = resolveFile();
			Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
			Files.createDirectories(file.getParent());
			Files.writeString(temporary, GSON.toJson(data), StandardCharsets.UTF_8);
			try {
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicMoveUnavailable) {
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Exception exception) {
			ChunkPatchMod.LOGGER.warn("Unable to save generation progress", exception);
		}
	}

	public static void clear() {
		try {
			Files.deleteIfExists(resolveFile());
		} catch (Exception exception) {
			ChunkPatchMod.LOGGER.warn("Unable to remove completed progress", exception);
		}
	}

	/**
	 * Resume intent only, not an authoritative transaction journal: it
	 * records which dimension and bounds a generation run was working on, not
	 * how far it got. The region scan is always re-derived from disk (or the
	 * in-memory scan updated per completed target) after a restart, so a
	 * completed-but-unsaved target, or a crash mid-write, is simply detected
	 * as a missing chunk and generated again rather than silently skipped.
	 */
	public static final class ProgressData {
		public int formatVersion = 2;
		public String dimensionPath;
		public String dimensionId;
		public int minX;
		public int maxX;
		public int minZ;
		public int maxZ;
	}
}
