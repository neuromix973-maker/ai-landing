package com.jyotisha.darpana;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int PHOTO_REQUEST = 7124;
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setTextZoom(100);

        webView.setBackgroundColor(0xFF020304);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView view,
                    ValueCallback<Uri[]> callback,
                    FileChooserParams params) {
                cancelPendingChooser();
                fileCallback = callback;
                return launchImagePickerSafely();
            }
        });

        // Minimal native bridge: maintenance reminders only.
        // Existing JS data model remains in localStorage.
        webView.addJavascriptInterface(new AutoToBridge(this), "AutoTOAndroid");

        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    private boolean launchImagePickerSafely() {
        Intent primary;
        if (Build.VERSION.SDK_INT >= 33) {
            primary = new Intent(MediaStore.ACTION_PICK_IMAGES);
            primary.setType("image/*");
        } else {
            primary = openDocumentIntent();
        }

        try {
            startActivityForResult(primary, PHOTO_REQUEST);
            return true;
        } catch (ActivityNotFoundException | SecurityException firstError) {
            try {
                Intent fallback = openDocumentIntent();
                startActivityForResult(fallback, PHOTO_REQUEST);
                return true;
            } catch (Exception secondError) {
                cancelPendingChooser();
                Toast.makeText(this, "Не удалось открыть галерею", Toast.LENGTH_LONG).show();
                return false;
            }
        } catch (Throwable unexpected) {
            cancelPendingChooser();
            Toast.makeText(this, "Галерея временно недоступна", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private Intent openDocumentIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        return intent;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PHOTO_REQUEST) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                result = new Uri[] { uri };
                try {
                    final int flags = data.getFlags() &
                            (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    if ((data.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
                        getContentResolver().takePersistableUriPermission(
                                uri, flags & Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    }
                } catch (Exception ignored) {
                    // Temporary access is enough for the current WebView file selection.
                }
            }
            ValueCallback<Uri[]> callback = fileCallback;
            fileCallback = null;
            if (callback != null) callback.onReceiveValue(result);
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void cancelPendingChooser() {
        if (fileCallback != null) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
        }
    }

    @Override
    protected void onDestroy() {
        cancelPendingChooser();
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
