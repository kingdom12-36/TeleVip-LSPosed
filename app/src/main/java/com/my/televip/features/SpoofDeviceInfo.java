package com.my.televip.features;

import com.my.televip.Configs.ConfigManager;
import com.my.televip.base.AbstractMethodHook;
import com.my.televip.logging.Logger;
import com.my.televip.utils.Utils;

import de.robv.android.xposed.XposedHelpers;

/**
 * SpoofDeviceInfo — replaces the device fingerprint sent in every TL_initConnection.
 *
 * Telegram includes device_model, system_version, app_version and lang_code in
 * TL_initConnection during the session handshake. This data appears in
 * Settings → Devices and can be used to correlate sessions across accounts or
 * identify your real hardware. We replace it with generic values so every
 * session looks like a common Android phone — not your actual device.
 *
 * Hook point: serializeToStream() — called just before the init packet hits the wire.
 */
public class SpoofDeviceInfo {

    public static boolean isEnable = false;

    private static final String FAKE_DEVICE  = "Samsung Galaxy S22";
    private static final String FAKE_SYSTEM  = "Android 14";
    private static final String FAKE_APP_VER = "10.14.5 (4595)";
    private static final String FAKE_LANG    = "en";

    public static void init() {
        try {
            if (!isEnable) {
                isEnable = true;
                hookInitConnection("org.telegram.tgnet.TLRPC$TL_initConnection");
                for (String alt : new String[]{
                    "org.telegram.tgnet.tl.TL_account$initConnection",
                    "org.telegram.tgnet.tl.TL_account.initConnection",
                    "org.telegram.tgnet.TLRPC$TL_help_getConfig" // fallback scan
                }) {
                    Class<?> c = XposedHelpers.findClassIfExists(alt, Utils.classLoader);
                    if (c != null) hookInitConnection(alt);
                }
            }
        } catch (Throwable e) { Logger.e(e); }
    }

    private static void hookInitConnection(String className) {
        try {
            Class<?> cls = XposedHelpers.findClassIfExists(className, Utils.classLoader);
            if (cls == null) return;
            // Only hook classes that actually carry the device fields
            try { cls.getDeclaredField("device_model"); } catch (NoSuchFieldException e) { return; }
            XposedHelpers.findAndHookMethod(cls, "serializeToStream",
                "org.telegram.tgnet.AbstractSerializedData",
                new AbstractMethodHook() {
                    @Override
                    protected void beforeMethod(MethodHookParam param) {
                        if (!ConfigManager.spoofDeviceInfo.isEnable()) return;
                        try {
                            XposedHelpers.setObjectField(param.thisObject, "device_model",   FAKE_DEVICE);
                            XposedHelpers.setObjectField(param.thisObject, "system_version", FAKE_SYSTEM);
                            XposedHelpers.setObjectField(param.thisObject, "app_version",    FAKE_APP_VER);
                            XposedHelpers.setObjectField(param.thisObject, "lang_code",      FAKE_LANG);
                        } catch (Throwable ignored) {}
                    }
                });
        } catch (Throwable t) { Logger.e(t); }
    }
}
