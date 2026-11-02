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
 * While the activate key is held, we watch the block you are mining. When it turns to air, we flood-fill
 * connected blocks of the same type and mine them one at a time with the normal client mining call
 * (the same thing holding left-click does). The server just sees ordinary mining, so it does not need this mod.
 */
public class VeinMinerClient implements ClientModInitializer {
	private static final int MAX_VEIN_SIZE = 64;
	private static final int STUCK_TICKS = 100;        // give up on a block after ~5 seconds
	private static final int MIN_DURABILITY_LEFT = 2;  // stop before the tool breaks
	private static final double MAX_REACH = 4.5;       // vanilla survival reach, measured to block center

	private static KeyMapping activateKey;
	private static final ArrayDeque<BlockPos> QUEUE = new ArrayDeque<>();
	private static int ticksOnCurrent = 0;

	// The block we saw you mining last tick
	private static BlockPos watchPos = null;
	private static BlockState watchState = null;

	@Override
	public void onInitializeClient() {
		// 26.3 replaced GLFW with SDL: use InputConstants.Type.KEYBOARD and InputConstants.KEY_* constants.
		activateKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.clientveinminer.activate",
				InputConstants.Type.KEYBOARD,
				InputConstants.KEY_V,
				KeyMapping.Category.MISC));

		ClientTickEvents.END_CLIENT_TICK.register(VeinMinerClient::onTick);
	}

	private static boolean isAllowed(BlockState state) {
		return state.is(ConventionalBlockTags.ORES) || state.is(BlockTags.LOGS);
	}

	private static void onTick(Minecraft mc) {
		LocalPlayer player = mc.player;
		MultiPlayerGameMode gameMode = mc.gameMode;
		Level level = mc.level;

		if (player == null || gameMode == null || level == null || !activateKey.isDown()) {
			QUEUE.clear();
			watchPos = null;
			watchState = null;
			return;
		}

		if (!QUEUE.isEmpty()) {
			processQueue(player, gameMode, level);
			return;
		}

		boolean attackDown = mc.options.keyAttack.isDown();

		// Did the block we were mining just break?
		if (watchPos != null) {
			if (level.getBlockState(watchPos).isAir()) {
				startVein(level, watchPos, watchState.getBlock());
				watchPos = null;
				watchState = null;
				return;
			} else if (!attackDown) {
				watchPos = null;
				watchState = null;
			}
		}

		// Remember the block we are mining right now
		if (attackDown && mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
			BlockState state = level.getBlockState(hit.getBlockPos());
			if (isAllowed(state)) {
				watchPos = hit.getBlockPos();
				watchState = state;
			}
		}
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
		gameMode.continueDestroyBlock(pos, face);
	}

	private static boolean toolAlmostBroken(LocalPlayer player) {
		var stack = player.getMainHandItem();
		return stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= MIN_DURABILITY_LEFT;
	}
}
