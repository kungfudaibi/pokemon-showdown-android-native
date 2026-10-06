package dev.local.showdownnative;

import android.content.Context;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Displays the official browser Battle renderer while native controls own the connection. */
final class OfficialBattleView extends WebView {
    private final List<String> history = new ArrayList<>();
    private boolean ready;
    private String desiredSide = "";

    OfficialBattleView(Context context) {
        super(context);
        getSettings().setJavaScriptEnabled(true);
        getSettings().setDomStorageEnabled(true);
        getSettings().setAllowFileAccess(false);
        setBackgroundColor(0xff161d2a);
        setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                Log.d("OfficialBattle", message.message());
                return true;
            }
        });
        setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                evaluateJavascript("window.psReady === true", result -> {
                    if (!"true".equals(result)) {
                        evaluateJavascript("window.psError || 'scripts unavailable'", error -> Log.e("OfficialBattle", "Renderer failed: " + error));
                        return;
                    }
                    ready = true;
                    send(history);
                    setSide(desiredSide);
                });
            }
        });
        try (InputStream input = context.getAssets().open("official_battle.html");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int size;
            while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
            loadDataWithBaseURL("https://play.pokemonshowdown.com/", output.toString(StandardCharsets.UTF_8.name()), "text/html", "UTF-8", null);
        } catch (Exception error) {
            Log.e("OfficialBattle", "Could not load battle page", error);
        }
    }

    void addLines(List<String> lines) {
        history.addAll(lines);
        if (ready) send(lines);
    }

    void setSide(String side) {
        desiredSide = side;
        if (ready) evaluateJavascript("window.psSetSide(" + JSONObject.quote(side) + ")", null);
    }

    private void send(List<String> lines) {
        if (lines.isEmpty()) return;
        JSONArray json = new JSONArray(lines);
        evaluateJavascript("window.psReceive(" + json + ")", null);
    }
}
