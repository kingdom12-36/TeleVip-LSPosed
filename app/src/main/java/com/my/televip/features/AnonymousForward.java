package com.my.televip.features;

import com.my.televip.Configs.ConfigManager;
import com.my.televip.base.AbstractMethodHook;
import com.my.televip.logging.Logger;
import com.my.televip.utils.Utils;

import de.robv.android.xposed.XposedHelpers;

/**
 * AnonymousForward — forwards messages without the "Forwarded from …" label.
 *
 * HOW IT WORKS
 * ────────────
 * Telegram's TL schema for messages.forwardMessages carries a drop_author flag
 * at bit 11 (0x800) in the flags bitmask (TL layer 170+):
 *
 *   messages.forwardMessages#c4f600c4
 *     flags:#
 *     ...
 *     drop_author:flags.11?true        ← bit 11
 *     drop_media_captions:flags.12?true
 *     ...
 *
 * IMPORTANT — why the old approach broke
 * ───────────────────────────────────────
 * We used to write directly to the `flags` int field before serializeToStream
 * ran.  In newer Telegram versions the method body starts with:
 *
 *   flags = 0;               ← wipes our value!
 *   if (silent)   flags |= …
 *   if (drop_author) flags |= 2048   ← bit 11
 *   …
 *   stream.writeInt32(flags);
 *
 * So the int write was always overwritten.  Additionally, bit 9 (0x200) is
 * `grouped`, not drop_author — setting the wrong bit corrupted the request
 * and caused the forward to silently fail ("message vanishes").
 *
 * THE FIX
 * ───────
 * Set the *boolean field* `drop_author = true` on the object.  When
 * serializeToStream then recomputes flags from booleans, it naturally includes
 * drop_author.  We also fall back to the direct flags approach (with correct
 * bit 11) for older Telegram builds that don't have the boolean field.
 */
public class AnonymousForward {

    public static boolean isEnable = false;

    // Correct bit positions per TL schema layer 170+
    private static final int FLAG_DROP_AUTHOR         = 1 << 11;  // 0x800
    private static final int FLAG_DROP_MEDIA_CAPTIONS = 1 << 12;  // 0x1000

    public static void init() {
        try {
            if (!isEnable) {
                isEnable = true;
                hookForwardClass("org.telegram.tgnet.TLRPC$TL_messages_forwardMessages");

                // Fallback class names used in some Telegram forks
                for (String alt : new String[]{
                    "org.telegram.tgnet.tl.TL_messages$forwardMessages",
                    "org.telegram.tgnet.tl.TL_messages.forwardMessages"
                }) {
                    Class<?> c = XposedHelpers.findClassIfExists(alt, Utils.classLoader);
                    if (c != null) hookForwardClass(alt);
                }
            }
        } catch (Throwable e) { Logger.e(e); }
    }

    private static void hookForwardClass(String className) {
        try {
            Class<?> cls = XposedHelpers.findClassIfExists(className, Utils.classLoader);
            if (cls == null) return;

            XposedHelpers.findAndHookMethod(cls, "serializeToStream",
                "org.telegram.tgnet.AbstractSerializedData",
                new AbstractMethodHook() {
                    @Override
                    protected void beforeMethod(MethodHookParam param) {
                        if (!ConfigManager.anonymousForward.isEnable()) return;

                        // ── Primary (newer Telegram): set the boolean field ────────
                        // serializeToStream resets `flags` to 0 then recomputes it
                        // from boolean fields, so we must set the boolean — not the int.
                        boolean boolSet = false;
                        try {
                            XposedHelpers.setBooleanField(param.thisObject, "drop_author", true);
                            boolSet = true;
                        } catch (Throwable ignored) {}

                        // ── Fallback (older Telegram): set flags int directly ──────
                        // Only used if the boolean field does not exist.
                        if (!boolSet) {
                            try {
                                int flags = XposedHelpers.getIntField(param.thisObject, "flags");
                                flags |= FLAG_DROP_AUTHOR;
                                XposedHelpers.setIntField(param.thisObject, "flags", flags);
                            } catch (Throwable ignored) {}
                        }
                    }
                });
        } catch (Throwable t) { Logger.e(t); }
    }
}
