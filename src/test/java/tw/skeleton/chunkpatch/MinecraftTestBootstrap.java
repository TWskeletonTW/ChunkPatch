package tw.skeleton.chunkpatch;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Initializes Minecraft's registries once per test JVM so tests can
 * reference real Minecraft classes (needed to Mockito-mock ServerLevel /
 * MinecraftServer / ServerChunkCache, and to reference constants like
 * Level.OVERWORLD or ChunkStatus.FEATURES) without hitting
 * "Not bootstrapped" / "Game version not set" errors. Bootstrap.bootStrap()
 * re-registers blocks/items on every call, so this must run at most once.
 */
final class MinecraftTestBootstrap {
	private static final AtomicBoolean DONE = new AtomicBoolean(false);

	private MinecraftTestBootstrap() {
	}

	static void ensureBootstrapped() {
		if (DONE.compareAndSet(false, true)) {
			SharedConstants.tryDetectVersion();
			Bootstrap.bootStrap();
		}
	}
}
