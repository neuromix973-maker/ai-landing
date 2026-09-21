package ai.neurograf.voice;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int MIC_PERMISSION_REQUEST = 101;
    private static final String PREFS = "neurograf_voice_ai";
    private static final String KEY_SERVER_URL = "server_url";

    private SharedPreferences prefs;
    private LinearLayout root;
    private WebView webView;
    private PermissionRequest pendingPermission;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(5,5,5));
        getWindow().setNavigationBarColor(Color.rgb(5,5,5));
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(5,5,5));
        setContentView(root);

        String url = prefs.getString(KEY_SERVER_URL, "");
        if (url.isEmpty()) showSetup(); else showAssistant(url);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, int sp) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16),dp(10),dp(16),dp(10));
        return t;
    }

    private Button gold(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setTextColor(Color.BLACK);
        b.setBackgroundColor(Color.rgb(212,175,55));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(54));
        lp.setMargins(dp(18),dp(8),dp(18),dp(8));
        b.setLayoutParams(lp);
        return b;
    }

    private void showSetup() {
        root.removeAllViews();
        root.setPadding(dp(12),dp(24),dp(12),dp(24));

        TextView logo = text("NG",34);
        logo.setTextColor(Color.rgb(212,175,55));
        root.addView(logo);
        root.addView(text("NEUROGRAF VOICE AI",23));

        TextView sub = text("Вставьте HTTPS-адрес вашего голосового AI-сервера.",15);
        sub.setTextColor(Color.LTGRAY);
        root.addView(sub);

        EditText input = new EditText(this);
        input.setHint("https://xxxxx.trycloudflare.com");
        input.setHintTextColor(Color.DKGRAY);
        input.setTextColor(Color.WHITE);
        input.setSingleLine();
        input.setBackgroundColor(Color.rgb(20,20,20));
        input.setPadding(dp(14),0,dp(14),0);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1,dp(56));
        ilp.setMargins(dp(18),dp(18),dp(18),dp(8));
        input.setLayoutParams(ilp);
        root.addView(input);

        Button connect = gold("ПОДКЛЮЧИТЬ");
        root.addView(connect);
        connect.setOnClickListener(v -> {
            String url = clean(input.getText().toString());
            if (!valid(url)) {
                Toast.makeText(this,"Нужен корректный HTTPS-адрес",Toast.LENGTH_LONG).show();
                return;
            }
            prefs.edit().putString(KEY_SERVER_URL,url).apply();
            showAssistant(url);
        });

        TextView note = text("Адрес сохранится на телефоне. Если Cloudflare выдаст новый адрес, замените его в настройках.",13);
        note.setTextColor(Color.GRAY);
        root.addView(note);
    }

    private String clean(String value) {
        String url = value.trim();
        while (url.endsWith("/")) url = url.substring(0,url.length()-1);
        return url;
    }

    private boolean valid(String value) {
        try {
            Uri uri = Uri.parse(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        } catch (Exception e) {
            return false;
        }
    }

    private void showAssistant(String url) {
        root.removeAllViews();
        root.setPadding(0,0,0,0);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(Color.rgb(8,8,8));
        top.setPadding(dp(14),dp(6),dp(6),dp(6));

        TextView brand = new TextView(this);
        brand.setText("NG  NeuroGraf Voice AI");
        brand.setTextColor(Color.rgb(212,175,55));
        brand.setTextSize(17);
        top.addView(brand,new LinearLayout.LayoutParams(0,dp(48),1));

        Button gear = new Button(this);
        gear.setText("⚙");
        gear.setTextSize(20);
        gear.setTextColor(Color.rgb(212,175,55));
        gear.setBackgroundColor(Color.TRANSPARENT);
        gear.setOnClickListener(v -> showSettings());
        top.addView(gear,new LinearLayout.LayoutParams(dp(58),dp(48)));
        root.addView(top);

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.setBackgroundColor(Color.rgb(5,5,5));

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri target = request.getUrl();
                Uri server = Uri.parse(url);
                if (target.getHost() != null && target.getHost().equalsIgnoreCase(server.getHost())) {
                    return false;
                }
                startActivity(new Intent(Intent.ACTION_VIEW,target));
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean audio = false;
                    for (String resource : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                            audio = true;
                            break;
                        }
                    }
                    if (!audio) {
                        request.deny();
                        return;
                    }

                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else {
                        pendingPermission = request;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},MIC_PERMISSION_REQUEST);
                    }
                });
            }
        });

        root.addView(webView,new LinearLayout.LayoutParams(-1,0,1));
        webView.loadUrl(url);
    }

    private void showSettings() {
        final String current = prefs.getString(KEY_SERVER_URL,"");
        root.removeAllViews();
        root.setPadding(dp(12),dp(24),dp(12),dp(24));
        root.addView(text("Настройки",24));

        EditText input = new EditText(this);
        input.setText(current);
        input.setTextColor(Color.WHITE);
        input.setSingleLine();
        input.setBackgroundColor(Color.rgb(20,20,20));
        input.setPadding(dp(14),0,dp(14),0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1,dp(56));
        lp.setMargins(dp(18),dp(12),dp(18),dp(8));
        input.setLayoutParams(lp);
        root.addView(input);

        Button save = gold("СОХРАНИТЬ И ПОДКЛЮЧИТЬСЯ");
        root.addView(save);
        save.setOnClickListener(v -> {
            String url = clean(input.getText().toString());
            if (!valid(url)) {
                Toast.makeText(this,"Нужен HTTPS-адрес",Toast.LENGTH_LONG).show();
                return;
            }
            prefs.edit().putString(KEY_SERVER_URL,url).apply();
            showAssistant(url);
        });

        Button back = gold("НАЗАД");
        root.addView(back);
        back.setOnClickListener(v -> {
            if (current.isEmpty()) showSetup(); else showAssistant(current);
        });

        Button permissions = gold("РАЗРЕШЕНИЯ ПРИЛОЖЕНИЯ");
        root.addView(permissions);
        permissions.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if (requestCode == MIC_PERMISSION_REQUEST && pendingPermission != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                pendingPermission.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } else {
                pendingPermission.deny();
            }
            pendingPermission = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
