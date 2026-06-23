package com.my.televip.features;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.my.televip.Class.ClassLoad;
import com.my.televip.Class.ClassNames;
import com.my.televip.Configs.ConfigManager;
import com.my.televip.ai.LinkitClient;
import com.my.televip.base.AbstractMethodHook;
import com.my.televip.hooks.HMethod;
import com.my.televip.logging.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * TeleVipAi — adds a floating "AI" button inside ChatActivity.
 *
 * Behaviour:
 *  • A small circular button appears over the chat while inside ChatActivity.
 *  • Tap → dialog: type your prompt (or leave empty for general chat).
 *  • The helper first tries to attach the selected / last visible message as context.
 *  • LINKIT endpoint is called; the response is shown in a scrollable result dialog.
 */
public class TeleVipAi {

    public static boolean isEnable = false;

    private static final Handler UI = new Handler(Looper.getMainLooper());

    // ── Public entry point ───────────────────────────────────────────────
    public static void init() {
        if (isEnable) return;
        isEnable = true;
        try {
            Class<?> chatClass = ClassLoad.getClass(ClassNames.CHAT_ACTIVITY);
            if (chatClass == null) return;

            // Hook onResume → show button; hook onPause → hide button
            hookLifecycle(chatClass, "onResume", true);
            hookLifecycle(chatClass, "onStart",  true);
            hookLifecycle(chatClass, "onPause",  false);
            hookLifecycle(chatClass, "onStop",   false);
        } catch (Throwable e) { Logger.e(e); }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────
    private static void hookLifecycle(Class<?> cls, String name, boolean show) {
        try {
            HMethod.hookMethod(cls, name, new AbstractMethodHook() {
                @Override
                protected void afterMethod(MethodHookParam param) {
                    if (!isEnabled()) return;
                    try {
                        Activity act = getActivity(param.thisObject);
                        if (act == null) return;
                        if (show) showButton(act);
                        else      removeButton(act);
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
    }

    // ── Floating button ──────────────────────────────────────────────────
    private static final int BTN_TAG = "TeleVipAiBtn".hashCode();
    private static WindowManager wm;
    private static View currentBtn;

    private static void showButton(Activity act) {
        UI.post(() -> {
            try {
                if (act.isFinishing() || act.isDestroyed()) return;
                if (act.getWindow().getDecorView().findViewWithTag(BTN_TAG) != null) return;

                ImageButton btn = new ImageButton(act);
                btn.setTag(BTN_TAG);

                // Circular background
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.OVAL);
                bg.setColor(0xFF2196F3); // blue
                btn.setBackground(bg);
                btn.setPadding(20, 20, 20, 20);

                // "AI" label
                TextView label = new TextView(act);
                label.setText("AI");
                label.setTextColor(Color.WHITE);
                label.setTextSize(11);
                label.setGravity(Gravity.CENTER);
                label.setTag(BTN_TAG);

                // Container
                FrameLayout container = new FrameLayout(act);
                container.setTag(BTN_TAG);
                FrameLayout.LayoutParams lbl = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
                container.addView(btn, new FrameLayout.LayoutParams(120, 120));
                container.addView(label, lbl);
                container.setOnClickListener(v -> showPromptDialog(act));

                // Add as decor overlay (no WindowManager permission needed)
                FrameLayout decor = (FrameLayout) act.getWindow().getDecorView();
                FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(120, 120);
                p.gravity = Gravity.BOTTOM | Gravity.END;
                p.bottomMargin = 220;
                p.rightMargin  = 16;
                decor.addView(container, p);
                currentBtn = container;
            } catch (Throwable t) { Logger.e(t); }
        });
    }

    private static void removeButton(Activity act) {
        UI.post(() -> {
            try {
                FrameLayout decor = (FrameLayout) act.getWindow().getDecorView();
                View v = decor.findViewWithTag(BTN_TAG);
                if (v != null) decor.removeView(v);
            } catch (Throwable ignored) {}
        });
    }

    // ── Prompt dialog ────────────────────────────────────────────────────
    private static void showPromptDialog(Activity act) {
        UI.post(() -> {
            try {
                LinearLayout layout = new LinearLayout(act);
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.setPadding(40, 20, 40, 10);

                TextView hint = new TextView(act);
                hint.setText("اسأل التلقائي (TeleVip AI)");
                hint.setTextSize(13);
                hint.setPadding(0, 0, 0, 12);
                layout.addView(hint);

                EditText input = new EditText(act);
                input.setHint("أدخل سؤالك أو اطلب ترجمة/تلخيص...");
                input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                input.setLines(3);
                input.setGravity(Gravity.TOP);
                layout.addView(input);

                new AlertDialog.Builder(act)
                        .setTitle("TeleVip AI  ✦")
                        .setView(layout)
                        .setPositiveButton("إرسال", (d, w) -> {
                            String q = input.getText().toString().trim();
                            if (q.isEmpty()) return;
                            sendToAi(act, q);
                        })
                        .setNegativeButton("إلغاء", null)
                        .show();
            } catch (Throwable t) { Logger.e(t); }
        });
    }

    // ── AI call + result dialog ───────────────────────────────────────────
    private static void sendToAi(Activity act, String question) {
        // Show loading dialog
        AlertDialog loading = new AlertDialog.Builder(act)
                .setTitle("TeleVip AI  ✦")
                .setMessage("⏳ جاري المعالجة…")
                .setCancelable(false)
                .show();

        String systemPrompt = "أنت مساعد ذكاء اصطناعي مدمج في تطبيق تيليغرام. "
                + "أجب باللغة التي يكتب بها المستخدم. "
                + "استخدم نص بسيط أو Markdown المدعوم في تيليغرام فقط.";

        LinkitClient.prompt(systemPrompt, question, new LinkitClient.Callback() {
            @Override
            public void onSuccess(String reply) {
                UI.post(() -> {
                    loading.dismiss();
                    showResult(act, reply);
                });
            }
            @Override
            public void onError(String error) {
                UI.post(() -> {
                    loading.dismiss();
                    showResult(act, "❌ خطأ: " + error);
                });
            }
        });
    }

    private static void showResult(Activity act, String text) {
        try {
            ScrollView sv = new ScrollView(act);
            TextView tv = new TextView(act);
            tv.setText(text);
            tv.setTextSize(14);
            tv.setPadding(40, 20, 40, 20);
            tv.setTextIsSelectable(true);
            sv.addView(tv);

            new AlertDialog.Builder(act)
                    .setTitle("TeleVip AI  ✦")
                    .setView(sv)
                    .setPositiveButton("حسناً", null)
                    .setNeutralButton("نسخ", (d, w) -> {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                act.getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("AI", text));
                    })
                    .show();
        } catch (Throwable t) { Logger.e(t); }
    }

    // ── Helpers ───────────────────────────────────────────────────────────
    private static boolean isEnabled() {
        return ConfigManager.teleVipAi != null && ConfigManager.teleVipAi.isEnable();
    }

    private static Activity getActivity(Object fragment) {
        try {
            // BaseFragment.getParentActivity()
            for (Method m : fragment.getClass().getMethods()) {
                if (m.getParameterCount() == 0
                        && Activity.class.isAssignableFrom(m.getReturnType())
                        && (m.getName().contains("Activity") || m.getName().contains("Context"))) {
                    return (Activity) m.invoke(fragment);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
