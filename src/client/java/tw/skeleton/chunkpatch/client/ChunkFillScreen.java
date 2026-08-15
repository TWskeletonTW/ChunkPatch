package tw.skeleton.chunkpatch.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import tw.skeleton.chunkpatch.ChunkBounds;
import tw.skeleton.chunkpatch.ChunkFillController;
import tw.skeleton.chunkpatch.ChunkPatchMod;
import tw.skeleton.chunkpatch.ChunkScanResult;

import java.util.Locale;

/** Full-screen, zoomable overview and controls for ChunkPatch. */
public final class ChunkFillScreen extends Screen {
	private static final double MIN_CHUNKS_PER_PIXEL = 1.0D / 16.0D;
	private static final double VIEW_PADDING = 1.08D;
	private static final int PANEL_MIN_WIDTH = 238;
	private static final int PANEL_MAX_WIDTH = 300;

	private EditBox minXField;
	private EditBox maxXField;
	private EditBox minZField;
	private EditBox maxZField;
	private EditBox rateField;
	private Button startButton;
	private Button pauseButton;
	private boolean seededFields;

	private int mapX;
	private int mapY;
	private int mapWidth;
	private int mapHeight;
	private int panelX;
	private int panelY;
	private int panelWidth;
	private int panelHeight;

	private double viewCenterChunkX;
	private double viewCenterChunkZ;
	private double chunksPerPixel = 1.0D;
	private boolean cameraInitialized;
	private int draggingEdge;
	private boolean panning;

	public ChunkFillScreen() {
		super(Component.translatable("chunkpatch.title"));
	}

	@Override
	protected void init() {
		panelWidth = Math.min(PANEL_MAX_WIDTH, Math.max(PANEL_MIN_WIDTH, width / 4));
		panelWidth = Math.min(panelWidth, Math.max(180, width - 160));
		panelX = width - panelWidth - 8;
		panelY = 8;
		panelHeight = Math.max(80, height - 16);

		mapX = 8;
		mapY = 8;
		mapWidth = Math.max(80, panelX - mapX - 8);
		mapHeight = Math.max(80, height - 34);

		addMapToolbar();
		addControlPanelWidgets();
		seedFromSnapshot();
		ChunkBounds detected = ChunkPatchMod.CONTROLLER.snapshot().detectedBounds();
		if (detected != null) {
			if (!cameraInitialized) fitBounds(detected);
			else clampCamera(detected);
		}
	}

	private void addMapToolbar() {
		int x = mapX + 6;
		int y = mapY + 6;
		addRenderableWidget(Button.builder(Component.translatable("chunkpatch.fit_all"), button -> {
			ChunkBounds detected = ChunkPatchMod.CONTROLLER.snapshot().detectedBounds();
			if (detected != null) fitBounds(detected);
		}).bounds(x, y, 86, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("chunkpatch.fit_selection"), button -> {
			ChunkFillController.StatusSnapshot snapshot = ChunkPatchMod.CONTROLLER.snapshot();
			ChunkBounds selected = uiBounds(snapshot);
			if (selected != null) fitBounds(selected);
		}).bounds(x + 90, y, 86, 20).build());
		addRenderableWidget(Button.builder(Component.literal("+"), button -> zoomAt(0.65D, mapX + mapWidth / 2.0D, mapY + mapHeight / 2.0D))
			.bounds(x + 180, y, 22, 20).build());
		addRenderableWidget(Button.builder(Component.literal("−"), button -> zoomAt(1.0D / 0.65D, mapX + mapWidth / 2.0D, mapY + mapHeight / 2.0D))
			.bounds(x + 206, y, 22, 20).build());
	}

