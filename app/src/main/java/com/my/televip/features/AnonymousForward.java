package com.my.televip.features;

import com.my.televip.Configs.ConfigManager;
import com.my.televip.base.AbstractMethodHook;
import com.my.televip.logging.Logger;
import com.my.televip.utils.Utils;

import java.lang.reflect.Constructor;

import de.robv.android.xposed.XposedBridge;
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
 *     drop_author:flags.11?true        ← bit 11
 *     drop_media_captions:flags.12?true
 *
 * WHY serializeToStream-only approaches break
 * ─────────────────────────────────────────────
 * In newer Telegram, serializeToStream recomputes flags FROM scratch:
 *
 *   flags = 0;
 *   if (drop_author) flags |= 2048;   ← bit 11
 *   stream.writeInt32(flags);
 *
 * So:
 *  • Setting the flags int in beforeMethod → wiped immediately by flags=0.
 *  • Setting the boolean field in beforeMethod → works only if the boolean
 *    field exists in this Telegram build.
 *
 * THE FIX (two-layer approach)
 * ─────────────────────────────
 * Layer 1 (primary): hook all constructors of TL_messages_forwardMessages.
 *   afterMethod: set drop_author=true on the brand-new object.
 *   This fires BEFORE serializeToStream, so even when flags is reset to 0,
 *   the recomputation reads drop_author=true and sets bit 11 correctly.
 *
 * Layer 2 (fallback): hook serializeToStream beforeMethod.
 *   Sets both the boolean field and flags bit 11 just in case the object
 *   was created before our module loaded, or a Telegram build that doesn't
 *   reset flags at the top of serializeToStream.
 */
public class AnonymousForward {

    public static boolean isEnable = false;

    private static final int FLAG_DROP_AUTHOR = 1 << 11;  // 0x800

    private static final String[] CLASS_NAMES = {
        "org.telegram.tgnet.TLRPC$TL_messages_forwardMessages",
        "org.telegram.tgnet.tl.TL_messages$forwardMessages",
        "org.telegram.tgnet.tl.TL_messages.forwardMessages",
    };

    public static void init() {
        try {
            if (!isEnable) {
                isEnable = true;
                for (String name : CLASS_NAMES) {
                    Class<?> cls = XposedHelpers.findClassIfExists(name, Utils.classLoader);
                    if (cls != null) hookForwardClass(cls);
                }
            }
        } catch (Throwable e) { Logger.e(e); }
    }

    private static void hookForwardClass(Class<?> cls) {
        // ── Layer 1: Constructor hook (primary) ──────────────────────────────
        // Fires right after the TL object is allocated. Setting drop_author
        // here guarantees it is true when serializeToStream later runs —
        // even if that method starts with "flags = 0".
        for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
            try {
                XposedBridge.hookMethod(ctor, new AbstractMethodHook() {
                    @Override
                    protected void afterMethod(MethodHookParam param) {
                        if (!ConfigManager.anonymousForward.isEnable()) return;
                        try {
                            XposedHelpers.setBooleanField(param.thisObject, "drop_author", true);
                        } catch (Throwable ignored) {
                            // Field absent in this build — Layer 2 handles it.
                        }
                    }
                });
            } catch (Throwable t) { Logger.e(t); }
        }

        // ── Layer 2: serializeToStream hook (fallback) ───────────────────────
        // Handles builds where the object may have been constructed before our
        // module was loaded, and older builds that don't reset flags to 0.
        try {
            XposedHelpers.findAndHookMethod(cls, "serializeToStream",
                "org.telegram.tgnet.AbstractSerializedData",
                new AbstractMethodHook() {
                    @Override
                    protected void beforeMethod(MethodHookParam param) {
                        if (!ConfigManager.anonymousForward.isEnable()) return;
                        // Try boolean field first (newer Telegram).
                        try {
                            XposedHelpers.setBooleanField(param.thisObject, "drop_author", true);
                        } catch (Throwable ignored) {}
                        // Also set bit 11 directly (older Telegram without flags reset).
                        try {
                            int flags = XposedHelpers.getIntField(param.thisObject, "flags");
                            XposedHelpers.setIntField(param.thisObject, "flags", flags | FLAG_DROP_AUTHOR);
                        } catch (Throwable ignored) {}
                    }
                });
        } catch (Throwable t) { Logger.e(t); }
    }
}
