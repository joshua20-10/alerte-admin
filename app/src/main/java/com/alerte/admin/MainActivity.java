package com.alerte.admin;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    static volatile boolean enAvant = false;
    private WebView web;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (u.startsWith("file:")) return false;
                String low = u.toLowerCase();
                if (low.contains("google.com/maps") || low.contains("maps.google")
                        || low.contains("goo.gl/maps") || low.contains("maps.app.goo.gl")) {
                    return ouvrirMaps(u);
                }
                try { startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl())); } catch (Exception e) { }
                return true;
            }
        });
        web.loadUrl("file:///android_asset/index.html");

        startForegroundService(new Intent(this, AlertService.class));

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        } else {
            demanderBatterie();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        demanderBatterie();
    }

    // Ouvre Google Maps directement sur les coordonnées (ou le lien), sans passer par le Play Store
    private boolean ouvrirMaps(String url) {
        try {
            String decode = Uri.decode(url);
            Matcher m = Pattern.compile("(-?\\d{1,3}\\.\\d+)\\s*,\\s*(-?\\d{1,3}\\.\\d+)").matcher(decode);
            Intent i;
            if (m.find()) {
                String c = m.group(1) + "," + m.group(2);
                i = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + c + "?q=" + c + "(Alerte)"));
            } else {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            }
            i.setPackage("com.google.android.apps.maps");
            startActivity(i);
            return true;
        } catch (Exception e) { }
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { }
        return true;
    }

    private void demanderBatterie() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        } catch (Exception e) { }
    }

    @Override protected void onResume() { super.onResume(); enAvant = true; }
    @Override protected void onPause() { super.onPause(); enAvant = false; }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else moveTaskToBack(true);
    }
}
