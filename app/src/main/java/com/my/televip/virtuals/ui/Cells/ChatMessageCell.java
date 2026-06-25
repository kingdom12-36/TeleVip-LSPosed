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
import android.util.TypedValue;

import com.my.televip.Class.ClassLoad;
import com.my.televip.Class.ClassNames;
import com.my.televip.Configs.ConfigManager;
import com.my.televip.base.AbstractMethodHook;
import com.my.televip.features.ShowDeletedMessages;
import com.my.televip.hooks.HMethod;
import com.my.televip.logging.Logger;
import com.my.televip.obfuscate.AutomationResolver;
import com.my.televip.virtuals.OfficialChatMessageCell;
import com.my.televip.virtuals.Theme;
import com.my.televip.virtuals.messenger.MessageObject;
import com.my.televip.virtuals.tgnet.TLRPC;

import de.robv.android.xposed.XposedHelpers;

/**
 * ChatMessageCell hooks — unchanged stable structure from mustafa1dev, with one
 * upgrade: the red "Deleted" text in the timestamp area is replaced by a proper
 * X icon drawn on a Bitmap so it looks like a visual symbol rather than a word.
 *
 * Only the measureTime hook is used.  FLAG_DELETED is the source of truth (set by
 * ShowDeletedMessages.processUpdateArray when a server-side deletion is intercepted).
 * No setMessageObject hook, no deletedIds list.
 */
public class ChatMessageCell {

    public static boolean isEnable = false;

    /** Cached X-icon span — built once per process, reused for every cell. */
    private static SpannableStringBuilder sCachedXSpan = null;

    public static void init() {
        try {
            if (!isEnable) {
                isEnable = true;
                if (ClassLoad.getClass(ClassNames.CHAT_MESSAGE_CELL) != null) {
                    HMethod.hookMethod(
                        ClassLoad.getClass(ClassNames.CHAT_MESSAGE_CELL),
                        AutomationResolver.resolve("ChatMessageCell", "measureTime", AutomationResolver.ResolverType.Method),
                        AutomationResolver.merge(
                            AutomationResolver.resolveObject("measureTime",
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

                                        if (showMessageId && owner.getID() != 0) {
                                            appendTextLabel("ID " + owner.getID(), param.thisObject, false);
                                        }

                                        if (showDeleted && (owner.getFlags() & ShowDeletedMessages.FLAG_DELETED) != 0) {
                                            appendXIcon(param.thisObject);
                                        }
                                    } catch (Throwable t) {
                                        Logger.e(t);
                                    }
                                }
                            }
                        )
                    );
                }
            }
        } catch (Throwable t) {
            Logger.e(t);
        }
    }

    // ── X icon ────────────────────────────────────────────────────────────────

    /**
     * Prepends a red X icon into the timestamp area of the message bubble.
     *
     * Priority:
     *   A. ImageSpan wrapping a Bitmap X drawn on a Canvas (crisp, fits naturally)
     *   B. Text fallback "✕" in red (if Bitmap creation fails on some devices)
     */
    private static void appendXIcon(Object thisObject) {
        try {
            OfficialChatMessageCell cell = new OfficialChatMessageCell(thisObject);
            SpannableStringBuilder time = convertToStringBuilder(cell.getCurrentTimeString());
            if (time == null) return;

            SpannableStringBuilder icon = getOrBuildXSpan();

            // Copy so each cell gets its own instance (avoids span-reuse issues)
            SpannableStringBuilder label = new SpannableStringBuilder(icon);
            label.append(" ");
            time.insert(0, label);
            cell.setCurrentTimeString(time);

            TextPaint paint = Theme.getTextPaint();
            if (paint != null) {
                int w = (int) Math.ceil(paint.measureText(label, 0, label.length()));
                cell.setTimeTextWidth(w + cell.getTimeTextWidth());
                cell.setTimeWidth(w + cell.getTimeWidth());
            }
        } catch (Throwable t) {
            Logger.e(t);
        }
    }

    private static SpannableStringBuilder getOrBuildXSpan() {
        if (sCachedXSpan != null) return sCachedXSpan;

        // Strategy A: bitmap X via ImageSpan
        try {
            Drawable d = buildXDrawable();
            if (d != null) {
                SpannableStringBuilder sb = new SpannableStringBuilder("\u200B"); // zero-width space as anchor
                sb.setSpan(new ImageSpan(d, ImageSpan.ALIGN_BASELINE), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sCachedXSpan = sb;
                return sCachedXSpan;
            }
        } catch (Throwable ignored) {}

        // Strategy B: text fallback
        SpannableStringBuilder sb = new SpannableStringBuilder("\u2715"); // ✕
        sb.setSpan(new ForegroundColorSpan(Color.rgb(220, 53, 69)), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sCachedXSpan = sb;
        return sCachedXSpan;
    }

    /**
     * Draws two crossing lines forming an X on a Bitmap sized to match Telegram's
     * status icon area (12 dp × 12 dp).  Returns null on failure so the caller
     * falls back to the text strategy.
     */
    private static Drawable buildXDrawable() {
        try {
            int size = dp(12);
            if (size <= 0) size = 36;

            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);

            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.argb(230, 220, 53, 69));   // red, slight transparency
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, size * 0.17f));
            paint.setStrokeCap(Paint.Cap.ROUND);

            float pad = size * 0.18f;
            canvas.drawLine(pad, pad, size - pad, size - pad, paint);   // \
            canvas.drawLine(size - pad, pad, pad, size - pad, paint);   // /

            BitmapDrawable drawable = new BitmapDrawable(
                android.content.res.Resources.getSystem(), bmp);
            drawable.setBounds(0, 0, size, size);
            return drawable;
        } catch (Throwable t) {
            Logger.e(t);
            return null;
        }
    }

    // ── Text label (for message-ID display) ──────────────────────────────────

    private static void appendTextLabel(String text, Object thisObject, boolean red) {
        try {
            OfficialChatMessageCell cell = new OfficialChatMessageCell(thisObject);
            SpannableStringBuilder time = convertToStringBuilder(cell.getCurrentTimeString());
            if (time == null) return;

            SpannableStringBuilder label = new SpannableStringBuilder(text);
            if (red) label.setSpan(new ForegroundColorSpan(Color.rgb(220, 53, 69)),
                    0, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            label.append(" ");
            time.insert(0, label);
            cell.setCurrentTimeString(time);

            TextPaint paint = Theme.getTextPaint();
            if (paint != null) {
                int w = (int) Math.ceil(paint.measureText(label, 0, label.length()));
                cell.setTimeTextWidth(w + cell.getTimeTextWidth());
                cell.setTimeWidth(w + cell.getTimeWidth());
            }
        } catch (Throwable t) {
            Logger.e(t);
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static int dp(float val) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, val,
            android.content.res.Resources.getSystem().getDisplayMetrics());
    }

    public static SpannableStringBuilder convertToStringBuilder(CharSequence charSequence) {
        if (charSequence != null)
            return charSequence instanceof SpannableStringBuilder
                ? (SpannableStringBuilder) charSequence
                : new SpannableStringBuilder(charSequence);
        return null;
    }

    // ── Instance wrapper ──────────────────────────────────────────────────────

    Object chatMessageCell;

    public ChatMessageCell(Object cell) { chatMessageCell = cell; }

    public MessageObject getMessageObject() {
        return new MessageObject(XposedHelpers.callMethod(chatMessageCell,
            AutomationResolver.resolve("ChatMessageCell", "getMessageObject",
                AutomationResolver.ResolverType.Method)));
    }

    public Object getChatMessageCell() { return chatMessageCell; }
}