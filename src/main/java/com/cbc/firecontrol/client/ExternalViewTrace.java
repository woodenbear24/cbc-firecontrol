package com.cbc.firecontrol.client;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

import com.cbc.firecontrol.CBCFireControl;
import com.cbc.firecontrol.compat.SableShipBounds;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

/**
 * Per-frame record of where the external camera actually stood, for the one question a log line cannot answer.
 *
 * <p>The complaint being chased is a ship that jitters against the camera while it moves. That is a difference
 * between two things on the same frame - where the hull is drawn and where the eye is placed - so the only way to
 * tell a curve mismatch from a rotation problem is to have both per frame rather than once every sixty frames:
 * the pivot the orbit turns about, the eye it produced, the angle the lens renders with, and the two partial-tick
 * conventions the camera and the renderer each use. A log line at sixty-frame cadence samples the very motion
 * being measured and cannot see it.
 *
 * <p>Written only while {@link CBCFireControl#DEBUG_CAMERA} is on, so an ordinary client pays nothing: the
 * condition is checked before any formatting, file work, or Sable lookup. The file is truncated when it is first
 * opened, so a run's rows are never mixed with a previous run's.
 */
public final class ExternalViewTrace {

	private static final Path FILE = Path.of(".temp", "external_view_trace.csv");
	private static final String HEADER = "frame,mode,setupPartialTick,timerPartialTick,orbit,pivotX,pivotY,pivotZ,"
			+ "eyeX,eyeY,eyeZ,yaw,pitch,playerYaw,playerYawO,cameraType,sableZoom\n";

	@Nullable
	private static BufferedWriter writer;
	private static boolean unusable;
	private static long frames;

	private ExternalViewTrace() {
	}

	/**
	 * One row. {@code pivot} is the point the orbit turned about this frame - on Sable's perspective that is the
	 * hull's own render pose origin, which is why comparing it against {@code eye} measures the disagreement
	 * directly. {@code orbit} is the eye distance on whichever path produced it.
	 */
	public static void record(String mode, float setupPartialTick, float orbit, @Nullable Vec3 pivot, Vec3 eye,
			float yaw, float pitch, Minecraft mc) {
		if (!CBCFireControl.DEBUG_CAMERA || unusable)
			return;
		try {
			if (writer == null) {
				Path parent = FILE.getParent();
				if (parent != null)
					Files.createDirectories(parent);
				writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
						StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.APPEND);
				writer.write(HEADER);
			}
			float timerPartialTick = mc.getTimer() == null ? Float.NaN
					: mc.getTimer().getGameTimeDeltaPartialTick(true);
			writer.write(Long.toString(frames++));
			writer.write(',');
			writer.write(mode);
			writeNumber(writer, setupPartialTick);
			writeNumber(writer, timerPartialTick);
			writeNumber(writer, orbit);
			writeNumber(writer, pivot == null ? Double.NaN : pivot.x);
			writeNumber(writer, pivot == null ? Double.NaN : pivot.y);
			writeNumber(writer, pivot == null ? Double.NaN : pivot.z);
			writeNumber(writer, eye.x);
			writeNumber(writer, eye.y);
			writeNumber(writer, eye.z);
			writeNumber(writer, yaw);
			writeNumber(writer, pitch);
			writeNumber(writer, mc.player == null ? Double.NaN : mc.player.getYRot());
			writeNumber(writer, mc.player == null ? Double.NaN : mc.player.yRotO);
			writer.write(',');
			writer.write(mc.options.getCameraType().name());
			// Read from the game's own main camera rather than from the caller: during {@code Camera#setup} that is
			// this same instance, and the mixin class is not a {@code Camera} to the compiler, so passing it would
			// not type-check.
			writeNumber(writer, SableShipBounds.cameraZoomAmount(mc.gameRenderer.getMainCamera()));
			writer.write('\n');
			if (frames % 30L == 0L)
				writer.flush();
		} catch (Exception e) {
			// A missing or unwritable directory is a property that was switched on in the wrong place, not a
			// reason to fail every rendered frame: the view keeps working and the trace stays off.
			unusable = true;
			CBCFireControl.LOGGER.warn("[cbc_firecontrol] external view trace off: {}", e.toString());
		}
	}

	/** Fixed-point rather than scientific, so a spreadsheet reads the columns without parsing exponents. */
	private static void writeNumber(BufferedWriter out, double value) throws java.io.IOException {
		out.write(',');
		if (Double.isNaN(value) || Double.isInfinite(value))
			out.write("");
		else
			out.write(String.format(Locale.ROOT, "%.4f", value));
	}

	/** Called when the view ends, so the last frames of a session are not left in the buffer. */
	public static void flush() {
		BufferedWriter out = writer;
		if (out == null)
			return;
		try {
			out.flush();
		} catch (Exception e) {
			// Nothing to recover: the file already holds everything that was written.
		}
	}
}
