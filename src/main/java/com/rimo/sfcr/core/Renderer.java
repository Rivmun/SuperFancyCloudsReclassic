package com.rimo.sfcr.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.FrameTimer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.*;

import java.util.ArrayList;

import static com.rimo.sfcr.Common.*;

public class Renderer {
	private final ResourceLocation whiteTexture = new ResourceLocation(MOD_ID, "white.png");
	private ArrayList<Integer> cloudsBuffer;
	private final ArrayList<CloudData> cloudDataGroup = new ArrayList<>();
	protected boolean isResampling = false;
	protected Thread resamplingThread;
	protected float cloudHeight;
	protected int oldGridX, oldGridZ;
	private Vec3d oldColor = Vec3d.ZERO;
	protected double xOffset, zOffset;
	protected double resamplingTimer = 0.0;  //manual update counter
	private int rebuildTimer = 0;  //measure in ticks
	public int cullStateSkipped, cullStateShown;  //debug counter
	public double debugRebuildTime, debugUploadTime;

	public void renderClassic(float partialTicks, int renderPass, double camX, double camY, double camZ, WorldClient world, int cloudTickCounter, TextureManager renderEngine) {
		GlStateManager.disableCull();
		Tessellator tessellator = Tessellator.getInstance();
		BufferBuilder bufferbuilder = tessellator.getBuffer();
		boolean isPause = Minecraft.getMinecraft().isGamePaused();

		//pos calc
		float cloudBlockSize = 12.0F;
		float cloudBlockHeight = cloudBlockSize / 2F;

		double timeOffset = (float)cloudTickCounter + partialTicks;
		double cloudX = (camX + timeOffset * 0.03) / cloudBlockSize;
		double cloudZ = camZ / cloudBlockSize + 0.33;
		int gridX = (int) Math.floor(cloudX);
		int gridZ = (int) Math.floor(cloudZ);
		double xOffset = cloudX - gridX * cloudBlockSize;
		double zOffset = cloudZ - gridZ * cloudBlockSize;

		float cloudY = world.provider.getCloudHeight() - (float)camY + 0.33F;
		int cameraGridY = (int) (camY / cloudBlockHeight);
		Vec3d cloudColor = world.getCloudColour(partialTicks);

		//refresh check
		FrameTimer frameTimer = Minecraft.getMinecraft().getFrameTimer();
		resamplingTimer += frameTimer.getFrames()[frameTimer.getLastIndex()] / 1000000F * 0.25 * 0.25;
		if (! isPause && ! isResampling) {
			if (resamplingTimer > DATA.getResamplingInterval() || oldGridX != gridX || oldGridZ != gridZ || oldColor.squareDistanceTo(cloudColor) > 2.0E-4) {
				isResampling = true;
				resamplingTimer = 0.0;
				oldColor = cloudColor;
				resamplingThread = new Thread(() -> {  //start data refresh thread
					try {
						collectCloudData(gridX, cameraGridY, gridZ);
					} catch (Exception e) {
						exceptionCatcher(e);
					} finally {
						synchronized (this) {
							oldGridX = gridX;  //delayed update to prevent flicker
							oldGridZ = gridZ;
						}
						markForRebuild();  //let cloudCell rebuilt instantly
						isResampling = false;
					}
				});
				resamplingThread.start();
			}
			/*
			 * if only Y changed (condition in cloudData) and not in resampling, and in cloudLayer, just refresh mesh.
			 * Normal culling already does in 1.21.6+ vanilla mesh building, we must remesh it to prevent top/bottom face disappear when Y changed.
			 */
			int cloudGridHeight = (int) (cloudHeight / cloudBlockHeight);
			if (! isResampling && cameraGridY > cloudGridHeight && cameraGridY < cloudGridHeight + CONFIG.getCloudLayerThickness() + 1) {
				cloudDataGroup.forEach(cloudData -> cloudData.tryRebuildMesh(cameraGridY));
			}
		}

		//remesh
		boolean isEnableCulling = CONFIG.getEnableViewCulling();
		if (! isPause && (isEnableCulling && ++ rebuildTimer > CONFIG.getRebuildInterval() || ! isEnableCulling && rebuildTimer == 99)) {
			rebuildTimer = 0;
			debugRebuildTime = System.currentTimeMillis() / 1000000F;
			cloudsBuffer = rebuildCloudMesh(cloudColor, timeOffset, cloudY);
			debugRebuildTime = System.currentTimeMillis() / 1000000F - debugRebuildTime;
		}

		renderEngine.bindTexture(whiteTexture);
		GlStateManager.enableBlend();
		GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
		float red = (float)cloudColor.x;
		float green = (float)cloudColor.y;
		float blue = (float)cloudColor.z;

		if (renderPass != 2) {
			// 转换为灰度及红-绿、红-蓝混合
			float gray = (red * 30.0F + green * 59.0F + blue * 11.0F) / 100.0F;
			float redGreen = (red * 30.0F + green * 70.0F) / 100.0F;
			float redBlue = (red * 30.0F + blue * 70.0F) / 100.0F;
			red = gray;
			green = redGreen;
			blue = redBlue;
		}

		int blockWidth = 8;   // 每个云朵块的宽度（未直接使用，保留常量）
		int blockHeight = 4;  // 每个云朵块的高度（未直接使用）
		float epsilon = 9.765625E-4F; // 微小偏移，用于防止深度冲突

		// 缩放世界坐标，使每个云朵块在渲染时实际尺寸为 12*8 = 96 单位
		GlStateManager.scale(12.0F, 1.0F, 12.0F);

		debugUploadTime = System.currentTimeMillis() / 1000000F;
		// 两个渲染通道：第一个仅写深度，第二个根据 renderPass 设置颜色掩码
		for (int pass = 0; pass < 2; ++pass) {
			if (pass == 0) {
				GlStateManager.colorMask(false, false, false, false); // 仅深度
			} else {
				switch (renderPass) {
					case 0:
						GlStateManager.colorMask(false, true, true, true);
						break;
					case 1:
						GlStateManager.colorMask(true, false, false, true);
						break;
					case 2:
						GlStateManager.colorMask(true, true, true, true);
				}
			}

			bufferbuilder.begin(7, DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL);

			for (int i = 0; i < 4; i ++)  // empty builder will lead game crash... we draw a holder face to prevent that.
				bufferbuilder.pos(i, -99, i).tex(0.5f, 0.5f).color(0, 0, 0, 0).normal(0, -1, 0).endVertex();

			ArrayList<Integer> vertexList = this.cloudsBuffer;
			int normCount = vertexList.size() / 4;
			for (int i = 0; i < normCount; ++ i) {
				int[][] verCache = new int[][]{		// exacting data...
						CloudData.depressVertex(vertexList.get(i * 4)),
						CloudData.depressVertex(vertexList.get(i * 4 + 1)),
						CloudData.depressVertex(vertexList.get(i * 4 + 2)),
						CloudData.depressVertex(vertexList.get(i * 4 + 3))
				};
				Facing facing = Facing.get(CloudData.depressFromHead(vertexList.get(i * 4)));
				Vec3d faceColor = facing.color;
				if (CONFIG.isEnableBottomDim())
					faceColor = faceColor.scale(MathHelper.clamp((255 - CloudData.depressFromHead(vertexList.get(i * 4 + 1)) * 8) / 255f, 0f, 1f));
				int nx = facing.normal.getX();
				int ny = facing.normal.getY();
				int nz = facing.normal.getZ();
				for (int k = 0; k < 4; ++k) {
					bufferbuilder
							.pos(
									verCache[k][0] * cloudBlockSize + xOffset,
									verCache[k][1] * cloudBlockHeight + cloudY,
									verCache[k][2] * cloudBlockSize + zOffset + epsilon
							)
							.tex(0.5, 0.5)
							.color((float) (red * faceColor.x), (float) (green * faceColor.y), (float) (blue * faceColor.z), 0.8F)
							.normal(nx, ny, nz)
							.endVertex();
				}
			}

			tessellator.draw();
		}
		debugUploadTime = System.currentTimeMillis() / 1000000F - debugUploadTime;

		GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
		GlStateManager.disableBlend();
		GlStateManager.enableCull();
	}

