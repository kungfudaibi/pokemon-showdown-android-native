package dev.local.showdownnative;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** The live Showdown teambuilder, with its saved teams mirrored for native matchmaking. */
final class OfficialTeambuilderView extends WebView {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Consumer<List<TeamStore.Team>> onTeamsChanged;
    private final String initialTeams;
    private boolean closed, initialized, querying;
    private String lastSnapshot = "";

    OfficialTeambuilderView(Context context, List<TeamStore.Team> existing,
                            Consumer<List<TeamStore.Team>> onTeamsChanged) {
        super(context);
        this.onTeamsChanged = onTeamsChanged;
        JSONArray seed = new JSONArray();
        for (TeamStore.Team team : existing) {
            JSONObject row = new JSONObject();
            try {
                row.put("name", team.name);
                row.put("format", team.format);
                row.put("export", team.export);
                row.put("packed", team.packed);
                seed.put(row);
            } catch (Exception error) { Log.w("OfficialTeambuilder", "Could not seed team", error); }
        }
        initialTeams = seed.toString();
        getSettings().setJavaScriptEnabled(true);
        getSettings().setDomStorageEnabled(true);
        setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) { if (!closed) poll(); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                android.net.Uri uri = request.getUrl();
                if ("https".equals(uri.getScheme()) && "play.pokemonshowdown.com".equals(uri.getHost())) return false;
                if ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) {
                    try { context.startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                    catch (Exception error) { Log.w("OfficialTeambuilder", "Could not open external link", error); }
                }
                return true;
            }
        });
        loadUrl("https://play.pokemonshowdown.com/#teambuilder");
    }

    void focusTeambuilder() { if (!closed) loadUrl("https://play.pokemonshowdown.com/#teambuilder"); }

    void syncNow(Runnable after) {
        if (closed) { after.run(); return; }
        readTeams(after);
    }

    void navigateBack(Consumer<Boolean> result) {
        if (closed) { result.accept(false); return; }
        String script = "(function(){var room=window.app&&app.rooms&&app.rooms.teambuilder;" +
                "if(room&&room.curTeam){room.back();return true;}return false;})()";
        evaluateJavascript(script, value -> result.accept("true".equals(value)));
    }

    private void poll() {
        if (closed || querying) return;
        querying = true;
        String check = "(function(){return !!(window.Storage && Array.isArray(Storage.teams) && Storage.saveTeams && Storage.importTeam && Storage.packTeam && Storage.exportTeam);})()";
        evaluateJavascript(check, value -> {
            querying = false;
            if (closed) return;
            if (!"true".equals(value)) { schedulePoll(); return; }
            if (!initialized) {
                initialized = true;
                seedLegacyTeams();
            } else readTeams(this::schedulePoll);
        });
    }

    private void seedLegacyTeams() {
        String script = "(function(){try{" +
                "if(localStorage.getItem('showdown_native_seed_v1'))return false;" +
                "var legacy=" + initialTeams + ";var added=false;" +
                "legacy.forEach(function(t){if(!t.export&&!t.packed)return;" +
                "if(Storage.teams.some(function(x){return x.name===t.name&&x.format===t.format;}))return;" +
                "Storage.teams.push({name:t.name,format:t.format,folder:'',team:t.packed||Storage.packTeam(Storage.importTeam(t.export))});added=true;});" +
                "localStorage.setItem('showdown_native_seed_v1','1');" +
                "if(added)Storage.saveTeams();return added;" +
                "}catch(e){return 'error:'+e.message;}})()";
        evaluateJavascript(script, result -> {
            if (closed) return;
            if ("true".equals(result)) {
                reload(); // Let the official team list render the newly imported teams.
            } else {
                if (result != null && result.contains("error:")) Log.e("OfficialTeambuilder", result);
                readTeams(this::schedulePoll);
            }
        });
    }

    private void readTeams(Runnable after) {
        String script = "(function(){if(!window.Storage||!Array.isArray(Storage.teams))return null;" +
                "return JSON.stringify(Storage.teams.map(function(t){return {name:t.name||'',format:t.format||''," +
                "packed:t.team||'',export:t.team?Storage.exportTeam(t.team):''};}));})()";
        evaluateJavascript(script, raw -> {
            if (!closed && raw != null && !raw.equals("null")) {
                try {
                    String json = String.valueOf(new JSONTokener(raw).nextValue());
                    if (!json.equals(lastSnapshot)) {
                        JSONArray rows = new JSONArray(json);
                        List<TeamStore.Team> teams = new ArrayList<>();
                        for (int i = 0; i < rows.length(); i++) {
                            JSONObject row = rows.getJSONObject(i);
                            teams.add(new TeamStore.Team(row.optString("name"), row.optString("format"),
                                    row.optString("export"), row.optString("packed")));
                        }
                        lastSnapshot = json;
                        onTeamsChanged.accept(teams);
                    }
                } catch (Exception error) { Log.e("OfficialTeambuilder", "Could not mirror teams", error); }
            }
            after.run();
        });
    }

    private void schedulePoll() { if (!closed) handler.postDelayed(this::poll, 1500); }

    void close() {
        closed = true;
        handler.removeCallbacksAndMessages(null);
        stopLoading();
        destroy();
    }
}
