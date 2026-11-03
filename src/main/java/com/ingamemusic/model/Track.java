package com.ingamemusic.model;

/** A playable track: resolved by Lavaplayer (id "lava:..."), a plain audioUrl, or a local file. */
public record Track(String id, String title, String artist, String album, long durationMs,
                    String artUrl, String audioUrl, String localPath) {
	public boolean isLocal() { return localPath != null; }
	/** Searched, resolved and decoded by Lavaplayer (YouTube, SoundCloud, Bandcamp, plain links). */
	public boolean isLava() { return id != null && id.startsWith("lava:"); }
}
