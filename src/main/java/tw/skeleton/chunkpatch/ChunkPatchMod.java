package tw.skeleton.chunkpatch;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ChunkPatchMod implements ModInitializer {
	public static final String MOD_ID = "chunkpatch";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static final ChunkFillController CONTROLLER = new ChunkFillController();

	@Override
	public void onInitialize() {
		ServerTickEvents.END_SERVER_TICK.register(CONTROLLER::tick);
		registerCommands();
		LOGGER.info("ChunkPatch initialized");
	}

	private static void registerCommands() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("chunkfill")
				.executes(context -> {
					context.getSource().sendSuccess(Component.translatable("chunkpatch.command.help"), false);
					return 1;
				})
				.then(Commands.literal("scan").executes(context -> {
					CONTROLLER.requestScan(context.getSource().getServer(), context.getSource().getLevel());
					context.getSource().sendSuccess(Component.literal("已開始掃描目前維度"), false);
					return 1;
				}))
				.then(Commands.literal("status").executes(context -> {
					context.getSource().sendSuccess(Component.literal(CONTROLLER.statusLine()), false);
					return 1;
				}))
				.then(Commands.literal("pause").executes(context -> {
					CONTROLLER.pause(context.getSource().getServer());
					context.getSource().sendSuccess(Component.literal(CONTROLLER.statusLine()), false);
					return 1;
				}))
				.then(Commands.literal("resume").executes(context -> {
					CONTROLLER.resume(context.getSource().getLevel());
					context.getSource().sendSuccess(Component.literal(CONTROLLER.statusLine()), false);
					return 1;
				}))
				.then(Commands.literal("stop").executes(context -> {
					CONTROLLER.stop(context.getSource().getServer());
					context.getSource().sendSuccess(Component.literal(CONTROLLER.statusLine()), false);
					return 1;
				}));

			RequiredArgumentBuilder<CommandSourceStack, Integer> maxZ = Commands.argument("maxZ", IntegerArgumentType.integer(-30_000_000, 30_000_000));
			maxZ.executes(context -> startFromCommand(context, 1));
			maxZ.then(Commands.argument("rate", IntegerArgumentType.integer(1, 8))
				.executes(context -> startFromCommand(context, IntegerArgumentType.getInteger(context, "rate"))));
			RequiredArgumentBuilder<CommandSourceStack, Integer> minZ = Commands.argument("minZ", IntegerArgumentType.integer(-30_000_000, 30_000_000));
			minZ.then(maxZ);
			RequiredArgumentBuilder<CommandSourceStack, Integer> maxX = Commands.argument("maxX", IntegerArgumentType.integer(-30_000_000, 30_000_000));
			maxX.then(minZ);
			RequiredArgumentBuilder<CommandSourceStack, Integer> minX = Commands.argument("minX", IntegerArgumentType.integer(-30_000_000, 30_000_000));
			minX.then(maxX);
			root.then(Commands.literal("start").then(minX));
			dispatcher.register(root);
		});
	}

	private static int startFromCommand(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> context, int rate) {
		int minX = IntegerArgumentType.getInteger(context, "minX");
		int maxX = IntegerArgumentType.getInteger(context, "maxX");
		int minZ = IntegerArgumentType.getInteger(context, "minZ");
		int maxZ = IntegerArgumentType.getInteger(context, "maxZ");
		ChunkBounds bounds = ChunkBounds.fromBlocks(minX, maxX, minZ, maxZ);
		boolean prepared = CONTROLLER.prepare(context.getSource().getLevel(), bounds, rate);
		boolean started = prepared && CONTROLLER.start(context.getSource().getLevel());
		context.getSource().sendSuccess(Component.literal(CONTROLLER.statusLine()), false);
		return started ? 1 : 0;
	}
}
