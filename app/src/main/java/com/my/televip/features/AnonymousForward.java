package com.my.televip.features;

import com.my.televip.Configs.ConfigManager;
import com.my.televip.base.AbstractMethodHook;
import com.my.televip.logging.Logger;
import com.my.televip.utils.Utils;

import java.lang.reflect.Field;

import de.robv.android.xposed.XposedHelpers;

/**
 * AnonymousForward — forwards messages without the "Forwarded from …" label.
 *
 * HOW IT WORKS
 * ────────────
 * Telegram's TL schema for messages.forwardMessages carries drop_author at
 * bit 11 (0x800).  In newer Telegram, serializeToStream recomputes flags from
 * scratch:
 *
 *   flags = 0;
 *   if (drop_author) flags |= 2048;   // bit 11
 *   stream.writeInt32(flags);
 *
 * So we MUST set the boolean field `drop_author = true` — not the int field —
 * because the int gets overwritten.  For older builds without that boolean,
 * we fall back to setting the flags int directly (which works when the int
 * is NOT reset at the start of serializeToStream).
 *
 * WHY the constructor-hook approach broke messages
 * ────────────────────────────────────────────────
 * Hooking constructors caused the hook callback to fire in contexts where
 * ConfigManager was not yet initialized.  The resulting NPE was propagated by
 * LSPosed into Telegram's object construction, producing a corrupted TL object
 * that the server rejected — the message appeared to vanish.
 *
 * That approach has been removed.  serializeToStream-only is safer: any
 * exception inside beforeMethod is either swallowed by the framework or caught
 * below, so Telegram's serialization always completes normally.
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
        try {
            XposedHelpers.findAndHookMethod(cls, "serializeToStream",
                "org.telegram.tgnet.AbstractSerializedData",
                new AbstractMethodHook() {
                    @Override
                    protected void beforeMethod(MethodHookParam param) {
                        try {
                            // Guard first — if ConfigManager isn't ready yet, bail safely.
                            if (ConfigManager.anonymousForward == null) return;
                            if (!ConfigManager.anonymousForward.isEnable()) return;

                            // ── Primary: boolean field (newer Telegram) ────────────────
                            // serializeToStream does "flags = 0; if (drop_author) flags |= 2048"
                            // so setting the boolean field is the only reliable approach.
                            boolean boolSet = false;
                            try {
                                XposedHelpers.setBooleanField(param.thisObject, "drop_author", true);
                                boolSet = true;
                            } catch (Throwable ignored) {}

                            // ── Secondary: scan declared booleans for the right bit ────
                            // For obfuscated builds where the field is renamed, iterate
                            // boolean fields in declaration order.  drop_author is bit 11
                            // (value 2048 in flags).  We rely on the known field ordering
                            // from TL schema to pick the correct field index (index 4,
                            // 0-based, counting boolean fields only: silent=0, background=1,
                            // with_my_score=2, grouped=3, drop_author=4).
                            if (!boolSet) {
                                try {
                                    int boolIdx = 0;
                                    for (Field f : cls.getDeclaredFields()) {
                                        if (f.getType() != boolean.class) continue;
                                        if (boolIdx == 4) {  // drop_author position
                                            f.setAccessible(true);
                                            f.set(param.thisObject, true);
                                            boolSet = true;
                                            break;
                                        }
                                        boolIdx++;
                                    }
                                } catch (Throwable ignored) {}
                            }

                            // ── Fallback: int flags directly (older Telegram) ──────────
                            // Only useful when serializeToStream does NOT reset flags=0.
                            if (!boolSet) {
                                try {
                                    int flags = XposedHelpers.getIntField(param.thisObject, "flags");
                                    XposedHelpers.setIntField(param.thisObject, "flags", flags | FLAG_DROP_AUTHOR);
                                } catch (Throwable ignored) {}
                            }

                        } catch (Throwable t) {
                            // Catch-all: never let an exception escape into Telegram's code.
                            Logger.e(t);
                        }
                    }
                });
        } catch (Throwable t) { Logger.e(t); }
    }
}
