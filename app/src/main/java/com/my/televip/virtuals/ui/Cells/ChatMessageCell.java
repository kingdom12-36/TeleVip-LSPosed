package com.my.televip.virtuals.ui.Cells;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;

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
import com.my.televip.utils.Utils;
import com.my.televip.virtuals.OfficialChatMessageCell;
import com.my.televip.virtuals.Theme;
import com.my.televip.virtuals.messenger.MessageObject;
import com.my.televip.virtuals.tgnet.TLRPC;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class ChatMessageCell {

    public static boolean isEnable = false;

    private static SpannableStringBuilder sDeletedIconSpan = null;
    private static final String COLORED_IMAGE_SPAN = "org.telegram.ui.Components.ColoredImageSpan";

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

                // ── Hook 2: setMessageObject ──────────────────────────────────────
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
     * Applies the time-area icon label — called from measureTime hook.
     * Uses a drawn X icon span (matching NagramX's ColoredImageSpan approach)
     * with a text fallback if the drawable approach is unavailable.
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
                appendTextTimeLabel("ID " + owner.getID(), thisObject, false);

            if (showDeleted && isDeleted(owner))
                appendDeletedIconLabel(thisObject);

        } catch (Throwable t) { Logger.e(t); }
    }

    /**
     * Injects an X icon span at the start of the message text bubble.
     * Uses the same ZERO_WIDTH_SPACE + ImageSpan trick as NagramX's deletedSpan.
     * Idempotent — skipped if the message already starts with the marker character.
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
                if (!txt.startsWith("\u274C") && !txt.startsWith("\u2716")
                        && !txt.startsWith("\uD83D\uDDD1") && !txt.startsWith("\u200B")) {
                    XposedHelpers.setObjectField(owner.message, "message", "\u2716 " + txt);
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
     * Appends the X icon span in the message timestamp corner.
     * Strategy A: ColoredImageSpan (Telegram's own tint-aware span — matches NagramX approach).
     * Strategy B: plain ImageSpan with a BitmapDrawable.
     * Strategy C: text fallback "\u2716 Deleted".
     */
    private static void appendDeletedIconLabel(Object thisObject) {
        try {
            OfficialChatMessageCell cell = new OfficialChatMessageCell(thisObject);
            CharSequence raw = cell.getCurrentTimeString();
            SpannableStringBuilder time = (raw != null)
                ? (raw instanceof SpannableStringBuilder
                    ? (SpannableStringBuilder) raw
                    : new SpannableStringBuilder(raw))
                : new SpannableStringBuilder();

            SpannableStringBuilder iconSpan = buildDeletedIconSpan();
            if (iconSpan == null) {
                // Strategy C text fallback
                String word = Translator.get(Keys.Deleted);
                String label = (word != null && !word.isEmpty() && !word.equals(Keys.Deleted))
                    ? "\u2716 " + word
                    : "\u2716 Deleted";
                iconSpan = new SpannableStringBuilder(label);
                iconSpan.setSpan(new ForegroundColorSpan(Color.rgb(255, 69, 69)),
                    0, iconSpan.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            iconSpan.append("  ");
            time.insert(0, iconSpan);
            cell.setCurrentTimeString(time);

            TextPaint paint = Theme.getTextPaint();
            if (paint == null) {
                paint = new TextPaint();
                DisplayMetrics dm = android.content.res.Resources.getSystem().getDisplayMetrics();
                paint.setTextSize(12f * dm.scaledDensity);
            }
            int w = (int) Math.ceil(paint.measureText(iconSpan, 0, iconSpan.length()));
            int extra = dp(14);
            cell.setTimeTextWidth(extra + cell.getTimeTextWidth());
            cell.setTimeWidth(extra + cell.getTimeWidth());
        } catch (Throwable t) { Logger.e(t); }
    }

    /**
     * Builds the X icon SpannableStringBuilder (cached after first call).
     * Mirrors NagramX's createSpan() — zero-width space + ColoredImageSpan/ImageSpan.
     * Returns null if the drawable cannot be created, triggering the text fallback.
     */
    private static SpannableStringBuilder buildDeletedIconSpan() {
        if (sDeletedIconSpan != null) return new SpannableStringBuilder(sDeletedIconSpan);
        try {
            Drawable icon = createXDrawable();
            if (icon == null) return null;

            SpannableStringBuilder sb = new SpannableStringBuilder("\u200B");

            // Strategy A: use Telegram's ColoredImageSpan (auto-tinted to match time text)
            boolean usedColoredSpan = false;
            try {
                Class<?> cls = XposedHelpers.findClassIfExists(COLORED_IMAGE_SPAN, Utils.classLoader);
                if (cls != null) {
                    for (Constructor<?> ctor : cls.getConstructors()) {
                        Class<?>[] types = ctor.getParameterTypes();
                        if (types.length == 2 && Drawable.class.isAssignableFrom(types[0])
                                && types[1] == boolean.class) {
                            Object span = ctor.newInstance(icon, true);
                            sb.setSpan(span, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                            usedColoredSpan = true;
                            break;
                        }
                        if (types.length == 1 && Drawable.class.isAssignableFrom(types[0])) {
                            Object span = ctor.newInstance(icon);
                            sb.setSpan(span, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                            usedColoredSpan = true;
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {}

            // Strategy B: plain ImageSpan
            if (!usedColoredSpan) {
                sb.setSpan(new ImageSpan(icon, ImageSpan.ALIGN_BASELINE),
                    0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            sDeletedIconSpan = sb;
            return new SpannableStringBuilder(sb);
        } catch (Throwable t) {
            Logger.e(t);
            return null;
        }
    }

    /**
     * Creates a small BitmapDrawable with two crossing lines forming an X.
     * Size is 12dp × 12dp to match Telegram's status icon size.
     * Color is semi-transparent red (#CCFF4444) to signal deletion clearly.
     */
    private static Drawable createXDrawable() {
        try {
            int size = dp(12);
            if (size <= 0) size = 36;
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);

            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.argb(204, 255, 68, 68));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2f, size * 0.16f));
            p.setStrokeCap(Paint.Cap.ROUND);

            float pad = size * 0.18f;
            c.drawLine(pad, pad, size - pad, size - pad, p);
            c.drawLine(size - pad, pad, pad, size - pad, p);

            BitmapDrawable drawable = new BitmapDrawable(
                android.app.ActivityThread.currentApplication().getResources(), bmp);
            drawable.setBounds(0, 0, size, size);
            return drawable;
        } catch (Throwable t) {
            Logger.e(t);
            return null;
        }
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
