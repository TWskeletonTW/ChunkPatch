package tw.skeleton.chunkpatch;

import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.border.WorldBorder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Coordinates scanning and throttled generation. Mutations run on the integrated server thread. */
public final class ChunkFillController {
	private static final long MIN_FREE_BYTES = 2L * 1024L * 1024L * 1024L;
	private static final long MAX_SELECTION_AREA = 5_000_000L;
	private static final int MAX_COORDINATE_CHECKS_PER_TICK = 20_000;
	private static final long SAVE_CHECKPOINT_INTERVAL = 256L;
	private static final DateTimeFormatter CLOCK_TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm", Locale.ROOT);
	private static final ExecutorService SCAN_EXECUTOR = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "ChunkPatch Region Scanner");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});

	private State state = State.IDLE;
	private String message = "尚未掃描";
	private ChunkScanResult scan;
	private ChunkBounds selected;
	private String dimensionId;
	private int nextX;
	private int nextZ;
	private int chunksPerTick = 1;
	private long planned;
	private long completed;
	private long existingInside;
	private long partialInside;
	private long corruptInside;
	private long lastUsableBytes = -1L;
	private long generatedSinceCheckpoint;
	private int scannedRegionFiles;
	private int totalRegionFiles;
	private long scanStartedNanos;
	private long lastScanProgressMessageNanos;
	private long generationActiveNanos;
	private long generationRunStartedNanos;

	public synchronized void requestScan(MinecraftServer server, ServerLevel level) {
		if (state == State.SCANNING) {
			message = "掃描已在進行中";
			return;
		}
		if (state == State.RUNNING) {
			message = "生成中不能重新掃描，請先暫停";
			return;
		}
		server.saveEverything(true, true, false);
		Path root = server.getWorldPath(LevelResource.ROOT);
		Path dimensionPath = DimensionType.getStorageFolder(level.dimension(), root).toAbsolutePath().normalize();
		String requestedDimension = level.dimension().location().toString();
		this.dimensionId = requestedDimension;
		state = State.SCANNING;
		message = "正在掃描區域檔…";
		scannedRegionFiles = 0;
		totalRegionFiles = 0;
		scanStartedNanos = System.nanoTime();
		lastScanProgressMessageNanos = 0L;
		CompletableFuture
			.supplyAsync(() -> {
				try {
					return RegionScanner.scan(dimensionPath, requestedDimension, this::updateScanProgress);
				} catch (Exception exception) {
					throw new RuntimeException(exception);
				}
			}, SCAN_EXECUTOR)
			.whenComplete((result, error) -> server.execute(() -> completeScan(result, error)));
	}

	private synchronized void updateScanProgress(int completed, int total) {
		if (state != State.SCANNING) return;
		scannedRegionFiles = completed;
		totalRegionFiles = total;
		long now = System.nanoTime();
		if (completed < total && lastScanProgressMessageNanos != 0L && now - lastScanProgressMessageNanos < 100_000_000L) return;
		lastScanProgressMessageNanos = now;
		if (total <= 0) {
			message = "正在掃描區域檔…";
			return;
		}
		double percent = completed * 100.0D / total;
		message = String.format(
			Locale.ROOT,
			"掃描中：%,d / %,d（%.1f%%）｜剩餘 %s",
			completed,
			total,
			percent,
			formatEta(estimateScanRemainingMillis())
		);
	}

	private synchronized void completeScan(ChunkScanResult result, Throwable error) {
		if (error != null) {
			state = State.ERROR;
			message = "掃描失敗：" + rootMessage(error);
			ChunkPatchMod.LOGGER.error("Chunk scan failed", error);
			return;
		}
		this.scan = result;
		this.dimensionId = result.dimensionId();
		this.selected = result.detectedBounds();
		this.planned = 0L;
		this.completed = 0L;
		resetGenerationTimer();
		this.existingInside = result.mapReadyCount();
		this.partialInside = result.partialCount();
		this.corruptInside = result.corruptCount();
		this.state = State.READY;
		this.scannedRegionFiles = result.regionFileCount();
		this.totalRegionFiles = result.regionFileCount();
		this.message = result.detectedBounds() == null
			? "找不到已生成區塊"
			: String.format(
				Locale.ROOT,
				"掃描完成：可繪 %,d（完整 %,d），不可繪半成品 %,d，異常 %,d",
				result.mapReadyCount(),
				result.fullCount(),
				result.partialCount(),
				result.corruptCount()
			);
		restoreProgressIfCompatible();
	}

	public synchronized boolean prepare(ServerLevel level, ChunkBounds bounds, int requestedRate) {
		if (scan == null || scan.detectedBounds() == null) {
			message = "請先掃描目前維度";
			return false;
		}
		if (state == State.RUNNING || state == State.SCANNING) {
			message = "目前狀態不能變更邊界";
			return false;
		}
		if (!scan.dimensionId().equals(level.dimension().location().toString())) {
			message = "維度已改變，請重新掃描";
			return false;
		}
		if (!insideWorldBorder(level.getWorldBorder(), bounds)) {
			message = "選取範圍超出世界邊界";
			return false;
		}
		if (bounds.area() > MAX_SELECTION_AREA) {
			message = String.format(Locale.ROOT, "範圍過大（%,d 區塊）；單次上限 %,d", bounds.area(), MAX_SELECTION_AREA);
			return false;
		}

		long existing = countInside(scan.fullIterator(), bounds) + countInside(scan.renderableIterator(), bounds);
		long partial = countInside(scan.partialIterator(), bounds);
		long invalid = countInside(scan.corruptIterator(), bounds);
		this.selected = bounds;
		this.existingInside = existing;
		this.partialInside = partial;
		this.corruptInside = invalid;
		this.planned = Math.max(0L, bounds.area() - existing - invalid);
		this.completed = 0L;
		this.generatedSinceCheckpoint = 0L;
		resetGenerationTimer();
		this.nextX = bounds.minX();
		this.nextZ = bounds.minZ();
		this.chunksPerTick = Math.max(1, Math.min(8, requestedRate));
		this.state = State.READY;
		this.message = String.format(Locale.ROOT, "估算完成：需生成到 FEATURES %,d（其中不可繪半成品 %,d），已有可繪 %,d，異常不覆寫 %,d", planned, partial, existing, invalid);
		ProgressStore.clear();
		return true;
	}

	public synchronized boolean start(ServerLevel level) {
		if (scan == null || selected == null || planned < 0L) {
			message = "請先掃描並估算";
			return false;
		}
		if (!scan.dimensionId().equals(level.dimension().location().toString())) {
			message = "維度已改變，請重新掃描";
			return false;
		}
		if (planned == 0L) {
			state = State.COMPLETE;
			message = "範圍內沒有缺少的區塊";
			return true;
		}
		if (usableBytes(scan.dimensionPath()) < MIN_FREE_BYTES) {
			state = State.PAUSED;
			message = "可用硬碟空間低於 2 GiB，已阻止生成";
			return false;
		}
		state = State.RUNNING;
		beginGenerationTimer();
		message = "正在生成 WorldMap 可繪區塊（目標 FEATURES）";
		saveProgress();
		return true;
	}

	public synchronized void pause(MinecraftServer server) {
		if (state != State.RUNNING) return;
		endGenerationTimer();
		state = State.PAUSED;
		message = "已暫停並儲存進度";
		ServerLevel level = findLevel(server);
		if (level != null) {
			level.getChunkSource().save(true);
			generatedSinceCheckpoint = 0L;
		}
		saveProgress();
	}

	public synchronized void resume(ServerLevel level) {
		if (state != State.PAUSED) {
			message = "目前沒有可繼續的工作";
			return;
		}
		if (scan == null || !scan.dimensionId().equals(level.dimension().location().toString())) {
			message = "請回到原維度並重新掃描";
			return;
		}
		state = State.RUNNING;
		beginGenerationTimer();
		message = "已繼續生成";
	}

	public synchronized void stop(MinecraftServer server) {
		if (state == State.RUNNING) endGenerationTimer();
		if (state == State.RUNNING || state == State.PAUSED) {
			ServerLevel level = findLevel(server);
			if (level != null) {
				level.getChunkSource().save(true);
				generatedSinceCheckpoint = 0L;
			}
		}
		state = scan == null ? State.IDLE : State.READY;
		message = "工作已停止；已生成區塊會保留";
		planned = 0L;
		completed = 0L;
		resetGenerationTimer();
		ProgressStore.clear();
	}

	public synchronized void tick(MinecraftServer server) {
		if (state != State.RUNNING || scan == null || selected == null) return;
		ServerLevel level = findLevel(server);
		if (level == null) {
			endGenerationTimer();
			state = State.PAUSED;
			message = "找不到原維度，已暫停";
			saveProgress();
			return;
		}

		int generatedThisTick = 0;
		int coordinateChecks = 0;
		try {
			while (generatedThisTick < chunksPerTick && coordinateChecks++ < MAX_COORDINATE_CHECKS_PER_TICK) {
				if (nextZ > selected.maxZ()) {
					finish(level);
					return;
				}
				int x = nextX;
				int z = nextZ;
				advanceCursor();
				if (scan.isMapReady(x, z) || scan.isCorrupt(x, z)) continue;

				boolean wasPartial = scan.isPartial(x, z);
				ChunkAccess chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FEATURES, true);
				if (chunk == null) throw new IllegalStateException("Minecraft returned no chunk for " + x + "," + z);
				scan.markMapReady(x, z);
				if (wasPartial && partialInside > 0L) partialInside--;
				completed++;
				generatedThisTick++;
				generatedSinceCheckpoint++;

				if ((generatedSinceCheckpoint & 63L) == 0L) {
					lastUsableBytes = usableBytes(scan.dimensionPath());
					if (lastUsableBytes < MIN_FREE_BYTES) {
						endGenerationTimer();
						state = State.PAUSED;
						message = "可用硬碟空間低於 2 GiB，已自動暫停";
						level.getChunkSource().save(true);
						generatedSinceCheckpoint = 0L;
						saveProgress();
						return;
					}
				}
				if (generatedSinceCheckpoint >= SAVE_CHECKPOINT_INTERVAL) {
					// Queue dirty chunks without blocking the server thread. Resume always
					// rescans disk instead of trusting this cursor, so an interrupted write
					// is detected as a missing chunk and generated again.
					level.getChunkSource().save(false);
					generatedSinceCheckpoint = 0L;
					saveProgress();
				}
			}
			message = String.format(
				Locale.ROOT,
				"生成中：%,d / %,d（%.1f%%）｜剩餘 %s",
				completed,
				planned,
				progressPercent(),
				formatEta(estimateGenerationRemainingMillis())
			);
		} catch (Throwable throwable) {
			endGenerationTimer();
			state = State.ERROR;
			message = "生成失敗並已停止：" + rootMessage(throwable);
			ChunkPatchMod.LOGGER.error("Chunk generation failed", throwable);
			level.getChunkSource().save(true);
			generatedSinceCheckpoint = 0L;
			saveProgress();
		}
	}

	private void finish(ServerLevel level) {
		endGenerationTimer();
		level.getChunkSource().save(true);
		generatedSinceCheckpoint = 0L;
		scan.rebuildPreview();
		state = State.COMPLETE;
		message = String.format(Locale.ROOT, "完成：已讓 %,d 個區塊達到 Xaero 可繪階段；請重新進入世界後讓 Xaero Reload Regions", completed);
		ProgressStore.clear();
	}

	private void advanceCursor() {
		if (nextX >= selected.maxX()) {
			nextX = selected.minX();
			nextZ++;
		} else {
			nextX++;
		}
	}

	private ServerLevel findLevel(MinecraftServer server) {
		if (dimensionId == null) return null;
		for (ServerLevel level : server.getAllLevels()) {
			if (dimensionId.equals(level.dimension().location().toString())) return level;
		}
		return null;
	}

	private static long countInside(LongIterator iterator, ChunkBounds bounds) {
		long count = 0L;
		while (iterator.hasNext()) {
			long packed = iterator.nextLong();
			if (bounds.contains(ChunkPos.getX(packed), ChunkPos.getZ(packed))) count++;
		}
		return count;
	}

	private static boolean insideWorldBorder(WorldBorder border, ChunkBounds bounds) {
		return bounds.minBlockX() >= Math.floor(border.getMinX())
			&& bounds.maxBlockX() <= Math.ceil(border.getMaxX()) - 1.0D
			&& bounds.minBlockZ() >= Math.floor(border.getMinZ())
			&& bounds.maxBlockZ() <= Math.ceil(border.getMaxZ()) - 1.0D;
	}

	private static long usableBytes(Path path) {
		try {
			return Files.getFileStore(path).getUsableSpace();
		} catch (Exception exception) {
			return Long.MAX_VALUE;
		}
	}

	private static String rootMessage(Throwable throwable) {
		Throwable current = throwable;
		while (current.getCause() != null) current = current.getCause();
		return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
	}

	private long estimateScanRemainingMillis() {
		if (scanStartedNanos == 0L || scannedRegionFiles <= 0 || totalRegionFiles <= scannedRegionFiles) {
			return totalRegionFiles > 0 && scannedRegionFiles >= totalRegionFiles ? 0L : -1L;
		}
		long elapsedNanos = Math.max(1L, System.nanoTime() - scanStartedNanos);
		double remainingNanos = elapsedNanos * (totalRegionFiles - (double)scannedRegionFiles) / scannedRegionFiles;
		return (long)Math.min(Long.MAX_VALUE, remainingNanos / 1_000_000.0D);
	}

	private long estimateGenerationRemainingMillis() {
		if (completed <= 0L || planned <= completed) return planned > 0L && completed >= planned ? 0L : -1L;
		long activeNanos = generationActiveNanos;
		if (generationRunStartedNanos != 0L) activeNanos += Math.max(0L, System.nanoTime() - generationRunStartedNanos);
		if (activeNanos <= 0L) return -1L;
		double remainingNanos = activeNanos * (planned - (double)completed) / completed;
		return (long)Math.min(Long.MAX_VALUE, remainingNanos / 1_000_000.0D);
	}

	private void resetGenerationTimer() {
		generationActiveNanos = 0L;
		generationRunStartedNanos = 0L;
	}

	private void beginGenerationTimer() {
		if (generationRunStartedNanos == 0L) generationRunStartedNanos = System.nanoTime();
	}

	private void endGenerationTimer() {
		if (generationRunStartedNanos == 0L) return;
		generationActiveNanos += Math.max(0L, System.nanoTime() - generationRunStartedNanos);
		generationRunStartedNanos = 0L;
	}

	private static String formatEta(long milliseconds) {
		if (milliseconds < 0L) return "計算中";
		if (milliseconds > TimeUnit.DAYS.toMillis(36_500L)) return "超過 100 年";
		long seconds = milliseconds / 1_000L + (milliseconds % 1_000L == 0L ? 0L : 1L);
		long days = seconds / 86_400L;
		long hours = seconds % 86_400L / 3_600L;
		long minutes = seconds % 3_600L / 60L;
		long remainingSeconds = seconds % 60L;
		String duration;
		if (days > 0L) duration = String.format(Locale.ROOT, "%d天 %d時", days, hours);
		else if (hours > 0L) duration = String.format(Locale.ROOT, "%d時 %d分", hours, minutes);
		else if (minutes > 0L) duration = String.format(Locale.ROOT, "%d分 %d秒", minutes, remainingSeconds);
		else duration = remainingSeconds + "秒";
		String completion = seconds >= 86_400L
			? LocalDateTime.now().plusSeconds(seconds).format(DATE_TIME)
			: LocalTime.now().plusSeconds(seconds).format(CLOCK_TIME);
		return duration + "（約 " + completion + "）";
	}

	private double progressPercent() {
		return planned <= 0L ? 100.0D : Math.min(100.0D, completed * 100.0D / planned);
	}

	private void saveProgress() {
		if (scan == null || selected == null) return;
		ProgressStore.ProgressData data = new ProgressStore.ProgressData();
		data.dimensionPath = scan.dimensionPath().toString();
		data.dimensionId = scan.dimensionId();
		data.minX = selected.minX();
		data.maxX = selected.maxX();
		data.minZ = selected.minZ();
		data.maxZ = selected.maxZ();
		data.nextX = nextX;
		data.nextZ = nextZ;
		data.planned = planned;
		data.completed = completed;
		data.chunksPerTick = chunksPerTick;
		ProgressStore.save(data);
	}

	private void restoreProgressIfCompatible() {
		ProgressStore.load().ifPresent(data -> {
			if (data.dimensionPath == null || data.dimensionId == null) return;
			if (!data.dimensionPath.equals(scan.dimensionPath().toString()) || !data.dimensionId.equals(scan.dimensionId())) return;
			try {
				selected = new ChunkBounds(data.minX, data.maxX, data.minZ, data.maxZ);
				// The region scan is the source of truth after a restart. Start at the
				// selection origin and skip chunks that are actually present on disk;
				// this also repairs gaps left by a crash or an older ChunkPatch build.
				existingInside = countInside(scan.fullIterator(), selected) + countInside(scan.renderableIterator(), selected);
				partialInside = countInside(scan.partialIterator(), selected);
				corruptInside = countInside(scan.corruptIterator(), selected);
				planned = Math.max(0L, selected.area() - existingInside - corruptInside);
				completed = 0L;
				resetGenerationTimer();
				nextX = selected.minX();
				nextZ = selected.minZ();
				generatedSinceCheckpoint = 0L;
				chunksPerTick = Math.max(1, Math.min(8, data.chunksPerTick));
				state = State.PAUSED;
				message = String.format(Locale.ROOT, "找到未完成工作：重新核對後尚缺 %,d，可按繼續", planned);
			} catch (RuntimeException exception) {
				ChunkPatchMod.LOGGER.warn("Saved progress is invalid", exception);
			}
		});
	}

	public synchronized StatusSnapshot snapshot() {
		return new StatusSnapshot(
			state,
			message,
			dimensionId,
			scan == null ? null : scan.detectedBounds(),
			selected,
			scan == null ? 0L : scan.fullCount(),
			scan == null ? 0L : scan.renderableCount(),
			scan == null ? 0L : scan.partialCount(),
			scan == null ? 0L : scan.corruptCount(),
			scan == null ? 0 : scan.regionFileCount(),
			scan == null ? 0 : scan.unreadableRegionFileCount(),
			scan == null ? 0 : scan.cachedRegionFileCount(),
			scan == null ? 0 : scan.rescannedRegionFileCount(),
			scannedRegionFiles,
			totalRegionFiles,
			estimateScanRemainingMillis(),
			existingInside,
			partialInside,
			corruptInside,
			planned,
			completed,
			nextX,
			nextZ,
			chunksPerTick,
			estimateGenerationRemainingMillis(),
			lastUsableBytes,
			scan == null ? null : scan.preview()
		);
	}

	public synchronized String statusLine() {
		return message;
	}

	public enum State { IDLE, SCANNING, READY, RUNNING, PAUSED, COMPLETE, ERROR }

	public record StatusSnapshot(
		State state,
		String message,
		String dimensionId,
		ChunkBounds detectedBounds,
		ChunkBounds selectedBounds,
		long fullChunks,
		long renderableChunks,
		long partialChunks,
		long corruptChunks,
		int regionFiles,
		int unreadableRegionFiles,
		int cachedRegionFiles,
		int rescannedRegionFiles,
		int scannedRegionFiles,
		int totalRegionFiles,
		long scanRemainingMillis,
		long existingInside,
		long partialInside,
		long corruptInside,
		long planned,
		long completed,
		int nextChunkX,
		int nextChunkZ,
		int chunksPerTick,
		long generationRemainingMillis,
		long usableBytes,
		ChunkScanResult.PreviewData preview
	) {
		public double progressPercent() {
			return planned <= 0L ? 0.0D : Math.min(100.0D, completed * 100.0D / planned);
		}

		public long mapReadyChunks() {
			return fullChunks + renderableChunks;
		}

		public double scanProgressPercent() {
			return totalRegionFiles <= 0 ? 0.0D : Math.min(100.0D, scannedRegionFiles * 100.0D / totalRegionFiles);
		}

		public String scanEtaText() {
			return formatEta(scanRemainingMillis);
		}

		public String generationEtaText() {
			return formatEta(generationRemainingMillis);
		}
	}
}
