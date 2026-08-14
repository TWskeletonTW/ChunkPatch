package tw.skeleton.chunkpatch.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public final class ChunkPatchClient implements ClientModInitializer {
	private static KeyMapping openKey;

	@Override
	public void onInitializeClient() {
		openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.chunkpatch.open",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_K,
			"category.chunkpatch"
		));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openKey.consumeClick()) {
				if (client.level != null && client.hasSingleplayerServer()) {
					client.setScreen(new ChunkFillScreen());
				} else if (client.player != null) {
					client.player.displayClientMessage(net.minecraft.network.chat.Component.translatable("chunkpatch.no_world"), false);
				}
			}
		});
	}
}