	public void markForRebuild() {
		this.rebuildTimer = 99;
	}

	public void stop() {
		cloudDataGroup.forEach(CloudData::stop);
		try {
			if (resamplingThread != null)
				resamplingThread.join();
		} catch (Exception e) {
			//Ignore...
		}
	}

	enum Facing {
		EAST  (new Vec3i(1, 0, 0),  new Vec3d(0.95f, 0.9f,  0.9f)),
		WEST  (new Vec3i(-1, 0, 0), new Vec3d(0.75f, 0.75f, 0.75f)),
		TOP   (new Vec3i(0, 1, 0),  new Vec3d(1f,    1f,    1f)),
		BOTTOM(new Vec3i(0, -1, 0), new Vec3d(0.6f,  0.6f,  0.6f)),
		SOUTH (new Vec3i(0, 0, 1),  new Vec3d(0.92f, 0.85f, 0.85f)),
		NORTH (new Vec3i(0, 0, -1), new Vec3d(0.8f,  0.8f,  0.8f));

		final Vec3i normal;
		final Vec3d color;

		Facing(Vec3i normal, Vec3d color) {
			this.normal = normal;
			this.color = color;
		}

		static Facing get(int i) {
			return Facing.values()[i];
		}
	}

	// Building mesh
	private ArrayList<Integer> rebuildCloudMesh(Vec3d cloudColor, double offset, float heightOffset) {
		Minecraft client = Minecraft.getMinecraft();

		Vec3d look = null, up = null, right = null;
		double tanHalfFov = 0, tanHalfFovHorizontal = 0;
		boolean enableCulling = CONFIG.getEnableViewCulling();
		if (enableCulling) {
			float cosYaw = ActiveRenderInfo.getRotationX();
			float sinYaw = ActiveRenderInfo.getRotationZ();
			float cosPitch = ActiveRenderInfo.getRotationXZ();
			float sinPitch = Math.abs(cosYaw) > 1e-4f ?
					 ActiveRenderInfo.getRotationXY() / cosYaw :
					-ActiveRenderInfo.getRotationYZ() / sinYaw;
			look = new Vec3d(
					-sinYaw * cosPitch,
					sinPitch,
					cosYaw * cosPitch
			).normalize();
			right = look.crossProduct(new Vec3d(0, 1, 0)).normalize();
			if (right.length() < 0.9)
				right = new Vec3d(cosYaw, 0, sinYaw).normalize();
			up = right.crossProduct(look).normalize();
			tanHalfFov = Math.tan(Math.toRadians(CONFIG.getCullRadianMultiplier() * client.gameSettings.fovSetting) / 2F);
			tanHalfFovHorizontal = tanHalfFov * client.displayWidth / client.displayHeight;
		}

		int customColor = CONFIG.getCloudColor();  //apply custom color
		cloudColor = cloudColor.add(((customColor & 0xFF0000) >> 16) / 255F, ((customColor & 0xFF00) >> 8) / 255F, (customColor & 0xFF) / 255F).normalize();
		float cloudAlpha = ((customColor & 0xFF000000) >>> 24) / 255F;
		if (CONFIG.isEnableDuskBlush())  //apply dawn/dusk blush
			cloudColor = cloudColor.add(getBlushColorByTime(client.world.getWorldTime())).normalize();

		ArrayList<Integer> newVertexList = new ArrayList<>();
		cullStateShown = 0;
		cullStateSkipped = 0;

		try {
			for (CloudData data : cloudDataGroup) {
				switch (data.getDataType()) {  // Smooth Change: Alpha changed by cloud type and lifetime
					case TRANS_IN: cloudAlpha *= 1F - data.getLifeTime() / CONFIG.getNormalRefreshSpeed().getValue() * 5f; break;
					case TRANS_OUT: cloudAlpha *= data.getLifeTime() / CONFIG.getNormalRefreshSpeed().getValue() * 5f; break;
					default: break;
				}

				ArrayList<Integer> vertexList = data.meshData;  //make a snapshot to prevent concurrent violate
				int normCount = vertexList.size() / 4;

				for (int i = 0; i < normCount; i++) {
					int[][] verCache = new int[][]{		// exacting data...
							CloudData.depressVertex(vertexList.get(i * 4)),
							CloudData.depressVertex(vertexList.get(i * 4 + 1)),
							CloudData.depressVertex(vertexList.get(i * 4 + 2)),
							CloudData.depressVertex(vertexList.get(i * 4 + 3))
					};
					boolean isDrawn = false;

					for (int j = 0; j < 4; j ++) {
						if (enableCulling) {
							Vec3d cloudVec = new Vec3d(  // turns to exactly pos & size to calc position culling (camera relative)
									(verCache[j][0] + offset - 1) * CONFIG.getCloudBlockSize(),
									verCache[j][1] * CONFIG.getCloudBlockSize() / 2f + 0.33f + heightOffset,
									(verCache[j][2] - 1) * CONFIG.getCloudBlockSize() + 0.33f
							);
							double depth = look.dotProduct(cloudVec);
							if (depth < 0.05F ||
									Math.abs(up.dotProduct(cloudVec)) / depth > tanHalfFov ||
									Math.abs(right.dotProduct(cloudVec)) / depth > tanHalfFovHorizontal)
								continue;
						}

						newVertexList.add(vertexList.get(i * 4));
						newVertexList.add(vertexList.get(i * 4 + 1));
						newVertexList.add(vertexList.get(i * 4 + 2));
						newVertexList.add(vertexList.get(i * 4 + 3));

						isDrawn = true;
						break;
					}

					if (isDrawn) {
						cullStateShown++;
					} else {
						cullStateSkipped++;
					}
				}

				if (data.getDataType().equals(CloudData.Type.NORMAL)) {
					break;
				} else if (data.getDataType().equals(CloudData.Type.TRANS_MID_BODY)) {
					data.tick();
					if (data.getLifeTime() <= 0) {		// Clear if lifetime reach end
						while (!cloudDataGroup.get(0).getDataType().equals(CloudData.Type.NORMAL)) {
							cloudDataGroup.remove(0);
						}
					}
					break;		// Only render IN, BODY, OUT till its life end and remove.
				} else {
					data.tick();
				}
			}

			return newVertexList;
		} catch (Exception e) {
			exceptionCatcher(e);
			return null;
		}
	}

