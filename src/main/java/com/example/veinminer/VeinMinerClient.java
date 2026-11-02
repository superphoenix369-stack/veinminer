package com.example.veinminer;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.player.v1.ClientPlayerBlockBreakEvents;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Client-only vein miner.
 *
 * After you break a block with the key held, we flood-fill connected blocks of the same type and
 * mine them one at a time with the normal client mining calls (the same thing holding left-click does).
 * The server just sees ordinary mining, so it does not need this mod.
 */
public class VeinMinerClient implements ClientModInitializer {
	private static final int MAX_VEIN_SIZE = 64;
	private static final int STUCK_TICKS = 100;      // give up on a block after ~5 seconds
	private static final int MIN_DURABILITY_LEFT = 2; // stop before the tool breaks

	private static KeyMapping activateKey;
	private static final ArrayDeque<BlockPos> QUEUE = new ArrayDeque<>();
	private static int ticksOnCurrent = 0;

	@Override
	public void onInitializeClient() {
		activateKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.clientveinminer.activate",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_GRAVE_ACCENT,
				KeyMapping.Category.MISC));

		ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> onBlockBroken(level, pos, state));
		ClientTickEvents.END_CLIENT_TICK.register(VeinMinerClient::onTick);
	}

	private static boolean isAllowed(BlockState state) {
		return state.is(ConventionalBlockTags.ORES) || state.is(BlockTags.LOGS);
	}

	private static void onBlockBroken(Level level, BlockPos origin, BlockState brokenState) {
		// Ignore breaks caused by our own queue, and only act while the key is held.
		if (!QUEUE.isEmpty() || !activateKey.isDown() || !isAllowed(brokenState)) {
			return;
		}

		Block target = brokenState.getBlock();
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

	private static void onTick(Minecraft mc) {
		if (QUEUE.isEmpty()) return;

		LocalPlayer player = mc.player;
		MultiPlayerGameMode gameMode = mc.gameMode;
		Level level = mc.level;

		if (player == null || gameMode == null || level == null
				|| !activateKey.isDown() || toolAlmostBroken(player)) {
			QUEUE.clear();
			return;
		}

		BlockPos pos = QUEUE.peek();

		if (level.getBlockState(pos).isAir()) {
			QUEUE.poll();
			ticksOnCurrent = 0;
			return;
		}
		if (!player.canInteractWithBlockAt(pos, 0.0) || ++ticksOnCurrent > STUCK_TICKS) {
			QUEUE.poll();
			ticksOnCurrent = 0;
			return;
		}

		Direction face = Direction.getApproximateNearest(player.getEyePosition().subtract(Vec3.atCenterOf(pos)));
		gameMode.continueDestroyBlock(pos, face);
		player.swing(InteractionHand.MAIN_HAND);
	}

	private static boolean toolAlmostBroken(LocalPlayer player) {
		var stack = player.getMainHandItem();
		return stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= MIN_DURABILITY_LEFT;
	}
}
