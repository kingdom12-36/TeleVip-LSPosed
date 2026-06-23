package com.my.televip.virtuals.ui.Cells;

import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;

import com.my.televip.Class.ClassLoad;
import com.my.televip.Class.ClassNames;
import com.my.televip.Configs.ConfigManager;
import com.my.televip.base.AbstractMethodHook;
import com.my.televip.features.ShowDeletedMessages;
import com.my.televip.hooks.HMethod;
import com.my.televip.language.Keys;
import com.my.televip.language.Translator;
import com.my.televip.logging.Logger;
import com.my.televip.obfuscate.AutomationResolver;
import com.my.televip.virtuals.OfficialChatMessageCell;
import com.my.televip.virtuals.Theme;
import com.my.televip.virtuals.messenger.MessageObject;
import com.my.televip.virtuals.tgnet.TLRPC;

import java.lang.reflect.Method;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class ChatMessageCell {

    public static boolean isEnable = false;

    public static void init() {
        try {
            if (!isEnable) {
                isEnable = true;
                Class<?> cellClass = ClassLoad.getClass(ClassNames.CHAT_MESSAGE_CELL);
                if (cellClass == null) return;
                Class<?> moClass = ClassLoad.getClass(ClassNames.MESSAGE_OBJECT);

                // Hook 1: measureTime — two-strategy approach
                // Strategy A: AutomationResolver (works when mapping is current)
                // Strategy B: brute-force scan for method(MessageObject)→void (fallback)
                hookMeasureTime(cellClass, moClass);

                // Hook 2: setMessageObject — called every time a cell renders a message
                // This is the reliable path: applies the ❌ prefix at render time, not deletion time
                // Works even after app restart because it checks FLAG_DELETED from DB
                hookSetMessageObject(cellClass, moClass);
            }
        } catch (Throwable t) {
            Logger.e(t);
        }
    }

    private static void hookMeasureTime(Class<?> cellClass, Class<?> moClass) {
        // Strategy A: AutomationResolver
        boolean hookedViaResolver = false;
        try {
            HMethod.hookMethod(cellClass,
                AutomationResolver.resolve("ChatMessageCell", "measureTime", AutomationResolver.ResolverType.Method),
                AutomationResolver.merge(AutomationResolver.resolveObject("measureTime",
                    new Class[]{moClass}),
                    new AbstractMethodHook() {
                        @Override
                        protected void afterMethod(MethodHookParam param) {
                            applyTimeLabel(param.args[0], param.thisObject);
                        }
                    }));
            hookedViaResolver = true;
        } catch (Throwable ignored) {}

        if (hookedViaResolver) return;

        // Strategy B: brute-force — find any method(MessageObject)→void that isn't a setter
        try {
            if (moClass == null) return;
            for (Method m : cellClass.getDeclaredMethods()) {
                if (m.getParameterCount() == 1
                        && moClass.isAssignableFrom(m.getParameterTypes()[0])
                        && m.getReturnType() == void.class
                        && !m.getName().toLowerCase().contains("set")) {
                    XposedBridge.hookMethod(m, new AbstractMethodHook() {
                        @Override
                        protected void afterMethod(MethodHookParam param) {
                            applyTimeLabel(param.args[0], param.thisObject);
                        }
                    });
                }
            }
        } catch (Throwable t) {
            Logger.e(t);
        }
    }

    private static void hookSetMessageObject(Class<?> cellClass, Class<?> moClass) {
        // Hook ALL overloads of setMessageObject — first parameter is MessageObject
        // This fires at render time on every scroll, resume, and initial load
        try {
            if (moClass == null) return;
            for (Method m : cellClass.getDeclaredMethods()) {
                if (m.getParameterCount() >= 1
                        && moClass.isAssignableFrom(m.getParameterTypes()[0])
                        && (m.getName().toLowerCase().contains("set")
                            || m.getName().toLowerCase().contains("bind"))) {
                    try {
                        XposedBridge.hookMethod(m, new AbstractMethodHook() {
                            @Override
                            protected void beforeMethod(MethodHookParam param) {
                                applyDeletedIndicator(param.args[0]);
                            }
                        });
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            Logger.e(t);
        }
    }

    /**
     * Applies the time-area label ("❌ Deleted") — called from measureTime hook.
     */
    private static void applyTimeLabel(Object msgObjRaw, Object thisObject) {
        boolean showDeleted   = ConfigManager.showDeletedMessages != null && ConfigManager.showDeletedMessages.isEnable();
        boolean showMessageId = ConfigManager.showMessageId      != null && ConfigManager.showMessageId.isEnable();
        if (!showDeleted && !showMessageId) return;
        try {
            MessageObject messageObject = new MessageObject(msgObjRaw);
            if (messageObject.getMessageObject() == null) return;
            TLRPC.Message owner = messageObject.getMessageOwner();
            if (owner == null) return;

            if (showMessageId && owner.getID() != 0)
                appendTimeLabel("ID " + owner.getID(), thisObject, false);

            if (showDeleted && isDeleted(owner)) {
                String word = Translator.get(Keys.Deleted);
                String label = (word != null && !word.isEmpty() && !word.equals(Keys.Deleted))
                    ? "❌ " + word
                    : "❌ Deleted";
                appendTimeLabel(label, thisObject, true);
            }
        } catch (Throwable t) { Logger.e(t); }
    }

    /**
     * Applies the "❌ " prefix to the message text bubble — called from setMessageObject hook.
     * This is the primary visible indicator: modifies the text the user sees inside the bubble.
     * Idempotent — skipped if prefix already present.
     * Works after restart because it checks FLAG_DELETED (persisted in DB).
     */
    private static void applyDeletedIndicator(Object msgObjRaw) {
        if (ConfigManager.showDeletedMessages == null || !ConfigManager.showDeletedMessages.isEnable()) return;
        try {
            MessageObject messageObject = new MessageObject(msgObjRaw);
            if (messageObject.getMessageObject() == null) return;
            TLRPC.Message owner = messageObject.getMessageOwner();
            if (owner == null) return;

            if (!isDeleted(owner)) return;

            // Prepend ❌ to the message text field (used by Telegram to render bubble text)
            try {
                String txt = (String) XposedHelpers.getObjectField(owner.message, "message");
                if (txt == null) txt = "";
                if (!txt.startsWith("\u274C")) {
                    XposedHelpers.setObjectField(owner.message, "message", "\u274C " + txt);
                }
            } catch (Throwable ignored) {}

            // Also mark message as deleted in the trash-bin emoji style (fallback)
            try {
                String txt = (String) XposedHelpers.getObjectField(owner.message, "message");
                if (txt != null && !txt.startsWith("\uD83D\uDDD1") && !txt.startsWith("\u274C")) {
                    XposedHelpers.setObjectField(owner.message, "message", "\uD83D\uDDD1 " + txt);
                }
            } catch (Throwable ignored) {}
        } catch (Throwable t) { Logger.e(t); }
    }

    /**
     * Returns true if this message was deleted.
     * Also populates deletedIds from FLAG_DELETED for session-level caching.
     */
    private static boolean isDeleted(TLRPC.Message owner) {
        if ((owner.getFlags() & ShowDeletedMessages.FLAG_DELETED) != 0) {
            ShowDeletedMessages.deletedIds.add(owner.getID());
            return true;
        }
        return ShowDeletedMessages.deletedIds.contains(owner.getID());
    }

    /**
     * Appends a colored label in the message timestamp corner.
     */
    private static void appendTimeLabel(String text, Object thisObject, boolean red) {
        try {
            OfficialChatMessageCell cell = new OfficialChatMessageCell(thisObject);
            CharSequence raw = cell.getCurrentTimeString();
            SpannableStringBuilder time = (raw != null)
                ? (raw instanceof SpannableStringBuilder
                    ? (SpannableStringBuilder) raw
                    : new SpannableStringBuilder(raw))
                : new SpannableStringBuilder();

            SpannableStringBuilder label = new SpannableStringBuilder(text);
            if (red) {
                label.setSpan(new ForegroundColorSpan(Color.rgb(255, 69, 69)),
                    0, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            label.append("  ");
            time.insert(0, label);
            cell.setCurrentTimeString(time);

            TextPaint paint = Theme.getTextPaint();
            if (paint == null) {
                paint = new TextPaint();
                DisplayMetrics dm = android.content.res.Resources.getSystem().getDisplayMetrics();
                paint.setTextSize(12f * dm.scaledDensity);
            }
            int w = (int) Math.ceil(paint.measureText(label, 0, label.length()));
            cell.setTimeTextWidth(w + cell.getTimeTextWidth());
            cell.setTimeWidth(w + cell.getTimeWidth());
        } catch (Throwable t) { Logger.e(t); }
    }
}
