package com.my.televip.features;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.my.televip.Configs.ConfigManager;
import com.my.televip.ai.LinkitClient;
import com.my.televip.logging.Logger;

/**
 * TeleVipAi — AI assistant accessible from the chat header menu.
 *
 * The floating button has been replaced with a menu item added by ChatHook.
 * This class only owns the dialog UI and the LinkitClient call.
 */
public class TeleVipAi {

    public static boolean isEnable = false;

    private static final Handler UI = new Handler(Looper.getMainLooper());

    // ── Public entry point ───────────────────────────────────────────────
    /** Called by ConfigManager when the switch is toggled on. */
    public static void init() {
        isEnable = true;
    }

    // ── Accessibility check ──────────────────────────────────────────────
    public static boolean isEnabled() {
        return ConfigManager.teleVipAi != null && ConfigManager.teleVipAi.isEnable();
    }

    // ── Prompt dialog (called from ChatHook menu click) ───────────────────
    public static void showPromptDialog(Context ctx) {
        UI.post(() -> {
            try {
                LinearLayout layout = new LinearLayout(ctx);
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.setPadding(40, 20, 40, 10);

                TextView hint = new TextView(ctx);
                hint.setText("اسأل TeleVip AI");
                hint.setTextSize(13);
                hint.setPadding(0, 0, 0, 12);
                layout.addView(hint);

                EditText input = new EditText(ctx);
                input.setHint("أدخل سؤالك أو اطلب ترجمة/تلخيص...");
                input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                input.setLines(3);
                input.setGravity(Gravity.TOP);
                layout.addView(input);

                new AlertDialog.Builder(ctx)
                        .setTitle("TeleVip AI  ✦")
                        .setView(layout)
                        .setPositiveButton("إرسال", (d, w) -> {
                            String q = input.getText().toString().trim();
                            if (q.isEmpty()) return;
                            sendToAi(ctx, q);
                        })
                        .setNegativeButton("إلغاء", null)
                        .show();
            } catch (Throwable t) { Logger.e(t); }
        });
    }

    // ── AI call + result dialog ───────────────────────────────────────────
    private static void sendToAi(Context ctx, String question) {
        AlertDialog[] loadingHolder = new AlertDialog[1];
        UI.post(() -> {
            try {
                loadingHolder[0] = new AlertDialog.Builder(ctx)
                        .setTitle("TeleVip AI  ✦")
                        .setMessage("⏳ جاري المعالجة…")
                        .setCancelable(false)
                        .show();
            } catch (Throwable t) { Logger.e(t); }
        });

        String systemPrompt = "أنت مساعد ذكاء اصطناعي مدمج في تطبيق تيليغرام. "
                + "أجب باللغة التي يكتب بها المستخدم. "
                + "استخدم نص بسيط أو Markdown المدعوم في تيليغرام فقط.";

        LinkitClient.prompt(systemPrompt, question, new LinkitClient.Callback() {
            @Override
            public void onSuccess(String reply) {
                UI.post(() -> {
                    if (loadingHolder[0] != null) loadingHolder[0].dismiss();
                    showResult(ctx, reply);
                });
            }
            @Override
            public void onError(String error) {
                UI.post(() -> {
                    if (loadingHolder[0] != null) loadingHolder[0].dismiss();
                    showResult(ctx, "❌ خطأ: " + error);
                });
            }
        });
    }

    private static void showResult(Context ctx, String text) {
        try {
            ScrollView sv = new ScrollView(ctx);
            TextView tv = new TextView(ctx);
            tv.setText(text);
            tv.setTextSize(14);
            tv.setPadding(40, 20, 40, 20);
            tv.setTextIsSelectable(true);
            sv.addView(tv);

            new AlertDialog.Builder(ctx)
                    .setTitle("TeleVip AI  ✦")
                    .setView(sv)
                    .setPositiveButton("حسناً", null)
                    .setNeutralButton("نسخ", (d, w) -> {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("AI", text));
                    })
                    .show();
        } catch (Throwable t) { Logger.e(t); }
    }
}
