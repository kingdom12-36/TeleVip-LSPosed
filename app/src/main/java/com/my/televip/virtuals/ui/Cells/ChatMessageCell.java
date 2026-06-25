package com.my.televip.virtuals.ui.Cells;

import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;

import com.my.televip.Class.ClassLoad;
import com.my.televip.Class.ClassNames;
import com.my.televip.Configs.ConfigManager;
import com.my.televip.base.AbstractMethodHook;
import com.my.televip.hooks.HMethod;
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
            }
        } catch (Throwable t) { Logger.e(t); }
    }

    /**
     * Applies the time-area label — called from measureTime hook.
     */
    private static void applyTimeLabel(Object msgObjRaw, Object thisObject) {
        boolean showMessageId = ConfigManager.showMessageId != null && ConfigManager.showMessageId.isEnable();
        if (!showMessageId) return;
        try {
            MessageObject messageObject = new MessageObject(msgObjRaw);
            if (messageObject.getMessageObject() == null) return;
            TLRPC.Message owner = messageObject.getMessageOwner();
            if (owner == null) return;

            if (owner.getID() != 0)
                appendTextTimeLabel("ID " + owner.getID(), thisObject, false);

        } catch (Throwable t) { Logger.e(t); }
    }

    /**
     * Appends a plain text label in the timestamp corner (used for message ID display).
     */
    private static void appendTextTimeLabel(String text, Object thisObject, boolean red) {
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

    private static int dp(float val) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, val,
            android.content.res.Resources.getSystem().getDisplayMetrics());
    }

    public static SpannableStringBuilder convertToStringBuilder(CharSequence seq) {
        if (seq == null) return null;
        return seq instanceof SpannableStringBuilder ? (SpannableStringBuilder) seq : new SpannableStringBuilder(seq);
    }

    // ── Instance wrapper ──────────────────────────────────────────────────────

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