	private void addControlPanelWidgets() {
		int innerX = panelX + 10;
		int innerWidth = panelWidth - 20;
		int gap = 6;
		int half = (innerWidth - gap) / 2;
		int fieldY = panelY + 70;

		minXField = coordinateField(innerX, fieldY, half, "chunkpatch.min_x");
		maxXField = coordinateField(innerX + half + gap, fieldY, half, "chunkpatch.max_x");
		minZField = coordinateField(innerX, fieldY + 34, half, "chunkpatch.min_z");
		maxZField = coordinateField(innerX + half + gap, fieldY + 34, half, "chunkpatch.max_z");
		rateField = coordinateField(innerX, fieldY + 68, innerWidth, "chunkpatch.rate");
		rateField.setValue("1");

		int buttonY = fieldY + 101;
		addRenderableWidget(Button.builder(Component.translatable("chunkpatch.scan"), button -> requestScan())
			.bounds(innerX, buttonY, half, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("chunkpatch.estimate"), button -> applyAndEstimate())
			.bounds(innerX + half + gap, buttonY, half, 20).build());
		startButton = addRenderableWidget(Button.builder(Component.translatable("chunkpatch.start"), button -> start())
			.bounds(innerX, buttonY + 24, half, 20).build());
		pauseButton = addRenderableWidget(Button.builder(Component.translatable("chunkpatch.pause"), button -> pauseOrResume())
			.bounds(innerX + half + gap, buttonY + 24, half, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("chunkpatch.stop"), button -> stop())
			.bounds(innerX, buttonY + 48, half, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("chunkpatch.close"), button -> onClose())
			.bounds(innerX + half + gap, buttonY + 48, half, 20).build());
	}

	@Override
	public void resize(Minecraft minecraft, int width, int height) {
		FieldValues values = captureFieldValues();
		draggingEdge = 0;
		panning = false;
		super.resize(minecraft, width, height);
		if (values != null) restoreFieldValues(values);
	}

	private FieldValues captureFieldValues() {
		if (minXField == null || maxXField == null || minZField == null || maxZField == null || rateField == null) return null;
		return new FieldValues(minXField.getValue(), maxXField.getValue(), minZField.getValue(), maxZField.getValue(), rateField.getValue(), seededFields);
	}

	private void restoreFieldValues(FieldValues values) {
		minXField.setValue(values.minX());
		maxXField.setValue(values.maxX());
		minZField.setValue(values.minZ());
		maxZField.setValue(values.maxZ());
		rateField.setValue(values.rate());
		seededFields = values.seeded();
	}

	private EditBox coordinateField(int x, int y, int fieldWidth, String translationKey) {
		EditBox field = new EditBox(font, x, y, fieldWidth, 20, Component.translatable(translationKey));
		field.setFilter(value -> value.matches("-?\\d*"));
		field.setMaxLength(11);
		addRenderableWidget(field);
		return field;
	}

	@Override
	public void tick() {
		minXField.tick();
		maxXField.tick();
		minZField.tick();
		maxZField.tick();
		rateField.tick();
		seedFromSnapshot();
		ChunkFillController.StatusSnapshot snapshot = ChunkPatchMod.CONTROLLER.snapshot();
		startButton.active = snapshot.state() == ChunkFillController.State.READY && snapshot.planned() > 0L;
		pauseButton.active = snapshot.state() == ChunkFillController.State.RUNNING || snapshot.state() == ChunkFillController.State.PAUSED;
		pauseButton.setMessage(Component.translatable(snapshot.state() == ChunkFillController.State.PAUSED ? "chunkpatch.resume" : "chunkpatch.pause"));
	}

	private void seedFromSnapshot() {
		ChunkFillController.StatusSnapshot snapshot = ChunkPatchMod.CONTROLLER.snapshot();
		ChunkBounds bounds = snapshot.selectedBounds() != null ? snapshot.selectedBounds() : snapshot.detectedBounds();
		if (!seededFields && bounds != null) {
			putBoundsInFields(bounds);
			seededFields = true;
		}
		if (!cameraInitialized && snapshot.state() != ChunkFillController.State.SCANNING && snapshot.detectedBounds() != null) {
			fitBounds(snapshot.detectedBounds());
		}
	}

