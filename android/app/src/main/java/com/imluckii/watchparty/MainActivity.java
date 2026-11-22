package com.imluckii.watchparty;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Watchparty shell.
 *
 * The whole UI is the upstream stremio-web bundle, built at compile time and shipped
 * inside the APK. {@link LocalServer} serves it from http://127.0.0.1:11471 and proxies
 * every other path to the Stremio engine on 127.0.0.1:11470, so the page and the engine
 * share one origin. That matters: the engine answers cross-origin requests only from
 * strem.io hosts, so a public appassets.androidplatform.net origin cannot talk to it.
 */
public class MainActivity extends Activity {

    private static final String TAG = "Watchparty";
    private static final String APP_URL = LocalServer.APP_URL;
    private static final String ENGINE_HOST = "127.0.0.1";
    private static final int ENGINE_PORT = 11470;

    private static final int SPLASH_MIN_MS = 120;

    private FrameLayout root;
    private WebView webView;
    private LocalServer server;

    private View splashView;
    private View setupView;
    private TextView setupStatus;
    private View setupPrimary;
    private View setupSecondary;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private boolean debugBuild = false;
    private boolean setupDismissed = false;
    private boolean checkInFlight = false;
    private long splashShownAt = 0L;

    private View fullscreenView = null;
    private WebChromeClient.CustomViewCallback fullscreenCallback = null;