	protected void collectCloudData(int x, int y, int z) {
		CloudData tmp;
		CloudData fadeIn = null, fadeOut = null, midBody = null;

		tmp = new CloudData(x, y, z, DATA.densityByWeather, DATA.densityByBiome).buildMesh();
		if (!cloudDataGroup.isEmpty() && CONFIG.isEnableSmoothChange()) {
			fadeIn = new CloudFadeData(cloudDataGroup.get(0), tmp, CloudData.Type.TRANS_IN).buildMesh();
			fadeOut = new CloudFadeData(tmp, cloudDataGroup.get(0), CloudData.Type.TRANS_OUT).buildMesh();
			midBody = new CloudMidData(cloudDataGroup.get(0), tmp, CloudData.Type.TRANS_MID_BODY).buildMesh();
		}
		cloudDataGroup.forEach(CloudData::stop);
		synchronized (this) {
			cloudDataGroup.clear();
			if (midBody != null) {
				cloudDataGroup.add(fadeIn);
				cloudDataGroup.add(fadeOut);
				cloudDataGroup.add(midBody);
			}
			cloudDataGroup.add(tmp);
		}
	}

	protected Vec3d getBlushColorByTime(long worldTime) {
		int r = 255, g = 255, b = 255;
		int t = (int) (worldTime % 24000);

		// Color changed by time...
		if (t > 22500 || t < 500) {		//Dawn, scale value in [0, 2000]
			t = t > 22500 ? t - 22500 : t + 1500;
			r = (int) (r * (1 - Math.sin(t / 2000d * Math.PI) / 8));
			double v = Math.cos((t - 1000) / 2000d * Math.PI) / 1.2 + Math.sin(t / 1000d * Math.PI) / 3;
			g = (int) (g * (1 - v / 2.1));
			b = (int) (b * (1 - v / 1.6));
		} else if (t < 13500 && t > 11500) {		//Dusk, reverse order
			t -= 11500;
			r = (int) (r * (1 - Math.sin(t / 2000d * Math.PI) / 8));
			double v = Math.cos((t - 1000) / 2000d * Math.PI) / 1.2 - Math.sin(t / 1000d * Math.PI) / 3;
			g = (int) (g * (1 - v / 2.1));
			b = (int) (b * (1 - v / 1.6));
		}
		return new Vec3d(r / 255F, g / 255F, b / 255F);
	}

	protected Vec3d getBrightMultiplier(Vec3d cloudColor) {
		return cloudColor.add(
				(1 - cloudColor.x) * CONFIG.getCloudBrightMultiplier(),
				(1 - cloudColor.y) * CONFIG.getCloudBrightMultiplier(),
				(1 - cloudColor.z) * CONFIG.getCloudBrightMultiplier()
		);
	}

	public float getCloudHeight() {
		return cloudHeight;
	}

	public boolean isCloudCovered(double x, double y, double z) {
		for(CloudData data : cloudDataGroup) {
			if (data != null && data.isCloudCovered(x + xOffset, y, z + zOffset))
				return true;
		}
		return false;
	}
}
