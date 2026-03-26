package com.rimo.sfcr.config;

import com.rimo.sfcr.Client;
import com.rimo.sfcr.Common;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.world.World;
import net.minecraftforge.fml.client.IModGuiFactory;
import net.minecraftforge.fml.client.config.GuiButtonExt;

import java.awt.*;
import java.util.Set;

import static com.rimo.sfcr.Common.*;

public class ConfigScreenFactory extends GuiScreen implements IModGuiFactory {
	private final GuiScreen parentScreen;
	private boolean isEdited = false;

	public ConfigScreenFactory(GuiScreen parent) {
		this.parentScreen = parent;
	}

	@Override
	public void initialize(Minecraft minecraftInstance) {}

	@Override
	public boolean hasConfigGui() {
		return true;
	}

	@Override
	public GuiScreen createConfigGui(GuiScreen guiScreen) {
		return new ConfigScreenFactory(guiScreen);
	}

	@Override
	public Set<RuntimeOptionCategoryElement> runtimeGuiCategories() {
		return null;
	}

	// GuiScreen 的方法
	@Override
	public void initGui() {
		this.buttonList.add(new GuiButtonExt(0, this.width / 2 - 100, this.height / 2 - 10, 200, 20,
				I18n.format("text.sfcr.openConfigFile")));
		this.buttonList.add(new GuiButtonExt(1, this.width / 2 - 100, this.height / 2 + 20, 200, 20,
				I18n.format("gui.back")));
	}

	@Override
	protected void actionPerformed(GuiButton button) {
		World world = Minecraft.getMinecraft().world;
		if (button.id == 0) {
			if (world != null) {
				Config.open(getDimensionName(world));
			} else {
				Config.open();
			}
			isEdited = true;
		} else if (button.id == 1) {  //reload when exit
			if (isEdited) {
				if (world != null) {
					CONFIG.load(getDimensionName(world));
				} else {
					CONFIG.load();
				}
			}
			Minecraft.getMinecraft().displayGuiScreen(parentScreen);
		}
	}

	@Override
	public void drawScreen(int mouseX, int mouseY, float partialTicks) {
		this.drawDefaultBackground();
		int y = this.height / 2 - 60;

		String info = I18n.format("text.sfcr.configScreen.info");
		this.drawCenteredString(this.fontRenderer, info, this.width / 2, y, 0xFFFFFF);
		y += 15;

		if (Client.isCustomDimensionConfig) {
			String worldName = Minecraft.getMinecraft().world != null ?
					getDimensionName(Minecraft.getMinecraft().world) : "unknown";
			String dimMsg = I18n.format("text.sfcr.option.customDimensionMode.@PrefixText", worldName);
			this.drawCenteredString(this.fontRenderer, dimMsg, this.width / 2, y, 0xFFFFAA);
			y += 12;
		}

		if (Client.isConfigHasBeenOverride) {
			String overrideMsg = I18n.format("text.sfcr.option.configHasBeenOverride.@PrefixText");
			this.drawCenteredString(this.fontRenderer, overrideMsg, this.width / 2, y, 0xFFAAFF);
			y += 12;
		}

		super.drawScreen(mouseX, mouseY, partialTicks);
	}

	@Override
	public boolean doesGuiPauseGame() {
		return true;
	}
}
