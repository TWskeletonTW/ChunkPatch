package tw.skeleton.chunkpatch;

import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
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

/**
 * Coordinates scanning and generation. Mutations run on the integrated server
 * thread: either directly (scan/prepare/start/pause/resume/stop/tick, all
 * called from Fabric callbacks on the server thread) or marshalled there via
 * {@link MinecraftServer#execute} from generation-future completion
 * callbacks (see {@link GenerationScheduler}).
 *
 * Generation itself is delegated to a {@link GenerationScheduler}, which
 * enforces a strict single-in-flight invariant: at most one FEATURES
 * generation request is ever outstanding, and a new one is only submitted
 * after the previous one's completion has been processed. This replaced an
 * earlier design that called the blocking {@code getChunk(FEATURES, true)}
 * synchronously inside the server tick, throttled only by after-the-fact
 * cooldowns once a tick had already run long — see
 * ChunkPatch_REFACTOR_PLAN.md for the investigation that led to this.
 */
public final class ChunkFillController {
	private static final long MIN_FREE_BYTES = 2L * 1024L * 1024L * 1024L;
	private static final long MAX_SELECTION_AREA = 5_000_000L;
	// How many candidate coordinates a single tick may walk past (skipping
	// chunks that are already map-ready or corrupt) while looking for the
	// next target. Bounded so a tick spent entirely on lookups (e.g. resuming
	// in a mostly-already-generated selection) still stays cheap.
	private static final int CURSOR_SCAN_MAX_CHECKS_PER_TICK = 4_096;
	private static final long SAVE_CHECKPOINT_INTERVAL = 256L;
	// Admission gates checked before submitting the *next* target — proactive
	// backpressure instead of a cooldown applied after an expensive burst has
	// already happened. Thresholds are conservative starting points, not a
	// proven safe boundary; hysteresis avoids flapping right at the edge.
	private static final float MSPT_PAUSE_THRESHOLD_MS = 45.0f;
	private static final float MSPT_RESUME_THRESHOLD_MS = 35.0f;
	private static final double HEAP_PAUSE_RATIO = 0.85D;
	private static final double HEAP_RESUME_RATIO = 0.75D;
	public static final long MAX_EXACT_SAMPLE_AREA = 131_072L;
	public static final byte SAMPLE_MISSING = 0;
	public static final byte SAMPLE_MAP_READY = 1;
	public static final byte SAMPLE_PARTIAL = 2;
	public static final byte SAMPLE_CORRUPT = 3;
	private static final DateTimeFormatter CLOCK_TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm", Locale.ROOT);
	private static final ExecutorService SCAN_EXECUTOR = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "ChunkPatch Region Scanner");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});

	private final GenerationScheduler.Listener schedulerListener = new GenerationScheduler.Listener() {
		@Override
		public void marshalToOwnerThread(Runnable task) {
			// If there is no active server, this callback is stale (the world
			// already closed) and must be dropped rather than run inline: the
			// caller here can be the coordinator executor or a Minecraft
			// worldgen completion thread, and running the task on it would
			// mutate controller/scan state off the server thread, breaking the
			// single-owner-thread assumption the rest of this class relies on.
			MinecraftServer server = activeServer;
			if (server == null) return;
			server.execute(task);
		}

		@Override
		public void onTargetCompleted(int x, int z, boolean wasPartial) {
			handleTargetCompleted(wasPartial);
		}

		@Override
		public void onTargetFailed(int x, int z, Throwable error) {
			handleTargetFailed(x, z, error);
		}

		@Override
		public void onExhausted() {
			handleExhausted();
		}
	};

	private State state = State.IDLE;
	private String message = "尚未掃描";
	private ChunkScanResult scan;
	private ChunkBounds selected;
	private String dimensionId;
	private long scanSessionId;
	private GenerationScheduler scheduler;
	private MinecraftChunkGenerationGateway gateway;
	private MinecraftServer activeServer;
	private boolean laggingHold;
	private boolean heapPressureHold;
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
	// Minimum submission interval: after a target commits, the next one may
	// only be submitted on a later tick, never the same one. This is not the
	// old post-burst cooldown — it is a small, fixed safety margin (a single
	// tick) that gives Minecraft one scheduling opportunity for bookkeeping,
	// saves, and GC between a completion and the next submission.
	private int nextEligibleTick;

	public synchronized void requestScan(MinecraftServer server, ServerLevel level) {
		if (state == State.SCANNING) {
			message = "掃描已在進行中";
			return;
		}
		if (state == State.RUNNING || state == State.PAUSING || state == State.STOPPING) {
			message = "生成中不能重新掃描，請先暫停";
			return;
		}
		disposeScheduler("new scan requested");
		server.saveEverything(true, true, false);
		Path root = server.getWorldPath(LevelResource.ROOT);
		Path dimensionPath = DimensionType.getStorageFolder(level.dimension(), root).toAbsolutePath().normalize();
		String requestedDimension = level.dimension().location().toString();
		this.dimensionId = requestedDimension;
		this.activeServer = server;
		state = State.SCANNING;
		message = "正在掃描區域檔…";
		scannedRegionFiles = 0;
		totalRegionFiles = 0;
		scanStartedNanos = System.nanoTime();
		lastScanProgressMessageNanos = 0L;
		// A scan runs on a background thread while the world (and this
		// controller) can move on: the world can close, a new scan can be
		// requested, or a new world can open before this one finishes. The
		// token check in updateScanProgress/completeScan below makes sure a
		// result or progress update from this specific submission is ignored
		// once it is no longer the current scan.
		long submittedScanSession = ++scanSessionId;
		MinecraftServer submittedServer = server;
		CompletableFuture
			.supplyAsync(() -> {
				try {
					return RegionScanner.scan(
						dimensionPath,
						requestedDimension,
						(completedFiles, totalFiles) -> updateScanProgress(submittedScanSession, completedFiles, totalFiles)
					);
				} catch (Exception exception) {
					throw new RuntimeException(exception);
				}
			}, SCAN_EXECUTOR)
			.whenComplete((result, error) -> {
				if (submittedServer.isStopped()) return;
				submittedServer.execute(() -> completeScan(submittedScanSession, submittedServer, result, error));
			});
	}

	// Package-private (not private) so tests can drive the session-token guard
	// directly and deterministically instead of racing a real background scan.
	synchronized void updateScanProgress(long submittedScanSession, int completed, int total) {
		if (submittedScanSession != scanSessionId) {
			ChunkPatchMod.LOGGER.debug("Discarding stale scan progress (session {}, current {})", submittedScanSession, scanSessionId);
			return;
		}
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
			"掃描中：%,d / %,d（%.1f%%）｜尚餘 %,d 個檔案｜預估剩餘 %s",
			completed,
			total,
			percent,
			total - completed,
			formatEta(estimateScanRemainingMillis())
		);
	}

	// Package-private (not private) so tests can drive the session-token guard
	// directly and deterministically instead of racing a real background scan.
	synchronized void completeScan(long submittedScanSession, MinecraftServer submittedServer, ChunkScanResult result, Throwable error) {
		if (submittedScanSession != scanSessionId) {
			ChunkPatchMod.LOGGER.debug("Discarding stale scan result (session {}, current {})", submittedScanSession, scanSessionId);
			return;
		}
		if (submittedServer != activeServer) {
			ChunkPatchMod.LOGGER.debug("Discarding scan result from a server that is no longer active (session {})", submittedScanSession);
			return;
		}
		if (state != State.SCANNING) return;
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

	public synchronized boolean prepare(ServerLevel level, ChunkBounds bounds) {
		if (scan == null || scan.detectedBounds() == null) {
			message = "請先掃描目前維度";
			return false;
		}
		if (state == State.RUNNING || state == State.SCANNING || state == State.PAUSING || state == State.STOPPING) {
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
		disposeScheduler("selection changed");
		this.selected = bounds;
		this.existingInside = existing;
		this.partialInside = partial;
		this.corruptInside = invalid;
		this.planned = Math.max(0L, bounds.area() - existing - invalid);
		this.completed = 0L;
		this.generatedSinceCheckpoint = 0L;
		resetGenerationTimer();
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
		disposeScheduler("restarting generation");
		buildScheduler(level);
		laggingHold = false;
		heapPressureHold = false;
		nextEligibleTick = 0;
		generatedSinceCheckpoint = 0L;
		state = State.RUNNING;
		beginGenerationTimer();
		message = "正在生成 WorldMap 可繪區塊（目標 FEATURES）";
		saveProgress();
		return true;
	}

	public synchronized void pause(MinecraftServer server) {
		if (state != State.RUNNING) return;
		if (scheduler != null && scheduler.hasInFlight()) {
			state = State.PAUSING;
			message = "正在暫停：等待目前區塊生成完成…";
			return;
		}
		finalizePause(server);
	}

	private void finalizePause(MinecraftServer server) {
		endGenerationTimer();
		state = State.PAUSED;
		message = "已暫停並儲存進度";
		ServerLevel level = server == null ? null : findLevel(server);
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
		if (scheduler == null) {
			// PAUSED was reached via restoreProgressIfCompatible() after a
			// restart, which never built a scheduler since there was nothing
			// in flight to resume. Build one fresh, exactly like start() does.
			buildScheduler(level);
		}
		activeServer = level.getServer();
		laggingHold = false;
		heapPressureHold = false;
		nextEligibleTick = 0;
		state = State.RUNNING;
		beginGenerationTimer();
		message = "已繼續生成";
	}

	public synchronized void stop(MinecraftServer server) {
		boolean active = state == State.RUNNING || state == State.PAUSED || state == State.PAUSING || state == State.STOPPING;
		if (!active) return;
		if (scheduler != null && scheduler.hasInFlight()) {
			state = State.STOPPING;
			message = "正在停止：等待目前區塊生成完成…";
			return;
		}
		finalizeStop(server);
	}

	private void finalizeStop(MinecraftServer server) {
		endGenerationTimer();
		if (server != null) {
			ServerLevel level = findLevel(server);
			if (level != null) {
				level.getChunkSource().save(true);
				generatedSinceCheckpoint = 0L;
			}
		}
		disposeScheduler("stop");
		state = scan == null ? State.IDLE : State.READY;
		message = "工作已停止；已生成區塊會保留";
		planned = 0L;
		completed = 0L;
		resetGenerationTimer();
		ProgressStore.clear();
	}

	/**
	 * Called on server shutdown. Invalidates every in-flight async
	 * callback (generation and scan) and releases every in-memory reference
	 * to this world/server, so a completion arriving after the world has
	 * closed has no stale state left to touch, and so the old
	 * MinecraftServer/ServerLevel/ServerChunkCache graph becomes collectable
	 * instead of being retained by this mod-scoped, long-lived controller.
	 *
	 * Does NOT clear persisted resumable progress: closing a world normally
	 * is not the same as the user explicitly cancelling the job, and a scan
	 * after the next world open should still be able to offer to resume.
	 */
	public synchronized void onServerStopping() {
		invalidateScanSession();
		disposeScheduler("server shutdown");

		endGenerationTimer();

		activeServer = null;

		// World/session-owned memory.
		scan = null;
		selected = null;
		dimensionId = null;

		// Runtime-only state.
		laggingHold = false;
		heapPressureHold = false;
		generatedSinceCheckpoint = 0L;
		scannedRegionFiles = 0;
		totalRegionFiles = 0;
		lastUsableBytes = -1L;

		planned = 0L;
		completed = 0L;
		existingInside = 0L;
		partialInside = 0L;
		corruptInside = 0L;

		resetGenerationTimer();

		state = State.IDLE;
		message = "伺服器已關閉";
	}

	public synchronized void tick(MinecraftServer server) {
		if (state != State.RUNNING || scan == null || selected == null || scheduler == null) return;
		ServerLevel level = findLevel(server);
		if (level == null) {
			if (scheduler.hasInFlight()) {
				state = State.PAUSING;
				message = "找不到原維度：等待目前區塊生成完成後暫停…";
			} else {
				endGenerationTimer();
				state = State.PAUSED;
				message = "找不到原維度，已暫停";
				saveProgress();
			}
			return;
		}
		if (scheduler.hasInFlight()) return;
		if (server.getTickCount() < nextEligibleTick) return;
		if (usableBytes(scan.dimensionPath()) < MIN_FREE_BYTES) {
			endGenerationTimer();
			state = State.PAUSED;
			message = "可用硬碟空間低於 2 GiB，已自動暫停";
			level.getChunkSource().save(true);
			generatedSinceCheckpoint = 0L;
			saveProgress();
			return;
		}
		if (!admissionAllowed(server)) {
			message = laggingHold ? "伺服器負載較高，暫緩提交下一個目標…" : "記憶體使用較高，暫緩提交下一個目標…";
			return;
		}
		scheduler.tick(CURSOR_SCAN_MAX_CHECKS_PER_TICK);
	}

	/** Proactive admission gate checked before submitting the next target — not a cooldown applied after a burst already happened. */
	private boolean admissionAllowed(MinecraftServer server) {
		float mspt = server.getAverageTickTime();
		if (laggingHold) {
			if (mspt < MSPT_RESUME_THRESHOLD_MS) {
				laggingHold = false;
				ChunkPatchMod.LOGGER.info("Generation admission resumed: MSPT {} < {}", mspt, MSPT_RESUME_THRESHOLD_MS);
			}
		} else if (mspt > MSPT_PAUSE_THRESHOLD_MS) {
			laggingHold = true;
			ChunkPatchMod.LOGGER.info("Generation admission paused: MSPT {} > {}", mspt, MSPT_PAUSE_THRESHOLD_MS);
		}
		if (laggingHold) return false;

		Runtime runtime = Runtime.getRuntime();
		long max = runtime.maxMemory();
		long used = runtime.totalMemory() - runtime.freeMemory();
		double ratio = max <= 0L ? 0.0D : (double)used / max;
		if (heapPressureHold) {
			if (ratio < HEAP_RESUME_RATIO) {
				heapPressureHold = false;
				ChunkPatchMod.LOGGER.info("Generation admission resumed: heap {}% < {}%", Math.round(ratio * 100.0D), Math.round(HEAP_RESUME_RATIO * 100.0D));
			}
		} else if (ratio > HEAP_PAUSE_RATIO) {
			heapPressureHold = true;
			ChunkPatchMod.LOGGER.info("Generation admission paused: heap {}% > {}%", Math.round(ratio * 100.0D), Math.round(HEAP_PAUSE_RATIO * 100.0D));
		}
		return !heapPressureHold;
	}

	private synchronized void handleTargetCompleted(boolean wasPartial) {
		advanceNextEligibleTick();
		if (wasPartial && partialInside > 0L) partialInside--;
		completed++;
		generatedSinceCheckpoint++;
		checkpointIfNeeded();
		if (state == State.PAUSING) {
			finalizePause(activeServer);
			return;
		}
		if (state == State.STOPPING) {
			finalizeStop(activeServer);
			return;
		}
		if (state == State.RUNNING) updateRunningMessage();
	}

	private synchronized void handleTargetFailed(int x, int z, Throwable error) {
		advanceNextEligibleTick();
		if (state == State.PAUSING) {
			ChunkPatchMod.LOGGER.warn("Chunk generation failed for {},{} while pausing; pausing anyway ({})", x, z, rootMessage(error));
			finalizePause(activeServer);
			return;
		}
		if (state == State.STOPPING) {
			ChunkPatchMod.LOGGER.warn("Chunk generation failed for {},{} while stopping; stopping anyway ({})", x, z, rootMessage(error));
			finalizeStop(activeServer);
			return;
		}
		endGenerationTimer();
		state = State.ERROR;
		message = "生成失敗並已停止：" + rootMessage(error);
		ChunkPatchMod.LOGGER.error("Chunk generation failed for " + x + "," + z, error);
		ServerLevel level = activeServer == null ? null : findLevel(activeServer);
		if (level != null) level.getChunkSource().save(true);
		generatedSinceCheckpoint = 0L;
		disposeScheduler("generation error");
		saveProgress();
	}

	private synchronized void handleExhausted() {
		endGenerationTimer();
		ServerLevel level = activeServer == null ? null : findLevel(activeServer);
		if (level != null) level.getChunkSource().save(true);
		generatedSinceCheckpoint = 0L;
		scan.rebuildPreview();
		disposeScheduler("generation complete");
		state = State.COMPLETE;
		message = String.format(Locale.ROOT, "完成：已讓 %,d 個區塊達到 Xaero 可繪階段；請重新進入世界後讓 Xaero Reload Regions", completed);
		ProgressStore.clear();
	}

	private void advanceNextEligibleTick() {
		if (activeServer == null) return;
		nextEligibleTick = activeServer.getTickCount() + 1;
	}

	private void checkpointIfNeeded() {
		if (generatedSinceCheckpoint < SAVE_CHECKPOINT_INTERVAL || activeServer == null) return;
		ServerLevel level = findLevel(activeServer);
		if (level == null) return;
		// Queue dirty chunks without blocking the server thread. Resume always
		// relies on the in-memory scan (updated per completed target) rather
		// than trusting this checkpoint, so an interrupted write is detected
		// as a missing chunk and generated again next run.
		level.getChunkSource().save(false);
		generatedSinceCheckpoint = 0L;
		saveProgress();
	}

	private void updateRunningMessage() {
		message = String.format(
			Locale.ROOT,
			"生成中：%,d / %,d（%.1f%%）｜尚餘 %,d 個區塊｜預估剩餘 %s",
			completed,
			planned,
			progressPercent(),
			planned - completed,
			formatEta(estimateGenerationRemainingMillis())
		);
	}

	private void buildScheduler(ServerLevel level) {
		activeServer = level.getServer();
		MinecraftChunkGenerationGateway newGateway = new MinecraftChunkGenerationGateway(level.getChunkSource());
		this.gateway = newGateway;
		this.scheduler = new GenerationScheduler(newGateway, scan, selected, schedulerListener);
		Runtime runtime = Runtime.getRuntime();
		ChunkPatchMod.LOGGER.info(
			"Generation session {} starting: dimension={} bounds=({},{})..({},{}) planned={} heapMax={}MiB heapUsed={}MiB msptPauseAt={} heapPauseAt={}%",
			scheduler.sessionId(),
			dimensionId,
			selected.minX(), selected.minZ(), selected.maxX(), selected.maxZ(),
			planned,
			runtime.maxMemory() / 1_048_576L,
			(runtime.totalMemory() - runtime.freeMemory()) / 1_048_576L,
			MSPT_PAUSE_THRESHOLD_MS,
			Math.round(HEAP_PAUSE_RATIO * 100.0D)
		);
	}

	/** Makes any in-flight scan's progress/result callbacks no-ops once they arrive. */
	private void invalidateScanSession() {
		scanSessionId++;
	}

	/** Package-private test accessor; mirrors {@link GenerationScheduler#sessionId()}. */
	synchronized long scanSessionId() {
		return scanSessionId;
	}

	/**
	 * Test-only seam: puts the controller into SCANNING for {@code server}
	 * without touching disk or kicking off a real background
	 * {@code RegionScanner} task, so tests can deterministically drive
	 * {@link #completeScan} / {@link #updateScanProgress} with a real
	 * session/state instead of racing the real async scan pipeline that
	 * {@link #requestScan} starts.
	 */
	synchronized long beginScanSessionForTest(MinecraftServer server, String dimensionId) {
		disposeScheduler("test setup");
		this.dimensionId = dimensionId;
		this.activeServer = server;
		state = State.SCANNING;
		message = "正在掃描區域檔…";
		return ++scanSessionId;
	}

	private void disposeScheduler(String reason) {
		if (scheduler != null) {
			ChunkPatchMod.LOGGER.info(
				"Generation session {} invalidated ({}); target was {}in flight",
				scheduler.sessionId(),
				reason,
				scheduler.hasInFlight() ? "" : "not "
			);
			scheduler.invalidateSession();
		}
		if (gateway != null) gateway.close();
		scheduler = null;
		gateway = null;
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
		ProgressStore.save(data);
	}

	private void restoreProgressIfCompatible() {
		ProgressStore.load().ifPresent(data -> {
			if (data.dimensionPath == null || data.dimensionId == null) return;
			if (!data.dimensionPath.equals(scan.dimensionPath().toString()) || !data.dimensionId.equals(scan.dimensionId())) return;
			try {
				selected = new ChunkBounds(data.minX, data.maxX, data.minZ, data.maxZ);
				// The region scan is the source of truth after a restart. A
				// fresh scheduler (built lazily by resume()) starts at the
				// selection origin and skips chunks already present on disk;
				// this also repairs gaps left by a crash or an older build.
				existingInside = countInside(scan.fullIterator(), selected) + countInside(scan.renderableIterator(), selected);
				partialInside = countInside(scan.partialIterator(), selected);
				corruptInside = countInside(scan.corruptIterator(), selected);
				planned = Math.max(0L, selected.area() - existingInside - corruptInside);
				completed = 0L;
				resetGenerationTimer();
				generatedSinceCheckpoint = 0L;
				disposeScheduler("progress restored from disk");
				state = State.PAUSED;
				message = String.format(Locale.ROOT, "找到未完成工作：重新核對後尚缺 %,d，可按繼續", planned);
			} catch (RuntimeException exception) {
				ChunkPatchMod.LOGGER.warn("Saved progress is invalid", exception);
			}
		});
	}

	/**
	 * Exact per-chunk states for a small window, used by the zoomed-in preview.
	 * Returns one byte per chunk in row-major order: 0 missing, 1 map-ready,
	 * 2 partial, 3 corrupt. Returns null when no scan exists or the window is
	 * too large to sample every frame.
	 */
	public synchronized byte[] sampleChunkStates(int minX, int minZ, int width, int height) {
		if (scan == null || width <= 0 || height <= 0 || (long)width * height > MAX_EXACT_SAMPLE_AREA) return null;
		byte[] states = new byte[width * height];
		for (int dz = 0; dz < height; dz++) {
			int z = minZ + dz;
			int row = dz * width;
			for (int dx = 0; dx < width; dx++) {
				int x = minX + dx;
				byte state;
				if (scan.isCorrupt(x, z)) state = SAMPLE_CORRUPT;
				else if (scan.isMapReady(x, z)) state = SAMPLE_MAP_READY;
				else if (scan.isPartial(x, z)) state = SAMPLE_PARTIAL;
				else state = SAMPLE_MISSING;
				states[row + dx] = state;
			}
		}
		return states;
	}

	public synchronized StatusSnapshot snapshot() {
		int cursorX = scheduler != null ? scheduler.cursorX() : (selected != null ? selected.minX() : 0);
		int cursorZ = scheduler != null ? scheduler.cursorZ() : (selected != null ? selected.minZ() : 0);
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
			cursorX,
			cursorZ,
			estimateGenerationRemainingMillis(),
			lastUsableBytes,
			scan == null ? null : scan.preview()
		);
	}

	public synchronized String statusLine() {
		return message;
	}

	public enum State { IDLE, SCANNING, READY, RUNNING, PAUSING, PAUSED, STOPPING, COMPLETE, ERROR }

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
		long generationRemainingMillis,
		long usableBytes,
		ChunkScanResult.PreviewSnapshot preview
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