	private void requestScan() {
		seededFields = false;
		cameraInitialized = false;
		runOnServer((server, level) -> ChunkPatchMod.CONTROLLER.requestScan(server, level));
	}

	private void applyAndEstimate() {
		try {
			ChunkBounds bounds = boundsFromFields();
			int rate = Math.max(1, Math.min(8, Integer.parseInt(rateField.getValue())));
			rateField.setValue(Integer.toString(rate));
			runOnServer((server, level) -> ChunkPatchMod.CONTROLLER.prepare(level, bounds, rate));
		} catch (RuntimeException exception) {
			setLocalError("座標或速度格式不正確");
		}
	}

	private void start() {
		runOnServer((server, level) -> ChunkPatchMod.CONTROLLER.start(level));
	}

	private void pauseOrResume() {
		ChunkFillController.StatusSnapshot snapshot = ChunkPatchMod.CONTROLLER.snapshot();
		if (snapshot.state() == ChunkFillController.State.PAUSED) {
			runOnServer((server, level) -> ChunkPatchMod.CONTROLLER.resume(level));
		} else {
			runOnServer((server, level) -> ChunkPatchMod.CONTROLLER.pause(server));
		}
	}

	private void stop() {
		runOnServer((server, level) -> ChunkPatchMod.CONTROLLER.stop(server));
	}

	private ChunkBounds boundsFromFields() {
		return ChunkBounds.fromBlocks(Integer.parseInt(minXField.getValue()), Integer.parseInt(maxXField.getValue()), Integer.parseInt(minZField.getValue()), Integer.parseInt(maxZField.getValue()));
	}

	private void putBoundsInFields(ChunkBounds bounds) {
		minXField.setValue(Integer.toString(bounds.minBlockX()));
		maxXField.setValue(Integer.toString(bounds.maxBlockX()));
		minZField.setValue(Integer.toString(bounds.minBlockZ()));
		maxZField.setValue(Integer.toString(bounds.maxBlockZ()));
	}

	private void runOnServer(ServerAction action) {
		Minecraft client = Minecraft.getInstance();
		IntegratedServer server = client.getSingleplayerServer();
		if (server == null || client.level == null) {
			setLocalError(Component.translatable("chunkpatch.no_world").getString());
			return;
		}
		ResourceKey<Level> dimension = client.level.dimension();
		server.execute(() -> {
			ServerLevel level = server.getLevel(dimension);
			if (level != null) action.run(server, level);
		});
	}

	private void setLocalError(String message) {
		if (minecraft != null && minecraft.player != null) minecraft.player.displayClientMessage(Component.literal(message), false);
	}

