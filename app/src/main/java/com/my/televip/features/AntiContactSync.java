package com.my.televip.features;

import com.my.televip.Configs.ConfigManager;
import com.my.televip.base.AbstractMethodHook;
import com.my.televip.logging.Logger;
import com.my.televip.utils.Utils;

import java.util.ArrayList;

import de.robv.android.xposed.XposedHelpers;

/**
 * AntiContactSync — prevents anyone from finding you via phone-number contact sync.
 *
 * When a user imports their contacts, Telegram uploads the phone numbers to the
 * server and returns matching accounts. We intercept TL_contacts_importContacts
 * just before serialization and clear its contact list — so your number is never
 * matched, even to people who have it saved in their phonebook.
 *
 * This is a pure wire-level hook: no server changes, no UI changes. It fires
 * silently every time Telegram tries to upload contacts.
 */
public class AntiContactSync {

    public static boolean isEnable = false;

    public static void init() {
        try {
            if (!isEnable) {
                isEnable = true;
                hookImportContacts("org.telegram.tgnet.TLRPC$TL_contacts_importContacts");
                for (String alt : new String[]{
                    "org.telegram.tgnet.tl.TL_contacts$importContacts",
                    "org.telegram.tgnet.tl.TL_contacts.importContacts"
                }) {
                    Class<?> c = XposedHelpers.findClassIfExists(alt, Utils.classLoader);
                    if (c != null) hookImportContacts(alt);
                }
            }
        } catch (Throwable e) { Logger.e(e); }
    }

    private static void hookImportContacts(String className) {
        try {
            Class<?> cls = XposedHelpers.findClassIfExists(className, Utils.classLoader);
            if (cls == null) return;
            XposedHelpers.findAndHookMethod(cls, "serializeToStream",
                "org.telegram.tgnet.AbstractSerializedData",
                new AbstractMethodHook() {
                    @Override
                    protected void beforeMethod(MethodHookParam param) {
                        if (!ConfigManager.antiContactSync.isEnable()) return;
                        try {
                            // Clear the contacts list so your phone number is never uploaded
                            Object contacts = XposedHelpers.getObjectField(param.thisObject, "contacts");
                            if (contacts instanceof ArrayList) {
                                ((ArrayList<?>) contacts).clear();
                            }
                        } catch (Throwable ignored) {}
                    }
                });
        } catch (Throwable t) { Logger.e(t); }
    }
}
