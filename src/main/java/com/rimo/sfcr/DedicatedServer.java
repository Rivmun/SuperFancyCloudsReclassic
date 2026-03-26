package com.rimo.sfcr;

import com.google.gson.JsonSyntaxException;
import com.rimo.sfcr.config.Config;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nonnull;

import static com.rimo.sfcr.Common.*;

@SideOnly(Side.SERVER)
public class DedicatedServer extends Common.Proxy {
	@Override
	public void init() {
		NETWORK.registerMessage(DimensionMessageHandlerServer.class, DimensionMessage.class, PACKET_DIMENSION_C2S, Side.SERVER);
	}

	@Mod.EventBusSubscriber(modid = Common.MOD_ID, value = Side.SERVER)
	public static class EventHandle {
		@SubscribeEvent
		public static void registerServerCommands(FMLServerStartingEvent event) {
			if (event.getServer().isDedicatedServer())
				event.registerServerCommand(new DedicatedServer.Commands());
		}
	}

	public static class Commands extends CommandBase {
		@Override
		public String getName() {
			return MOD_ID;
		}

		@Override
		public String getUsage(@Nonnull ICommandSender sender) {
			return "/" + MOD_ID + " <status|enable|debug|upload> [arg]";
		}

		@Override
		public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
			if (! sender.canUseCommand(2, sender.getName()))
				throw new CommandException("Your permission is not enough.");
			if (args.length < 1)
				throw new CommandException(getUsage(sender));  // 没有子命令时显示帮助信息
			String subCommand = args[0];
			String name, configJson;
			boolean value;
			switch (subCommand) {
				case "status":
					name = getDimensionName(sender.getEntityWorld());
					configJson = Common.getDimensionConfigJson(name);
					if (configJson.isEmpty()) {
						sender.sendMessage(new TextComponentString("[SFCRe] This dimension '" + name + "' has no config."));
						sender.sendMessage(new TextComponentString("[SFCRe] Use '/sfcr upload' to upload your current config to server."));
						return;
					}
					sender.sendMessage(new TextComponentString("[SFCRe] Dimension config of '" + name + "' are:"));
					sender.sendMessage(new TextComponentString(configJson));
					break;
				case "enable":
					if (args.length < 2)
						throw new CommandException("/" + MOD_ID + " enable [true|false]");
					value = Boolean.parseBoolean(args[1]);
					Common.CONFIG.setEnableServer(value);
					sender.sendMessage(new TextComponentString("[SFCRe] server status changed!"));
					break;
				case "debug":
					if (args.length < 2)
						throw new CommandException("/" + MOD_ID + " debug [true|false]");
					value = Boolean.parseBoolean(args[1]);
					Common.CONFIG.setEnableDebug(value);
					sender.sendMessage(new TextComponentString("[SFCRe] debug status changed!"));
					break;
				case "upload":
					if (! sender.canUseCommand(4, sender.getName()))
						throw new CommandException("Your permission is not enough.");
					if (! (sender instanceof  EntityPlayerMP))
						sender.sendMessage(new TextComponentString("§4[SFCRe] Please cast it from client!"));
					name = getDimensionName(sender.getEntityWorld());
					configJson = Common.getDimensionConfigJson(name);
					NETWORK.sendTo(new DimensionMessage(name, configJson), (EntityPlayerMP) sender);
			}
		}
	}

	// Shared Config Receiver
	// allows server can get a new dimension config uploaded by player
	public static class DimensionMessageHandlerServer implements IMessageHandler<Common.DimensionMessage, IMessage> {
		@Override
		public IMessage onMessage(Common.DimensionMessage message, MessageContext context) {
			String configJson = message.configJson;
			EntityPlayer player = context.getServerHandler().player;
			if (! player.canUseCommand(4, player.getName())) {  //check permission again
				player.sendMessage(new TextComponentString("§4[SFCRe] Your permission is not enough to upload config!"));
				LOGGER.warn("{} was refuse a configJson uploaded by {} because his/her permission check was fail. But why he/she can use 'upload' command?", MOD_ID, player.getName());
				return null;
			}
			String dimensionName = getDimensionName(player.getEntityWorld());
			Config config = new Config();
			try {
				config.fromString(configJson);
			} catch (JsonSyntaxException e) {
				player.sendMessage(new TextComponentString("§4[SFCRe] You upload a config that server cannot read, please check your mod version!"));
				LOGGER.error("{} receive a broken config of {}, uploaded by {}", MOD_ID, dimensionName, player.getName());
				return null;
			}
			config.save(dimensionName);
			setDimensionConfigJson(dimensionName, configJson);
			player.sendMessage(new TextComponentString("[SFCRe] Config was success to upload!"));
			LOGGER.info("{} receive a config of {}, uploaded by {}", MOD_ID, dimensionName, player.getName());
			return null;
		}
	}
}
