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
                Class<?> moClass  = ClassLoad.getClass(ClassNames.MESSAGE_OBJECT);

                // ── Hook 1: measureTime ───────────────────────────────────────────
                // Strategy A: AutomationResolver (works when the mapping is up-to-date)
                // Strategy B: brute-force scan for method(MessageObject)→void (fallback)
                boolean hookedMeasureTime = false;
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
                    hookedMeasureTime = true;
                } catch (Throwable ignored) {}

                if (!hookedMeasureTime && moClass != null) {
                    // Brute-force: any method(MessageObject) → void that isn't a setter
                    for (Method m : cellClass.getDeclaredMethods()) {
                        if (m.getParameterCount() == 1
                                && moClass.isAssignableFrom(m.getParameterTypes()[0])
                                && m.getReturnType() == void.class
                                && !m.getName().toLowerCase().contains("set")) {
                            try {
                                XposedBridge.hookMethod(m, new AbstractMethodHook() {
                                    @Override
                                    protected void afterMethod(MethodHookParam param) {
                                        applyTimeLabel(param.args[0], param.thisObject);
                                    }
                                });
                            } catch (Throwable ignored) {}
                        }
                    }
                }

                // ── Hook 2: setMessageObject ──────────────────────────────────────
                // Called every time a cell is populated (scroll, resume, initial load).
                // Injects \u274C prefix into the message text at render time.
                // Works after restart because FLAG_DELETED is persisted in the DB blob.
                if (moClass != null) {
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
                }
            }
        } catch (Throwable t) {
            Logger.e(t);
        }
    }

    /**
     * Applies the time-area label ("\u274C Deleted") — called from measureTime hook.
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
                    ? "\u274C " + word
                    : "\u274C Deleted";
                appendTimeLabel(label, thisObject, true);
            }
        } catch (Throwable t) { Logger.e(t); }
    }

    /**
     * Injects "\u274C " into the message text bubble at render time.
     * Idempotent — skipped when the prefix is already present.
     * Restores the indicator after app restart by checking FLAG_DELETED (in DB blob).
     */
    private static void applyDeletedIndicator(Object msgObjRaw) {
        if (ConfigManager.showDeletedMessages == null || !ConfigManager.showDeletedMessages.isEnable()) return;
        try {
            MessageObject messageObject = new MessageObject(msgObjRaw);
            if (messageObject.getMessageObject() == null) return;
            TLRPC.Message owner = messageObject.getMessageOwner();
            if (owner == null) return;
            if (!isDeleted(owner)) return;

            try {
                String txt = (String) XposedHelpers.getObjectField(owner.message, "message");
                if (txt == null) txt = "";
                if (!txt.startsWith("\u274C") && !txt.startsWith("\uD83D\uDDD1")) {
                    XposedHelpers.setObjectField(owner.message, "message", "\u274C " + txt);
                }
            } catch (Throwable ignored) {}
        } catch (Throwable t) { Logger.e(t); }
    }

    /**
     * Returns true when the message was deleted by its sender.
     * Also caches the ID into deletedIds for faster subsequent checks.
     */
    private static boolean isDeleted(TLRPC.Message owner) {
        if ((owner.getFlags() & ShowDeletedMessages.FLAG_DELETED) != 0) {
            ShowDeletedMessages.deletedIds.add(owner.getID());
            return true;
        }
        return ShowDeletedMessages.deletedIds.contains(owner.getID());
    }

    /**
     * Appends a coloured label in the message timestamp corner.
     * Always updates the measured width so the time-row is wide enough to show the label.
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

    public static SpannableStringBuilder convertToStringBuilder(CharSequence seq) {
        if (seq == null) return null;
        return seq instanceof SpannableStringBuilder ? (SpannableStringBuilder) seq : new SpannableStringBuilder(seq);
    }

    // ── Instance wrapper ──────────────────────────────────────────────────────
    // Used by SecretMediaSave and other features that need to wrap a real
    // Telegram ChatMessageCell object in this TeleVip accessor.

    Object chatMessageCell;

    public ChatMessageCell(Object cell) {
        chatMessageCell = cell;
    }

    public MessageObject getMessageObject() {
        return new MessageObject(XposedHelpers.callMethod(chatMessageCell,
            AutomationResolver.resolve("ChatMessageCell", "getMessageObject", AutomationResolver.ResolverType.Method)));
    }

    public Object getChatMessageCell() {
        return chatMessageCell;
    }
}
