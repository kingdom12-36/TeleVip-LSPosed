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

import de.robv.android.xposed.XposedHelpers;

public class ChatMessageCell {

    public static boolean isEnable = false;

    public static void init() {
        try {
            if (!isEnable) {
                isEnable = true;
                if (ClassLoad.getClass(ClassNames.CHAT_MESSAGE_CELL) == null) return;

                // Hook 1: measureTime — adds label to the timestamp corner
                HMethod.hookMethod(ClassLoad.getClass(ClassNames.CHAT_MESSAGE_CELL),
                    AutomationResolver.resolve("ChatMessageCell", "measureTime", AutomationResolver.ResolverType.Method),
                    AutomationResolver.merge(AutomationResolver.resolveObject("measureTime",
                        new Class[]{ClassLoad.getClass(ClassNames.MESSAGE_OBJECT)}),
                        new AbstractMethodHook() {
                            @Override
                            protected void afterMethod(MethodHookParam param) {
                                boolean showDeleted   = ConfigManager.showDeletedMessages != null && ConfigManager.showDeletedMessages.isEnable();
                                boolean showMessageId = ConfigManager.showMessageId      != null && ConfigManager.showMessageId.isEnable();
                                if (!showDeleted && !showMessageId) return;
                                try {
                                    MessageObject messageObject = new MessageObject(param.args[0]);
                                    if (messageObject.getMessageObject() == null) return;
                                    TLRPC.Message owner = messageObject.getMessageOwner();
                                    if (owner == null) return;

                                    if (showMessageId && owner.getID() != 0)
                                        appendTimeLabel("ID " + owner.getID(), param.thisObject, false);

                                    if (showDeleted && isDeleted(owner)) {
                                        String word = Translator.get(Keys.Deleted);
                                        String label = (word != null && !word.isEmpty() && !word.equals(Keys.Deleted))
                                            ? "\uD83D\uDDD1 " + word
                                            : "\uD83D\uDDD1 Deleted";
                                        appendTimeLabel(label, param.thisObject, true);
                                    }
                                } catch (Throwable t) { Logger.e(t); }
                            }
                        }));

                // Hook 2: setMessageObject overloads — inject trash-bin into bubble text
                try {
                    Class<?> moClass = ClassLoad.getClass(ClassNames.MESSAGE_OBJECT);
                    for (java.lang.reflect.Method m :
                            ClassLoad.getClass(ClassNames.CHAT_MESSAGE_CELL).getDeclaredMethods()) {
                        if (m.getParameterCount() >= 1
                                && m.getParameterTypes()[0] != null
                                && m.getParameterTypes()[0].equals(moClass)) {
                            HMethod.hookMethod(m, new AbstractMethodHook() {
                                @Override
                                protected void afterMethod(MethodHookParam param) {
                                    if (param.args[0] == null) return;
                                    if (ConfigManager.showDeletedMessages == null
                                            || !ConfigManager.showDeletedMessages.isEnable()) return;
                                    try {
                                        MessageObject mo = new MessageObject(param.args[0]);
                                        TLRPC.Message owner = mo.getMessageOwner();
                                        if (owner == null) return;
                                        if (isDeleted(owner))
                                            injectDeletedText(owner);
                                    } catch (Throwable t) { Logger.e(t); }
                                }
                            });
                        }
                    }
                } catch (Throwable t) { Logger.e(t); }
            }
        } catch (Throwable t) { Logger.e(t); }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /**
     * Returns true if this message has been marked as deleted.
     * Checks BOTH the in-memory FLAG_DELETED bit AND the static deletedIds set.
     * The set is populated immediately when the delete update arrives, so it
     * works even when dialogMessagesByIds doesn't contain the message.
     */
    private static boolean isDeleted(TLRPC.Message owner) {
        if ((owner.getFlags() & ShowDeletedMessages.FLAG_DELETED) != 0) return true;
        return ShowDeletedMessages.deletedIds.contains(owner.getID());
    }

    /**
     * Inject the trash-bin emoji prefix into the raw TL message text.
     * Idempotent — safe to call on every cell bind.
     */
    private static void injectDeletedText(TLRPC.Message owner) {
        try {
            String txt = (String) XposedHelpers.getObjectField(owner.message, "message");
            if (txt == null) txt = "";
            if (!txt.startsWith("\uD83D\uDDD1")) {
                XposedHelpers.setObjectField(owner.message, "message", "\uD83D\uDDD1  " + txt);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Prepend a coloured label to the message timestamp string.
     * Always updates timeWidth/timeTextWidth — falls back to a 12sp TextPaint
     * when Theme.getTextPaint() cannot resolve the obfuscated chat_timePaint
     * field, so the timestamp area is always wide enough to show the label.
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

            // Always update widths — fallback to 12sp when themed paint unavailable
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

    Object chatMessageCell;
    public ChatMessageCell(Object cell) { chatMessageCell = cell; }
    public MessageObject getMessageObject() {
        return new MessageObject(XposedHelpers.callMethod(chatMessageCell,
            AutomationResolver.resolve("ChatMessageCell", "getMessageObject", AutomationResolver.ResolverType.Method)));
    }
    public Object getChatMessageCell() { return chatMessageCell; }
}