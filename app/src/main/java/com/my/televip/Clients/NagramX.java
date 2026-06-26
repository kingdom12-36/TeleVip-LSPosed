package com.my.televip.Clients;

import com.my.televip.obfuscate.struct.ClassInfo;
import com.my.televip.obfuscate.struct.FieldInfo;
import com.my.televip.obfuscate.struct.MethodInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NagramX {
    private static final List<ClassInfo> classList = new ArrayList<>();
    private static final List<FieldInfo> fieldList = new ArrayList<>();
    private static final List<MethodInfo> methodList = new ArrayList<>();

    /**
     * Obfuscation mappings for NagramX (Nekogram-based).
     * Source: Re-Telegram project (Sakion-Team/Re-Telegram) — Nekogram.java resolver.
     *
     * NOTE: These mappings target a specific NagramX build.
     * If a future NagramX update changes the obfuscation, update accordingly.
     * DontWipeMessages.java has a dynamic fallback via reflection that handles
     * unknown obfuscation gracefully.
     */
    static {
        // ── Classes ─────────────────────────────────────────────────────────
        classList.add(new ClassInfo("org.telegram.ui.Cells.ChatMessageCell",                "X50"));
        classList.add(new ClassInfo("org.telegram.tgnet.TLRPC$Message",                    "aA3"));
        classList.add(new ClassInfo("org.telegram.tgnet.TLRPC$TL_updateDeleteMessages",     "Hj4"));
        classList.add(new ClassInfo("org.telegram.tgnet.TLRPC$TL_updateDeleteChannelMessages", "Gj4"));

        // ── Fields ──────────────────────────────────────────────────────────
        fieldList.add(new FieldInfo("TLRPC$Message",          "id",                       "a"));
        fieldList.add(new FieldInfo("TLRPC$Message",          "flags",                    "k"));
        fieldList.add(new FieldInfo("TLRPC$TL_updateDeleteMessages",        "messages",   "a"));
        fieldList.add(new FieldInfo("TLRPC$TL_updateDeleteChannelMessages", "channel_id", "a"));
        fieldList.add(new FieldInfo("TLRPC$TL_updateDeleteChannelMessages", "messages",   "b"));
        fieldList.add(new FieldInfo("Theme",                  "chat_timePaint",            "K2"));
        fieldList.add(new FieldInfo("MessagesController",     "dialogMessage",             "D"));
        fieldList.add(new FieldInfo("MessagesController",     "dialogMessagesByIds",       "G"));
        fieldList.add(new FieldInfo("NotificationCenter",     "messagesDeleted",           "v"));

        // ── Methods ─────────────────────────────────────────────────────────
        methodList.add(new MethodInfo("ChatMessageCell",        "measureTime",                         "h7"));
        methodList.add(new MethodInfo("MessagesController",     "deleteMessages",                      "W8"));
        methodList.add(new MethodInfo("MessagesController",     "markDialogMessageAsDeleted",          "ol"));
        methodList.add(new MethodInfo("MessagesStorage",        "markMessagesAsDeleted",               "t8"));
        methodList.add(new MethodInfo("NotificationCenter",     "postNotificationName",                "L"));
        methodList.add(new MethodInfo("NotificationsController","removeDeletedMessagesFromNotifications","b2"));
        methodList.add(new MethodInfo("DownloadController",     "canDownloadMedia",                    "r"));
        methodList.add(new MethodInfo("MessageObject",          "getDialogId",                         "G0"));
    }

    public static class ClassResolver
    {
        public static String resolve(String name) {
            for (ClassInfo info : classList)
                if (info.getOriginal().equals(name))
                    return info.getResolved();

            return null;
        }

        public static boolean has(String name)
        {
            boolean has = false;
            for (ClassInfo info : classList) {
                if (info.getOriginal().equals(name)) {
                    has = true;
                    break;
                }
            }
            return has;
        }
    }

    public static class FieldResolver
    {
        public static String resolve(String className, String name) {
            for (FieldInfo info : fieldList)
                if (info.getClassName().equals(className) && info.getOriginal().equals(name))
                    return info.getResolved();

            return null;
        }

        public static boolean has(String className, String name)
        {
            boolean has = false;
            for (FieldInfo info : fieldList) {
                if (info.getClassName().equals(className) && info.getOriginal().equals(name)) {
                    has = true;
                    break;
                }
            }
            return has;
        }
    }

    public static class MethodResolver
    {
        public static String resolve(String className, String name) {
            for (MethodInfo info : methodList)
                if (info.getClassName().equals(className) && info.getOriginal().equals(name))
                    return info.getResolved();

            return null;
        }

        public static boolean has(String className, String name)
        {
            boolean has = false;
            for (MethodInfo info : methodList) {
                if (info.getClassName().equals(className) && info.getOriginal().equals(name)) {
                    has = true;
                    break;
                }
            }
            return has;
        }
    }

    public static class ParameterResolver
    {
        static Map<String,Class<?>[]> objectList = new HashMap<>();

        public static void register(String name,  Class<?>[] classes){
            objectList.put(name,classes);
        }

        public static Class<?>[] resolve(String name) {
            return objectList.get(name);
        }

        public static boolean has(String name)
        {
            boolean has = false;
           Class<?>[] classes = objectList.get(name);
           if (classes != null){
               has = true;
           }
            return has;
        }
    }

    public static void loadParameter() {}
}
