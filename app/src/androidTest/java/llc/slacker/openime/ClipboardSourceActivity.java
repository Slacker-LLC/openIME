package llc.slacker.openime;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.InputType;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

/** Separate UID fixture: only platform classes, since the test APK excludes Kotlin runtime. */
public final class ClipboardSourceActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private EditText editor;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        editor = new EditText(this);
        editor.setId(android.R.id.edit);
        editor.setHint("外部 App 输入框");
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(32, 80, 32, 32);
        body.addView(editor);
        addButton(body, "复制跨应用测试内容", () -> copy("外部App复制 · 第一条 😀"));
        addButton(body, "复制跨应用长文本", () -> copy(longText()));
        addButton(body, "收起键盘后复制", () -> {
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(editor.getWindowToken(), 0);
            handler.postDelayed(() -> copy("外部App复制 · 键盘收起时"), 400);
        });
        addButton(body, "返回原输入框", this::finish);
        setContentView(body);
        editor.requestFocus();
        handler.postDelayed(this::showKeyboard, 300);
        handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent.getBooleanExtra("finish_fixture", false)) { finish(); return; }
        if (intent.getBooleanExtra("request_keyboard", false)) handler.post(this::showKeyboard);
        if (intent.getBooleanExtra("copy_short", false)) {
            handler.postDelayed(() -> copy("外部App复制 · 第一条 😀"), 600);
        } else if (intent.getBooleanExtra("copy_long", false)) {
            handler.postDelayed(() -> copy(longText()), 600);
        }
    }

    private void showKeyboard() {
        editor.requestFocus();
        getSystemService(InputMethodManager.class).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
    }

    private void copy(String text) {
        getSystemService(ClipboardManager.class).setPrimaryClip(
            ClipData.newPlainText("fixture-uid:" + Process.myUid(), text));
    }

    private String longText() {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < 80; i++) value.append("跨应用长内容 😀。\n");
        return value.toString();
    }

    private void addButton(LinearLayout body, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> action.run());
        body.addView(button);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
