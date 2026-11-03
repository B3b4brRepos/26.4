package com.ingamemusic.lava;

import com.ingamemusic.IngameMusic;
import com.ingamemusic.model.Track;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.bandcamp.BandcampAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Lavaplayer (https://github.com/lavalink-devs/lavaplayer) does all searching, stream resolving and decoding.
 * Sources: YouTube (youtube-source plugin), YouTube Music, SoundCloud, Bandcamp links and plain audio URLs.
 * PCM comes out as signed 16-bit little-endian, 44.1 kHz stereo (see {@link LavaSource}).
 */
public final class LavaService {
	private LavaService() {}

	/** What the search box looks in. Pasting a link always works whatever is selected. */
	public enum Src {
		YOUTUBE("YT", "YouTube", "ytsearch:"), YT_MUSIC("YTM", "YouTube Music", "ytmsearch:"), SOUNDCLOUD("SC", "SoundCloud", "scsearch:");
		public final String shortName, longName, prefix;
		Src(String s, String l, String p) { shortName = s; longName = l; prefix = p; }
		public Src next() { return values()[(ordinal() + 1) % values().length]; }
		public static Src of(String name) { try { return valueOf(name); } catch (Exception e) { return YOUTUBE; } }
	}

	private static volatile AudioPlayerManager manager;
	private static volatile Throwable initError;

	public static Src source() { return Src.of(IngameMusic.config.searchSource); }

	/** Short text for the UI when Lavaplayer could not start (null = fine or not started yet). */
	public static String problem() {
		Throwable t = initError;
		if (t == null) return null;
		Throwable r = t; while (r.getCause() != null && r.getCause() != r) r = r.getCause();
		String m = r.getMessage();
		String name = r.getClass().getSimpleName();
		return "Lavaplayer could not start: " + name + (m == null || m.isBlank() ? "" : " - " + m);
	}

	/** Builds the player manager once (heavy: loads native decoders, so it happens on first use, off the render thread). */
	public static synchronized AudioPlayerManager manager() throws IOException {
		if (manager != null) return manager;
		if (initError != null) throw new IOException(problem(), initError);
		try {
			DefaultAudioPlayerManager m = new DefaultAudioPlayerManager();
			AudioConfiguration cfg = m.getConfiguration();
			cfg.setOutputFormat(StandardAudioDataFormats.COMMON_PCM_S16_LE);
			cfg.setResamplingQuality(AudioConfiguration.ResamplingQuality.MEDIUM);
			m.setFrameBufferDuration(3000);
			m.setUseSeekGhosting(false);                    // a seek must be heard immediately
			m.setPlayerCleanupThreshold(Long.MAX_VALUE);    // a long pause must not make Lavaplayer drop the track
			m.setItemLoaderThreadPoolSize(4);

			YoutubeAudioSourceManager yt = new YoutubeAudioSourceManager();
			String token = IngameMusic.config.youtubeToken;
			if (token != null && !token.isBlank()) yt.useOauth2(token.trim(), true);
			m.registerSourceManager(yt);
			m.registerSourceManager(SoundCloudAudioSourceManager.createDefault());
			m.registerSourceManager(new BandcampAudioSourceManager());
			m.registerSourceManager(new HttpAudioSourceManager());
			manager = m;
			return m;
		} catch (Throwable t) {
			initError = t;
			IngameMusic.LOG.error("Lavaplayer failed to start", t);
			throw new IOException(problem(), t);
		}
	}

	public static void shutdown() {
		AudioPlayerManager m = manager; manager = null;
		if (m != null) { try { m.shutdown(); } catch (Throwable ignored) { } }
	}

	/** Search by words with the selected source, or load a pasted link (track or playlist). */
	public static CompletableFuture<List<Track>> search(String query) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				String q = query.trim();
				boolean link = q.startsWith("http://") || q.startsWith("https://");
				AudioItem item = manager().loadItemSync(link ? q : source().prefix + q);
				return toTracks(item);
			} catch (RuntimeException e) {
				throw new IllegalStateException(describe(e), e);
			} catch (IOException e) {
				throw new IllegalStateException(e.getMessage(), e);
			}
		}, IngameMusic.IO);
	}

	/** Blocking: resolves one of our tracks to something Lavaplayer can play. */
	public static AudioTrack resolve(String uri) throws IOException {
		AudioItem item;
		try { item = manager().loadItemSync(uri); }
		catch (RuntimeException e) { throw new IOException(describe(e), e); }
		if (item instanceof AudioTrack t) return t;
		if (item instanceof AudioPlaylist p && !p.getTracks().isEmpty()) return p.getSelectedTrack() != null ? p.getSelectedTrack() : p.getTracks().get(0);
		throw new IOException("Track not found (it may be private, region-locked or removed)");
	}

	static String describe(Throwable e) {
		Throwable r = e; while (r.getCause() != null && r.getCause() != r && r.getMessage() == null) r = r.getCause();
		String m = r.getMessage();
		if (m == null) m = r.getClass().getSimpleName();
		if (m.contains("Sign in to confirm") || m.contains("confirm you're not a bot") || m.contains("429"))
			m = "YouTube is blocking this connection (bot check). Try SoundCloud (SC) or sign in via Opts.";
		return m;
	}

	private static List<Track> toTracks(AudioItem item) {
		List<Track> out = new ArrayList<>();
		if (item instanceof AudioTrack t) out.add(convert(t));
		else if (item instanceof AudioPlaylist p) for (AudioTrack t : p.getTracks()) { if (out.size() >= 40) break; out.add(convert(t)); }
		return out;
	}

	private static Track convert(AudioTrack t) {
		AudioTrackInfo i = t.getInfo();
		String source = t.getSourceManager() != null ? t.getSourceManager().getSourceName() : "web";
		String title = i.title == null ? "Unknown" : i.title, artist = i.author == null ? "Unknown" : i.author;
		// YouTube titles are usually "Artist - Song (Official Video)": tidy them so lyrics lookup and the UI read well.
		artist = artist.replaceAll("(?i)\\s*-\\s*topic$", "").replaceAll("(?i)vevo$", "").trim();
		title = title.replaceAll("(?i)\\s*[\\(\\[](official[^\\)\\]]*|lyrics?[^\\)\\]]*|audio[^\\)\\]]*|video[^\\)\\]]*|hd|hq|4k|visuali[sz]er)[\\)\\]]", "").trim();
		int dash = title.indexOf(" - ");
		if (dash > 0 && dash < title.length() - 3) { artist = title.substring(0, dash).trim(); title = title.substring(dash + 3).trim(); }
		if (artist.isEmpty()) artist = "Unknown";
		if (title.isEmpty()) title = i.title == null ? "Unknown" : i.title;
		String art = i.artworkUrl == null ? "" : i.artworkUrl;
		if (art.isEmpty() && "youtube".equals(source) && i.identifier != null) art = "https://i.ytimg.com/vi/" + i.identifier + "/mqdefault.jpg";
		return new Track("lava:" + source + ":" + i.identifier, title, artist, "", i.isStream ? 0L : i.length, art, i.uri, null);
	}
}
