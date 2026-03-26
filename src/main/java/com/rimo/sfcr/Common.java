package com.rimo.sfcr;

import com.rimo.sfcr.config.Config;
import com.rimo.sfcr.core.Data;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Mod(modid = Common.MOD_ID, guiFactory = "com.rimo.config.ConfigScreenFactory")
public class Common {
	public static final String MOD_ID = "sfcr";
	public static final Logger LOGGER = LogManager.getLogger(MOD_ID);
	public static Path configPath;
	public static final Config CONFIG = new Config();
	private static final Map<String, String> CONFIG_CACHE = new ConcurrentHashMap<>();  // cache config to prevent high frequent IO
	private static final Object CACHE_LOCK = new Object();
	public static final Data DATA = new Data(CONFIG);
	public static final SimpleNetworkWrapper NETWORK = NetworkRegistry.INSTANCE.newSimpleChannel(MOD_ID);
	public static final int PACKET_SEED_S2C = 1;
	public static final int PACKET_WEATHER_S2C = 2;
	public static final int PACKET_DIMENSION_S2C = 3;
	public static final int PACKET_UPLOAD_REQUEST_S2C = 4;
	public static final int PACKET_DIMENSION_C2S = 5;
	/**
	 * long seed - part of world seed use to init sampler, sent when player join this world.
	 */
	public static class SeedMessage implements IMessage {
		long seed;
		public SeedMessage() {}
		public SeedMessage(long seed) {this.seed = seed;}
		@Override
		public void fromBytes(ByteBuf buf) {seed = buf.readLong();}
		@Override
		public void toBytes(ByteBuf buf) {buf.writeLong(seed);}
	}
	/**
	 * Data.Weather - use to pre-detect function, sent when weather will be changed.
	 */
	public static class WeatherMessage implements IMessage {
		Data.Weather weather;
		public WeatherMessage() {}
		public WeatherMessage(Data.Weather weather) {this.weather = weather;}
		@Override
		public void fromBytes(ByteBuf buf) {weather = Data.Weather.values()[buf.readByte()];}
		@Override
		public void toBytes(ByteBuf buf) {buf.writeByte(weather.ordinal());}
	}
	/**
	 * Contains:<br>
	 * 1.String dimensionName - use to load specific config, sent when player join at first time and dimension change. <br>
	 * 2.@Emptyable String dimensionConfigJson - specific configJson which existing on server side when serverConfig is enabled
	 */
	public static class DimensionMessage implements IMessage {
		String name;
		String configJson;
		public DimensionMessage() {}
		public DimensionMessage(String name, String configJson) {
			this.name = name;
			this.configJson = configJson;
		}
		@Override
		public void fromBytes(ByteBuf buf) {
			this.name = ByteBufUtils.readUTF8String(buf);
			this.configJson =  ByteBufUtils.readUTF8String(buf);
		}
		@Override
		public void toBytes(ByteBuf buf) {
			ByteBufUtils.writeUTF8String(buf, this.name);
			ByteBufUtils.writeUTF8String(buf, this.configJson);
		}
	}
	/**
	 * an empty packet to notice client upload its config
	 */
	public static class UploadRequestMessage implements IMessage {
		@Override
		public void fromBytes(ByteBuf byteBuf) {}
		@Override
		public void toBytes(ByteBuf byteBuf) {}
	}

	@SidedProxy(clientSide = "com.rimo.sfcr.Client", serverSide = "com.rimo.sfcr.DedicatedServer")
	public static Proxy proxy;
	public static class Proxy {public void init() {}}

	@Mod.EventHandler
	public static void init(FMLPreInitializationEvent event) {
		configPath = event.getModConfigurationDirectory().toPath();
		proxy.init();
		CONFIG.load();
		DATA.setConfig(CONFIG);
	}

	@Mod.EventBusSubscriber(modid = Common.MOD_ID)
	public static class EventHandle {
		// Seed Sender
		@SubscribeEvent
		public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
			EntityPlayer player = event.player;
			MinecraftServer server = player.getServer();
			if (! CONFIG.isEnableServer() && server != null && ! player.getName().equals(server.getServerOwner()))
				return;
			long seed = player.world.getSeed() >> 5 & 0x7FFFFFFFFFFFFFFFL;  // don't send actually seed for anti-cheat
			NETWORK.sendTo(new SeedMessage(seed), (EntityPlayerMP) player);
			if (CONFIG.isEnableDebug())
				LOGGER.info("{} send seed {} to {}", MOD_ID, seed, player.getName());

			// Dimension Sender also run here because CHANGE_DIMENSION event not be called when join
			sendDimensionPacket(player, getDimensionName(player.world));
		}
		@SubscribeEvent
		public static void onDimensionChanged(PlayerEvent.PlayerChangedDimensionEvent event) {
			EntityPlayer player = event.player;
			MinecraftServer server = event.player.getServer();
			// Always send config to host whatever isEnable, to prevent function shutdown when read a config which enabled is not.
			if (! CONFIG.isEnableServer() && server != null && ! player.getName().equals(server.getServerOwner()))
				return;
			sendDimensionPacket(player, getDimensionName(player.world));
		}

		//weather sender
		@SubscribeEvent
		public static void onServerTick(TickEvent.ServerTickEvent event) {
			if (event.phase == TickEvent.Phase.END) {
				World world = FMLCommonHandler.instance().getMinecraftServerInstance().getEntityWorld();
				if (world.getWorldTime() % 20 == 0 && DATA.updateWeather(world)) {  // always update
					if (!CONFIG.isEnableServer())
						return;
					Data.Weather nextWeather = DATA.getNextWeather();
					NETWORK.sendToAll(new WeatherMessage(nextWeather));
					if (CONFIG.isEnableDebug())
						LOGGER.info("{} broadcast next weather: {}", MOD_ID, nextWeather);
				}
			}
		}
	}

	// Dimension Packet Sender
	private static void sendDimensionPacket(EntityPlayer player, String name) {
		String configJson = CONFIG.isEnableServer() ? getDimensionConfigJson(name) : "";
		NETWORK.sendTo(new DimensionMessage(name, configJson), (EntityPlayerMP) player);
		if (CONFIG.isEnableDebug())
			LOGGER.info("{} send dimension '{}' packet to {}", MOD_ID, name, player.getName());
	}

	static void setDimensionConfigJson(String dimensionName, String configJson) {
		synchronized (CACHE_LOCK) {
			clearConfigCache(dimensionName);
			CONFIG_CACHE.put(dimensionName, configJson);
		}
	}

	// - concurrent check powered by doubao.ai & deepseek.ai
	static String getDimensionConfigJson(String dimensionName) {
		return CONFIG_CACHE.computeIfAbsent(dimensionName, name -> {
			Config config = new Config();
			return config.load(name) ? config.toString() : "";
		});
	}

	/**
	 * @param name syntax like "minecraft:overworld", input null to clear all
	 */
	public static void clearConfigCache(String name) {
		if (name == null)
			CONFIG_CACHE.clear();
		else
			CONFIG_CACHE.remove(name);
	}

	public static String getDimensionName(World world) {
		return world.provider.getDimensionType().getName();
	}

	//Debug
	public static void exceptionCatcher(Exception e) {
		StringBuilder text = new StringBuilder(MOD_ID + " got an error:\n" + e.toString());
		for (StackTraceElement i : e.getStackTrace()) {
			text.append("\n    at ").append(i.toString());
		}
		LOGGER.error(text.toString());
	}
}
