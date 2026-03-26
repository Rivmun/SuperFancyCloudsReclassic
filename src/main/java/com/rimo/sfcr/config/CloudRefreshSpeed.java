package com.rimo.sfcr.config;

import net.minecraft.client.resources.I18n;

public enum CloudRefreshSpeed {
	VERY_SLOW(40, I18n.format("text.sfcr.enum.cloudRefreshSpeed.VERY_SLOW")),
	SLOW(30, I18n.format("text.sfcr.enum.cloudRefreshSpeed.SLOW")),
	NORMAL(20, I18n.format("text.sfcr.enum.cloudRefreshSpeed.NORMAL")),
	FAST(10, I18n.format("text.sfcr.enum.cloudRefreshSpeed.FAST")),
	VERY_FAST(5, I18n.format("text.sfcr.enum.cloudRefreshSpeed.VERY_FAST"));

	private final int value;
	private final String name;

	CloudRefreshSpeed(int value, String name) {
		this.value = value;
		this.name = name;
	}

	public int getValue() {
		return value;
	}

	public String getName() {
		return name;
	}
}
