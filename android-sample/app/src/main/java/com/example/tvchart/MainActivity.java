package com.example.tvchart;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.util.Collections;
import java.util.Locale;

/**
 * One screen: a TradingView chart in a WebView plus a live price badge fed by
 * {@link TvQuoteClient}. No AndroidX, no third-party UI library.
 */
public class MainActivity extends Activity {

    private static final String DEFAULT_SYMBOL = "BINANCE:BTCUSDT";
    private static final String DEFAULT_INTERVAL = "60";

    private WebView webView;
    private TextView priceText;
    private TextView statusText;
    private EditText symbolInput;

    private TvQuoteClient quoteClient;
    private String symbol = DEFAULT_SYMBOL;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.chart_webview);
        priceText = findViewById(R.id.price_text);
        statusText = findViewById(R.id.status_text);
        symbolInput = findViewById(R.id.symbol_input);
        symbolInput.setText(DEFAULT_SYMBOL);

        Button loadButton = findViewById(R.id.load_button);
        loadButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                loadSymbol(symbolInput.getText().toString().trim());
            }
        });

        configureWebView();
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);          // the widget is pure JavaScript
        settings.setDomStorageEnabled(true);          // TradingView stores layout state here
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        // The page itself is a local asset, its content is https.
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(TvProtocol.USER_AGENT);

        webView.addJavascriptInterface(new WebBridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                setStatus("chart loaded");
            }

            // Deprecated signature on purpose: the modern one needs API 23, minSdk is 21.
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                setStatus("webview error " + errorCode + ": " + description);
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    setStatus("js: " + message.message());
                }
                return true;
            }
        });

        loadSymbol(DEFAULT_SYMBOL);
    }

    private void loadSymbol(String requested) {
        String next = (requested == null || requested.isEmpty()) ? DEFAULT_SYMBOL : requested;
        if (!next.equals(symbol)) {
            symbol = next;
        }
        priceText.setText(R.string.price_placeholder);
        // Query parameters let chart.html pick the symbol before the widget mounts.
        webView.loadUrl("file:///android_asset/chart.html?symbol=" + encode(symbol)
                + "&interval=" + DEFAULT_INTERVAL);
        restartQuotes();
    }

    private static String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException error) {
            return value;
        }
    }

    private void restartQuotes() {
        if (quoteClient != null) {
            quoteClient.stop();
        }
        quoteClient = new TvQuoteClient(Collections.singletonList(symbol), new UiListener());
        quoteClient.start();
        setStatus("connecting…");
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onPause() {
        webView.onPause();
        // Do not keep a market-data socket open while the screen is away.
        if (quoteClient != null) {
            quoteClient.stop();
            quoteClient = null;
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (quoteClient != null) {
            quoteClient.stop();
            quoteClient = null;
        }
        webView.destroy();
        super.onDestroy();
    }

    private void setStatus(final String status) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusText.setText(status);
            }
        });
    }

    private void setPrice(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                priceText.setText(text);
            }
        });
        // Mirror the badge inside the page, over the chart.
        webView.post(new Runnable() {
            @Override
            public void run() {
                webView.evaluateJavascript("window.setQuote(" + TvJson.write(text) + ")", null);
            }
        });
    }

    /** Receives socket callbacks on background threads and hops to the UI thread. */
    private final class UiListener implements TvQuoteClient.Listener {

        @Override
        public void onConnected() {
            setStatus("connected");
        }

        @Override
        public void onQuote(String quoteSymbol, TvProtocol.Quote quote) {
            if (quote.isError()) {
                setStatus("quote error: " + quote.error);
                return;
            }
            if (quote.lastPrice == null) {
                return;
            }
            String change = quote.changePercent == null
                    ? ""
                    : String.format(Locale.US, "  (%+.2f%%)", quote.changePercent);
            setPrice(String.format(Locale.US, "%s  %,.2f%s", quoteSymbol, quote.lastPrice, change));
        }

        @Override
        public void onStatus(String status) {
            setStatus(status);
        }

        @Override
        public void onFailure(Throwable error) {
            setStatus("socket error: " + error.getMessage());
        }
    }

    /** Lets the page talk back to the app (see window.reportReady in chart.html). */
    private final class WebBridge {

        @JavascriptInterface
        public void onChartReady() {
            setStatus("chart ready");
        }
    }
}
