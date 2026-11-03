package com.ingamemusic;

import com.google.gson.*;
import java.nio.file.*;
import net.fabricmc.loader.api.FabricLoader;

public final class MusicConfig {
	public enum Anchor {
		TOP_CENTER("Top Center"), TOP_LEFT("Top Left"), TOP_RIGHT("Top Right"), BOTTOM_RIGHT("Bottom Right");
		public final String label;
		Anchor(String l) { label = l; }
		public Anchor next() { return values()[(ordinal() + 1) % values().length]; }
	}
	public float volume = 0.6f;
	public boolean hudVisible = true;
	public boolean hudVisualizer = true;
	public Anchor hudAnchor = Anchor.TOP_CENTER;
	public int hudOffsetX = 0, hudMarginY = 0;
	public float hudScale = 1.0f;
	public boolean lyricsOn = true;
	/** Big current lyric line drawn on the game screen while a song with synced lyrics plays. */
	/** Search source: YOUTUBE, YT_MUSIC or SOUNDCLOUD. */
	public String searchSource = "YOUTUBE";
	/** Optional YouTube OAuth refresh token (helps when YouTube shows a bot check). Applied after a restart. */
	public String youtubeToken = "";
	/** Lyrics floating in the world in front of you (3D) and how far away they float (blocks) / how big they are. */
	public boolean lyrics3d = true;
	public float lyrics3dDistance = 8f, lyrics3dSize = 1f;
	public boolean lyricsOverlay = true;
	public float lyricsOverlayScale = 1.4f;
	/** Size of the full player panel (1.0 = full size). Small by default so it fits between launcher touch buttons. */
	public float uiScale = 0.7f;
	/** Horizontal shift of the player panel in GUI pixels (negative = left). Lets it sit between launcher touch buttons. */
	public int uiOffsetX = 0;
	public int lyricsOffsetMs = 0;
	public boolean shuffle = false;
	public String repeat = "OFF";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("ingamemusic.json"); }

	public static MusicConfig load() {
		try {
			if (Files.exists(file())) {
				MusicConfig c = GSON.fromJson(Files.readString(file()), MusicConfig.class);
				if (c != null) { c.clamp(); return c; }
			}
		} catch (Exception e) { IngameMusic.LOG.warn("Bad config, using defaults", e); }
		MusicConfig c = new MusicConfig(); c.save(); return c;
	}
	private void clamp() {
		volume = Math.max(0f, Math.min(1f, volume));
		hudScale = Math.max(0.5f, Math.min(3f, hudScale));
		uiScale = Math.max(0.4f, Math.min(1.5f, uiScale));
		lyricsOverlayScale = Math.max(0.8f, Math.min(3f, lyricsOverlayScale));
		lyrics3dDistance = Math.max(3f, Math.min(40f, lyrics3dDistance));
		lyrics3dSize = Math.max(0.4f, Math.min(4f, lyrics3dSize));
		if (youtubeToken == null) youtubeToken = "";
		if (searchSource == null) searchSource = "YOUTUBE";
		uiOffsetX = Math.max(-300, Math.min(300, uiOffsetX));
		if (hudAnchor == null) hudAnchor = Anchor.TOP_CENTER;
	}
	public void save() {
		String json = GSON.toJson(this);
		IngameMusic.SAVE.execute(() -> { try { Files.writeString(file(), json); } catch (Exception e) { IngameMusic.LOG.warn("Config save failed", e); } });
	}
}
