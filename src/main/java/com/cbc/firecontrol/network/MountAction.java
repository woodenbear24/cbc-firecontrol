package com.cbc.firecontrol.network;

/** Console actions triggered by GUI buttons (client -> server). */
public enum MountAction {
	ASSEMBLE,
	DISASSEMBLE,
	FIRE,
	YAW,
	PITCH,
	REQUEST;

	/** Ordinal-indexed array so a StreamCodec can decode a varInt back to an enum safely. */
	public static final MountAction[] ACTIONS = values();
}