    private final Runnable splashWatchdog = () -> hideSplash();

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.rgb(0x0C, 0x0B, 0x14));
        getWindow().setNavigationBarColor(Color.rgb(0x0C, 0x0B, 0x14));

        setContentView(R.layout.activity_main);

        root = findViewById(R.id.root);
        splashView = findViewById(R.id.splash);
        setupView = findViewById(R.id.setup);
        setupStatus = findViewById(R.id.setup_status);
        setupPrimary = findViewById(R.id.setup_primary);
        setupSecondary = findViewById(R.id.setup_secondary);

        debugBuild = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        splashShownAt = System.currentTimeMillis();

        try {
            server = new LocalServer(getAssets());
        } catch (IOException e) {
            Log.e(TAG, "could not start local server: " + e);
        }
        mainHandler.postDelayed(splashWatchdog, 7000L);

        webView = createWebView();
        root.addView(webView, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setupPrimary.setOnClickListener(v -> openStremio());
        setupSecondary.setOnClickListener(v -> probeEngine(true));
        findViewById(R.id.setup_skip).setOnClickListener(v -> {
            setupDismissed = true;
            hideSetup();
        });

        probeEngine(false);
        webView.loadUrl(APP_URL);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
        // Coming back from Stremio is the common path to a now-working engine.
        if (setupView.getVisibility() == View.VISIBLE && !checkInFlight) {
            probeEngine(false);
        }
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        io.shutdownNow();
        if (server != null) {
            server.close();
            server = null;
        }
        if (webView != null) {
            root.removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (fullscreenView != null) {
            hideFullscreen();
            return;
        }
        if (setupView.getVisibility() == View.VISIBLE) {
            setupDismissed = true;
            hideSetup();
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    // ---------------------------------------------------------------- webview

    private WebView createWebView() {
        WebView view = new WebView(this);
        view.setBackgroundColor(Color.rgb(0x0C, 0x0B, 0x14));

        ApplicationInfo info = getApplicationInfo();
        WebView.setWebContentsDebuggingEnabled((info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);

        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setTextZoom(100);
        settings.setOffscreenPreRaster(true);
        settings.setLoadsImagesAutomatically(true);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true);

        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                return handleNavigation(request.getUrl());
            }

            @Override
            public void onPageCommitVisible(WebView v, String url) {
                Log.i(TAG, "page commit: " + url);
                hideSplash();
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                Log.i(TAG, "page finished: " + url);
                // Fallback for pages that commit without firing onPageCommitVisible.
                mainHandler.postDelayed(MainActivity.this::hideSplash, 400L);
                if (debugBuild) {
                    v.evaluateJavascript("(function(){try{var a=document.getElementById('app');"
                        + "return JSON.stringify({body:document.body.innerHTML.length,"
                        + "app:a?a.innerHTML.length:-1,"
                        + "bg:getComputedStyle(document.body).backgroundColor,"
                        + "w:innerWidth,h:innerHeight,dpr:devicePixelRatio,"
                        + "scripts:Array.prototype.map.call(document.scripts,function(s){return s.src}).join('|')});"
                            + "}catch(e){return 'ERR '+e.message}})()", value -> Log.i(TAG, "state: " + value));
                }
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest request, WebResourceError error) {
                Log.e(TAG, "load error " + error.getErrorCode() + ": "
                        + error.getDescription() + " -> " + request.getUrl());
            }

            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                Log.w(TAG, "render process gone, crashed=" + detail.didCrash());
                rebuildWebView();
                return true;
            }
        });

        view.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View customView, CustomViewCallback callback) {
                if (fullscreenView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                fullscreenView = customView;
                fullscreenCallback = callback;
                root.addView(customView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                if (webView != null) {
                    webView.setVisibility(View.INVISIBLE);
                }
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }

            @Override
            public void onHideCustomView() {
                hideFullscreen();
            }

            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage message) {
                if (debugBuild) {
                    Log.i(TAG, "console " + message.messageLevel() + " " + message.message()
                            + " @" + message.sourceId() + ":" + message.lineNumber());
                }
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                // The shell never needs mic/camera; deny anything the bundle asks for.
                mainHandler.post(request::deny);
            }
        });

        view.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            // Keep downloads out of the WebView: hand them to the system.
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (ActivityNotFoundException ignored) {
            }
        });

        return view;
    }

    private void rebuildWebView() {
        if (webView != null) {
            root.removeView(webView);
            webView.destroy();
        }
        webView = createWebView();
        root.addView(webView, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        showSplash();
        webView.loadUrl(APP_URL);
    }

    private boolean handleNavigation(Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (isLocal(uri)) {
            return false;
        }
        if ("http".equals(scheme) || "https".equals(scheme) || "mailto".equals(scheme)
                || "tel".equals(scheme)) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (ActivityNotFoundException ignored) {
            }
            return true;
        }
        // stremio:, blob:, data:, about: — let the page deal with them.
        return !("blob".equals(scheme) || "data".equals(scheme) || "about".equals(scheme));
    }

    private void hideFullscreen() {
        if (fullscreenView == null) {
            return;
        }
        if (fullscreenCallback != null) {
            fullscreenCallback.onCustomViewHidden();
        }
        try {
            root.removeView(fullscreenView);
        } catch (IllegalArgumentException ignored) {
        }
        fullscreenView = null;
        fullscreenCallback = null;
        if (webView != null) {
            webView.setVisibility(View.VISIBLE);
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(0);
    }

    // ---------------------------------------------------------------- engine

    private void probeEngine(boolean fromUser) {
        if (checkInFlight) {
            return;
        }
        checkInFlight = true;
        if (fromUser) {
            setupStatus.setText(R.string.setup_checking);
            setupStatus.setVisibility(View.VISIBLE);
        }
        io.execute(() -> {
            boolean alive = engineAlive();
            mainHandler.post(() -> onEngineProbed(alive, fromUser));
        });
    }

    private void onEngineProbed(boolean alive, boolean fromUser) {
        checkInFlight = false;
        setupStatus.setVisibility(View.GONE);
        Log.i(TAG, "engine probe: " + (alive ? "up" : "down") + (fromUser ? " (manual)" : ""));

        if (alive) {
            if (setupView.getVisibility() == View.VISIBLE) {
                hideSetup();
                if (fromUser) {
                    // Re-initialise against a live engine.
                    webView.loadUrl(APP_URL);
                }
            }
            return;
        }
        if (!setupDismissed && !isFinishing()) {
            showSetup();
        }
    }

    private static boolean engineAlive() {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(ENGINE_HOST, ENGINE_PORT), 450);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void openStremio() {
        String[] packages = {"com.stremio.one", "com.stremio"};
        for (String pkg : packages) {
            Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
                return;
            }
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.stremio.one")));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=com.stremio.one")));
        }
    }

    // ---------------------------------------------------------------- chrome

    private void showSplash() {
        debugBuild = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        splashShownAt = System.currentTimeMillis();

        try {
            server = new LocalServer(getAssets());
        } catch (IOException e) {
            Log.e(TAG, "could not start local server: " + e);
        }
        splashView.animate().cancel();
        splashView.setAlpha(1f);
        splashView.setVisibility(View.VISIBLE);
        mainHandler.postDelayed(splashWatchdog, 7000L);
    }

    private void hideSplash() {
        if (splashView.getVisibility() != View.VISIBLE) {
            return;
        }
        mainHandler.removeCallbacks(splashWatchdog);
        long elapsed = System.currentTimeMillis() - splashShownAt;
        long delay = Math.max(0L, SPLASH_MIN_MS - elapsed);
        splashView.animate()
                .alpha(0f)
                .setStartDelay(delay)
                .setDuration(180L)
                .withEndAction(() -> {
                    splashView.setVisibility(View.GONE);
                    splashView.setAlpha(1f);
                })
                .start();
    }

    private void showSetup() {
        if (setupView.getVisibility() == View.VISIBLE) {
            return;
        }
        setupView.animate().cancel();
        setupView.setAlpha(0f);
        setupView.setVisibility(View.VISIBLE);
        setupView.animate().alpha(1f).setDuration(160L).start();
    }

    private void hideSetup() {
        if (setupView.getVisibility() != View.VISIBLE) {
            return;
        }
        setupView.animate().cancel();
        setupView.animate()
                .alpha(0f)
                .setDuration(140L)
                .withEndAction(() -> {
                    setupView.setVisibility(View.GONE);
                    setupView.setAlpha(1f);
                })
                .start();
    }

    private static boolean isLocal(Uri uri) {
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        if ("127.0.0.1".equals(host) || "localhost".equals(host)) {
            return true;
        }
        return false;
    }
}
