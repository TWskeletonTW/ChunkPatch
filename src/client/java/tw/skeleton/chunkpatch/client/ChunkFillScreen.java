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
import tw.skeleton.chunkpatch.ChunkPatchMod;
import tw.skeleton.chunkpatch.ChunkBounds;
import tw.skeleton.chunkpatch.ChunkFillController;
import tw.skeleton.chunkpatch.ChunkScanResult;

import java.util.Locale;

public final class ChunkFillScreen extends Screen {
	private EditBox minXField;
	private EditBox maxXField;
	private EditBox minZField;
	private EditBox maxZField;
	private EditBox rateField;
	private Button startButton;
	private Button pauseButton;
	private boolean seededFields;
	private int previewX;
	private int previewY;
	private int previewWidth;
	private int previewHeight;
	private int legendY;
	private int detailsY;
	private int draggingEdge;

	public ChunkFillScreen() {
		super(Component.translatable("chunkpatch.title"));
	}

	@Override
	protected void init() {
		previewX = 18;
		previewY = 40;
		previewWidth = Math.max(0, width - 36);

		int buttonGap = 6;
		int buttonColumns = width < 650 ? 3 : 6;
		int buttonRows = 6 / buttonColumns;
		int buttonWidth = Math.min(104, Math.max(48, (width - 20 - buttonGap * (buttonColumns - 1)) / buttonColumns));
		int buttonsTotal = buttonWidth * buttonColumns + buttonGap * (buttonColumns - 1);
		int buttonX = Math.max(4, (width - buttonsTotal) / 2);
		int buttonY = height - buttonRows * 24 - 8;

		int fieldGap = 4;
		int fieldWidth = Math.min(112, Math.max(36, (width - 20 - fieldGap * 4) / 5));
		int fieldsTotal = fieldWidth * 5 + fieldGap * 4;
		int fieldX = Math.max(2, (width - fieldsTotal) / 2);
		int fieldY = buttonY - 42;
		minXField = coordinateField(fieldX, fieldY, fieldWidth, "chunkpatch.min_x");
		maxXField = coordinateField(fieldX + fieldWidth + fieldGap, fieldY, fieldWidth, "chunkpatch.max_x");
		minZField = coordinateField(fieldX + (fieldWidth + fieldGap) * 2, fieldY, fieldWidth, "chunkpatch.min_z");
		maxZField = coordinateField(fieldX + (fieldWidth + fieldGap) * 3, fieldY, fieldWidth, "chunkpatch.max_z");
		rateField = coordinateField(fieldX + (fieldWidth + fieldGap) * 4, fieldY, fieldWidth, "chunkpatch.rate");
		rateField.setValue("1");

		addRenderableWidget(Button.builder(Component.translatable("chunkpatch.scan"), button -> requestScan())
			.bounds(buttonXFor(0, buttonX, buttonWidth, buttonGap, buttonColumns), buttonYFor(0, buttonY, buttonColumns), buttonWidth, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("chunkpatch.estimate"), button -> applyAndEstimate())
			.bounds(buttonXFor(1, buttonX, buttonWidth, buttonGap, buttonColumns), buttonYFor(1, buttonY, buttonColumns), buttonWidth, 20).build());
		startButton = addRenderableWidget(Button.builder(Component.translatable("chunkpatch.start"), button -> start())
			.bounds(buttonXFor(2, buttonX, buttonWidth, buttonGap, buttonColumns), buttonYFor(2, buttonY, buttonColumns), buttonWidth, 20).build());
		pauseButton = addRenderableWidget(Button.builder(Component.translatable("chunkpatch.pause"), button -> pauseOrResume())
			.bounds(buttonXFor(3, buttonX, buttonWidth, buttonGap, buttonColumns), buttonYFor(3, buttonY, buttonColumns), buttonWidth, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("chunkpatch.stop"), button -> stop())
			.bounds(buttonXFor(4, buttonX, buttonWidth, buttonGap, buttonColumns), buttonYFor(4, buttonY, buttonColumns), buttonWidth, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("chunkpatch.close"), button -> onClose())
			.bounds(buttonXFor(5, buttonX, buttonWidth, buttonGap, buttonColumns), buttonYFor(5, buttonY, buttonColumns), buttonWidth, 20).build());

		legendY = fieldY - 40;
		detailsY = fieldY - 28;
		previewHeight = Math.max(0, legendY - previewY - 6);
		seedFromSnapshot();
	}

	private static int buttonXFor(int index, int startX, int buttonWidth, int gap, int columns) {
		return startX + (index % columns) * (buttonWidth + gap);
	}

	private static int buttonYFor(int index, int startY, int columns) {
		return startY + (index / columns) * 24;
	}

