package com.example.veinminer;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Client-only vein miner.
 *
 * START of tick: remember the block under your crosshair while you hold attack.
 * END of tick:   if that block just turned to air, flood-fill connected blocks of the same type and
 *                mine them one at a time with the normal client mining call.
 * The server just sees ordinary mining, so it does not need this mod.
 *
 * While the queue is running, MultiPlayerGameModeMixin stops vanilla's own "mine what's under the
 * crosshair" logic from resetting our mining progress every tick.
 */
public class VeinMinerClient implements ClientModInitializer {
	private static final int MAX_VEIN_SIZE = 64;
	private static final int STUCK_TICKS = 100;        // give up on a block after ~5 seconds
	private static final int MIN_DURABILITY_LEFT = 2;  // stop before the tool breaks
	private static final double MAX_REACH = 4.5;       // vanilla survival reach, measured to block center

	private static KeyMapping activateKey;
	private static final ArrayDeque<BlockPos> QUEUE = new ArrayDeque<>();
	private static int ticksOnCurrent = 0;

	private static BlockPos watchPos = null;
	private static BlockState watchState = null;

	private static boolean ourCall = false;

	// Set to false once everything works to silence the chat messages
	private static final boolean DEBUG = true;
	private static boolean announced = false;
	private static boolean lastKeyDown = false;
	private static String lastReport = "";

	/** True while a vein is being mined. Used by the mixin. */
	public static boolean isVeinActive() {
		return !QUEUE.isEmpty();
	}

	/** True while WE are calling continueDestroyBlock (so the mixin lets it through). */
	public static boolean isOurCall() {
		return ourCall;
	}

	@Override
	public void onInitializeClient() {
		// 26.3 replaced GLFW with SDL: use InputConstants.Type.KEYBOARD and InputConstants.KEY_* constants.
		activateKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.clientveinminer.activate",
				InputConstants.Type.KEYBOARD,
				InputConstants.KEY_V,
				KeyMapping.Category.MISC));

		ClientTickEvents.START_CLIENT_TICK.register(VeinMinerClient::onStartTick);
		ClientTickEvents.END_CLIENT_TICK.register(VeinMinerClient::onEndTick);
	}

	private static boolean isAllowed(BlockState state) {
		if (state.is(ConventionalBlockTags.ORES) || state.is(BlockTags.LOGS)) return true;
		// Fallback in case the tags are missing/renamed: match by block name, e.g. "Block{minecraft:iron_ore}"
		String name = state.getBlock().toString();
		return name.contains("_ore") || name.contains("ancient_debris");
	}

	private static void debug(Minecraft mc, String msg) {
		if (DEBUG && mc.player != null) {
			mc.player.sendSystemMessage(Component.literal("[Vein Miner] " + msg));
		}
	}

	private static void onStartTick(Minecraft mc) {
		watchPos = null;
		watchState = null;

		if (mc.player == null || mc.level == null) {
			announced = false;
			return;
		}
		if (!announced) {
			announced = true;
			debug(mc, "loaded. Hold " + activateKey.getTranslatedKeyMessage().getString() + " while mining an ore or log.");
		}

		boolean keyDown = activateKey.isDown();
		if (keyDown != lastKeyDown) {
			lastKeyDown = keyDown;
			if (keyDown) debug(mc, "key is held");
		}
		if (!QUEUE.isEmpty() || !keyDown) return;

		if (mc.options.keyAttack.isDown() && mc.hitResult instanceof BlockHitResult hit
				&& hit.getType() == HitResult.Type.BLOCK) {
			BlockState state = mc.level.getBlockState(hit.getBlockPos());
			String report = (isAllowed(state) ? "watching " : "ignoring (not an ore/log) ") + state.getBlock();
			if (!report.equals(lastReport)) {
				lastReport = report;
				debug(mc, report);
			}
			if (isAllowed(state)) {
				watchPos = hit.getBlockPos();
				watchState = state;
			}
		}
	}

	private static void onEndTick(Minecraft mc) {
		LocalPlayer player = mc.player;
		MultiPlayerGameMode gameMode = mc.gameMode;
		Level level = mc.level;

		if (player == null || gameMode == null || level == null || !activateKey.isDown()) {
			QUEUE.clear();
			return;
		}

		if (!QUEUE.isEmpty()) {
			processQueue(player, gameMode, level);
			return;
		}

		// The block we were mining at the start of this tick is now gone: start the vein.
		if (watchPos != null && level.getBlockState(watchPos).isAir()) {
			startVein(level, watchPos, watchState.getBlock());
			if (QUEUE.isEmpty()) {
				player.sendSystemMessage(Component.literal("[Vein Miner] No connected blocks found"));
			} else {
				player.sendSystemMessage(Component.literal("[Vein Miner] Mining " + QUEUE.size() + " more blocks"));
			}
		}
		watchPos = null;
		watchState = null;
	}

	private static void startVein(Level level, BlockPos origin, Block target) {
		Set<BlockPos> seen = new HashSet<>();
		ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
		seen.add(origin);
		frontier.add(origin);

		while (!frontier.isEmpty() && QUEUE.size() < MAX_VEIN_SIZE) {
			BlockPos current = frontier.poll();
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						if (dx == 0 && dy == 0 && dz == 0) continue;
						BlockPos next = current.offset(dx, dy, dz);
						if (!seen.add(next)) continue;
						if (level.getBlockState(next).is(target)) {
							frontier.add(next);
							if (QUEUE.size() < MAX_VEIN_SIZE) QUEUE.add(next);
						}
					}
				}
			}
		}
		ticksOnCurrent = 0;
	}

	private static void processQueue(LocalPlayer player, MultiPlayerGameMode gameMode, Level level) {
		if (toolAlmostBroken(player)) {
			QUEUE.clear();
			return;
		}

		BlockPos pos = QUEUE.peek();
		Vec3 center = Vec3.atCenterOf(pos);

		if (level.getBlockState(pos).isAir()) {
			QUEUE.poll();
			ticksOnCurrent = 0;
			return;
		}
		if (player.getEyePosition().distanceToSqr(center) > MAX_REACH * MAX_REACH || ++ticksOnCurrent > STUCK_TICKS) {
			QUEUE.poll();
			ticksOnCurrent = 0;
			return;
		}

		Direction face = Direction.getApproximateNearest(player.getEyePosition().subtract(center));
		ourCall = true;
		try {
			gameMode.continueDestroyBlock(pos, face);
		} finally {
			ourCall = false;
		}
	}

	private static boolean toolAlmostBroken(LocalPlayer player) {
		var stack = player.getMainHandItem();
		return stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= MIN_DURABILITY_LEFT;
	}
}
