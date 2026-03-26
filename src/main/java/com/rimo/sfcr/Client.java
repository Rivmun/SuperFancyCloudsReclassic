package com.rimo.sfcr;

import com.google.gson.JsonSyntaxException;
import com.rimo.sfcr.config.Config;
import com.rimo.sfcr.core.CloudData;
import com.rimo.sfcr.core.Renderer;
import net.minecraft.client.Minecraft;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Random;

import static com.rimo.sfcr.Common.*;

@SideOnly(Side.CLIENT)
public class Client extends Common.Proxy {
	private static boolean hasServer = false;
	public static boolean isConfigHasBeenOverride = false;
	public static boolean isCustomDimensionConfig = false;
	public static Renderer RENDERER = new Renderer();

	@Override
	public void init() {
		NETWORK.registerMessage(SeedMessageHandler.class, SeedMessage.class, PACKET_SEED_S2C, Side.CLIENT);
		NETWORK.registerMessage(WeatherMessageHandler.class, WeatherMessage.class, PACKET_WEATHER_S2C, Side.CLIENT);
		NETWORK.registerMessage(DimensionMessageHandler.class, DimensionMessage.class, PACKET_DIMENSION_S2C, Side.CLIENT);
		NETWORK.registerMessage(UploadRequestMessageHandler.class, UploadRequestMessage.class, PACKET_UPLOAD_REQUEST_S2C, Side.CLIENT);
	}

	@Mod.EventBusSubscriber(modid = Common.MOD_ID, value = Side.CLIENT)
	public static class EventHandle {
		// World loaded
		@SubscribeEvent
		public static void onPlayerJoin(EntityJoinWorldEvent event) {
			if (! hasServer)
				CloudData.initSampler(new Random().nextLong());  //get a random seed before server send
		}
		@SubscribeEvent
		public static void onWorldLoaded(WorldEvent.Load event) {
			String dimensionName = getDimensionName(event.getWorld());
			if (! hasServer || ! CONFIG.isEnableServer()) {  //if not sfcr server or disabled server config, read config by client itself.
				if (CONFIG.load(dimensionName) && ! dimensionName.equals(Config.OVERWORLD))
					isCustomDimensionConfig = true;
				isConfigHasBeenOverride = false;
			}
		}

		//update Data
		@SubscribeEvent
		public static void onClientTick(TickEvent.ClientTickEvent event) {
			Minecraft client = Minecraft.getMinecraft();
			if (event.phase == TickEvent.Phase.END) {
				if (! CONFIG.isEnableRender() || client.world == null || client.world.getWorldTime() % 20 != 0)
					return;
				if (! hasServer && ! client.isIntegratedServerRunning())
					DATA.updateWeatherClient(client.world);
				if (client.player != null)
					DATA.updateDensity(client.player);
			}
		}

		//quit reset
		@SubscribeEvent
		public static void onQuit(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
			hasServer = false;
			isCustomDimensionConfig = false;
			isConfigHasBeenOverride = false;
			RENDERER.stop();
			CONFIG.load();
			Common.clearConfigCache(null);
		}
	}

	//seed receiver
	public static class SeedMessageHandler implements IMessageHandler<Common.SeedMessage, IMessage> {
		@Override
		public IMessage onMessage(Common.SeedMessage message, MessageContext context) {
			CloudData.initSampler(message.seed);
			if (CONFIG.isEnableDebug())
				LOGGER.info("{} receive seed {}", MOD_ID, message.seed);
			return null;
		}
	}

	//weather receiver
	public static class WeatherMessageHandler implements IMessageHandler<Common.WeatherMessage, IMessage> {
		@Override
		public IMessage onMessage(Common.WeatherMessage message, MessageContext context) {
			DATA.setNextWeather(message.weather);
			if (CONFIG.isEnableDebug())
				LOGGER.info("{} receive weather: {}", MOD_ID, message.weather);
			return null;
		}
	}

	//dimention packet receiver
	public static class DimensionMessageHandler implements IMessageHandler<DimensionMessage, IMessage> {
		@Override
		public IMessage onMessage(DimensionMessage message, MessageContext context) {
			String name = message.name;
			String configJson = message.configJson;
			if (! configJson.isEmpty() && CONFIG.isEnableServer()) {
				try {
					CONFIG.fromString(configJson);
					if (! Minecraft.getMinecraft().isIntegratedServerRunning())  //singleplayer override itself? ur joking...
						isConfigHasBeenOverride = true;
					if (! name.equals(Config.OVERWORLD))
						isCustomDimensionConfig = true;
					if (CONFIG.isEnableDebug())
						LOGGER.info("{} receive sharedConfig of '{}'", MOD_ID, name);
				} catch (JsonSyntaxException e) {
					LOGGER.error("{} cannot read config for {} which is received from server, please check your mod version!", MOD_ID, name);
				}
			} else {
				if (CONFIG.load(name) && ! name.equals(Config.OVERWORLD))  //Client trying to load dimension config if server not send...
					isCustomDimensionConfig = true;
				isConfigHasBeenOverride = false;
				if (CONFIG.isEnableDebug())
					LOGGER.info("{} receive dimension name '{}'", MOD_ID, name);
			}
			return null;
		}
	}

	//upload request message receiver
	public static class UploadRequestMessageHandler implements IMessageHandler<Common.UploadRequestMessage, IMessage> {
		@Override
		public IMessage onMessage(Common.UploadRequestMessage message, MessageContext context) {
			String name = getDimensionName(Minecraft.getMinecraft().world);
			String configJson = CONFIG.toString();
			return new DimensionMessage(name, configJson);
		}
	}

	/**
	 * @return true if this point is covered by SFC clouds, false if not.<br>
	 * Note that if this point is above cloud, it always returns false.
	 */
	public static boolean isNoCloudCovered(double x, double y, double z) {
		return RENDERER == null || ! RENDERER.isCloudCovered(x, y, z);
	}
}