	@Override
	public void resize(Minecraft minecraft, int width, int height) {
		FieldValues values = captureFieldValues();
		draggingEdge = 0;
		super.resize(minecraft, width, height);
		if (values != null) restoreFieldValues(values);
	}

	private FieldValues captureFieldValues() {
		if (minXField == null || maxXField == null || minZField == null || maxZField == null || rateField == null) return null;
		return new FieldValues(
			minXField.getValue(),
			maxXField.getValue(),
			minZField.getValue(),
			maxZField.getValue(),
			rateField.getValue(),
			seededFields
		);
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
		pauseButton.setMessage(Component.translatable(snapshot.state() == ChunkFillController.State.PAUSED
			? "chunkpatch.resume"
			: "chunkpatch.pause"));
	}

	private void seedFromSnapshot() {
		if (seededFields) return;
		ChunkFillController.StatusSnapshot snapshot = ChunkPatchMod.CONTROLLER.snapshot();
		ChunkBounds bounds = snapshot.selectedBounds() != null ? snapshot.selectedBounds() : snapshot.detectedBounds();
		if (bounds != null) {
			putBoundsInFields(bounds);
			seededFields = true;
		}
	}

	private void requestScan() {
		seededFields = false;
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
		return ChunkBounds.fromBlocks(
			Integer.parseInt(minXField.getValue()),
			Integer.parseInt(maxXField.getValue()),
			Integer.parseInt(minZField.getValue()),
			Integer.parseInt(maxZField.getValue())
		);
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
		if (minecraft != null && minecraft.player != null) {
			minecraft.player.displayClientMessage(Component.literal(message), false);
		}
	}

