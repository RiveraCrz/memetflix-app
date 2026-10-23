package com.memetflix.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.UiModeManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final String CUSTOM_UI_URL = "file:///android_asset/memetflix.html";
    private static final String WEB_HOME_URL = "https://lamovie.la/";
    private static final String ALLOWED_HOST_KEYWORD = "lamovie.";
    private static final String PREFS_NAME = "MemetflixPrefs";
    private static final String PREF_CURSOR_ENABLED = "cursor_enabled";
    private static final String PREF_USE_CLASSIC_WEB = "use_classic_web";

    private static final String UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private static final String[] AD_DOMAINS = new String[]{
            "agl0", "agl001.", "agl002.", "agl003.", "agl004.", "agl005.", "adangle",
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "googletagmanager.com", "google-analytics.com", "adservice.google.",
            "popads.net", "popcash.net", "propellerads.com", "adsterra.com",
            "exoclick.com", "juicyads.com", "onclickads.net", "hilltopads.net",
            "clickadu.com", "adcash.com", "trafficjunky.net", "ero-advertising.com",
            "tsyndicate.com", "realsrv.com", "histats.com", "acscdn.com",
            "highperformanceformat", "alwingulla.com", "pushame.com", "monetag.",
            "outbrain.com", "taboola.com", "mgid.com", "revcontent.com",
            "adskeeper.com", "admaven.com", "richads.com", "evadav.com",
            "pushhouse.", "galaksion.com", "rollerads.com", "kadam.net",
            "bidgear.com", "pubfuture.com", "criteo.com", "criteo.net",
            "rubiconproject.com", "pubmatic.com", "openx.net", "adnxs.com",
            "smartadserver.com", "teads.tv", "magsrv.com", "sb-cd.com",
            "bet365.", "1xbet.", "caliente.mx", "codere.", "bwin.", "melbet.",
            "mostbet.", "pin-up.", "1win.", "parimatch.", "betway.", "stake.com",
            "casino", "kazino", "plinko", "megamoolah", "chicken-road", "chickenroad"
    };

    private static final String[] AD_PATH_PATTERNS = new String[]{
            "/o/s/", "/b/data", "/pop.js", "/popunder", "/pophit", "/pop-under",
            "/ads.js", "/ad.js", "/adscript", "/adserver", "/adframe", "/adbanner",
            "/vast.xml", "/vmap.xml", "/preroll", "/ima3.js", "adangle"
    };

    private FrameLayout rootContainer;
    private WebView webView;
    private FrameLayout fullscreenVideoContainer;
    private View customVideoView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    private LinearLayout splashOverlay;
    private LinearLayout errorOverlay;
    private CursorOverlayView cursorOverlay;

    private boolean isTvDevice = false;
    private boolean cursorEnabled = false;
    private boolean useClassicWeb = false;
    private boolean backLongPressHandled = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideCursorRunnable = new Runnable() {
        @Override
        public void run() {
            if (cursorOverlay != null) {
                cursorOverlay.setCursorVisible(false);
            }
        }
    };

    private final Runnable backLongPressRunnable = new Runnable() {
        @Override
        public void run() {
            backLongPressHandled = true;
            showQuickMenu();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        isTvDevice = detectTvDevice();
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        // En nuestra interfaz nativa las flechas D-Pad funcionan directamente sobre cada póster y botón
        cursorEnabled = prefs.getBoolean(PREF_CURSOR_ENABLED, false);
        useClassicWeb = prefs.getBoolean(PREF_USE_CLASSIC_WEB, false);

        buildNativeUi();
        enableImmersiveFullscreen();
        configureWebView();

        if (isNetworkAvailable()) {
            loadStartPage();
        } else {
            showErrorOverlay();
        }
    }

    private void loadStartPage() {
        webView.loadUrl(useClassicWeb ? WEB_HOME_URL : CUSTOM_UI_URL);
    }

    private boolean detectTvDevice() {
        UiModeManager uiModeManager = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
        if (uiModeManager != null && uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true;
        }
        PackageManager pm = getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                || !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
    }

    private void buildNativeUi() {
        rootContainer = new FrameLayout(this);
        rootContainer.setBackgroundColor(Color.BLACK);
        rootContainer.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        rootContainer.addView(webView);

        fullscreenVideoContainer = new FrameLayout(this);
        fullscreenVideoContainer.setBackgroundColor(Color.BLACK);
        fullscreenVideoContainer.setVisibility(View.GONE);
        rootContainer.addView(fullscreenVideoContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        cursorOverlay = new CursorOverlayView(this);
        cursorOverlay.setVisibility(cursorEnabled ? View.VISIBLE : View.GONE);
        rootContainer.addView(cursorOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        errorOverlay = buildErrorOverlay();
        errorOverlay.setVisibility(View.GONE);
        rootContainer.addView(errorOverlay);

        splashOverlay = buildSplashOverlay();
        rootContainer.addView(splashOverlay);

        setContentView(rootContainer);
    }

    private LinearLayout buildSplashOverlay() {
        LinearLayout splash = new LinearLayout(this);
        splash.setOrientation(LinearLayout.VERTICAL);
        splash.setGravity(Gravity.CENTER);
        splash.setBackgroundColor(Color.BLACK);
        splash.setClickable(true);
        splash.setFocusable(false);
        splash.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        TextView brandTitle = new TextView(this);
        brandTitle.setText("Memetflix");
        brandTitle.setTextColor(Color.parseColor("#E50914"));
        brandTitle.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        brandTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, isTvDevice ? 68 : 48);
        brandTitle.setGravity(Gravity.CENTER);
        brandTitle.setShadowLayer(24f, 0f, 6f, Color.parseColor("#99B20710"));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            brandTitle.setLetterSpacing(0.06f);
        }
        splash.addView(brandTitle);

        View accentLine = new View(this);
        accentLine.setBackgroundColor(Color.parseColor("#E50914"));
        LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(dpToPx(160), dpToPx(4));
        lineParams.topMargin = dpToPx(8);
        lineParams.bottomMargin = dpToPx(32);
        splash.addView(accentLine, lineParams);

        ProgressBar loadingProgressBar = new ProgressBar(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && loadingProgressBar.getIndeterminateDrawable() != null) {
            loadingProgressBar.getIndeterminateDrawable().setTint(Color.parseColor("#E50914"));
        }
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dpToPx(44), dpToPx(44));
        splash.addView(loadingProgressBar, progressParams);

        TextView statusTextView = new TextView(this);
        statusTextView.setText("Iniciando experiencia sin anuncios...");
        statusTextView.setTextColor(Color.parseColor("#B3FFFFFF"));
        statusTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusTextView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        statusParams.topMargin = dpToPx(16);
        splash.addView(statusTextView, statusParams);

        return splash;
    }

    private LinearLayout buildErrorOverlay() {
        LinearLayout errorLayout = new LinearLayout(this);
        errorLayout.setOrientation(LinearLayout.VERTICAL);
        errorLayout.setGravity(Gravity.CENTER);
        errorLayout.setBackgroundColor(Color.BLACK);
        errorLayout.setPadding(dpToPx(32), dpToPx(32), dpToPx(32), dpToPx(32));
        errorLayout.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        TextView logo = new TextView(this);
        logo.setText("Memetflix");
        logo.setTextColor(Color.parseColor("#E50914"));
        logo.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        logo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 48);
        logo.setGravity(Gravity.CENTER);
        errorLayout.addView(logo);

        TextView msg = new TextView(this);
        msg.setText("No se pudo conectar al servidor.\nVerifica tu conexión a Internet.");
        msg.setTextColor(Color.WHITE);
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        msg.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams msgParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        msgParams.topMargin = dpToPx(24);
        msgParams.bottomMargin = dpToPx(28);
        errorLayout.addView(msg, msgParams);

        Button retryBtn = new Button(this);
        retryBtn.setText("Reintentar");
        retryBtn.setTextColor(Color.WHITE);
        retryBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        retryBtn.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(Color.parseColor("#E50914"));
        btnBg.setCornerRadius(dpToPx(8));
        retryBtn.setBackground(btnBg);
        retryBtn.setPadding(dpToPx(32), dpToPx(12), dpToPx(32), dpToPx(12));
        retryBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                errorOverlay.setVisibility(View.GONE);
                showSplashOverlay();
                loadStartPage();
            }
        });
        errorLayout.addView(retryBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        return errorLayout;
    }

    @SuppressWarnings({"SetJavaScriptEnabled", "JavascriptInterface"})
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(UA_DESKTOP);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.setAcceptCookie(true);
            cookieManager.setAcceptThirdPartyCookies(webView, true);
        }

        // Registrar puente nativo para consumir los endpoints de LaMovie y extraer streams .m3u8 directos
        webView.addJavascriptInterface(new MemetflixBridge(), "MemetflixNative");

        webView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                return true;
            }
        });
        webView.setLongClickable(false);

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    return handleNavigationUri(request.getUrl(), request.isForMainFrame());
                }
                return false;
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url == null) return true;
                return handleNavigationUri(Uri.parse(url), true);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && request != null && request.getUrl() != null) {
                    if (isAdUri(request.getUrl())) {
                        return createStealthAdResponse(request.getUrl());
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @SuppressWarnings("deprecation")
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                if (url != null) {
                    Uri uri = Uri.parse(url);
                    if (isAdUri(uri)) {
                        return createStealthAdResponse(uri);
                    }
                }
                return super.shouldInterceptRequest(view, url);
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                super.onPageCommitVisible(view, url);
                hideSplashOverlayDelayed(180);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                hideSplashOverlayDelayed(250);
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {

            @SuppressWarnings("SetJavaScriptEnabled")
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                if (resultMsg != null && resultMsg.obj instanceof WebView.WebViewTransport) {
                    final WebView popupSink = new WebView(MainActivity.this);
                    popupSink.getSettings().setJavaScriptEnabled(true);
                    popupSink.setWebViewClient(new WebViewClient() {
                        @Override
                        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                            return true;
                        }

                        @SuppressWarnings("deprecation")
                        @Override
                        public boolean shouldOverrideUrlLoading(WebView v, String url) {
                            return true;
                        }
                    });
                    WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                    transport.setWebView(popupSink);
                    resultMsg.sendToTarget();

                    mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                popupSink.stopLoading();
                                popupSink.destroy();
                            } catch (Throwable ignored) {
                            }
                        }
                    }, 2500);
                    return true;
                }
                return false;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customVideoView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customVideoView = view;
                customViewCallback = callback;
                fullscreenVideoContainer.addView(customVideoView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));
                fullscreenVideoContainer.setVisibility(View.VISIBLE);
                webView.setVisibility(View.GONE);
                enableImmersiveFullscreen();
            }

            @Override
            public void onHideCustomView() {
                if (customVideoView == null) {
                    return;
                }
                fullscreenVideoContainer.removeView(customVideoView);
                fullscreenVideoContainer.setVisibility(View.GONE);
                customVideoView = null;
                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                    customViewCallback = null;
                }
                webView.setVisibility(View.VISIBLE);
                enableImmersiveFullscreen();
            }
        });
    }

    /**
     * Puente Nativo Java -> JavaScript para consumir la API de LaMovie y extraer
     * directamente el flujo HLS (.m3u8) y subtítulos (.vtt) de vimeos.net sin cargar publicidad.
     */
    public class MemetflixBridge {

        @JavascriptInterface
        public String httpGet(String urlStr, String bearerToken) {
            return performHttpRequest(urlStr, "GET", null, bearerToken, WEB_HOME_URL);
        }

        @JavascriptInterface
        public String httpPost(String urlStr, String jsonBody, String bearerToken) {
            return performHttpRequest(urlStr, "POST", jsonBody, bearerToken, WEB_HOME_URL);
        }

        @JavascriptInterface
        public String resolveVideoStream(String postId) {
            try {
                String playerApiUrl = "https://lamovie.la/wp-api/v1/player?postId=" + postId + "&demo=0";
                String playerJsonStr = performHttpRequest(playerApiUrl, "GET", null, null, WEB_HOME_URL);
                JSONObject root = new JSONObject(playerJsonStr);
                JSONObject data = root.optJSONObject("data");
                if (data == null) {
                    return "{\"ok\":false}";
                }

                JSONArray embeds = data.optJSONArray("embeds");
                if (embeds == null || embeds.length() == 0) {
                    return "{\"ok\":false}";
                }

                String selectedEmbedUrl = null;
                for (int i = 0; i < embeds.length(); i++) {
                    JSONObject item = embeds.optJSONObject(i);
                    if (item != null) {
                        String u = item.optString("url", "");
                        if (u.contains("vimeos")) {
                            selectedEmbedUrl = u;
                            break;
                        }
                        if (selectedEmbedUrl == null && !u.isEmpty()) {
                            selectedEmbedUrl = u;
                        }
                    }
                }

                if (selectedEmbedUrl == null || selectedEmbedUrl.isEmpty()) {
                    return "{\"ok\":false}";
                }

                // Descargar el HTML del embed (ej. https://vimeos.net/embed-xxxx.html) y extraer el master.m3u8 directo
                String embedHtml = performHttpRequest(selectedEmbedUrl, "GET", null, null, WEB_HOME_URL);
                String unpacked = unpackDeanEdwards(embedHtml);
                String searchSpace = (unpacked != null && !unpacked.isEmpty()) ? (unpacked + "\n" + embedHtml) : embedHtml;

                String m3u8Url = extractFirstMatch(searchSpace, "https://[^\"'\\s<>]+\\.m3u8[^\"'\\s<>]*");
                String posterUrl = extractFirstMatch(searchSpace, "https://[^\"'\\s<>]+\\.(?:jpg|webp|png)");

                JSONArray subtitlesArr = new JSONArray();
                Pattern vttPattern = Pattern.compile("https://[^\"'\\s<>]+\\.vtt");
                Matcher vttMatcher = vttPattern.matcher(searchSpace);
                while (vttMatcher.find()) {
                    String vttUrl = vttMatcher.group();
                    JSONObject subObj = new JSONObject();
                    subObj.put("file", vttUrl);
                    subObj.put("label", "Español");
                    subtitlesArr.put(subObj);
                }

                JSONObject result = new JSONObject();
                result.put("ok", true);
                result.put("m3u8", m3u8Url != null ? m3u8Url : "");
                result.put("poster", posterUrl != null ? posterUrl : "");
                result.put("embedUrl", selectedEmbedUrl);
                result.put("subtitles", subtitlesArr);
                return result.toString();
            } catch (Exception e) {
                return "{\"ok\":false}";
            }
        }
    }

    private String performHttpRequest(String urlStr, String method, String body, String bearer, String referer) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("User-Agent", UA_DESKTOP);
            conn.setRequestProperty("Accept", "application/json, text/html, */*");
            if (referer != null && !referer.isEmpty()) {
                conn.setRequestProperty("Referer", referer);
            }
            if (bearer != null && !bearer.trim().isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + bearer.trim());
            }

            if ("POST".equalsIgnoreCase(method) && body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                OutputStream os = conn.getOutputStream();
                os.write(bytes);
                os.flush();
                os.close();
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return "{}";

            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            return "{\"error\":true,\"message\":\"" + e.getMessage() + "\"}";
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Desempaqueta bloques JavaScript ofuscados con Dean Edwards Packer:
     * eval(function(p,a,c,k,e,d){...}('payload',36,339,'sym1|sym2|...'.split('|')))
     */
    private String unpackDeanEdwards(String html) {
        if (html == null || html.isEmpty()) return "";
        try {
            Pattern packerPattern = Pattern.compile(
                    "eval\\(function\\(p,a,c,k,e,d\\)\\{.*?\\}\\('(.*?)',(\\d+),(\\d+),'(.*?)'\\.split\\('\\|'\\)\\)\\)",
                    Pattern.DOTALL
            );
            Matcher m = packerPattern.matcher(html);
            if (!m.find()) return "";

            String p = m.group(1);
            int a = Integer.parseInt(m.group(2));
            int c = Integer.parseInt(m.group(3));
            String[] k = m.group(4).split("\\|", -1);

            Map<String, String> dict = new HashMap<>();
            for (int i = 0; i < c; i++) {
                if (i < k.length && k[i] != null && !k[i].isEmpty()) {
                    dict.put(toBase(i, a), k[i]);
                }
            }

            Pattern wordPattern = Pattern.compile("\\b[0-9a-zA-Z]+\\b");
            Matcher wordMatcher = wordPattern.matcher(p);
            StringBuffer sb = new StringBuffer();
            while (wordMatcher.find()) {
                String word = wordMatcher.group();
                String replacement = dict.get(word);
                if (replacement != null) {
                    wordMatcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                } else {
                    wordMatcher.appendReplacement(sb, Matcher.quoteReplacement(word));
                }
            }
            wordMatcher.appendTail(sb);
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String toBase(int num, int radix) {
        String chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        if (num == 0) return "0";
        StringBuilder sb = new StringBuilder();
        int n = num;
        while (n > 0) {
            sb.insert(0, chars.charAt(n % radix));
            n /= radix;
        }
        return sb.toString();
    }

    private String extractFirstMatch(String text, String regex) {
        if (text == null) return null;
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group() : null;
    }

    private boolean handleNavigationUri(Uri uri, boolean isMainFrame) {
        if (uri == null) return true;
        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
        if (scheme.equals("file")) return false;
        if (!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("blob") && !scheme.equals("about")) {
            return true;
        }
        String host = uri.getHost() != null ? uri.getHost().toLowerCase(Locale.ROOT) : "";
        if (isAdDomain(host) || isAdUri(uri)) {
            return true;
        }
        if (isMainFrame) {
            return !host.isEmpty() && !host.contains(ALLOWED_HOST_KEYWORD);
        }
        return false;
    }

    private WebResourceResponse createStealthAdResponse(Uri uri) {
        String path = (uri != null && uri.getPath() != null) ? uri.getPath().toLowerCase(Locale.ROOT) : "";
        String mimeType = "application/javascript";
        byte[] bodyBytes;

        if (path.contains("/b/data") || path.endsWith(".json")) {
            mimeType = "application/json";
            bodyBytes = "{\"error\":false,\"banners\":{},\"trackers\":{}}".getBytes(StandardCharsets.UTF_8);
        } else if (path.contains("vast") || path.contains("vmap") || path.endsWith(".xml")) {
            mimeType = "application/xml";
            bodyBytes = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><VAST version=\"3.0\"></VAST>".getBytes(StandardCharsets.UTF_8);
        } else {
            // Incluye window.cRAds = true (requerido por vimeos.net/js/pop.js para no mostrar #adbd)
            String stealthJs = "var xRds=false;var cRAds=true;window.cRAds=true;window.canRunAds=true;window.isAdBlockActive=false;";
            bodyBytes = stealthJs.getBytes(StandardCharsets.UTF_8);
        }

        WebResourceResponse response = new WebResourceResponse(
                mimeType,
                "UTF-8",
                new ByteArrayInputStream(bodyBytes)
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Map<String, String> headers = new HashMap<>();
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Cache-Control", "no-store");
            response.setResponseHeaders(headers);
            response.setStatusCodeAndReasonPhrase(200, "OK");
        }
        return response;
    }

    private boolean isAdUri(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
        if (scheme.equals("file")) return false;
        String host = uri.getHost() != null ? uri.getHost().toLowerCase(Locale.ROOT) : "";
        String fullUrl = uri.toString().toLowerCase(Locale.ROOT);
        String path = uri.getPath() != null ? uri.getPath().toLowerCase(Locale.ROOT) : "";

        if (path.endsWith(".m3u8") || path.endsWith(".ts") || path.endsWith(".mp4")
                || path.endsWith(".vtt") || path.endsWith(".srt") || path.endsWith(".webm")
                || path.endsWith(".mkv") || path.endsWith(".m4s") || path.endsWith(".aac")
                || fullUrl.contains("/hls")) {
            return false;
        }
        if (isAdDomain(host)) return true;
        for (String pattern : AD_PATH_PATTERNS) {
            if (fullUrl.contains(pattern.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean isAdDomain(String host) {
        if (host == null || host.isEmpty()) return false;
        String lower = host.toLowerCase(Locale.ROOT);
        for (String ad : AD_DOMAINS) {
            if (lower.contains(ad.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private void showSplashOverlay() {
        if (splashOverlay != null) {
            splashOverlay.animate().cancel();
            splashOverlay.setAlpha(1f);
            splashOverlay.setVisibility(View.VISIBLE);
        }
    }

    private void hideSplashOverlayDelayed(long delayMs) {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (splashOverlay != null && splashOverlay.getVisibility() == View.VISIBLE) {
                    splashOverlay.animate()
                            .alpha(0f)
                            .setDuration(350)
                            .withEndAction(new Runnable() {
                                @Override
                                public void run() {
                                    splashOverlay.setVisibility(View.GONE);
                                }
                            })
                            .start();
                }
            }
        }, delayMs);
    }

    private void showErrorOverlay() {
        if (splashOverlay != null) {
            splashOverlay.setVisibility(View.GONE);
        }
        if (errorOverlay != null) {
            errorOverlay.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();

        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            if (action == KeyEvent.ACTION_UP) {
                showQuickMenu();
            }
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    backLongPressHandled = false;
                    mainHandler.postDelayed(backLongPressRunnable, 850);
                }
                return true;
            } else if (action == KeyEvent.ACTION_UP) {
                mainHandler.removeCallbacks(backLongPressRunnable);
                if (!backLongPressHandled) {
                    handleBackNavigation();
                }
                return true;
            }
        }

        if (cursorEnabled && customVideoView == null && errorOverlay.getVisibility() != View.VISIBLE) {
            if (isDpadDirectionKey(keyCode)) {
                if (action == KeyEvent.ACTION_DOWN) {
                    cursorOverlay.moveByKey(keyCode, event.getRepeatCount());
                    scheduleHideCursor();
                }
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (cursorOverlay.isCursorVisible()) {
                    if (action == KeyEvent.ACTION_UP) {
                        cursorOverlay.performVirtualClick(webView);
                        scheduleHideCursor();
                    }
                    return true;
                }
            }
        }

        return super.dispatchKeyEvent(event);
    }

    private boolean isDpadDirectionKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    private void scheduleHideCursor() {
        mainHandler.removeCallbacks(hideCursorRunnable);
        mainHandler.postDelayed(hideCursorRunnable, 3800);
    }

    private void handleBackNavigation() {
        if (customVideoView != null) {
            webView.getWebChromeClient().onHideCustomView();
            return;
        }
        if (!useClassicWeb && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.evaluateJavascript("window.__handleAndroidBack && window.__handleAndroidBack()", new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    if (!"true".equals(value)) {
                        showQuickMenu();
                    }
                }
            });
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        showQuickMenu();
    }

    private void showQuickMenu() {
        String cursorLabel = cursorEnabled
                ? "Control Remoto: Puntero Mouse (Cambiar a Flechas D-Pad)"
                : "Control Remoto: Flechas D-Pad (Cambiar a Puntero Mouse)";

        String uiModeLabel = useClassicWeb
                ? "Cambiar a Interfaz Propia Memetflix (Sin Anuncios)"
                : "Cambiar a Vista Web Original (lamovie.la)";

        final String[] options = new String[]{
                "Ir al Inicio de Memetflix",
                "Recargar catálogo",
                cursorLabel,
                uiModeLabel,
                "Salir de Memetflix"
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK);
        builder.setTitle("Memetflix — Opciones");
        builder.setItems(options, (dialog, which) -> {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            switch (which) {
                case 0:
                    showSplashOverlay();
                    loadStartPage();
                    break;
                case 1:
                    showSplashOverlay();
                    webView.reload();
                    break;
                case 2:
                    cursorEnabled = !cursorEnabled;
                    prefs.edit().putBoolean(PREF_CURSOR_ENABLED, cursorEnabled).apply();
                    cursorOverlay.setVisibility(cursorEnabled ? View.VISIBLE : View.GONE);
                    if (cursorEnabled) {
                        cursorOverlay.setCursorVisible(true);
                        scheduleHideCursor();
                    }
                    Toast.makeText(this, cursorEnabled ? "Puntero virtual activado" : "Navegación por flechas activada", Toast.LENGTH_SHORT).show();
                    break;
                case 3:
                    useClassicWeb = !useClassicWeb;
                    prefs.edit().putBoolean(PREF_USE_CLASSIC_WEB, useClassicWeb).apply();
                    showSplashOverlay();
                    loadStartPage();
                    break;
                case 4:
                    finish();
                    break;
            }
        });
        builder.show();
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return true;
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnected();
    }

    private void enableImmersiveFullscreen() {
        try {
            Window window = getWindow();
            if (window == null) return;
            View decorView = window.getDecorView();
            if (decorView == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false);
                WindowInsetsController controller = decorView.getWindowInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                //noinspection deprecation
                decorView.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                );
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enableImmersiveFullscreen();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        enableImmersiveFullscreen();
        if (webView != null) {
            webView.onResume();
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
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }

    private int dpToPx(int dp) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        return Math.round(dp * metrics.density);
    }

    private class CursorOverlayView extends View {
        private float cursorX = -1f;
        private float cursorY = -1f;
        private boolean cursorVisible = true;
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint centerDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path pointerPath = new Path();

        public CursorOverlayView(Context context) {
            super(context);
            setClickable(false);
            setFocusable(false);

            fillPaint.setColor(Color.parseColor("#E50914"));
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setShadowLayer(8f, 0f, 3f, Color.BLACK);

            strokePaint.setColor(Color.WHITE);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(dpToPx(2));

            centerDotPaint.setColor(Color.WHITE);
            centerDotPaint.setStyle(Paint.Style.FILL);
        }

        public boolean isCursorVisible() {
            return cursorVisible;
        }

        public void setCursorVisible(boolean visible) {
            this.cursorVisible = visible;
            invalidate();
        }

        public void moveByKey(int keyCode, int repeatCount) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            if (cursorX < 0 || cursorY < 0) {
                cursorX = w / 2f;
                cursorY = h / 2f;
            }

            cursorVisible = true;
            float baseStep = dpToPx(16);
            float accel = Math.min(3.5f, 1.0f + (repeatCount * 0.35f));
            float delta = baseStep * accel;

            int scrollMargin = dpToPx(56);
            int scrollStep = Math.round(delta * 2.2f);

            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                cursorY -= delta;
                if (cursorY < scrollMargin && webView != null) {
                    webView.scrollBy(0, -scrollStep);
                }
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                cursorY += delta;
                if (cursorY > h - scrollMargin && webView != null) {
                    webView.scrollBy(0, scrollStep);
                }
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                cursorX -= delta;
                if (cursorX < scrollMargin && webView != null) {
                    webView.scrollBy(-scrollStep, 0);
                }
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                cursorX += delta;
                if (cursorX > w - scrollMargin && webView != null) {
                    webView.scrollBy(scrollStep, 0);
                }
            }

            cursorX = Math.max(dpToPx(8), Math.min(w - dpToPx(8), cursorX));
            cursorY = Math.max(dpToPx(8), Math.min(h - dpToPx(8), cursorY));
            invalidate();
        }

        public void performVirtualClick(View targetView) {
            if (targetView == null || cursorX < 0 || cursorY < 0) return;
            long downTime = SystemClock.uptimeMillis();
            long eventTime = SystemClock.uptimeMillis() + 40;

            MotionEvent downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, cursorX, cursorY, 0);
            MotionEvent upEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_UP, cursorX, cursorY, 0);

            targetView.dispatchTouchEvent(downEvent);
            targetView.dispatchTouchEvent(upEvent);

            downEvent.recycle();
            upEvent.recycle();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (!cursorVisible) return;

            if (cursorX < 0 || cursorY < 0) {
                cursorX = getWidth() / 2f;
                cursorY = getHeight() / 2f;
            }

            float size = dpToPx(22);
            pointerPath.reset();
            pointerPath.moveTo(cursorX, cursorY);
            pointerPath.lineTo(cursorX, cursorY + size);
            pointerPath.lineTo(cursorX + size * 0.32f, cursorY + size * 0.72f);
            pointerPath.lineTo(cursorX + size * 0.75f, cursorY + size * 0.72f);
            pointerPath.close();

            canvas.drawPath(pointerPath, fillPaint);
            canvas.drawPath(pointerPath, strokePaint);
            canvas.drawCircle(cursorX, cursorY, dpToPx(2), centerDotPaint);
        }
    }
}
