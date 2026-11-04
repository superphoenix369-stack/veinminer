package com.example.veinminer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.example.veinminer.VeinMinerClient;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * While a vein is being mined, vanilla's per-tick "mine the block under the crosshair" (and "stop mining"
 * when nothing is targeted) would reset our mining progress every tick. Ignore vanilla's calls during that
 * time. require = 0 means a renamed method just disables the fix instead of crashing the game.
 */
@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {
	@Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true, require = 0)
	private void veinminer$ignoreVanillaContinue(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
		if (VeinMinerClient.isVeinActive() && !VeinMinerClient.isOurCall()) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "stopDestroyBlock", at = @At("HEAD"), cancellable = true, require = 0)
	private void veinminer$ignoreVanillaStop(CallbackInfo ci) {
		if (VeinMinerClient.isVeinActive() && !VeinMinerClient.isOurCall()) {
			ci.cancel();
		}
	}
}