	@Override
	public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
		renderBackground(poseStack);
		drawCenteredString(poseStack, font, title, width / 2, 12, 0xFFFFFF);
		ChunkFillController.StatusSnapshot snapshot = ChunkPatchMod.CONTROLLER.snapshot();
		drawCenteredString(poseStack, font, Component.literal(snapshot.message()), width / 2, 25, statusColor(snapshot.state()));
		renderPreview(poseStack, snapshot);
		renderLabels(poseStack);
		super.render(poseStack, mouseX, mouseY, partialTick);
	}

	private void renderPreview(PoseStack poseStack, ChunkFillController.StatusSnapshot snapshot) {
		if (previewWidth < 2 || previewHeight < 2) return;
		fill(poseStack, previewX - 1, previewY - 1, previewX + previewWidth + 1, previewY + previewHeight + 1, 0xFF8A94A6);
		fill(poseStack, previewX, previewY, previewX + previewWidth, previewY + previewHeight, 0xFF020509);
		ChunkScanResult.PreviewData preview = snapshot.preview();
		if (preview == null || snapshot.detectedBounds() == null) {
			drawCenteredString(poseStack, font, Component.literal("按「掃描世界」讀取目前維度"), previewX + previewWidth / 2, previewY + previewHeight / 2 - 4, 0xFFAAB4C4);
			return;
		}

		for (int pz = 0; pz < preview.height(); pz++) {
			int y0 = previewY + pz * previewHeight / preview.height();
			int y1 = previewY + (pz + 1) * previewHeight / preview.height();
			for (int px = 0; px < preview.width(); px++) {
				int index = pz * preview.width() + px;
				int density = preview.density(index);
				if (density == 0 && !preview.invalid()[index]) continue;
				int x0 = previewX + px * previewWidth / preview.width();
				int x1 = previewX + (px + 1) * previewWidth / preview.width();
				int color;
				if (preview.invalid()[index]) {
					color = 0xFFB91C1C;
				} else {
					int green = 45 + density * 170 / 255;
					int red = 8 + density * 24 / 255;
					color = 0xFF000000 | red << 16 | green << 8 | 35;
				}
				fill(poseStack, x0, y0, Math.max(x0 + 1, x1), Math.max(y0 + 1, y1), color);
			}
		}

		ChunkBounds selected = uiBounds(snapshot);
		if (selected != null) drawSelection(poseStack, snapshot.detectedBounds(), selected);
	}

	private void drawSelection(PoseStack poseStack, ChunkBounds detected, ChunkBounds selected) {
		int left = chunkToPreviewX(selected.minX(), detected);
		int right = chunkToPreviewX(selected.maxX() + 1, detected);
		int top = chunkToPreviewY(selected.minZ(), detected);
		int bottom = chunkToPreviewY(selected.maxZ() + 1, detected);
		left = Math.max(previewX, Math.min(previewX + previewWidth, left));
		right = Math.max(previewX, Math.min(previewX + previewWidth, right));
		top = Math.max(previewY, Math.min(previewY + previewHeight, top));
		bottom = Math.max(previewY, Math.min(previewY + previewHeight, bottom));
		int yellow = 0xFFFFD54A;
		fill(poseStack, left, top, Math.max(left + 2, right), top + 2, yellow);
		fill(poseStack, left, Math.max(top, bottom - 2), Math.max(left + 2, right), bottom, yellow);
		fill(poseStack, left, top, left + 2, Math.max(top + 2, bottom), yellow);
		fill(poseStack, Math.max(left, right - 2), top, right, Math.max(top + 2, bottom), yellow);
	}

	private void renderLabels(PoseStack poseStack) {
		int y = minXField.getY() - 11;
		drawString(poseStack, font, Component.translatable("chunkpatch.min_x"), minXField.getX(), y, 0xFFD7DEE9);
		drawString(poseStack, font, Component.translatable("chunkpatch.max_x"), maxXField.getX(), y, 0xFFD7DEE9);
		drawString(poseStack, font, Component.translatable("chunkpatch.min_z"), minZField.getX(), y, 0xFFD7DEE9);
		drawString(poseStack, font, Component.translatable("chunkpatch.max_z"), maxZField.getX(), y, 0xFFD7DEE9);
		drawString(poseStack, font, Component.translatable("chunkpatch.rate"), rateField.getX(), y, 0xFFD7DEE9);
		drawCenteredString(poseStack, font, Component.translatable("chunkpatch.legend"), width / 2, legendY, 0xFFB8C2D1);
		ChunkFillController.StatusSnapshot snapshot = ChunkPatchMod.CONTROLLER.snapshot();
		String details = String.format(Locale.ROOT, "維度 %s｜區域檔 %,d｜已存在 %,d｜異常 %,d｜待生成 %,d｜進度 %.1f%%",
			snapshot.dimensionId() == null ? "-" : snapshot.dimensionId(), snapshot.regionFiles(), snapshot.generatedChunks(), snapshot.corruptChunks(), snapshot.planned(), snapshot.progressPercent());
		drawCenteredString(poseStack, font, Component.literal(details), width / 2, detailsY, 0xFFE5E7EB);
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
		if (button == 0 && insidePreview(mouseX, mouseY)) {
			ChunkFillController.StatusSnapshot snapshot = ChunkPatchMod.CONTROLLER.snapshot();
			ChunkBounds detected = snapshot.detectedBounds();
			ChunkBounds selected = uiBounds(snapshot);
			if (detected != null && selected != null) {
				int left = chunkToPreviewX(selected.minX(), detected);
				int right = chunkToPreviewX(selected.maxX() + 1, detected);
				int top = chunkToPreviewY(selected.minZ(), detected);
				int bottom = chunkToPreviewY(selected.maxZ() + 1, detected);
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
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		if (button == 0 && draggingEdge != 0) {
			ChunkFillController.StatusSnapshot snapshot = ChunkPatchMod.CONTROLLER.snapshot();
			ChunkBounds detected = snapshot.detectedBounds();
			ChunkBounds selected = uiBounds(snapshot);
			if (detected != null && selected != null) {
				int x = previewToChunkX(mouseX, detected);
				int z = previewToChunkZ(mouseY, detected);
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
		return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		draggingEdge = 0;
		return super.mouseReleased(mouseX, mouseY, button);
	}

	private boolean insidePreview(double mouseX, double mouseY) {
		return previewWidth > 0 && previewHeight > 0
			&& mouseX >= previewX && mouseX <= previewX + previewWidth
			&& mouseY >= previewY && mouseY <= previewY + previewHeight;
	}

	private int chunkToPreviewX(int chunkX, ChunkBounds detected) {
		long range = (long)detected.maxX() - detected.minX() + 1L;
		return previewX + (int)(((long)chunkX - detected.minX()) * previewWidth / range);
	}

	private int chunkToPreviewY(int chunkZ, ChunkBounds detected) {
		long range = (long)detected.maxZ() - detected.minZ() + 1L;
		return previewY + (int)(((long)chunkZ - detected.minZ()) * previewHeight / range);
	}

	private int previewToChunkX(double mouseX, ChunkBounds detected) {
		long range = (long)detected.maxX() - detected.minX() + 1L;
		long offset = Math.max(0L, Math.min(previewWidth - 1L, (long)mouseX - previewX));
		return detected.minX() + (int)(offset * range / previewWidth);
	}

	private int previewToChunkZ(double mouseY, ChunkBounds detected) {
		long range = (long)detected.maxZ() - detected.minZ() + 1L;
		long offset = Math.max(0L, Math.min(previewHeight - 1L, (long)mouseY - previewY));
		return detected.minZ() + (int)(offset * range / previewHeight);
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
