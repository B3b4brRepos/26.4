package com.ingamemusic.audio;

import com.ingamemusic.lava.LavaService;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sound.sampled.AudioFormat;

/** Feeds the engine with PCM decoded by Lavaplayer (44.1 kHz, stereo, signed 16-bit little-endian). */
public final class LavaSource implements AudioSource {
	private static final AudioFormat FMT = new AudioFormat(44100f, 16, 2, true, false);
	private static final long STALL_MS = 40_000;

	private final AudioPlayer player;
	private final AudioTrack track;
	private volatile boolean ended;
	private volatile String failure;

	private LavaSource(AudioTrack track) throws IOException {
		this.track = track;
		this.player = LavaService.manager().createPlayer();
		player.addListener(new AudioEventAdapter() {
			@Override public void onTrackEnd(AudioPlayer p, AudioTrack t, AudioTrackEndReason reason) { ended = true; }
			@Override public void onTrackException(AudioPlayer p, AudioTrack t, FriendlyException e) {
				Throwable r = e; while (r.getCause() != null && r.getCause() != r && (r.getMessage() == null || r.getMessage().startsWith("Something went wrong"))) r = r.getCause();
				failure = r.getMessage() == null ? "Playback failed" : r.getMessage();
			}
			@Override public void onTrackStuck(AudioPlayer p, AudioTrack t, long thresholdMs) { failure = "Stream stalled"; }
		});
		player.playTrack(track);
	}

	/** Blocking (resolves the stream URL over the network). */
	public static LavaSource open(String uri) throws IOException { return new LavaSource(LavaService.resolve(uri)); }

	@Override public AudioFormat format() { return FMT; }

	@Override public int read(byte[] buf) throws IOException {
		int filled = 0;
		long lastData = System.currentTimeMillis();
		try {
			while (buf.length - filled >= 3840) {                      // one 960-sample stereo frame = 3840 bytes
				AudioFrame f;
				try { f = player.provide(40, TimeUnit.MILLISECONDS); } catch (TimeoutException e) { f = null; }
				if (f != null) {
					int n = f.getDataLength();
					if (n > buf.length - filled) break;                // cannot happen with the 8 KB engine buffer; be safe
					f.getData(buf, filled);
					filled += n; lastData = System.currentTimeMillis();
					continue;
				}
				String fail = failure;
				if (fail != null && filled == 0) throw new IOException(fail);
				if (ended) return filled > 0 ? filled : -1;
				if (filled > 0) break;                                  // hand over what we have, do not stall playback
				if (System.currentTimeMillis() - lastData > STALL_MS) throw new IOException("Timed out waiting for audio");
				Thread.sleep(8);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted", e);
		}
		return filled;
	}

	@Override public void seek(long ms) { if (track.isSeekable()) track.setPosition(Math.max(0, ms)); }

	@Override public long durationMs() { return track.getInfo().isStream ? -1 : track.getDuration(); }

	@Override public void close() {
		try { player.stopTrack(); } catch (Throwable ignored) { }
		try { player.destroy(); } catch (Throwable ignored) { }
	}
}
