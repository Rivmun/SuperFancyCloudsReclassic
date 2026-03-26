package com.rimo.sfcr.mixin;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.texture.TextureManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static com.rimo.sfcr.Client.RENDERER;
import static com.rimo.sfcr.Common.CONFIG;

@Mixin(RenderGlobal.class)
public class RenderGlobalMixin {
	@Shadow private WorldClient world;
	@Shadow private int cloudTickCounter;
	@Shadow @Final private TextureManager renderEngine;

	@Inject(method = "renderCloudsFancy", at = @At("HEAD"), cancellable = true)
	private void sfcr$renderCloudsFancy(float partialTicks, int renderPass, double camX, double camY, double camZ, CallbackInfo ci) {
		if (CONFIG.isEnableRender() && world.provider.hasSkyLight()) {
			RENDERER.renderClassic(partialTicks, renderPass, camX, camY, camZ, world, cloudTickCounter, renderEngine);
			ci.cancel();
		}
	}
}
