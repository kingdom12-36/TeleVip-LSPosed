package com.my.televip.virtuals.ui.Cells;

import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;

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
                if (ClassLoad.getClass(ClassNames.CHAT_MESSAGE_CELL) != null) {
                    HMethod.hookMethod(ClassLoad.getClass(ClassNames.CHAT_MESSAGE_CELL),
                        AutomationResolver.resolve("ChatMessageCell", "measureTime", AutomationResolver.ResolverType.Method),
                        AutomationResolver.merge(AutomationResolver.resolveObject("measureTime",
                            new Class[]{ClassLoad.getClass(ClassNames.MESSAGE_OBJECT)}),
                            new AbstractMethodHook() {
                                @Override
                                protected void afterMethod(MethodHookParam param) {
                                    boolean showDeleted  = ConfigManager.showDeletedMessages != null && ConfigManager.showDeletedMessages.isEnable();
                                    boolean showMessageId = ConfigManager.showMessageId      != null && ConfigManager.showMessageId.isEnable();
                                    if (!showDeleted && !showMessageId) return;
                                    try {
                                        MessageObject messageObject = new MessageObject(param.args[0]);
                                        if (messageObject.getMessageObject() == null) return;
                                        TLRPC.Message owner = messageObject.getMessageOwner();
                                        if (owner == null) return;

                                        if (showMessageId && owner.getID() != 0) {
                                            appendTimeLabel("ID " + owner.getID(), param.thisObject, false);
                                        }

                                        int flags = owner.getFlags();
                                        if (showDeleted && (flags & ShowDeletedMessages.FLAG_DELETED) != 0) {
                                            appendTimeLabel(Translator.get(Keys.Deleted), param.thisObject, true);
                                        }
                                    } catch (Throwable t) { Logger.e(t); }
                                }
                            }));
                }
            }
        } catch (Throwable t) { Logger.e(t); }
    }

    /**
     * Prepends a label to the message timestamp.
     * FIX: if currentTimeString is null (not yet set), create a fresh one
     * instead of silently returning — this is the root cause of the invisible indicator.
     */
    private static void appendTimeLabel(String text, Object thisObject, boolean red) {
        try {
            OfficialChatMessageCell cell = new OfficialChatMessageCell(thisObject);
            CharSequence raw = cell.getCurrentTimeString();

            // ── ROOT CAUSE FIX ──
            // Before: if (time == null) return;   ← silent bail-out
            // Now:    create an empty builder so the label always shows
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
            if (paint != null) {
                int w = (int) Math.ceil(paint.measureText(label, 0, label.length()));
                cell.setTimeTextWidth(w + cell.getTimeTextWidth());
                cell.setTimeWidth(w + cell.getTimeWidth());
            }
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
