package com.ingamemusic;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Minecraft 26.3 reads the keyboard through SDL and indexes a fixed-size state array with every key binding's number when a
 * world opens. One binding with a number outside that array (for example a stale "-1" saved in options.txt) crashes the
 * game with an IndexOutOfBoundsException. This tests every keyboard binding with the exact call the game makes and resets
 * any that would crash, so a bad binding can never take the game down.
 */
public final class KeyGuard {
	private KeyGuard() {}

	public static void check() {
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc == null || mc.options == null) return;
			boolean changed = false;
			for (KeyMapping m : mc.options.keyMappings) {
				try {
					InputConstants.Key k = InputConstants.getKey(m.saveString());
					if (k.getType() != InputConstants.Type.KEYBOARD || k.getValue() == InputConstants.UNKNOWN.getValue()) continue;
					InputConstants.isKeyDown(k.getValue());                 // the call KeyMapping.setAll() makes on world join
				} catch (RuntimeException e) {
					IngameMusic.LOG.warn("Resetting invalid key binding {} ({})", m.getName(), e.toString());
					InputConstants.Key def = m.getDefaultKey();
					boolean ok = true;
					try { if (def.getType() == InputConstants.Type.KEYBOARD && def.getValue() != InputConstants.UNKNOWN.getValue()) InputConstants.isKeyDown(def.getValue()); }
					catch (RuntimeException e2) { ok = false; }
					m.setKey(ok ? def : InputConstants.UNKNOWN);
					changed = true;
				}
			}
			if (changed) { KeyMapping.resetMapping(); mc.options.save(); }
		} catch (Throwable t) {
			IngameMusic.LOG.warn("Key binding check failed: {}", t.toString());
		}
	}
}
