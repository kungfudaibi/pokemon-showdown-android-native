package dev.local.showdownnative;

import android.content.Context;
import android.util.Log;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Queries the official teambuilder's current learnset table without bundling a stale copy. */
final class TeamCatalog extends WebView {
    interface Callback { void onResult(List<String> moves, String error); }
    interface MoveInfoCallback { void onResult(JSONObject info); }
    interface MoveSummariesCallback { void onResult(JSONObject descriptions); }
    interface SpeciesCallback { void onResult(List<DexNames.Species> species, String error); }
    private final List<Runnable> queued = new ArrayList<>();
    private boolean ready, failed;

    TeamCatalog(Context context) {
        super(context);
        getSettings().setJavaScriptEnabled(true);
        getSettings().setAllowFileAccess(false);
        setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                evaluateJavascript("window.catalogReady===true", value -> {
                    ready = "true".equals(value);
                    failed = !ready;
                    for (Runnable task : new ArrayList<>(queued)) task.run();
                    queued.clear();
                    if (failed) Log.e("TeamCatalog", "Official teambuilder data unavailable");
                });
            }
        });
        try (InputStream input = context.getAssets().open("team_catalog.html");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int size;
            while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
            loadDataWithBaseURL("https://play.pokemonshowdown.com/", output.toString(StandardCharsets.UTF_8.name()), "text/html", "UTF-8", null);
        } catch (Exception error) {
            failed = true;
            Log.e("TeamCatalog", "Could not open catalog", error);
        }
    }

    void getMoves(String species, String format, Callback callback) {
        Runnable query = () -> {
            if (failed) { callback.onResult(null, "无法加载官网招式学习表，请检查网络"); return; }
            String expression = "window.catalogMoves(" + JSONObject.quote(species) + "," + JSONObject.quote(format) + ")";
            evaluateJavascript(expression, raw -> {
                try {
                    JSONArray array = new JSONArray(raw);
                    List<String> moves = new ArrayList<>();
                    for (int i = 0; i < array.length(); i++) moves.add(array.getString(i));
                    callback.onResult(moves, null);
                } catch (Exception error) {
                    Log.e("TeamCatalog", "Could not parse moves", error);
                    callback.onResult(null, "招式列表解析失败");
                }
            });
        };
        if (ready || failed) query.run(); else queued.add(query);
    }

    void getMoveInfo(String moveId, String format, MoveInfoCallback callback) {
        Runnable query = () -> {
            if (failed) { callback.onResult(null); return; }
            evaluateJavascript("window.catalogMoveInfo(" + JSONObject.quote(moveId) + ","
                    + JSONObject.quote(format) + ")", raw -> {
                try { callback.onResult(new JSONObject(raw)); }
                catch (Exception ignored) { callback.onResult(null); }
            });
        };
        if (ready || failed) query.run(); else queued.add(query);
    }

    void getMoveSummaries(List<String> moveIds, String format, MoveSummariesCallback callback) {
        Runnable query = () -> {
            if (failed) { callback.onResult(new JSONObject()); return; }
            JSONArray ids = new JSONArray();
            for (String id : moveIds) ids.put(id);
            evaluateJavascript("window.catalogMoveSummaries(" + ids + "," + JSONObject.quote(format) + ")",
                    raw -> {
                        try { callback.onResult(new JSONObject(raw)); }
                        catch (Exception ignored) { callback.onResult(new JSONObject()); }
                    });
        };
        if (ready || failed) query.run(); else queued.add(query);
    }

    void getItems(String format, String species, Callback callback) {
        queryNames("window.catalogItems(" + JSONObject.quote(format) + "," + JSONObject.quote(species) + ")", callback);
    }

    void getAbilities(String species, Callback callback) {
        queryNames("window.catalogAbilities(" + JSONObject.quote(species) + ")", callback);
    }

    void getSpecies(String format, SpeciesCallback callback) {
        Runnable query = () -> {
            if (failed) { callback.onResult(null, "无法加载官网宝可梦资料，请检查网络"); return; }
            evaluateJavascript("window.catalogSpecies(" + JSONObject.quote(format) + ")", raw -> {
                try {
                    JSONArray array = new JSONArray(raw);
                    List<DexNames.Species> species = new ArrayList<>();
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject one = array.getJSONObject(i);
                        species.add(new DexNames.Species(one.getString("english"), one.getString("chinese"),
                                one.optString("formEnglish"), one.optString("requiredItem")));
                    }
                    callback.onResult(species, null);
                } catch (Exception error) { callback.onResult(null, "官网宝可梦列表解析失败"); }
            });
        };
        if (ready || failed) query.run(); else queued.add(query);
    }

    private void queryNames(String expression, Callback callback) {
        Runnable query = () -> {
            if (failed) { callback.onResult(null, "无法加载官网配置资料，请检查网络"); return; }
            evaluateJavascript(expression, raw -> {
                try {
                    JSONArray array = new JSONArray(raw);
                    List<String> names = new ArrayList<>();
                    for (int i = 0; i < array.length(); i++) names.add(array.getString(i));
                    callback.onResult(names, null);
                } catch (Exception error) { callback.onResult(null, "官网配置列表解析失败"); }
            });
        };
        if (ready || failed) query.run(); else queued.add(query);
    }
}
