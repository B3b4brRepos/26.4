package com.ingamemusic.gui;

import com.ingamemusic.IngameMusic;
import com.ingamemusic.MusicConfig;
import com.ingamemusic.audio.AudioEngine;
import com.ingamemusic.lyrics.Lyrics;
import com.ingamemusic.player.PlayerController;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Matrix4f;

/**
 * 3D lyrics: every new line floats in the world a few blocks in front of you, faces the camera, fades in and fades out
 * when the next line starts. It is anchored to a direction (so it stays put when you turn) and eases back into view when
 * you look too far away ("stay on screen"). Modelled on the world lyrics of DangoMusic (GPL-3.0); the code is new.
 */
public final class Lyrics3D {
	private Lyrics3D() {}

	private static final class Floating {
		List<FormattedCharSequence> lines = new ArrayList<>(); List<Integer> widths = new ArrayList<>();
		float yaw, pitch;                               // degrees, the direction from the camera
		long born, fadeStart;
	}

	private static final Random RNG = new Random();
	private static Floating active;
	private static final List<Floating> fading = new ArrayList<>();
	private static int lastIdx = -2; private static boolean wasPaused; private static long lastNanos;
	private static final float APPEAR_S = 0.25f, FADE_S = 0.6f;

	private static void fade(Floating f) { if (f != null) { f.fadeStart = System.nanoTime(); fading.add(f); } }
	private static void reset() { active = null; fading.clear(); lastIdx = -2; }

	private static volatile boolean broken;

	/** Called every frame from LevelRenderEvents.COLLECT_SUBMITS. Never lets an error reach the game: it switches itself off instead. */
	public static void collect(LevelRenderContext ctx) {
		if (broken) return;
		try { collectInner(ctx); } catch (Throwable t) { broken = true; reset(); IngameMusic.LOG.error("3D lyrics disabled after an error", t); }
	}

	private static void collectInner(LevelRenderContext ctx) {
		MusicConfig cfg = IngameMusic.config; PlayerController pc = IngameMusic.player;
		Minecraft mc = Minecraft.getInstance();
		if (cfg == null || pc == null || !cfg.lyrics3d || !cfg.lyricsOn || mc.player == null || pc.current == null) { if (active != null || !fading.isEmpty()) reset(); return; }
		CameraRenderState cam = ctx.levelState().cameraRenderState;
		if (cam == null || cam.orientation == null) return;
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0.016f : Math.min(0.1f, (now - lastNanos) / 1e9f); lastNanos = now;
		float dist = cfg.lyrics3dDistance;

		Lyrics l = pc.lyrics;
		boolean playing = pc.engine.state() == AudioEngine.State.PLAYING;
		if (l != null && l.synced) {
			int idx = l.indexAt(pc.lyricPosition());
			if (!playing) {                                              // paused: the line fades away and comes back on resume
				if (active != null) { fade(active); active = null; }
				lastIdx = idx; wasPaused = true;
			} else if (idx != lastIdx || wasPaused) {
				lastIdx = idx; wasPaused = false;
				if (active != null) { fade(active); active = null; }
				if (idx >= 0 && !l.lines[idx].isBlank()) active = spawn(l.lines[idx], cam, dist, mc.font);
			}
		} else if (active != null) { fade(active); active = null; lastIdx = -2; }

		PoseStack ps = ctx.poseStack(); SubmitNodeCollector sc = ctx.submitNodeCollector();
		if (active != null) {
			follow(active, cam, dt, dist);
			float a = UiFx.easeOut(Math.min(1f, (now - active.born) / (APPEAR_S * 1e9f)));
			draw(active, a, ps, sc, cam, dist, cfg.lyrics3dSize);
		}
		for (int i = fading.size() - 1; i >= 0; i--) {
			Floating f = fading.get(i);
			float a = 1f - (now - f.fadeStart) / (FADE_S * 1e9f);
			if (a <= 0.02f) { fading.remove(i); continue; }
			draw(f, a * a, ps, sc, cam, dist, cfg.lyrics3dSize);
		}
	}

	private static Floating spawn(String text, CameraRenderState cam, float dist, Font font) {
		Floating f = new Floating();
		f.born = System.nanoTime();
		f.yaw = cam.yRot + (RNG.nextFloat() * 2f - 1f) * 12f;
		float pitch = Math.max(-60f, Math.min(30f, cam.xRot - 3f - RNG.nextFloat() * 10f));
		float maxPitch = (float) Math.toDegrees(Math.asin(Math.min(1.0, 0.5 / dist)));   // never lower than half a block under the eyes
		f.pitch = Math.min(pitch, maxPitch);
		for (FormattedCharSequence s : font.split(Component.literal(text), 220)) { f.lines.add(s); f.widths.add(font.width(s)); }
		return f;
	}

	/** Eases the line back into view when the player looks too far away from it. */
	private static void follow(Floating f, CameraRenderState cam, float dt, float dist) {
		float k = 1f - (float) Math.exp(-dt * 6f);
		float dyaw = wrap(cam.yRot - f.yaw), limYaw = 26f;
		if (Math.abs(dyaw) > limYaw) f.yaw += (dyaw - Math.signum(dyaw) * limYaw) * k;
		float dp = cam.xRot - f.pitch, limP = 16f;
		if (Math.abs(dp) > limP) f.pitch += (dp - Math.signum(dp) * limP) * k;
		float maxPitch = (float) Math.toDegrees(Math.asin(Math.min(1.0, 0.5 / dist)));
		f.pitch = Math.min(f.pitch, maxPitch);
	}
	private static float wrap(float d) { d %= 360f; if (d > 180f) d -= 360f; if (d < -180f) d += 360f; return d; }

	private static void draw(Floating f, float alpha, PoseStack ps, SubmitNodeCollector sc, CameraRenderState cam, float dist, float size) {
		int a = (int) (255 * Math.max(0f, Math.min(1f, alpha)));
		if (a < 8 || f.lines.isEmpty()) return;
		double yr = Math.toRadians(f.yaw), pr = Math.toRadians(f.pitch);
		double dx = -Math.sin(yr) * Math.cos(pr) * dist, dy = -Math.sin(pr) * dist, dz = Math.cos(yr) * Math.cos(pr) * dist;
		float s = 0.011f * dist * size;                                  // letters keep the same apparent size at any distance
		ps.pushPose();
		try {
			ps.translate(dx, dy, dz);                                          // positions are relative to the camera
			ps.mulPose(new Matrix4f().rotation(cam.orientation));              // billboard: face the camera
			ps.scale(s, -s, s);
			int n = f.lines.size(), color = (a << 24) | 0xF6E8EE;             // warm white, slightly pink
			for (int i = 0; i < n; i++) {
				float x = -f.widths.get(i) / 2f, y = (i - n / 2f) * 11f;
				sc.submitText(ps, x, y, f.lines.get(i), true, Font.DisplayMode.SEE_THROUGH, LightCoordsUtil.FULL_BRIGHT, color, 0, 0);
			}
		} finally { ps.popPose(); }
	}
}
