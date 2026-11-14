package com.cbc.firecontrol.client.gui;

/**
 * Flat palette shared by the procedurally drawn consoles (the calculator and the mount radio).
 *
 * <p>These screens deliberately paint their own panel with {@code GuiGraphics#fill} instead of shipping a bespoke
 * texture: the readout columns are wide and text-driven, so a dark panel with row strips reads better than art and
 * adds no asset. Because the fills are code, the colours have to be shared - a strip drawn by one class and a band
 * drawn by another must agree, or the panel looks like two widgets welded together.
 */
public final class ConsoleTheme {

	private ConsoleTheme() {
	}

	/* Panel chrome. */
	public static final int PANEL_BG = 0xFF26262B;
	public static final int PANEL_EDGE = 0xFF41414A;
	public static final int ROW_BG = 0xFF1C1C20;
	public static final int INVENTORY_BG = 0xFF191920;

	/* Text. */
	public static final int HEADER_TEXT = 0xFFD8C58C;
	public static final int TEXT_SECONDARY = 0xFFA8A8B2;
	public static final int CHANNEL_LABEL = 0xFF9AA6C4;

	/* Verdict colours. Blue is deliberate on the fixable failures: they come with a number the gunner can act
	 * on, so they must not read like a dead end. */
	public static final int COLOR_OK = 0xFF7BC96F;
	public static final int COLOR_HIT = 0xFFE7C25C;
	public static final int COLOR_STALLED = 0xFF8FB3D9;
	public static final int COLOR_ERROR = 0xFFCE6A5C;

	/* Channel grid. An armed row gets the lighter backdrop so a column that can transmit is distinguishable
	 * from one that is only configured but idle. */
	public static final int CHANNEL_BG_ACTIVE = 0xFF2E3340;
	public static final int CHANNEL_BG_IDLE = 0xFF202126;

	/* Target-entry strip and its fields. */
	public static final int TARGET_BG = 0xFF1A1D24;
	public static final int FIELD_TEXT = 0xFFE4E4EA;
	public static final int FIELD_TEXT_INVALID = 0xFFE08B7C;

	/* Tab strip. */
	public static final String TRUNCATION_MARK = "...";
	public static final int STRIP_BG = 0xFF15161A;
	public static final int TAB_BG = 0xFF23242A;
	public static final int TAB_BG_HOVER = CHANNEL_BG_ACTIVE;
	public static final int TAB_BG_ACTIVE = ROW_BG;
	public static final int TAB_TEXT = TEXT_SECONDARY;
	public static final int TAB_TEXT_HOVER = 0xFFD8D8E0;
	public static final int TAB_TEXT_ACTIVE = HEADER_TEXT;
}