	@Override
	public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
		renderBackground(poseStack);
		ChunkFillController.StatusSnapshot snapshot = ChunkPatchMod.CONTROLLER.snapshot();
		renderMap(poseStack, snapshot);
		renderPanel(poseStack, snapshot);
		renderMapReadout(poseStack, snapshot, mouseX, mouseY);
		super.render(poseStack, mouseX, mouseY, partialTick);
	}

	private void renderMap(PoseStack poseStack, ChunkFillController.StatusSnapshot snapshot) {
		fill(poseStack, mapX - 1, mapY - 1, mapX + mapWidth + 1, mapY + mapHeight + 1, 0xFF8A94A6);
		fill(poseStack, mapX, mapY, mapX + mapWidth, mapY + mapHeight, 0xFF020509);
		ChunkScanResult.PreviewData preview = snapshot.preview();
		ChunkBounds detected = snapshot.detectedBounds();
		if (preview == null || detected == null || !cameraInitialized) {
			drawCenteredString(poseStack, font, Component.translatable("chunkpatch.scan_prompt"), mapX + mapWidth / 2, mapY + mapHeight / 2 - 4, 0xFFAAB4C4);
			return;
		}

		long rangeX = (long)detected.maxX() - detected.minX() + 1L;
		long rangeZ = (long)detected.maxZ() - detected.minZ() + 1L;
		for (int pz = 0; pz < preview.height(); pz++) {
			double chunkZ0 = detected.minZ() + pz * rangeZ / (double)preview.height();
			double chunkZ1 = detected.minZ() + (pz + 1.0D) * rangeZ / preview.height();
			int rawY0 = chunkToScreenY(chunkZ0);
			int rawY1 = chunkToScreenY(chunkZ1);
			if (rawY1 < mapY || rawY0 > mapY + mapHeight) continue;
			for (int px = 0; px < preview.width(); px++) {
				int index = pz * preview.width() + px;
				int density = preview.density(index);
				int partialCount = preview.partialCounts()[index];
				if (density == 0 && partialCount == 0 && !preview.invalid()[index]) continue;
				double chunkX0 = detected.minX() + px * rangeX / (double)preview.width();
				double chunkX1 = detected.minX() + (px + 1.0D) * rangeX / preview.width();
				int rawX0 = chunkToScreenX(chunkX0);
				int rawX1 = chunkToScreenX(chunkX1);
				if (rawX1 < mapX || rawX0 > mapX + mapWidth) continue;
				int color;
				if (preview.invalid()[index]) {
					color = 0xFFB91C1C;
				} else if (partialCount > 0) {
					color = 0xFFD97706;
				} else {
					int green = 45 + density * 170 / 255;
					int red = 8 + density * 24 / 255;
					color = 0xFF000000 | red << 16 | green << 8 | 35;
				}
				int x0 = Math.max(mapX, rawX0);
				int x1 = Math.min(mapX + mapWidth, Math.max(rawX0 + 1, rawX1));
				int y0 = Math.max(mapY, rawY0);
				int y1 = Math.min(mapY + mapHeight, Math.max(rawY0 + 1, rawY1));
				if (x1 > x0 && y1 > y0) fill(poseStack, x0, y0, x1, y1, color);
			}
		}

		renderGrid(poseStack);
		ChunkBounds selected = uiBounds(snapshot);
		if (selected != null) drawSelection(poseStack, selected);
		if (snapshot.state() == ChunkFillController.State.RUNNING || snapshot.state() == ChunkFillController.State.PAUSED) {
			drawGenerationCursor(poseStack, snapshot.nextChunkX(), snapshot.nextChunkZ());
		}
	}

	private void drawGenerationCursor(PoseStack poseStack, int chunkX, int chunkZ) {
		int x = chunkToScreenX(chunkX + 0.5D);
		int y = chunkToScreenY(chunkZ + 0.5D);
		if (!insideMap(x, y)) return;
		int cyan = 0xFF4DEBFF;
		fill(poseStack, Math.max(mapX, x - 7), y, Math.min(mapX + mapWidth, x + 8), y + 1, cyan);
		fill(poseStack, x, Math.max(mapY, y - 7), x + 1, Math.min(mapY + mapHeight, y + 8), cyan);
		String cursorText = Component.translatable("chunkpatch.cursor").getString();
		int textX = Math.min(mapX + mapWidth - font.width(cursorText) - 3, x + 5);
		int textY = Math.max(mapY + 30, y - 11);
		drawString(poseStack, font, Component.literal(cursorText), textX, textY, cyan);
	}

	private void renderGrid(PoseStack poseStack) {
		if (chunksPerPixel <= 4.0D) drawGrid(poseStack, 32, 0x553F5068);
		if (chunksPerPixel <= 0.25D) drawGrid(poseStack, 1, 0x3326303D);
	}

	private void drawGrid(PoseStack poseStack, int step, int color) {
		double minChunkX = screenToChunkX(mapX);
		double maxChunkX = screenToChunkX(mapX + mapWidth);
		double minChunkZ = screenToChunkZ(mapY);
		double maxChunkZ = screenToChunkZ(mapY + mapHeight);
		int firstX = Math.floorDiv((int)Math.floor(minChunkX), step) * step;
		int firstZ = Math.floorDiv((int)Math.floor(minChunkZ), step) * step;
		for (int x = firstX; x <= maxChunkX; x += step) {
			int screenX = chunkToScreenX(x);
			if (screenX >= mapX && screenX <= mapX + mapWidth) fill(poseStack, screenX, mapY, screenX + 1, mapY + mapHeight, color);
		}
		for (int z = firstZ; z <= maxChunkZ; z += step) {
			int screenY = chunkToScreenY(z);
			if (screenY >= mapY && screenY <= mapY + mapHeight) fill(poseStack, mapX, screenY, mapX + mapWidth, screenY + 1, color);
		}
	}

	private void drawSelection(PoseStack poseStack, ChunkBounds selected) {
		int left = chunkToScreenX(selected.minX());
		int right = chunkToScreenX((double)selected.maxX() + 1.0D);
		int top = chunkToScreenY(selected.minZ());
		int bottom = chunkToScreenY((double)selected.maxZ() + 1.0D);
		int yellow = 0xFFFFD54A;
		boolean crossesHorizontally = right >= mapX && left <= mapX + mapWidth;
		boolean crossesVertically = bottom >= mapY && top <= mapY + mapHeight;
		if (crossesHorizontally && top >= mapY && top <= mapY + mapHeight) fill(poseStack, Math.max(mapX, left), top, Math.min(mapX + mapWidth, Math.max(left + 2, right)), top + 2, yellow);
		if (crossesHorizontally && bottom >= mapY && bottom <= mapY + mapHeight) fill(poseStack, Math.max(mapX, left), bottom - 2, Math.min(mapX + mapWidth, Math.max(left + 2, right)), bottom, yellow);
		if (crossesVertically && left >= mapX && left <= mapX + mapWidth) fill(poseStack, left, Math.max(mapY, top), left + 2, Math.min(mapY + mapHeight, Math.max(top + 2, bottom)), yellow);
		if (crossesVertically && right >= mapX && right <= mapX + mapWidth) fill(poseStack, right - 2, Math.max(mapY, top), right, Math.min(mapY + mapHeight, Math.max(top + 2, bottom)), yellow);
	}

	private void renderPanel(PoseStack poseStack, ChunkFillController.StatusSnapshot snapshot) {
		fill(poseStack, panelX - 1, panelY - 1, panelX + panelWidth + 1, panelY + panelHeight + 1, 0xFF697386);
		fill(poseStack, panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xE6111822);
		drawCenteredString(poseStack, font, title, panelX + panelWidth / 2, panelY + 10, 0xFFFFFFFF);
		String status = font.plainSubstrByWidth(snapshot.message(), panelWidth - 20);
		drawCenteredString(poseStack, font, Component.literal(status), panelX + panelWidth / 2, panelY + 27, statusColor(snapshot.state()));

		label(poseStack, "chunkpatch.min_x", minXField.getX(), minXField.getY() - 11);
		label(poseStack, "chunkpatch.max_x", maxXField.getX(), maxXField.getY() - 11);
		label(poseStack, "chunkpatch.min_z", minZField.getX(), minZField.getY() - 11);
		label(poseStack, "chunkpatch.max_z", maxZField.getX(), maxZField.getY() - 11);
		label(poseStack, "chunkpatch.rate", rateField.getX(), rateField.getY() - 11);

		int y = rateField.getY() + 110;
		panelLine(poseStack, Component.translatable("chunkpatch.legend").getString(), y, 0xFFB8C2D1);
		y += 17;
		panelLine(poseStack, "維度：" + (snapshot.dimensionId() == null ? "-" : snapshot.dimensionId()), y, 0xFFE5E7EB);
		y += 13;
		if (snapshot.state() == ChunkFillController.State.SCANNING) {
			panelLine(poseStack, String.format(Locale.ROOT, "掃描 %,d / %,d（%.1f%%）", snapshot.scannedRegionFiles(), snapshot.totalRegionFiles(), snapshot.scanProgressPercent()), y, 0xFFFFD54A);
			y += 13;
			panelLine(poseStack, "預估剩餘 " + snapshot.scanEtaText(), y, 0xFFFFD54A);
			return;
		}
		ChunkBounds detected = snapshot.detectedBounds();
		if (detected != null) {
			panelLine(poseStack, String.format(Locale.ROOT, "最外圍 X %,d ～ %,d", detected.minBlockX(), detected.maxBlockX()), y, 0xFFE5E7EB);
			y += 13;
			panelLine(poseStack, String.format(Locale.ROOT, "最外圍 Z %,d ～ %,d", detected.minBlockZ(), detected.maxBlockZ()), y, 0xFFE5E7EB);
			y += 13;
		}
		panelLine(poseStack, String.format(Locale.ROOT, "區域檔 %,d｜快取 %,d｜重掃 %,d", snapshot.regionFiles(), snapshot.cachedRegionFiles(), snapshot.rescannedRegionFiles()), y, 0xFFE5E7EB);
		y += 13;
		panelLine(poseStack, String.format(Locale.ROOT, "完整 %,d｜可繪半成品 %,d", snapshot.fullChunks(), snapshot.renderableChunks()), y, 0xFF63E68B);
		y += 13;
		panelLine(poseStack, String.format(Locale.ROOT, "不可繪半成品 %,d｜異常 %,d", snapshot.partialChunks(), snapshot.corruptChunks()), y, 0xFFFFB74D);
		y += 13;
		panelLine(poseStack, String.format(Locale.ROOT, "待生成到 FEATURES %,d", snapshot.planned()), y, 0xFFE5E7EB);
		y += 13;
		panelLine(poseStack, String.format(Locale.ROOT, "進度 %,d / %,d（%.1f%%）", snapshot.completed(), snapshot.planned(), snapshot.progressPercent()), y, 0xFFE5E7EB);
		if (snapshot.planned() > 0L && (snapshot.state() == ChunkFillController.State.RUNNING || snapshot.state() == ChunkFillController.State.PAUSED)) {
			y += 13;
			panelLine(poseStack, "預估剩餘 " + snapshot.generationEtaText(), y, 0xFFFFD54A);
		}
	}

	private void label(PoseStack poseStack, String key, int x, int y) {
		drawString(poseStack, font, Component.translatable(key), x, y, 0xFFD7DEE9);
	}

	private void panelLine(PoseStack poseStack, String text, int y, int color) {
		if (y + font.lineHeight <= panelY + panelHeight - 5) drawString(poseStack, font, font.plainSubstrByWidth(text, panelWidth - 20), panelX + 10, y, color);
	}

	private void renderMapReadout(PoseStack poseStack, ChunkFillController.StatusSnapshot snapshot, int mouseX, int mouseY) {
		drawCenteredString(poseStack, font, Component.translatable("chunkpatch.map_help"), mapX + mapWidth / 2, mapY + mapHeight + 8, 0xFFC7D0DD);
		if (!insideMap(mouseX, mouseY) || snapshot.detectedBounds() == null || !cameraInitialized) return;
		double chunkX = screenToChunkX(mouseX);
		double chunkZ = screenToChunkZ(mouseY);
		int blockX = (int)Math.floor(chunkX * 16.0D);
		int blockZ = (int)Math.floor(chunkZ * 16.0D);
		int cx = (int)Math.floor(chunkX);
		int cz = (int)Math.floor(chunkZ);
		String zoom = chunksPerPixel < 1.0D
			? String.format(Locale.ROOT, "1 區塊 = %.1f px", 1.0D / chunksPerPixel)
			: String.format(Locale.ROOT, "1 px = %.1f 區塊", chunksPerPixel);
		String text = String.format(Locale.ROOT, "X %,d  Z %,d｜Chunk %,d, %,d｜%s", blockX, blockZ, cx, cz, zoom);
		int boxWidth = Math.min(mapWidth - 12, font.width(text) + 10);
		int x = mapX + 6;
		int y = mapY + mapHeight - 18;
		fill(poseStack, x, y, x + boxWidth, y + 13, 0xCC05080D);
		drawString(poseStack, font, font.plainSubstrByWidth(text, boxWidth - 8), x + 4, y + 3, 0xFFFFFFFF);
	}

	private static int statusColor(ChunkFillController.State state) {
		return switch (state) {
			case ERROR -> 0xFFFF6B6B;
			case COMPLETE -> 0xFF63E68B;
			case RUNNING -> 0xFFFFD54A;
			default -> 0xFFE5E7EB;
		};
	}

	private ChunkBounds uiBounds(ChunkFillController.StatusSnapshot snapshot) {
		try {
			if (minXField != null && !minXField.getValue().isBlank()) return boundsFromFields();
		} catch (RuntimeException ignored) {
		}
		return snapshot.selectedBounds() != null ? snapshot.selectedBounds() : snapshot.detectedBounds();
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (super.mouseClicked(mouseX, mouseY, button)) return true;
		if (!insideMap(mouseX, mouseY) || button < 0 || button > 2) return false;
		if (button == 0) {
			ChunkFillController.StatusSnapshot snapshot = ChunkPatchMod.CONTROLLER.snapshot();
			ChunkBounds selected = uiBounds(snapshot);
			if (selected != null) {
				int left = chunkToScreenX(selected.minX());
				int right = chunkToScreenX((double)selected.maxX() + 1.0D);
				int top = chunkToScreenY(selected.minZ());
				int bottom = chunkToScreenY((double)selected.maxZ() + 1.0D);
				double dl = Math.abs(mouseX - left);
				double dr = Math.abs(mouseX - right);
				double dt = Math.abs(mouseY - top);
				double db = Math.abs(mouseY - bottom);
				double nearest = Math.min(Math.min(dl, dr), Math.min(dt, db));
				if (nearest <= 7.0D) {
					draggingEdge = nearest == dl ? 1 : nearest == dr ? 2 : nearest == dt ? 3 : 4;
					return true;
				}
			}
		}
		panning = true;
		return true;
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		if (button == 0 && draggingEdge != 0) {
			ChunkFillController.StatusSnapshot snapshot = ChunkPatchMod.CONTROLLER.snapshot();
			ChunkBounds selected = uiBounds(snapshot);
			if (selected != null) {
				int x = (int)Math.floor(screenToChunkX(mouseX));
				int z = (int)Math.floor(screenToChunkZ(mouseY));
				ChunkBounds changed = switch (draggingEdge) {
					case 1 -> ChunkBounds.normalized(x, selected.maxX(), selected.minZ(), selected.maxZ());
					case 2 -> ChunkBounds.normalized(selected.minX(), x, selected.minZ(), selected.maxZ());
					case 3 -> ChunkBounds.normalized(selected.minX(), selected.maxX(), z, selected.maxZ());
					default -> ChunkBounds.normalized(selected.minX(), selected.maxX(), selected.minZ(), z);
				};
				putBoundsInFields(changed);
				return true;
			}
		}
		if (panning && insideMap(mouseX, mouseY)) {
			viewCenterChunkX -= dragX * chunksPerPixel;
			viewCenterChunkZ -= dragY * chunksPerPixel;
			ChunkBounds detected = ChunkPatchMod.CONTROLLER.snapshot().detectedBounds();
			if (detected != null) clampCamera(detected);
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		boolean handled = draggingEdge != 0 || panning;
		draggingEdge = 0;
		panning = false;
		return super.mouseReleased(mouseX, mouseY, button) || handled;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
		if (insideMap(mouseX, mouseY)) {
			zoomAt(Math.pow(0.8D, delta), mouseX, mouseY);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, delta);
	}

	private void zoomAt(double factor, double mouseX, double mouseY) {
		ChunkBounds detected = ChunkPatchMod.CONTROLLER.snapshot().detectedBounds();
		if (detected == null || !cameraInitialized || mapWidth <= 0 || mapHeight <= 0) return;
		double anchorX = screenToChunkX(mouseX);
		double anchorZ = screenToChunkZ(mouseY);
		double maxScale = maximumChunksPerPixel(detected);
		chunksPerPixel = clamp(chunksPerPixel * factor, MIN_CHUNKS_PER_PIXEL, maxScale);
		viewCenterChunkX = anchorX - (mouseX - (mapX + mapWidth / 2.0D)) * chunksPerPixel;
		viewCenterChunkZ = anchorZ - (mouseY - (mapY + mapHeight / 2.0D)) * chunksPerPixel;
		clampCamera(detected);
	}

	private void fitBounds(ChunkBounds bounds) {
		if (mapWidth <= 0 || mapHeight <= 0) return;
		viewCenterChunkX = (bounds.minX() + (double)bounds.maxX() + 1.0D) / 2.0D;
		viewCenterChunkZ = (bounds.minZ() + (double)bounds.maxZ() + 1.0D) / 2.0D;
		double scaleX = ((double)bounds.maxX() - bounds.minX() + 1.0D) / mapWidth;
		double scaleZ = ((double)bounds.maxZ() - bounds.minZ() + 1.0D) / mapHeight;
		chunksPerPixel = Math.max(MIN_CHUNKS_PER_PIXEL, Math.max(scaleX, scaleZ) * VIEW_PADDING);
		cameraInitialized = true;
		ChunkBounds detected = ChunkPatchMod.CONTROLLER.snapshot().detectedBounds();
		if (detected != null) clampCamera(detected);
	}

	private void clampCamera(ChunkBounds detected) {
		chunksPerPixel = clamp(chunksPerPixel, MIN_CHUNKS_PER_PIXEL, maximumChunksPerPixel(detected));
		double halfWidth = mapWidth * chunksPerPixel / 2.0D;
		double halfHeight = mapHeight * chunksPerPixel / 2.0D;
		double minCenterX = detected.minX() - halfWidth * 0.75D;
		double maxCenterX = detected.maxX() + 1.0D + halfWidth * 0.75D;
		double minCenterZ = detected.minZ() - halfHeight * 0.75D;
		double maxCenterZ = detected.maxZ() + 1.0D + halfHeight * 0.75D;
		viewCenterChunkX = clamp(viewCenterChunkX, minCenterX, maxCenterX);
		viewCenterChunkZ = clamp(viewCenterChunkZ, minCenterZ, maxCenterZ);
	}

	private double maximumChunksPerPixel(ChunkBounds detected) {
		double fitX = ((double)detected.maxX() - detected.minX() + 1.0D) / Math.max(1, mapWidth);
		double fitZ = ((double)detected.maxZ() - detected.minZ() + 1.0D) / Math.max(1, mapHeight);
		return Math.max(1.0D, Math.max(fitX, fitZ) * 8.0D);
	}

	private boolean insideMap(double mouseX, double mouseY) {
		return mapWidth > 0 && mapHeight > 0 && mouseX >= mapX && mouseX <= mapX + mapWidth && mouseY >= mapY && mouseY <= mapY + mapHeight;
	}

	private int chunkToScreenX(double chunkX) {
		return (int)Math.round(mapX + mapWidth / 2.0D + (chunkX - viewCenterChunkX) / chunksPerPixel);
	}

	private int chunkToScreenY(double chunkZ) {
		return (int)Math.round(mapY + mapHeight / 2.0D + (chunkZ - viewCenterChunkZ) / chunksPerPixel);
	}

	private double screenToChunkX(double screenX) {
		return viewCenterChunkX + (screenX - (mapX + mapWidth / 2.0D)) * chunksPerPixel;
	}

	private double screenToChunkZ(double screenY) {
		return viewCenterChunkZ + (screenY - (mapY + mapHeight / 2.0D)) * chunksPerPixel;
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@FunctionalInterface
	private interface ServerAction {
		void run(MinecraftServer server, ServerLevel level);
	}

	private record FieldValues(String minX, String maxX, String minZ, String maxZ, String rate, boolean seeded) {
	}
}
