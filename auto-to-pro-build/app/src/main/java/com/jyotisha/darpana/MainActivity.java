package com.jyotisha.darpana;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int PHOTO_REQUEST = 7124;
    private WebView webView;
    private AutoToBridge autoToBridge;
    private boolean photoPickerOpen = false;

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

        autoToBridge = new AutoToBridge(this, webView);
        webView.addJavascriptInterface(autoToBridge, "AutoTOAndroid");

        webView.setBackgroundColor(0xFF020304);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                WebResourceResponse local = autoToBridge.interceptVehiclePhotoRequest(request.getUrl());
                return local != null ? local : super.shouldInterceptRequest(view, request);
            }

            @Override
            @SuppressWarnings("deprecation")
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                WebResourceResponse local = autoToBridge.interceptVehiclePhotoRequest(Uri.parse(url));
                return local != null ? local : super.shouldInterceptRequest(view, url);
            }
        });

        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    public void launchVehiclePhotoPicker() {
        runOnUiThread(() -> {
            if (photoPickerOpen) return;
            photoPickerOpen = true;

            Intent primary;
            if (Build.VERSION.SDK_INT >= 33) {
                primary = new Intent(MediaStore.ACTION_PICK_IMAGES);
                primary.setType("image/*");
            } else {
                primary = openDocumentIntent();
            }

            try {
                startActivityForResult(primary, PHOTO_REQUEST);
            } catch (ActivityNotFoundException | SecurityException firstError) {
                try {
                    startActivityForResult(openDocumentIntent(), PHOTO_REQUEST);
                } catch (Throwable secondError) {
                    photoPickerOpen = false;
                    if (autoToBridge != null) autoToBridge.onVehiclePhotoPicked(null);
                    Toast.makeText(this, "Не удалось открыть галерею", Toast.LENGTH_LONG).show();
                }
            } catch (Throwable unexpected) {
                photoPickerOpen = false;
                if (autoToBridge != null) autoToBridge.onVehiclePhotoPicked(null);
                Toast.makeText(this, "Галерея временно недоступна", Toast.LENGTH_LONG).show();
            }
        });
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
            photoPickerOpen = false;
            Uri uri = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                uri = data.getData();
                try {
                    final int flags = data.getFlags() &
                            (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    if ((data.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
                        getContentResolver().takePersistableUriPermission(
                                uri,
                                flags & Intent.FLAG_GRANT_READ_URI_PERMISSION
                        );
                    }
                } catch (Exception ignored) { }
            }
            if (autoToBridge != null) autoToBridge.onVehiclePhotoPicked(uri);
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (autoToBridge != null) {
            boolean granted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                    break;
                }
            }
            autoToBridge.onPermissionResult(requestCode, granted);
        }
    }

    @Override
    protected void onDestroy() {
        if (autoToBridge != null) autoToBridge.destroy();
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
