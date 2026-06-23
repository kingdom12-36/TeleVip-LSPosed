package com.my.televip.features;

import com.my.televip.Configs.ConfigManager;
import com.my.televip.base.AbstractMethodHook;
import com.my.televip.logging.Logger;
import com.my.televip.utils.Utils;

import de.robv.android.xposed.XposedHelpers;

/**
 * AnonymousForward — forwards messages without the "Forwarded from …" label.
 *
 * Telegram's TL protocol includes a drop_author flag (bit 9, 0x200) in the
 * messages.forwardMessages request.  When set, the server omits the fwd_from
 * attribution field and recipients see the message as if you sent it fresh.
 * This is the same mechanism Telegram Premium uses for "Forward Anonymously".
 *
 * We hook TLRPC$TL_messages_forwardMessages serializeToStream() — called
 * whenever a forward request is about to be written to the wire — and force
 * the drop_author bit on before serialization.
 */
public class AnonymousForward {

    public static boolean isEnable = false;

    private static final int FLAG_DROP_AUTHOR         = 1 << 9;   // 0x200
    private static final int FLAG_DROP_MEDIA_CAPTIONS = 1 << 10;  // 0x400

    public static void init() {
        try {
            if (!isEnable) {
                isEnable = true;

                // ── Primary: hook the TL class that carries the forward request ──
                hookForwardClass("org.telegram.tgnet.TLRPC$TL_messages_forwardMessages");

                // ── Fallback class names used in some Telegram forks ──
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

            // serializeToStream is called just before the request is written to
            // the socket — the last safe point to mutate the flags field.
            XposedHelpers.findAndHookMethod(cls, "serializeToStream",
                "org.telegram.tgnet.AbstractSerializedData",
                new AbstractMethodHook() {
                    @Override
                    protected void beforeMethod(MethodHookParam param) {
                        if (!ConfigManager.anonymousForward.isEnable()) return;
                        try {
                            int flags = XposedHelpers.getIntField(param.thisObject, "flags");
                            // Set drop_author; also drop media captions for full anonymity
                            flags |= FLAG_DROP_AUTHOR | FLAG_DROP_MEDIA_CAPTIONS;
                            XposedHelpers.setIntField(param.thisObject, "flags", flags);
                        } catch (Throwable ignored) {}
                    }
                });
        } catch (Throwable t) { Logger.e(t); }
    }
}
