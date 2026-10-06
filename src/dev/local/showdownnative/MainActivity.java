package dev.local.showdownnative;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.PorterDuff;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Html;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.BaseAdapter;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native battle controls over Showdown's documented public protocol. */
public final class MainActivity extends Activity {
    private final int background = Color.rgb(21, 28, 42);
    private final int card = Color.rgb(36, 48, 68);
    private final int accent = Color.rgb(85, 181, 245);
    private ShowdownSocket socket;
    private TeamStore teamStore;
    private LinearLayout root, page, actions, battleOptions, battleControls, topTabs, logDrawer;
    private TextView heading, connectionLabel, battleTitle, myPokemon, foePokemon, logView;
    private ImageView mySprite, foeSprite;
    private ImageView backgroundArt;
    private BattleBackdropView backdrop;
    private MoveEffectsView moveEffects;
    private OfficialBattleView officialBattle;
    private OfficialTeambuilderView officialTeambuilder;
    private TeamCatalog teamCatalog;
    private final FormatCatalog formatCatalog = new FormatCatalog();
    private TextView validationStatus;
    private boolean validationPending;
    private int validationSequence;
    private final List<String> battleHistory = new ArrayList<>();
    private static final class BattleSession {
        final String id;
        String title;
        final List<String> history = new ArrayList<>();
        JSONObject request;
        boolean ended;
        BattleSession(String id) { this.id = id; this.title = id; }
    }
    private final Map<String, BattleSession> battles = new LinkedHashMap<>();
    private TextView searchTimeView;
    private long searchStartedAt;
    private final Runnable searchTicker = new Runnable() {
        @Override public void run() {
            if (destroyed || searchStartedAt == 0) return;
            if (searchTimeView != null) searchTimeView.setText(searchTimeLabel());
            handler.postDelayed(this, 1000);
        }
    };
    private boolean officialRenderer = true;
    private ScrollView logScroll;
    private String challenge = "", username = "", room = "", searchFormat = "", selectedMatchFormat = "";
    private final StringBuilder log = new StringBuilder();
    private final LruCache<String, Bitmap> spriteCache = new LruCache<>(8);
    private final LruCache<String, Bitmap> backgroundCache = new LruCache<>(4);
    private final LruCache<String, byte[]> animationCache = new LruCache<String, byte[]>(4_000_000) {
        @Override protected int sizeOf(String key, byte[] value) { return value.length; }
    };
    private final Set<String> missingAnimations = new HashSet<>();
    private DexNames dexNames;
    private String mySideId = "";
    private String currentWeather = "";
    private final String[] sideDetails = {"", ""};
    private final String[] sideHealth = {"", ""};
    private JSONObject choiceRequest;
    private boolean connected, destroyed, timerOn, battleEnded, processingHistory;
    private String battleMechanic = "";
    private int tab = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService outgoing = Executors.newSingleThreadExecutor();
    private final StringBuilder moveInfo = new StringBuilder();
    private Runnable infoPopup;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        teamStore = new TeamStore(this);
        dexNames = new DexNames(this);
        buildShell();
        connect();
    }

    private void buildShell() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 0, 0, 0);
        root.setBackgroundColor(background);
        setContentView(root);
        heading = text("Pokémon Showdown", 24, Color.WHITE);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(heading, new LinearLayout.LayoutParams(-1, dp(40)));
        connectionLabel = text("正在连接…", 13, accent);
        root.addView(connectionLabel);
        topTabs = row();
        root.addView(topTabs);
        addButton(topTabs, "主页", () -> showBattle());
        addButton(topTabs, "编辑队伍", () -> showTeams());
        addButton(topTabs, "账号", this::accountMenu);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        root.addView(page, new LinearLayout.LayoutParams(-1, 0, 1));
        showBattle();
    }

    private void showBattle() {
        tab = 0;
        root.setPadding(0, 0, 0, 0);
        closeOfficialBattle();
        closeTeamCatalog();
        page.removeAllViews();
        boolean inBattle = !room.isEmpty();
        heading.setVisibility(View.GONE);
        connectionLabel.setVisibility(View.GONE);
        topTabs.setVisibility(View.GONE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        if (room.isEmpty()) {
            battleOptions = null;
            battleControls = null;
            actions = null;
            logDrawer = null;
            showHome();
            return;
        }
        LinearLayout bar = row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(bar, new LinearLayout.LayoutParams(-1, dp(44)));
        addButton(bar, "‹ 大厅", this::leaveToLobby);
        battleTitle = text(room, 15, Color.WHITE);
        battleTitle.setSingleLine(true);
        battleTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(battleTitle, new LinearLayout.LayoutParams(0, -2, 2));
        addButton(bar, "切换 · " + battles.size(), this::showBattleSwitcher);
        if (searchStartedAt != 0) {
            searchTimeView = text(searchTimeLabel(), 12, Color.rgb(246, 236, 185));
            bar.addView(searchTimeView);
        } else searchTimeView = null;
        addButton(bar, "记录", () -> logDrawer.setVisibility(logDrawer.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        addButton(bar, officialRenderer ? "官方画面" : "原生画面", () -> { officialRenderer = !officialRenderer; showBattle(); });
        FrameLayout layout = new FrameLayout(this);
        page.addView(layout, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout battleRow = row();
        layout.addView(battleRow, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout stage = new FrameLayout(this);
        stage.setClipChildren(false);
        battleRow.addView(stage, new LinearLayout.LayoutParams(0, -1, 1.9f));
        backdrop = new BattleBackdropView(this);
        backdrop.setWeather(currentWeather);
        stage.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        backgroundArt = new ImageView(this);
        backgroundArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        stage.addView(backgroundArt, new FrameLayout.LayoutParams(-1, -1));
        loadBattleBackground();
        mySprite = new ImageView(this);
        foeSprite = new ImageView(this);
        mySprite.setScaleType(ImageView.ScaleType.FIT_CENTER);
        foeSprite.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams mine = new FrameLayout.LayoutParams(dp(148), dp(150), Gravity.BOTTOM | Gravity.LEFT);
        mine.leftMargin = dp(12); mine.bottomMargin = dp(14);
        stage.addView(mySprite, mine);
        FrameLayout.LayoutParams foe = new FrameLayout.LayoutParams(dp(140), dp(133), Gravity.TOP | Gravity.RIGHT);
        foe.rightMargin = dp(12); foe.topMargin = dp(12);
        stage.addView(foeSprite, foe);
        moveEffects = new MoveEffectsView(this);
        stage.addView(moveEffects, new FrameLayout.LayoutParams(-1, -1));
        foePokemon = battleLabel("对手：等待对战信息");
        stage.addView(foePokemon, new FrameLayout.LayoutParams(-1, dp(31), Gravity.TOP));
        myPokemon = battleLabel("我方：等待对战信息");
        stage.addView(myPokemon, new FrameLayout.LayoutParams(-1, dp(31), Gravity.BOTTOM));
        myPokemon.setOnLongClickListener(v -> { showActivePokemonInfo(); return true; });
        mySprite.setOnLongClickListener(v -> { showActivePokemonInfo(); return true; });
        if (officialRenderer) {
            officialBattle = new OfficialBattleView(this);
            stage.addView(officialBattle, new FrameLayout.LayoutParams(-1, -1));
            officialBattle.addLines(battleHistory);
            officialBattle.setSide(mySideId);
            officialBattle.setOnLongClickListener(v -> { showActivePokemonInfo(); return true; });
        }
        battleControls = new LinearLayout(this);
        battleControls.setOrientation(LinearLayout.VERTICAL);
        battleControls.setPadding(dp(6), 0, 0, 0);
        battleControls.setBackgroundColor(card);
        battleRow.addView(battleControls, new LinearLayout.LayoutParams(0, -1, 1));
        TextView controlsTitle = text("选择行动", 16, Color.WHITE);
        controlsTitle.setPadding(dp(6), dp(3), 0, 0);
        battleControls.addView(controlsTitle);
        ScrollView actionScroll = new ScrollView(this);
        actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actionScroll.addView(actions);
        battleControls.addView(actionScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        battleOptions = row();
        battleControls.addView(battleOptions);
        renderBattleOptions();
        renderCombatants();
        logDrawer = new LinearLayout(this);
        logDrawer.setOrientation(LinearLayout.VERTICAL);
        logDrawer.setBackgroundColor(Color.rgb(25, 35, 53));
        FrameLayout.LayoutParams drawerParams = new FrameLayout.LayoutParams(dp(370), -1, Gravity.LEFT);
        layout.addView(logDrawer, drawerParams);
        addButton(logDrawer, "关闭战斗记录 ✕", () -> logDrawer.setVisibility(View.GONE));
        logScroll = new ScrollView(this);
        logView = text(log.toString(), 14, Color.rgb(230, 235, 245));
        logView.setTextIsSelectable(true);
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        logScroll.addView(logView);
        logDrawer.addView(logScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        logDrawer.setVisibility(View.GONE);
        renderChoices();
        updateBattleControls();
    }

    private void updateBattleControls() {
        if (battleControls != null) battleControls.setVisibility(canControlBattle() ? View.VISIBLE : View.GONE);
    }

    private void showHome() {
        FrameLayout screen = new FrameLayout(this);
        page.addView(screen, new LinearLayout.LayoutParams(-1, 0, 1));
        screen.addView(new RetroTitleView(this), new FrameLayout.LayoutParams(-1, -1));
        ImageView leftShark = titleSprite("title-garchomp.gif", "title-garchomp.png");
        ImageView rightShark = titleSprite("title-garchomp.gif", "title-garchomp.png");
        ImageView leftTiger = titleSprite("title-incineroar.gif", "title-incineroar.png");
        ImageView rightTiger = titleSprite("title-incineroar.gif", "title-incineroar.png");
        ImageView[] characters = {leftShark, rightShark, leftTiger, rightTiger};
        for (ImageView character : characters) screen.addView(character);
        rightShark.setScaleX(-1);
        rightTiger.setScaleX(-1);
        screen.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int width = r - l, height = b - t;
            if (width == 0 || height == 0) return;
            placeTitleSprite(leftShark, width, height, .50f, .51f, .54f);
            placeTitleSprite(rightShark, width, height, .79f, .43f, .47f);
            placeTitleSprite(leftTiger, width, height, .61f, .71f, .61f);
            placeTitleSprite(rightTiger, width, height, .88f, .73f, .53f);
        });
        for (int i = 0; i < characters.length; i++) {
            ImageView character = characters[i];
            ObjectAnimator bob = ObjectAnimator.ofFloat(character, "translationY", 0f, -dp(i % 2 == 0 ? 5 : 8), 0f);
            bob.setDuration(2300 + i * 380L);
            bob.setRepeatCount(ValueAnimator.INFINITE);
            bob.start();
            character.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) { }
                @Override public void onViewDetachedFromWindow(View view) {
                    bob.cancel();
                    Drawable art = character.getDrawable();
                    if (art instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) art).stop();
                }
            });
        }
        TextView logo = text("SHOWDOWN", 43, Color.WHITE);
        logo.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD));
        logo.setShadowLayer(dp(5), 0, dp(3), Color.BLACK);
        FrameLayout.LayoutParams logoParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        logoParams.leftMargin = dp(26); logoParams.topMargin = dp(16);
        screen.addView(logo, logoParams);
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams menuParams = new FrameLayout.LayoutParams(dp(260), -2, Gravity.BOTTOM | Gravity.LEFT);
        menuParams.leftMargin = dp(26); menuParams.bottomMargin = dp(17);
        screen.addView(menu, menuParams);
        TextView caption = text("随时开战  ·  随心组队", 14, Color.rgb(236, 240, 228));
        caption.setShadowLayer(dp(3), 0, dp(2), Color.BLACK);
        menu.addView(caption);
        titleAction(menu, "开始对战  ›", true, () -> showFormatPicker(false, option -> {
            if (option.preset) startSearch(null, option.id);
            else { selectedMatchFormat = option.id; pickTeam(); }
        }));
        LinearLayout shortcuts = row();
        menu.addView(shortcuts);
        titleAction(shortcuts, "我的队伍", false, () -> { selectedMatchFormat = ""; pickTeam(); });
        titleAction(shortcuts, "编辑队伍", false, this::showTeams);
        LinearLayout secondary = row();
        menu.addView(secondary);
        titleAction(secondary, "观战", false, () -> send("", "/query roomlist gen9randombattle"));
        titleAction(secondary, "账号", false, this::accountMenu);
        if (!searchFormat.isEmpty()) titleAction(menu, "取消匹配 · " + searchFormat, false,
                () -> { send("", "/cancelsearch"); searchFormat = ""; setSearchStarted(false); showBattle(); });
        if (searchStartedAt != 0) {
            searchTimeView = text(searchTimeLabel(), 14, Color.rgb(246, 236, 185));
            menu.addView(searchTimeView);
        } else searchTimeView = null;
        if (!battles.isEmpty()) titleAction(menu, "正在进行的对战 · " + battles.size() + "  ›", false,
                this::showBattleSwitcher);
        TextView status = text((connected ? "● " : "○ ") + (username.isEmpty() ? "连接中" : username),
                12, Color.rgb(230, 235, 224));
        status.setPadding(dp(3), dp(5), 0, 0);
        menu.addView(status);
        mySprite = null; foeSprite = null; backdrop = null; backgroundArt = null; moveEffects = null;
        myPokemon = null; foePokemon = null; battleTitle = null; logView = null; logScroll = null;
    }

    private ImageView titleSprite(String animatedAsset, String stillAsset) {
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        ColorMatrix mono = new ColorMatrix();
        mono.setSaturation(0);
        image.setColorFilter(new ColorMatrixColorFilter(mono));
        try {
            Drawable art = Build.VERSION.SDK_INT >= 28
                    ? ImageDecoder.decodeDrawable(ImageDecoder.createSource(getAssets(), animatedAsset))
                    : Drawable.createFromStream(getAssets().open(stillAsset), null);
            image.setImageDrawable(art);
            if (art instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) art).start();
        } catch (Exception error) {
            try { image.setImageDrawable(Drawable.createFromStream(getAssets().open(stillAsset), null)); }
            catch (Exception ignored) { }
        }
        return image;
    }

    private void placeTitleSprite(ImageView image, int width, int height, float centerX, float centerY, float heightFraction) {
        int size = Math.round(height * heightFraction);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size);
        params.leftMargin = Math.round(width * centerX - size / 2f);
        params.topMargin = Math.round(height * centerY - size / 2f);
        FrameLayout.LayoutParams old = (FrameLayout.LayoutParams) image.getLayoutParams();
        if (old != null && old.width == params.width && old.height == params.height
                && old.leftMargin == params.leftMargin && old.topMargin == params.topMargin) return;
        image.setLayoutParams(params);
    }

    private void titleAction(LinearLayout parent, String label, boolean primary, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(primary ? 18 : 13);
        button.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        button.setTextColor(primary ? Color.rgb(22, 30, 28) : Color.WHITE);
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(dp(8));
        fill.setColor(primary ? Color.rgb(246, 236, 185) : 0xB21A2428);
        fill.setStroke(dp(1), primary ? Color.rgb(255, 250, 216) : 0x77FFFFFF);
        button.setBackground(fill);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = parent.getOrientation() == LinearLayout.HORIZONTAL
                ? new LinearLayout.LayoutParams(0, dp(primary ? 48 : 37), 1)
                : new LinearLayout.LayoutParams(-1, dp(primary ? 48 : 37));
        params.setMargins(dp(2), dp(3), dp(2), dp(3));
        parent.addView(button, params);
    }

    private void showFormatHelp(FormatCatalog.Option option) {
        if (option == null) {
            new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle("对战格式是什么？")
                    .setMessage("“世代”决定宝可梦、招式和机制的范围。\n\n"
                            + "Ubers：高强度宝可梦分级；OU：标准常用竞技分级；UU、RU、NU、PU：按使用率继续细分；LC：未进化宝可梦；VGC：官方赛事双打。\n\n"
                            + "Mega 进化见第六、第七世代或支持它的全国图鉴规则；第八世代的 Max 指极巨化，是否开放取决于具体规则。\n\n"
                            + "具体禁用名单以官网当前规则和合法性校验为准。")
                    .setPositiveButton("知道了", null).show();
            return;
        }
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle(FormatCatalog.displayName(option))
                .setMessage(FormatCatalog.help(option))
                .setPositiveButton("知道了", null).show();
    }

    private void showFormatPicker(boolean teamOnly, java.util.function.Consumer<FormatCatalog.Option> onSelect) {
        if (formatCatalog.all().isEmpty()) {
            alert("正在从服务器获取当前可用规则，请稍后再试。");
            return;
        }
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(12), dp(5), dp(12), dp(5));
        layout.setBackgroundColor(Color.rgb(25, 37, 40));
        EditText search = pickerSearch("搜索世代或规则，例如：第9世代、OU、双打");
        layout.addView(search);
        ListView list = new ListView(this);
        layout.addView(list, new LinearLayout.LayoutParams(-1, dp(370)));
        List<FormatCatalog.Option> matches = new ArrayList<>();
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return matches.size(); }
            @Override public Object getItem(int position) { return matches.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View recycled, android.view.ViewGroup parent) {
                FormatCatalog.Option option = matches.get(position);
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(8), dp(3), dp(8), dp(3));
                LinearLayout labels = new LinearLayout(MainActivity.this);
                labels.setOrientation(LinearLayout.VERTICAL);
                row.addView(labels, new LinearLayout.LayoutParams(0, dp(53), 1));
                labels.addView(text(FormatCatalog.displayName(option), 16, Color.WHITE));
                labels.addView(text(option.section + " · " + option.id, 11, Color.rgb(180, 195, 188)));
                Button help = new Button(MainActivity.this);
                help.setText("?");
                help.setFocusable(false);
                help.setTextColor(Color.rgb(245, 236, 189));
                help.setBackgroundColor(Color.rgb(48, 65, 66));
                help.setContentDescription("查看" + option.name + "规则说明");
                help.setOnClickListener(v -> showFormatHelp(option));
                row.addView(help, new LinearLayout.LayoutParams(dp(42), dp(42)));
                return row;
            }
        };
        list.setAdapter(adapter);
        Runnable update = () -> {
            String query = search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
            matches.clear();
            for (FormatCatalog.Option option : formatCatalog.all()) {
                if ((!teamOnly && !option.searchable) || (teamOnly && option.preset)) continue;
                String haystack = (option.name + " " + option.id + " " + option.section + " "
                        + FormatCatalog.displayName(option)).toLowerCase(java.util.Locale.ROOT);
                if (haystack.contains(query)) matches.add(option);
            }
            adapter.notifyDataSetChanged();
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { update.run(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        update.run();
        AlertDialog picker = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle(teamOnly ? "选择队伍适用规则" : "选择对战规则")
                .setView(layout).setNegativeButton("返回", null).create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            FormatCatalog.Option selected = matches.get(position);
            picker.dismiss();
            onSelect.accept(selected);
        });
        picker.show();
    }

    private void showTeams() {
        tab = 1;
        root.setPadding(0, 0, 0, 0);
        closeOfficialBattle();
        heading.setVisibility(View.GONE);
        connectionLabel.setVisibility(View.GONE);
        topTabs.setVisibility(View.GONE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        if (officialTeambuilder != null) officialTeambuilder.syncNow(() -> {
            if (!destroyed && tab == 1) showLegacyTeams();
        });
        else showLegacyTeams();
    }

    private void showOfficialTeams() {
        tab = 1;
        root.setPadding(dp(14), dp(12), dp(14), dp(8));
        closeOfficialBattle();
        heading.setVisibility(View.VISIBLE);
        connectionLabel.setVisibility(View.VISIBLE);
        topTabs.setVisibility(View.VISIBLE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        page.removeAllViews();
        if (officialTeambuilder == null) {
            officialTeambuilder = new OfficialTeambuilderView(this, new ArrayList<>(teamStore.all()),
                    teams -> teamStore.replaceAll(teams));
        } else officialTeambuilder.focusTeambuilder();
        page.addView(officialTeambuilder, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void showLegacyTeams() {
        tab = 1;
        closeOfficialBattle();
        page.removeAllViews();
        List<TeamStore.Team> teams = teamStore.all();
        teamHeader("编辑队伍", teams.size() + " 支队伍 · 点配置进入阵容");
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(20), dp(5), dp(20), dp(20));
        scroll.addView(list);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout actions = row();
        list.addView(actions);
        titleAction(actions, "＋ 新建队伍", true, () -> editTeam(-1));
        titleAction(actions, "高级配置", false, this::showOfficialTeams);
        LinearLayout.LayoutParams createParams = new LinearLayout.LayoutParams(0, dp(46), 1);
        createParams.rightMargin = dp(8);
        actions.getChildAt(0).setLayoutParams(createParams);
        actions.getChildAt(1).setLayoutParams(new LinearLayout.LayoutParams(dp(116), dp(46)));
        if (teams.isEmpty()) {
            TextView empty = text("还没有队伍\n从新建队伍开始，选择规则并添加宝可梦。", 16, Color.rgb(211, 222, 215));
            empty.setGravity(Gravity.CENTER_VERTICAL);
            empty.setPadding(dp(18), dp(20), dp(18), dp(20));
            GradientDrawable emptyShape = new GradientDrawable();
            emptyShape.setColor(Color.rgb(29, 43, 46));
            emptyShape.setCornerRadius(dp(10));
            emptyShape.setStroke(dp(1), Color.rgb(76, 98, 97));
            empty.setBackground(emptyShape);
            LinearLayout.LayoutParams emptyParams = new LinearLayout.LayoutParams(-1, dp(110));
            emptyParams.topMargin = dp(12);
            list.addView(empty, emptyParams);
        }
        for (int i = 0; i < teams.size(); i++) {
            TeamStore.Team team = teams.get(i);
            list.addView(teamCard(team, () -> editExistingTeam(team), "配置队伍  ›", () -> new AlertDialog.Builder(this)
                    .setMessage("删除队伍「" + team.name + "」？")
                    .setPositiveButton("删除", (dialog, which) -> {
                        int currentIndex = teamStore.all().indexOf(team);
                        if (currentIndex >= 0) teamStore.remove(currentIndex);
                        showTeams();
                    })
                    .setNegativeButton("取消", null).show(), "删除", () -> editExistingTeam(team)));
        }
    }

    private void teamHeader(String title, String subtitle) {
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(9), dp(18), dp(9));
        header.setBackgroundColor(Color.rgb(25, 37, 40));
        page.addView(header, new LinearLayout.LayoutParams(-1, dp(72)));
        titleAction(header, "‹ 返回", false, () -> { if (tab == 3) showTeams(); else showBattle(); });
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(dp(92), dp(38));
        backParams.rightMargin = dp(16);
        header.getChildAt(0).setLayoutParams(backParams);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        header.addView(labels, new LinearLayout.LayoutParams(0, -2, 3));
        TextView heading = text(title, 24, Color.WHITE);
        heading.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        labels.addView(heading);
        labels.addView(text(subtitle, 12, Color.rgb(178, 192, 188)));
    }

    private View teamCard(TeamStore.Team team, Runnable primaryAction, String primaryLabel,
                          Runnable secondaryAction, String secondaryLabel, Runnable editAction) {
        LinearLayout cardView = new LinearLayout(this);
        cardView.setOrientation(LinearLayout.VERTICAL);
        cardView.setPadding(dp(15), dp(11), dp(15), dp(11));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.rgb(30, 44, 48));
        shape.setCornerRadius(dp(10));
        shape.setStroke(dp(1), Color.rgb(89, 107, 104));
        cardView.setBackground(shape);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.setMargins(0, dp(10), 0, 0);
        cardView.setLayoutParams(cardParams);
        LinearLayout titleRow = row();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        cardView.addView(titleRow);
        TextView name = text(team.name, 19, Color.WHITE);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleRow.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        List<String> members = teamSpecies(team);
        TextView count = text(members.size() + " / 6", 13, Color.rgb(246, 236, 185));
        count.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable badge = new GradientDrawable();
        badge.setColor(Color.rgb(51, 66, 59));
        badge.setCornerRadius(dp(6));
        count.setBackground(badge);
        titleRow.addView(count);
        FormatCatalog.Option option = formatCatalog.byId(team.format);
        cardView.addView(text(option == null ? team.format : FormatCatalog.displayName(option), 12,
                Color.rgb(197, 212, 203)));
        LinearLayout slots = row();
        slots.setGravity(Gravity.CENTER_VERTICAL);
        cardView.addView(slots, new LinearLayout.LayoutParams(-1, dp(60)));
        for (int slot = 0; slot < 6; slot++) {
            FrameLayout iconSlot = new FrameLayout(this);
            GradientDrawable slotShape = new GradientDrawable();
            slotShape.setColor(Color.rgb(23, 34, 37));
            slotShape.setCornerRadius(dp(7));
            iconSlot.setBackground(slotShape);
            LinearLayout.LayoutParams slotParams = new LinearLayout.LayoutParams(0, dp(50), 1);
            slotParams.setMargins(dp(2), 0, dp(2), 0);
            slots.addView(iconSlot, slotParams);
            iconSlot.setContentDescription(slot < members.size() ? "编辑" + members.get(slot) : "添加宝可梦");
            iconSlot.setOnClickListener(v -> editAction.run());
            if (slot < members.size()) {
                ImageView sprite = new ImageView(this);
                sprite.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iconSlot.addView(sprite, new FrameLayout.LayoutParams(-1, -1));
                showStaticSprite(sprite, members.get(slot).toLowerCase(java.util.Locale.ROOT)
                        .replace("♀", "f").replace("♂", "m").replaceAll("[^a-z0-9-]", ""), false);
            } else {
                TextView empty = text("＋", 23, Color.rgb(100, 119, 120));
                empty.setGravity(Gravity.CENTER);
                iconSlot.addView(empty, new FrameLayout.LayoutParams(-1, -1));
            }
        }
        LinearLayout buttons = row();
        cardView.addView(buttons);
        titleAction(buttons, primaryLabel, true, primaryAction);
        titleAction(buttons, secondaryLabel, false, secondaryAction);
        LinearLayout.LayoutParams primaryParams = new LinearLayout.LayoutParams(0, dp(46), 1);
        primaryParams.setMargins(dp(2), dp(5), dp(4), 0);
        buttons.getChildAt(0).setLayoutParams(primaryParams);
        LinearLayout.LayoutParams secondaryParams = new LinearLayout.LayoutParams(dp(92), dp(46));
        secondaryParams.setMargins(dp(4), dp(5), dp(2), 0);
        buttons.getChildAt(1).setLayoutParams(secondaryParams);
        return cardView;
    }

    private List<String> teamSpecies(TeamStore.Team team) {
        List<String> species = new ArrayList<>();
        String source = team.export == null ? "" : team.export.trim();
        if (source.isEmpty()) {
            String packed = team.packed == null ? "" : team.packed;
            for (String set : packed.split("\\]")) {
                if (set.isEmpty()) continue;
                String[] fields = set.split("\\|", -1);
                if (fields.length < 2) continue;
                species.add(fields[1].isEmpty() ? fields[0] : fields[1]);
                if (species.size() == 6) break;
            }
            return species;
        }
        for (String block : source.replace("\r", "").split("\n\\s*\n")) {
            if (block.trim().isEmpty()) continue;
            String header = block.split("\n", 2)[0].trim();
            int at = header.indexOf(" @ ");
            if (at >= 0) header = header.substring(0, at).trim();
            header = header.replaceFirst(" \\([MF]\\)$", "");
            java.util.regex.Matcher named = java.util.regex.Pattern.compile(".* \\(([^()]*)\\)$").matcher(header);
            if (named.matches()) header = named.group(1);
            if (!header.isEmpty()) species.add(header);
            if (species.size() == 6) break;
        }
        return species;
    }

    private boolean canControlBattle() {
        return connected && !room.isEmpty() && !battleEnded && (!mySideId.isEmpty()
                || choiceRequest != null && choiceRequest.optJSONObject("side") != null);
    }

    private void renderBattleOptions() {
        if (battleOptions == null || tab != 0) return;
        battleOptions.removeAllViews();
        updateBattleControls();
        if (!canControlBattle()) return;
        addButton(battleOptions, timerOn ? "关闭计时器" : "开启计时器", () -> {
            send(room, timerOn ? "/timer off" : "/timer on");
            append("已请求" + (timerOn ? "关闭" : "开启") + "计时器，等待服务器确认");
        });
        addButton(battleOptions, "投降", () -> new AlertDialog.Builder(this)
                .setTitle("确认投降？")
                .setMessage("此操作会立即结束本场对战，无法撤销。")
                .setPositiveButton("投降", (dialog, which) -> send(room, "/forfeit"))
                .setNegativeButton("继续对战", null).show());
    }

    private void editTeam(int index) {
        if (index < -1 || index >= teamStore.all().size()) {
            toast("队伍列表已更新，请重新选择");
            showTeams();
            return;
        }
        validationPending = false;
        validationSequence++;
        TeamStore.Team current = index < 0 ? new TeamStore.Team("新队伍", "gen9ou", "") : teamStore.all().get(index);
        tab = 3;
        root.setPadding(0, 0, 0, 0);
        heading.setVisibility(View.GONE);
        connectionLabel.setVisibility(View.GONE);
        topTabs.setVisibility(View.GONE);
        page.removeAllViews();
        teamHeader(index < 0 ? "创建队伍" : "配置队伍", "挑选宝可梦与招式，保存后可在“我的队伍”出战");
        LinearLayout columns = row();
        columns.setPadding(dp(18), dp(8), dp(18), dp(12));
        page.addView(columns, new LinearLayout.LayoutParams(-1, 0, 1));
        ScrollView leftScroll = new ScrollView(this);
        columns.addView(leftScroll, new LinearLayout.LayoutParams(0, -1, 0.85f));
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(10), dp(4), dp(18), dp(10));
        leftScroll.addView(form);
        EditText name = field("队伍名称", current.name, false);
        EditText format = field("单打格式 ID，例如 gen9ou", current.format, false);
        format.setFocusable(false);
        format.setClickable(true);
        format.setOnClickListener(v -> showFormatPicker(true, option -> format.setText(option.id)));
        EditText export = field("粘贴 Showdown 标准导出文本", current.export, true);
        styleTeamField(name);
        styleTeamField(format);
        styleTeamField(export);
        name.setSingleLine(true);
        format.setSingleLine(true);
        form.addView(text("队伍名称", 13, Color.rgb(189, 204, 195)));
        form.addView(name);
        LinearLayout formatHeading = row();
        formatHeading.setGravity(Gravity.CENTER_VERTICAL);
        form.addView(formatHeading);
        formatHeading.addView(text("对战格式", 13, Color.rgb(189, 204, 195)),
                new LinearLayout.LayoutParams(0, -2, 1));
        Button help = new Button(this);
        help.setText("?");
        help.setOnClickListener(v -> showFormatHelp(formatCatalog.byId(format.getText().toString())));
        formatHeading.addView(help, new LinearLayout.LayoutParams(dp(40), dp(34)));
        form.addView(format);
        titleAction(form, "选择对战格式  ▾", false,
                () -> showFormatPicker(true, option -> format.setText(option.id)));
        titleAction(form, "保存队伍", true, () -> {
            try {
                String source = export.getText().toString().trim();
                TeamStore.pack(source);
                String chosenFormat = format.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
                if (!chosenFormat.matches("[a-z0-9]+")) throw new IllegalArgumentException("格式 ID 只能包含英文字母和数字");
                String chosenName = name.getText().toString().trim();
                if (chosenName.isEmpty()) chosenName = "未命名队伍";
                teamStore.put(index, new TeamStore.Team(chosenName, chosenFormat, source));
                showTeams();
            } catch (Exception error) { alert(error.getMessage()); }
        });
        validationStatus = text("官方合法性：尚未校验", 13, Color.rgb(189, 204, 195));
        form.addView(validationStatus);
        titleAction(form, "官方合法性校验", false,
                () -> validateTeam(format.getText().toString().trim(), export.getText().toString()));
        titleAction(form, "官网完整配置工具", false, this::showOfficialTeams);
        ScrollView rightScroll = new ScrollView(this);
        rightScroll.setFillViewport(true);
        columns.addView(rightScroll, new LinearLayout.LayoutParams(0, -1, 1.5f));
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(13), dp(6), dp(4), dp(14));
        rightScroll.addView(right);
        right.addView(text("出战阵容   ·   点击空位添加宝可梦", 20, Color.WHITE));
        LinearLayout preview = new LinearLayout(this);
        preview.setOrientation(LinearLayout.VERTICAL);
        right.addView(preview);
        renderTeamPreview(preview, export.getText().toString(), export, format);
        titleAction(right, "导入或修改详细配置文本  ▾", false,
                () -> export.setVisibility(export.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        right.addView(export, new LinearLayout.LayoutParams(-1, dp(180)));
        export.setVisibility(View.GONE);
        TextWatcher invalidate = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                validationPending = false;
                validationSequence++;
                validationStatus.setText("官方合法性：内容已更改，请重新校验");
                renderTeamPreview(preview, export.getText().toString(), export, format);
            }
            @Override public void afterTextChanged(Editable s) { }
        };
        format.addTextChangedListener(invalidate);
        export.addTextChangedListener(invalidate);
        leftScroll.setFocusableInTouchMode(true);
        leftScroll.requestFocus();
        leftScroll.post(() -> leftScroll.scrollTo(0, 0));
    }

    private void editExistingTeam(TeamStore.Team team) {
        int index = teamStore.all().indexOf(team);
        if (index < 0) { toast("队伍列表已更新，请重新选择"); showTeams(); return; }
        editTeam(index);
    }

    private void styleTeamField(EditText field) {
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(Color.rgb(150, 166, 162));
        field.setPadding(dp(12), dp(9), dp(12), dp(9));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(26, 38, 40));
        background.setCornerRadius(dp(7));
        background.setStroke(dp(1), Color.rgb(91, 111, 107));
        field.setBackground(background);
    }

    private EditText pickerSearch(String hint) {
        EditText search = field(hint, "", false);
        styleTeamField(search);
        search.setSingleLine(true);
        return search;
    }

    private void renderTeamPreview(LinearLayout preview, String source, EditText export, EditText format) {
        preview.removeAllViews();
        List<String> species = teamSpecies(new TeamStore.Team("", "", source));
        LinearLayout slots = null;
        for (int i = 0; i < 6; i++) {
            if (i % 3 == 0) {
                slots = row();
                preview.addView(slots, new LinearLayout.LayoutParams(-1, dp(89)));
            }
            FrameLayout cell = new FrameLayout(this);
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(Color.rgb(30, 42, 44));
            shape.setCornerRadius(dp(6));
            shape.setStroke(dp(1), Color.rgb(100, 116, 109));
            cell.setBackground(shape);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(83), 1);
            params.setMargins(dp(4), dp(3), dp(4), dp(3));
            slots.addView(cell, params);
            if (i < species.size()) {
                ImageView icon = new ImageView(this);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(-1, dp(57), Gravity.TOP);
                iconParams.setMargins(dp(4), dp(2), dp(4), 0);
                cell.addView(icon, iconParams);
                showStaticSprite(icon, species.get(i).toLowerCase(java.util.Locale.ROOT)
                        .replace("♀", "f").replace("♂", "m").replaceAll("[^a-z0-9-]", ""), false);
                TextView label = text((i + 1) + "   " + species.get(i), 12, Color.rgb(244, 238, 212));
                label.setGravity(Gravity.CENTER);
                label.setBackgroundColor(0xCC182327);
                cell.addView(label, new FrameLayout.LayoutParams(-1, dp(24), Gravity.BOTTOM));
                final int setIndex = i;
                final String speciesName = species.get(i);
                cell.setContentDescription("编辑" + speciesName + "配置");
                cell.setOnClickListener(v -> editPokemonSet(export, dexNames.speciesByEnglish(speciesName),
                        format.getText().toString().trim(), setIndex));
            } else {
                TextView empty = text("＋\n添加宝可梦", 16, Color.rgb(218, 224, 211));
                empty.setGravity(Gravity.CENTER);
                cell.addView(empty, new FrameLayout.LayoutParams(-1, -1));
                cell.setContentDescription("添加宝可梦");
                cell.setOnClickListener(v -> chooseSpecies(export, format.getText().toString().trim()));
            }
        }
        preview.addView(text(species.size() + " / 6 只宝可梦", 13, Color.rgb(192, 205, 197)));
    }

    private void chooseSpecies(EditText export, String format) {
        ensureTeamCatalog();
        AlertDialog loading = new AlertDialog.Builder(this).setMessage("正在读取当前规则的宝可梦…")
                .setNegativeButton("取消", null).create();
        final boolean[] finished = {false};
        loading.setOnDismissListener(ignored -> finished[0] = true);
        loading.show();
        handler.postDelayed(() -> {
            if (finished[0] || destroyed) return;
            finished[0] = true;
            loading.dismiss();
            alert("宝可梦列表加载超时，请检查网络后重试。");
        }, 25000);
        teamCatalog.getSpecies(format, (options, error) -> {
            if (finished[0] || destroyed) return;
            finished[0] = true;
            loading.dismiss();
            if (error != null || options == null || options.isEmpty()) {
                alert(error == null ? "当前规则没有找到可选宝可梦" : error);
                return;
            }
            showSpeciesPicker(export, format, options);
        });
    }

    private void showSpeciesPicker(EditText export, String format, List<DexNames.Species> options) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(14), dp(8), dp(14), dp(8));
        layout.setBackgroundColor(Color.rgb(25, 37, 40));
        EditText search = pickerSearch("搜索中文名或英文名");
        layout.addView(search);
        layout.addView(text(options.size() + " 只候选 · 最终以官方合法性校验为准", 12,
                Color.rgb(180, 195, 188)));
        if (format.matches("^gen[67].*") || format.contains("natdex") || format.contains("nationaldex"))
            layout.addView(text("可直接选 Mega 形态，系统会配普通形态和进化石；战斗中再启动进化。", 12,
                    Color.rgb(180, 195, 188)));
        ListView list = new ListView(this);
        layout.addView(list, new LinearLayout.LayoutParams(-1, dp(340)));
        List<DexNames.Species> matches = new ArrayList<>();
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return matches.size(); }
            @Override public Object getItem(int position) { return matches.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View recycled, android.view.ViewGroup parent) {
                LinearLayout row;
                if (recycled instanceof LinearLayout) row = (LinearLayout) recycled;
                else {
                    row = new LinearLayout(MainActivity.this);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(8), dp(3), dp(8), dp(3));
                    ImageView icon = new ImageView(MainActivity.this);
                    icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    row.addView(icon, new LinearLayout.LayoutParams(dp(58), dp(58)));
                    TextView label = text("", 16, Color.WHITE);
                    label.setPadding(dp(10), 0, 0, 0);
                    row.addView(label);
                }
                DexNames.Species species = matches.get(position);
                showStaticSprite((ImageView) row.getChildAt(0), (species.formEnglish.isEmpty() ? species.english : species.formEnglish).toLowerCase(java.util.Locale.ROOT)
                        .replace("♀", "f").replace("♂", "m").replaceAll("[^a-z0-9-]", ""), false);
                ((TextView) row.getChildAt(1)).setText(species.label());
                return row;
            }
        };
        list.setAdapter(adapter);
        Runnable update = () -> {
            String query = search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
            matches.clear();
            for (DexNames.Species one : options) if (query.isEmpty()
                    || one.english.toLowerCase(java.util.Locale.ROOT).contains(query)
                    || one.formEnglish.toLowerCase(java.util.Locale.ROOT).contains(query)
                    || one.chinese.contains(query)) matches.add(one);
            adapter.notifyDataSetChanged();
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { update.run(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        update.run();
        AlertDialog picker = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle("选择宝可梦")
                .setView(layout).setNegativeButton("返回", null).create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            DexNames.Species selected = matches.get(position);
            picker.dismiss();
            editPokemonSet(export, selected, format);
        });
        picker.show();
    }

    private void editPokemonSet(EditText export, DexNames.Species species, String format) {
        editPokemonSet(export, species, format, -1);
    }

    private void editPokemonSet(EditText export, DexNames.Species species, String format, int editIndex) {
        String[] existingBlocks = export.getText().toString().trim().split("\\n\\s*\\n");
        String existing = editIndex >= 0 && editIndex < existingBlocks.length ? existingBlocks[editIndex] : "";
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(14), dp(8), dp(14), dp(4));
        form.setBackgroundColor(Color.rgb(25, 37, 40));
        scroll.addView(form);
        String header = existing.isEmpty() ? species.english : existing.split("\\n", 2)[0].split(" @ ", 2)[0];
        String oldGender = header.endsWith(" (M)") ? "M" : header.endsWith(" (F)") ? "F" : "";
        String cleanHeader = oldGender.isEmpty() ? header : header.substring(0, header.length() - 4);
        String oldNickname = cleanHeader.equals(species.english) ? "" : cleanHeader.endsWith(" (" + species.english + ")")
                ? cleanHeader.substring(0, cleanHeader.length() - species.english.length() - 3) : "";
        EditText nickname = field("昵称（可留空）", oldNickname, false);
        EditText gender = field("性别 · 点击选择", oldGender, false);
        styleTeamField(nickname); styleTeamField(gender);
        gender.setFocusable(false); gender.setClickable(true);
        gender.setOnClickListener(v -> showNamePicker("选择性别", java.util.Arrays.asList("雄性 · M", "雌性 · F"), gender));
        EditText item = field("道具（可留空，英文）", species.requiredItem, false);
        EditText ability = field("特性（可留空，英文）", "", false);
        styleTeamField(item);
        styleTeamField(ability);
        if (!existing.isEmpty()) {
            String[] lines = existing.split("\\n");
            int at = lines[0].indexOf(" @ ");
            if (at >= 0) item.setText(lines[0].substring(at + 3).trim());
            for (String line : lines) if (line.startsWith("Ability: "))
                ability.setText(line.substring(9).trim());
        }
        item.setHint("道具 · 点击选择");
        ability.setHint("特性 · 点击选择");
        item.setFocusable(false); item.setClickable(true);
        ability.setFocusable(false); ability.setClickable(true);
        item.setOnClickListener(v -> chooseSetValue("选择道具", species, format, item, true));
        ability.setOnClickListener(v -> chooseSetValue("选择特性", species, format, ability, false));
        form.addView(text(species.label(), 17, Color.WHITE));
        if (!species.requiredItem.isEmpty()) form.addView(text("这只 Mega 在对战开始时仍是普通形态；选招时点 Mega 才会进化。", 12,
                Color.rgb(180, 195, 188)));
        form.addView(nickname); form.addView(gender);
        form.addView(item); form.addView(ability);
        form.addView(text("能力与性格", 15, Color.WHITE));
        EditText nature = field("性格 · 点击选择", setLine(existing, " Nature", ""), false);
        styleTeamField(nature);
        nature.setFocusable(false); nature.setClickable(true);
        nature.setOnClickListener(v -> showNaturePicker(nature));
        form.addView(nature);
        EditText level = numericField("等级", setLine(existing, "Level: ", "100"));
        form.addView(level);
        EditText happiness = numericField("亲密度", setLine(existing, "Happiness: ", "255"));
        form.addView(happiness);
        CheckBox shiny = new CheckBox(this);
        shiny.setText("闪光宝可梦"); shiny.setTextColor(Color.WHITE);
        shiny.setChecked(existing.contains("Shiny: Yes"));
        form.addView(shiny);
        String[] statKeys = {"HP", "Atk", "Def", "SpA", "SpD", "Spe"};
        String[] statNames = {"生命", "攻击", "防御", "特攻", "特防", "速度"};
        int[] oldEvs = readStats(setLine(existing, "EVs: ", ""), statKeys, 0);
        int[] oldIvs = readStats(setLine(existing, "IVs: ", ""), statKeys, 31);
        EditText[] evFields = new EditText[6], ivFields = new EditText[6];
        TextView evTotal = text("", 12, Color.rgb(180, 195, 188));
        form.addView(text("努力值 EV / 个体值 IV", 13, Color.WHITE));
        for (int stat = 0; stat < 6; stat++) {
            LinearLayout statRow = row(); statRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView label = text(statNames[stat], 13, Color.WHITE);
            statRow.addView(label, new LinearLayout.LayoutParams(dp(48), dp(46)));
            evFields[stat] = numericField("EV", Integer.toString(oldEvs[stat]));
            ivFields[stat] = numericField("IV", Integer.toString(oldIvs[stat]));
            statRow.addView(evFields[stat], new LinearLayout.LayoutParams(0, dp(46), 1));
            statRow.addView(ivFields[stat], new LinearLayout.LayoutParams(0, dp(46), 1));
            form.addView(statRow);
            evFields[stat].addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    int sum = 0;
                    for (EditText field : evFields) if (field != null) sum += safeNumber(field.getText().toString());
                    evTotal.setText("努力值合计 " + sum + " / 510");
                    evTotal.setTextColor(sum > 510 ? Color.rgb(255, 116, 100) : Color.rgb(180, 195, 188));
                }
                @Override public void afterTextChanged(Editable s) { }
            });
        }
        int initialTotal = 0; for (int ev : oldEvs) initialTotal += ev;
        evTotal.setText("努力值合计 " + initialTotal + " / 510");
        form.addView(evTotal);
        EditText tera = field("太晶属性 · 点击选择", setLine(existing, "Tera Type: ", ""), false);
        styleTeamField(tera); tera.setFocusable(false); tera.setClickable(true);
        tera.setOnClickListener(v -> showTeraPicker(tera));
        if (format.startsWith("gen9")) form.addView(tera);
        form.addView(text("四个招式", 15, Color.WHITE));
        EditText[] moves = new EditText[4];
        List<String> existingMoves = new ArrayList<>();
        for (String line : existing.split("\\n")) if (line.trim().startsWith("- "))
            existingMoves.add(line.trim().substring(2).trim());
        for (int slot = 0; slot < moves.length; slot++) {
            EditText move = field("第 " + (slot + 1) + " 个招式 · 点击选择", "", false);
            styleTeamField(move);
            if (slot < existingMoves.size()) move.setText(existingMoves.get(slot));
            move.setFocusable(false);
            move.setClickable(true);
            move.setOnClickListener(v -> chooseMoveForSlot(species, format, move));
            moves[slot] = move;
            form.addView(move);
        }
        form.addView(text("点招式栏选择可学招式，列表中直接显示效果；保存前请进行官方合法性校验。", 12,
                Color.rgb(180, 195, 188)));
        AlertDialog dialog = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle(editIndex < 0 ? "添加到队伍" : "编辑宝可梦")
                .setView(scroll).setPositiveButton(editIndex < 0 ? "添加" : "保存配置", null)
                .setNegativeButton("返回", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int moveCount = 0;
            for (EditText move : moves) if (!move.getText().toString().trim().isEmpty()) moveCount++;
            if (moveCount == 0) { toast("请至少选择一个招式"); return; }
            int levelValue = safeNumber(level.getText().toString());
            if (levelValue < 1 || levelValue > 100) { toast("等级须在 1 到 100 之间"); return; }
            int happinessValue = safeNumber(happiness.getText().toString());
            if (happinessValue < 0 || happinessValue > 255) { toast("亲密度须在 0 到 255 之间"); return; }
            int[] evs = new int[6], ivs = new int[6]; int evSum = 0;
            for (int i = 0; i < 6; i++) {
                evs[i] = safeNumber(evFields[i].getText().toString());
                ivs[i] = safeNumber(ivFields[i].getText().toString());
                if (evs[i] > 252 || ivs[i] > 31) { toast(statNames[i] + "的 EV 或 IV 超出范围"); return; }
                evSum += evs[i];
            }
            if (evSum > 510) { toast("努力值合计不能超过 510"); return; }
            String chosenNickname = nickname.getText().toString().trim();
            StringBuilder set = new StringBuilder(chosenNickname.isEmpty() || chosenNickname.equalsIgnoreCase(species.english)
                    ? species.english : chosenNickname + " (" + species.english + ")");
            String chosenGender = gender.getText().toString().trim();
            if (!chosenGender.isEmpty()) set.append(" (").append(chosenGender).append(")");
            if (!item.getText().toString().trim().isEmpty()) set.append(" @ ").append(item.getText().toString().trim());
            if (!ability.getText().toString().trim().isEmpty()) set.append("\nAbility: ").append(ability.getText().toString().trim());
            if (evSum > 0) set.append("\nEVs: ").append(statLine(evs, statKeys, 0));
            if (!allStats(ivs, 31)) set.append("\nIVs: ").append(statLine(ivs, statKeys, 31));
            if (!nature.getText().toString().trim().isEmpty())
                set.append("\n").append(nature.getText().toString().trim()).append(" Nature");
            if (levelValue != 100) set.append("\nLevel: ").append(levelValue);
            if (happinessValue != 255) set.append("\nHappiness: ").append(happinessValue);
            if (shiny.isChecked()) set.append("\nShiny: Yes");
            if (format.startsWith("gen9") && !tera.getText().toString().trim().isEmpty())
                set.append("\nTera Type: ").append(tera.getText().toString().trim());
            if (!existing.isEmpty()) for (String line : existing.split("\\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("- ") || trimmed.startsWith("Ability: ")
                        || trimmed.startsWith("EVs: ") || trimmed.startsWith("IVs: ")
                        || trimmed.endsWith(" Nature") || trimmed.startsWith("Level: ")
                        || trimmed.startsWith("Happiness: ") || trimmed.equalsIgnoreCase("Shiny: Yes")
                        || (format.startsWith("gen9") && trimmed.startsWith("Tera Type: "))
                        || line.equals(existing.split("\\n", 2)[0])) continue;
                set.append("\n").append(line);
            }
            for (EditText move : moves) {
                String choice = move.getText().toString().trim();
                if (choice.isEmpty()) continue;
                set.append("\n- ").append(dexNames.englishMove(choice));
            }
            List<String> blocks = new ArrayList<>();
            for (String block : existingBlocks) if (!block.trim().isEmpty()) blocks.add(block.trim());
            if (editIndex >= 0 && editIndex < blocks.size()) blocks.set(editIndex, set.toString());
            else blocks.add(set.toString());
            String candidate = String.join("\n\n", blocks);
            try { TeamStore.pack(candidate); }
            catch (IllegalArgumentException error) { alert(error.getMessage()); return; }
            export.setText(candidate); export.setSelection(candidate.length());
            dialog.dismiss();
        }));
        dialog.show();
    }

    private EditText numericField(String hint, String value) {
        EditText field = field(hint, value, false);
        styleTeamField(field);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setSelectAllOnFocus(true);
        return field;
    }

    private static int safeNumber(String value) {
        try { return Integer.parseInt(value.trim()); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static String setLine(String block, String marker, String fallback) {
        for (String line : block.split("\\n")) {
            String trimmed = line.trim();
            if (marker.equals(" Nature") && trimmed.endsWith(marker))
                return trimmed.substring(0, trimmed.length() - marker.length());
            if (!marker.equals(" Nature") && trimmed.startsWith(marker))
                return trimmed.substring(marker.length()).trim();
        }
        return fallback;
    }

    private static int[] readStats(String line, String[] names, int fallback) {
        int[] result = new int[names.length];
        java.util.Arrays.fill(result, fallback);
        for (String part : line.split(" / ")) {
            java.util.regex.Matcher match = java.util.regex.Pattern.compile("^(\\d+)\\s+(HP|Atk|Def|SpA|SpD|Spe)$",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(part.trim());
            if (!match.matches()) continue;
            for (int i = 0; i < names.length; i++)
                if (names[i].equalsIgnoreCase(match.group(2))) result[i] = safeNumber(match.group(1));
        }
        return result;
    }

    private static boolean allStats(int[] values, int expected) {
        for (int value : values) if (value != expected) return false;
        return true;
    }

    private static String statLine(int[] values, String[] names, int fallback) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < names.length; i++) if (values[i] != fallback)
            parts.add(values[i] + " " + names[i]);
        return String.join(" / ", parts);
    }

    private void showNaturePicker(EditText target) {
        String[] english = {"Hardy", "Lonely", "Brave", "Adamant", "Naughty", "Bold", "Docile", "Relaxed",
                "Impish", "Lax", "Timid", "Hasty", "Serious", "Jolly", "Naive", "Modest", "Mild", "Quiet",
                "Bashful", "Rash", "Calm", "Gentle", "Sassy", "Careful", "Quirky"};
        String[] chinese = {"勤奋", "怕寂寞", "勇敢", "固执", "顽皮", "大胆", "坦率", "悠闲", "淘气", "乐天",
                "胆小", "急躁", "认真", "爽朗", "天真", "内敛", "慢吞吞", "冷静", "害羞", "马虎", "温和",
                "温顺", "自大", "慎重", "浮躁"};
        List<String> names = new ArrayList<>();
        for (int i = 0; i < english.length; i++) names.add(chinese[i] + " · " + english[i]);
        showNamePicker("选择性格", names, target);
    }

    private void showTeraPicker(EditText target) {
        String[] english = {"Normal", "Fire", "Water", "Electric", "Grass", "Ice", "Fighting", "Poison", "Ground",
                "Flying", "Psychic", "Bug", "Rock", "Ghost", "Dragon", "Dark", "Steel", "Fairy", "Stellar"};
        String[] chinese = {"一般", "火", "水", "电", "草", "冰", "格斗", "毒", "地面", "飞行", "超能力",
                "虫", "岩石", "幽灵", "龙", "恶", "钢", "妖精", "星晶"};
        List<String> names = new ArrayList<>();
        for (int i = 0; i < english.length; i++) names.add(chinese[i] + " · " + english[i]);
        showNamePicker("选择太晶属性", names, target);
    }

    private void chooseMoveForSlot(DexNames.Species species, String format, EditText slot) {
        ensureTeamCatalog();
        AlertDialog loading = new AlertDialog.Builder(this).setMessage("正在读取官网可学招式…")
                .setNegativeButton("取消", null).create();
        final boolean[] finished = {false};
        loading.setOnDismissListener(ignored -> finished[0] = true);
        loading.show();
        handler.postDelayed(() -> {
            if (finished[0] || destroyed) return;
            finished[0] = true;
            loading.dismiss();
            alert("加载招式列表超时。你仍可手动填写英文招式，再进行官方合法性校验。");
        }, 25000);
        teamCatalog.getMoves(species.english, format, (candidates, error) -> {
            if (finished[0] || destroyed) return;
            finished[0] = true;
            loading.dismiss();
            if (error != null || candidates == null || candidates.isEmpty()) {
                alert(error == null ? "当前格式没有找到可学招式；请检查格式 ID 或手动填写。" : error);
                return;
            }
            teamCatalog.getMoveSummaries(candidates, format,
                    descriptions -> showSingleMovePicker(species, candidates, descriptions, slot));
        });
    }

    private void ensureTeamCatalog() {
        if (teamCatalog != null) return;
        teamCatalog = new TeamCatalog(this);
        teamCatalog.setVisibility(View.INVISIBLE);
        root.addView(teamCatalog, new LinearLayout.LayoutParams(dp(1), dp(1)));
    }

    private void chooseSetValue(String title, DexNames.Species species, String format,
                                EditText target, boolean item) {
        ensureTeamCatalog();
        AlertDialog loading = new AlertDialog.Builder(this).setMessage("正在读取官网" + (item ? "道具" : "特性") + "列表…")
                .setNegativeButton("取消", null).create();
        final boolean[] finished = {false};
        loading.setOnDismissListener(ignored -> finished[0] = true);
        loading.show();
        handler.postDelayed(() -> {
            if (finished[0] || destroyed) return;
            finished[0] = true;
            loading.dismiss();
            alert("官网配置列表加载超时，请检查网络后重试。");
        }, 25000);
        TeamCatalog.Callback callback = (names, error) -> {
            if (finished[0] || destroyed) return;
            finished[0] = true;
            loading.dismiss();
            if (error != null || names == null || names.isEmpty()) {
                alert(error == null ? "当前宝可梦没有可选" + (item ? "道具" : "特性") : error);
                return;
            }
            showNamePicker(title, names, target);
        };
        if (item) teamCatalog.getItems(format, species.english, callback);
        else teamCatalog.getAbilities(species.english, callback);
    }

    private void showNamePicker(String title, List<String> names, EditText target) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(12), dp(6), dp(12), 0);
        layout.setBackgroundColor(Color.rgb(25, 37, 40));
        EditText search = pickerSearch("搜索名称");
        layout.addView(search);
        ListView list = new ListView(this);
        layout.addView(list, new LinearLayout.LayoutParams(-1, dp(340)));
        List<String> visible = new ArrayList<>();
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return visible.size(); }
            @Override public Object getItem(int position) { return visible.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View recycled, android.view.ViewGroup parent) {
                TextView label = text(visible.get(position), 16, Color.WHITE);
                label.setPadding(dp(14), dp(12), dp(8), dp(12));
                return label;
            }
        };
        list.setAdapter(adapter);
        Runnable update = () -> {
            String query = search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
            visible.clear();
            visible.add("无");
            for (String name : names) if (name.toLowerCase(java.util.Locale.ROOT).contains(query)) visible.add(name);
            adapter.notifyDataSetChanged();
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { update.run(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        update.run();
        AlertDialog picker = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle(title)
                .setView(layout).setNegativeButton("返回", null).create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            String value = visible.get(position);
            int separator = value.lastIndexOf(" · ");
            target.setText(value.equals("无") ? "" : separator < 0 ? value : value.substring(separator + 3));
            picker.dismiss();
        });
        picker.show();
    }

    private void showSingleMovePicker(DexNames.Species species, List<String> candidates,
                                      JSONObject descriptions, EditText slot) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(12), dp(6), dp(12), 0);
        layout.setBackgroundColor(Color.rgb(25, 37, 40));
        EditText search = pickerSearch("搜索中文或英文招式");
        layout.addView(search);
        ListView list = new ListView(this);
        layout.addView(list, new LinearLayout.LayoutParams(-1, dp(370)));
        List<String> visible = new ArrayList<>();
        AlertDialog picker = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle(species.chinese + " · 选择招式")
                .setView(layout).setNegativeButton("返回", null).create();
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return visible.size(); }
            @Override public Object getItem(int position) { return visible.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View recycled, android.view.ViewGroup parent) {
                String moveId = visible.get(position);
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(12), dp(6), dp(12), dp(6));
                row.addView(text(dexNames.move(moveId) + " · " + dexNames.moveEnglish(moveId), 15, Color.WHITE));
                String effect = descriptions.optString(moveId, "");
                row.addView(text(effect.isEmpty() ? "暂无官方中文效果说明" : effect, 12,
                        Color.rgb(180, 195, 188)));
                return row;
            }
        };
        list.setAdapter(adapter);
        Runnable update = () -> {
            visible.clear();
            String query = search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
            for (String id : candidates) {
                String english = dexNames.moveEnglish(id), chinese = dexNames.move(id);
                if (!query.isEmpty() && !id.contains(query) && !english.toLowerCase(java.util.Locale.ROOT).contains(query)
                        && !chinese.contains(query)) continue;
                visible.add(id);
            }
            adapter.notifyDataSetChanged();
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { update.run(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        update.run();
        list.setOnItemClickListener((parent, view, position, id) -> {
            slot.setText(dexNames.moveEnglish(visible.get(position)));
            picker.dismiss();
        });
        picker.show();
    }

    private void validateTeam(String format, String source) {
        if (!connected) { alert("需要连接官方服务器才能校验队伍"); return; }
        if (validationPending) { toast("正在等待上一次校验结果"); return; }
        if (!format.matches("[a-z0-9]+")) { alert("请填写有效的对战格式 ID"); return; }
        String packed;
        try { packed = TeamStore.pack(source); }
        catch (IllegalArgumentException error) { alert(error.getMessage()); return; }
        validationPending = true;
        int sequence = ++validationSequence;
        if (validationStatus != null) validationStatus.setText("官方合法性：校验中…");
        send("", "/utm " + packed);
        send("", "/vtm " + format);
        handler.postDelayed(() -> {
            if (!validationPending || sequence != validationSequence) return;
            validationPending = false;
            if (validationStatus != null) validationStatus.setText("官方合法性：校验超时，请重试");
        }, 20000);
    }

    private void pickTeam() {
        if (officialTeambuilder != null) {
            officialTeambuilder.syncNow(this::showTeamPicker);
        } else showTeamPicker();
    }

    private void showTeamPicker() {
        tab = 2;
        root.setPadding(0, 0, 0, 0);
        closeOfficialBattle();
        heading.setVisibility(View.GONE);
        connectionLabel.setVisibility(View.GONE);
        topTabs.setVisibility(View.GONE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        page.removeAllViews();
        FormatCatalog.Option selectedFormat = formatCatalog.byId(selectedMatchFormat);
        String formatName = selectedFormat == null ? "选一支已配置的队伍参加匹配" :
                "当前规则：" + FormatCatalog.displayName(selectedFormat);
        teamHeader("我的队伍", formatName);
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(20), dp(6), dp(20), dp(20));
        scroll.addView(list);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        List<TeamStore.Team> teams = new ArrayList<>();
        for (TeamStore.Team team : teamStore.all()) {
            if ((!team.packed.isEmpty() || !team.export.isEmpty()) &&
                    (selectedMatchFormat.isEmpty() || selectedMatchFormat.equals(team.format))) teams.add(team);
        }
        if (teams.isEmpty()) {
            TextView empty = text(selectedMatchFormat.isEmpty()
                    ? "还没有可出战队伍。先创建队伍并添加宝可梦与招式。"
                    : "还没有适用当前规则的队伍。请先创建或调整队伍。", 18, Color.WHITE);
            empty.setPadding(0, dp(38), 0, dp(15));
            list.addView(empty);
            titleAction(list, "前往编辑队伍  ›", true, this::showTeams);
        }
        for (TeamStore.Team team : teams) {
            list.addView(teamCard(team, () -> startSearch(team), "使用这支队伍出战  ›",
                    () -> editExistingTeam(team), "编辑", () -> editExistingTeam(team)));
        }
    }

    private void startSearch(TeamStore.Team team) {
        startSearch(team, team == null ? "gen9randombattle" : team.format);
    }

    private void startSearch(TeamStore.Team team, String formatId) {
        if (!connected) { toast("尚未连接服务器"); return; }
        if (username.startsWith("Guest ")) { accountMenu(); return; }
        if (team == null) {
            send("", "/utm null");
            send("", "/search " + formatId);
        } else {
            try {
                send("", "/utm " + (team.packed.isEmpty() ? TeamStore.pack(team.export) : team.packed));
                send("", "/search " + formatId);
            } catch (IllegalArgumentException e) { alert(e.getMessage()); return; }
        }
        append("已发送匹配请求，等待服务器确认");
        searchFormat = formatId;
        setSearchStarted(true);
        if (room.isEmpty()) showBattle();
    }

    private String searchTimeLabel() {
        long seconds = Math.max(0, (SystemClock.elapsedRealtime() - searchStartedAt) / 1000);
        return "匹配中 · " + seconds / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", seconds % 60);
    }

    private void setSearchStarted(boolean searching) {
        if (searching) {
            if (searchStartedAt == 0) {
                searchStartedAt = SystemClock.elapsedRealtime();
                handler.post(searchTicker);
            }
        } else {
            searchStartedAt = 0;
            handler.removeCallbacks(searchTicker);
            if (searchTimeView != null) searchTimeView.setText("");
        }
        updateConnection();
    }

    private BattleSession battle(String id) {
        BattleSession session = battles.get(id);
        if (session == null) {
            session = new BattleSession(id);
            battles.put(id, session);
        }
        return session;
    }

    private void showBattleSwitcher() {
        if (battles.isEmpty()) { toast("当前没有对战"); return; }
        List<BattleSession> sessions = new ArrayList<>(battles.values());
        String[] labels = new String[sessions.size()];
        for (int i = 0; i < sessions.size(); i++) {
            BattleSession battle = sessions.get(i);
            labels[i] = (battle.id.equals(room) ? "● " : "○ ") + battle.title
                    + (battle.ended ? " · 已结束" : battle.request != null ? " · 可操作" : " · 进行中");
        }
        new AlertDialog.Builder(this).setTitle("我的对战")
                .setItems(labels, (dialog, index) -> activateBattle(sessions.get(index).id))
                .setNeutralButton("清理已结束", (dialog, which) -> {
                    boolean closeCurrent = false;
                    for (BattleSession session : sessions) {
                        if (!session.ended) continue;
                        send(session.id, "/leave");
                        battles.remove(session.id);
                        if (session.id.equals(room)) closeCurrent = true;
                    }
                    if (closeCurrent) leaveToLobby();
                    else if (room.isEmpty()) showBattle();
                })
                .setNegativeButton("关闭", null).show();
    }

    private void activateBattle(String id) {
        BattleSession session = battle(id);
        room = id;
        battleHistory.clear();
        battleHistory.addAll(session.history);
        log.setLength(0);
        choiceRequest = null;
        mySideId = "";
        currentWeather = "";
        sideDetails[0] = sideDetails[1] = "";
        sideHealth[0] = sideHealth[1] = "";
        timerOn = false;
        battleEnded = false;
        showBattle();
        boolean wasProcessingHistory = processingHistory;
        processingHistory = true;
        try {
            for (String line : session.history) {
                if (!line.startsWith("|")) continue;
                String[] parts = line.split("\\|", -1);
                if (parts.length > 1) handleBattleEvent(parts[1], parts);
            }
        } finally { processingHistory = wasProcessingHistory; }
        choiceRequest = session.ended ? null : session.request;
        battleEnded = session.ended;
        if (choiceRequest != null) {
            JSONObject side = choiceRequest.optJSONObject("side");
            if (side != null) mySideId = side.optString("id", mySideId);
        }
        renderCombatants();
        renderChoices();
        renderBattleOptions();
        updateBattleControls();
        if (officialBattle != null) officialBattle.setSide(mySideId);
    }

    private void connect() {
        socket = new ShowdownSocket(new ShowdownSocket.Listener() {
            @Override public void onOpen() { Log.i("ShowdownNative", "Socket connected"); runOnUiThread(() -> {
                connected = true; updateConnection(); append("已连接 Showdown 对战服务器");
                for (String id : battles.keySet()) send("", "/join " + id);
            }); }
            @Override public void onMessage(String message) { runOnUiThread(() -> handleMessage(message)); }
            @Override public void onClosed(String reason) {
                Log.w("ShowdownNative", "Socket closed: " + reason);
                runOnUiThread(() -> {
                    connected = false;
                    searchFormat = "";
                    setSearchStarted(false);
                    choiceRequest = null;
                    renderChoices();
                    renderBattleOptions();
                    updateConnection();
                    append("连接断开：" + reason);
                    if (!destroyed) page.postDelayed(() -> { if (!connected && !destroyed) connect(); }, 4000);
                });
            }
        });
        socket.connect();
    }

    private void send(String targetRoom, String command) {
        if (destroyed) return;
        String message = targetRoom + "|" + command;
        outgoing.execute(() -> {
            try { socket.send(message); }
            catch (Exception e) {
                Log.e("ShowdownNative", "Send failed", e);
                runOnUiThread(() -> toast("发送失败：" + e.getClass().getSimpleName() + " " + e.getMessage()));
            }
        });
    }

    private void handleMessage(String packet) {
        String packetRoom = "";
        Map<String, List<String>> visualLines = new LinkedHashMap<>();
        processingHistory = packet.contains("|init|battle");
        for (String line : packet.split("\n")) {
            if (line.startsWith(">")) { packetRoom = line.substring(1); continue; }
            if (line.isEmpty()) continue;
            handleLine(packetRoom, line);
            if (battles.containsKey(packetRoom) && line.startsWith("|")
                    && !line.startsWith("|request|") && !line.startsWith("|error|")
                    && !line.startsWith("|queryresponse|"))
                visualLines.computeIfAbsent(packetRoom, key -> new ArrayList<>()).add(line);
        }
        for (Map.Entry<String, List<String>> entry : visualLines.entrySet()) {
            battle(entry.getKey()).history.addAll(entry.getValue());
            if (entry.getKey().equals(room)) {
                battleHistory.addAll(entry.getValue());
                if (officialBattle != null) officialBattle.addLines(entry.getValue());
            }
        }
        processingHistory = false;
    }

    private void handleLine(String packetRoom, String line) {
        String[] parts = line.split("\\|", -1);
        if (!line.startsWith("|")) { if (packetRoom.equals(room)) append(line); return; }
        String type = parts.length > 1 ? parts[1] : "";
        String a = parts.length > 2 ? parts[2] : "";
        String b = parts.length > 3 ? parts[3] : "";
        switch (type) {
            case "challstr": challenge = line.substring("|challstr|".length()); break;
            case "formats": formatCatalog.parse(line); break;
            case "updateuser": username = a.trim(); Log.i("ShowdownNative", "Guest session ready"); updateConnection(); break;
            case "updatesearch":
                try {
                    JSONObject state = new JSONObject(line.substring("|updatesearch|".length()));
                    JSONArray searching = state.optJSONArray("searching");
                    searchFormat = searching != null && searching.length() > 0 ? searching.optString(0) : "";
                    setSearchStarted(!searchFormat.isEmpty());
                    JSONObject games = state.optJSONObject("games");
                    if (games != null) {
                        java.util.Iterator<String> ids = games.keys();
                        while (ids.hasNext()) {
                            String id = ids.next();
                            if (!id.startsWith("battle-")) continue;
                            boolean missing = !battles.containsKey(id);
                            battle(id).title = games.optString(id, id);
                            if (missing) send("", "/join " + id);
                        }
                    }
                    if (room.isEmpty() && tab == 0) showBattle();
                    updateConnection();
                } catch (Exception e) { Log.w("ShowdownNative", "Search update", e); }
                break;
            case "queryresponse": if ("roomlist".equals(a)) showRoomList(line.substring("|queryresponse|roomlist|".length())); break;
            case "popup":
                String message = line.substring("|popup|".length()).replace("||", "\n");
                if (validationPending) {
                    boolean valid = message.contains("Your team is valid for") || message.contains("队伍符合");
                    boolean rejected = message.contains("Your team was rejected") || message.contains("队伍被拒绝")
                            || message.contains("Please provide a valid format") || message.contains("was not found");
                    if (valid || rejected) {
                        validationPending = false;
                        if (validationStatus != null) validationStatus.setText(valid && !rejected
                                ? "官方合法性：通过" : "官方合法性：未通过，查看原因");
                    }
                }
                alert(message);
                break;
            case "pm": if (line.contains("|/raw ")) receiveMoveInfo(line.substring(line.indexOf("|/raw ") + 6)); break;
            case "init":
                if ("battle".equals(a)) {
                    BattleSession session = battle(packetRoom);
                    session.history.clear();
                    session.request = null;
                    session.ended = false;
                    if (room.isEmpty() && tab == 0) activateBattle(packetRoom);
                    else if (packetRoom.equals(room)) activateBattle(packetRoom);
                }
                break;
            case "title":
                if (battles.containsKey(packetRoom)) battle(packetRoom).title = a;
                if (packetRoom.equals(room)) handleBattleEvent(type, parts);
                break;
            case "deinit":
                battles.remove(packetRoom);
                if (packetRoom.equals(room)) leaveToLobby();
                break;
            case "request":
                String requestBody = line.substring("|request|".length()).trim();
                if (!packetRoom.isEmpty() && battles.containsKey(packetRoom)) {
                    try { battle(packetRoom).request = requestBody.isEmpty() || "null".equals(requestBody)
                            ? null : new JSONObject(requestBody); }
                    catch (Exception e) { Log.w("ShowdownNative", "Battle request", e); }
                }
                if (packetRoom.equals(room)) {
                    try {
                        choiceRequest = requestBody.isEmpty() || "null".equals(requestBody)
                                ? null : new JSONObject(requestBody);
                        battleMechanic = "";
                        JSONObject side = choiceRequest == null ? null : choiceRequest.optJSONObject("side");
                        if (side != null) mySideId = side.optString("id", mySideId);
                        renderChoices(); updateMyPokemon(); renderBattleOptions();
                    }
                    catch (Exception e) { append("无法解析本回合选招请求"); }
                }
                break;
            case "error": if (packetRoom.equals(room)) { append("⚠ " + a); renderChoices(); } break;
            case "win": case "tie":
                if (battles.containsKey(packetRoom)) {
                    battle(packetRoom).ended = true;
                    battle(packetRoom).request = null;
                }
                if (packetRoom.equals(room)) handleBattleEvent(type, parts);
                break;
            default: if (packetRoom.equals(room) && !room.isEmpty()) handleBattleEvent(type, parts);
        }
    }

    private void handleBattleEvent(String type, String[] p) {
        String a = p.length > 2 ? p[2] : "";
        String b = p.length > 3 ? p[3] : "";
        switch (type) {
            case "title": battleTitle.setText(a); break;
            case "turn": append("第 " + a + " 回合"); break;
            case "player": if (b.equalsIgnoreCase(username)) { mySideId = a; renderCombatants(); renderBattleOptions(); if (officialBattle != null) officialBattle.setSide(mySideId); } break;
            case "switch": case "drag":
                append(shortName(a) + " 上场了（" + b + "）");
                setCombatant(a, b, p.length > 4 ? p[4] : "");
                if (!processingHistory) animateSwitch(a);
                break;
            case "move":
                append(shortName(a) + " 使用了 " + dexNames.move(b) + " → " + (p.length > 4 ? shortName(p[4]) : ""));
                if (!processingHistory) {
                    animateMove(a);
                    if (moveEffects != null) {
                        int targetSide = p.length > 4 ? sideOf(p[4]) : -1;
                        moveEffects.play(dexNames.moveInfo(b), a.startsWith(mySide()),
                                targetSide < 0 || targetSide == sideOf(a));
                    }
                }
                break;
            case "-damage": append(shortName(a) + " 受到伤害，HP " + b); updateHealth(a, b); flash(a, Color.RED); break;
            case "-heal": append(shortName(a) + " 恢复了体力，HP " + b); updateHealth(a, b); flash(a, Color.GREEN); break;
            case "-status": append(shortName(a) + " 陷入" + statusName(b) + "状态"); flash(a, Color.YELLOW); break;
            case "-curestatus": append(shortName(a) + " 的" + statusName(b) + "状态解除"); break;
            case "-boost": append(shortName(a) + " 的" + statName(b) + "上升 " + (p.length > 4 ? p[4] : "") + " 级"); break;
            case "-unboost": append(shortName(a) + " 的" + statName(b) + "下降 " + (p.length > 4 ? p[4] : "") + " 级"); break;
            case "-supereffective": append("效果绝佳！"); break;
            case "-resisted": append("效果不理想"); break;
            case "-immune": append(shortName(a) + " 不受招式影响"); break;
            case "-crit": append("击中了要害！"); break;
            case "-miss": append(shortName(a) + " 的攻击落空"); break;
            case "-ability": append(shortName(a) + " 发动特性 " + b); break;
            case "-item": append(shortName(a) + " 使用道具 " + b); break;
            case "-terastallize": append(shortName(a) + " 太晶化为 " + b + " 属性"); break;
            case "-start": append(shortName(a) + " 获得效果 " + effectName(b)); break;
            case "-end": append(shortName(a) + " 的 " + effectName(b) + " 效果结束"); break;
            case "-activate": append(shortName(a) + " 触发 " + b); break;
            case "-fail": append(shortName(a) + " 的招式失败"); break;
            case "cant": append(shortName(a) + " 无法行动：" + b); break;
            case "-formechange": append(shortName(a) + " 变为 " + b); break;
            case "-mega": append(shortName(a) + " 超级进化为 " + b); break;
            case "-weather": append("天气变化：" + a); currentWeather = a; updateBattleBackground(); break;
            case "-fieldstart": append("场地效果：" + a); break;
            case "-fieldend": append("场地效果结束：" + a); break;
            case "-sidestart": append("场地效果：" + b); break;
            case "-sideend": append("场地效果结束：" + b); break;
            case "faint": append(shortName(a) + " 失去了战斗能力"); if (!processingHistory) animateFaint(a); break;
            case "win": append("对战结束，" + a + " 获胜"); choiceRequest = null; battleEnded = true; renderChoices(); renderBattleOptions(); break;
            case "tie": append("对战结束：平局"); choiceRequest = null; battleEnded = true; renderChoices(); renderBattleOptions(); break;
            case "inactive": timerOn = true; append("计时器已开启"); renderBattleOptions(); break;
            case "inactiveoff": timerOn = false; append("计时器已关闭"); renderBattleOptions(); break;
            case "-message": case "message": append(a); break;
            default: break;
        }
    }

    private void setCombatant(String ident, String details, String health) {
        int side = sideOf(ident);
        if (side < 0) return;
        sideDetails[side] = details;
        sideHealth[side] = health;
        renderCombatants();
    }
    private void updateHealth(String ident, String health) {
        int side = sideOf(ident);
        if (side < 0) return;
        sideHealth[side] = health;
        renderCombatants();
    }
    private static int sideOf(String ident) {
        if (ident.startsWith("p1")) return 0;
        if (ident.startsWith("p2")) return 1;
        return -1;
    }
    private void renderCombatants() {
        if (myPokemon == null || foePokemon == null) return;
        boolean player = !mySideId.isEmpty() || (choiceRequest != null && choiceRequest.optJSONObject("side") != null);
        int mine = "p2".equals(mySide()) ? 1 : 0;
        int foe = 1 - mine;
        myPokemon.setText((player ? "我方：" : "P1：") + combatant(mine));
        foePokemon.setText((player ? "对手：" : "P2：") + combatant(foe));
        if (mySprite != null && !sideDetails[mine].isEmpty()) showSprite(mySprite, sideDetails[mine].split(",", 2)[0], true);
        if (foeSprite != null && !sideDetails[foe].isEmpty()) showSprite(foeSprite, sideDetails[foe].split(",", 2)[0], false);
    }
    private String combatant(int side) {
        if (sideDetails[side].isEmpty()) return "等待对战信息";
        return sideDetails[side] + " · " + localizedCondition(sideHealth[side]);
    }

    private String localizedCondition(String condition) {
        String translated = condition;
        for (String code : new String[]{"brn", "par", "slp", "frz", "psn", "tox", "fnt"})
            translated = translated.replaceAll("\\b" + code + "\\b", statusName(code));
        return translated;
    }

    private void showActivePokemonInfo() {
        JSONObject side = choiceRequest == null ? null : choiceRequest.optJSONObject("side");
        JSONArray pokemon = side == null ? null : side.optJSONArray("pokemon");
        JSONObject active = null;
        if (pokemon != null) for (int i = 0; i < pokemon.length(); i++) {
            JSONObject one = pokemon.optJSONObject(i);
            if (one != null && one.optBoolean("active")) { active = one; break; }
        }
        if (active == null) {
            alert("这场对战目前没有提供我方宝可梦的详细能力值；观战时也无法获知精确速度。");
            return;
        }
        JSONObject stats = active.optJSONObject("stats");
        int speed = stats == null ? -1 : stats.optInt("spe", -1);
        String name = shortName(active.optString("ident"));
        StringBuilder info = new StringBuilder();
        info.append("HP：").append(localizedCondition(active.optString("condition", "未知")));
        info.append("\n速度能力值：").append(speed >= 0 ? speed : "服务器未提供");
        info.append("\n\n速度能力值不包含能力等级、麻痹、顺风、特性和道具造成的临时变化。");
        new AlertDialog.Builder(this).setTitle(name).setMessage(info.toString())
                .setPositiveButton("知道了", null).show();
    }
    private String mySide() {
        if (!mySideId.isEmpty()) return mySideId;
        if (choiceRequest == null) return "p1";
        return choiceRequest.optJSONObject("side") == null ? "p1" : choiceRequest.optJSONObject("side").optString("id", "p1");
    }
    private void flash(String ident, int color) {
        ImageView target = ident.startsWith(mySide()) ? mySprite : foeSprite;
        if (target == null) return;
        target.setColorFilter(color, PorterDuff.Mode.SRC_ATOP);
        handler.postDelayed(target::clearColorFilter, 350);
    }
    private ImageView spriteFor(String ident) {
        return ident.startsWith(mySide()) ? mySprite : foeSprite;
    }
    private void animateSwitch(String ident) {
        ImageView target = spriteFor(ident);
        if (target == null) return;
        target.animate().cancel();
        target.setAlpha(0f); target.setScaleX(.55f); target.setScaleY(.55f);
        target.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(400).start();
    }
    private void animateMove(String ident) {
        ImageView attacker = spriteFor(ident);
        if (attacker == null) return;
        attacker.animate().cancel();
        attacker.setAlpha(1f);
        float travel = dp(45) * (attacker == mySprite ? 1 : -1);
        attacker.animate().translationX(travel).setDuration(160)
                .withEndAction(() -> attacker.animate().translationX(0).setDuration(220).start()).start();
        ImageView defender = attacker == mySprite ? foeSprite : mySprite;
        if (defender != null) {
            defender.animate().cancel();
            defender.setAlpha(1f);
            defender.animate().scaleX(1.13f).scaleY(1.13f).setDuration(180)
                    .withEndAction(() -> defender.animate().scaleX(1f).scaleY(1f).setDuration(220).start()).start();
        }
    }
    private void animateFaint(String ident) {
        ImageView target = spriteFor(ident);
        if (target == null) return;
        target.animate().cancel();
        target.animate().alpha(0f).translationY(dp(35)).setDuration(500).start();
    }
    private void showSprite(ImageView view, String species, boolean back) {
        String id = species.toLowerCase(java.util.Locale.ROOT)
                .replace("♀", "f").replace("♂", "m").replaceAll("[^a-z0-9-]", "");
        if (id.isEmpty()) return;
        String animation = (back ? "ani-back/" : "ani/") + id + ".gif";
        if (Build.VERSION.SDK_INT >= 28 && !missingAnimations.contains(animation)) {
            showAnimatedSprite(view, animation, id, back);
            return;
        }
        showStaticSprite(view, id, back);
    }

    private void showAnimatedSprite(ImageView view, String path, String id, boolean back) {
        if (path.equals(view.getTag())) return;
        view.setTag(path);
        view.setTranslationY(0f);
        byte[] cached = animationCache.get(path);
        if (cached != null) { decodeAnimation(view, path, cached, id, back); return; }
        view.setImageDrawable(null);
        new Thread(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL("https://play.pokemonshowdown.com/sprites/" + path).openConnection();
                connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
                byte[] bytes;
                try (java.io.InputStream input = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] chunk = new byte[8192]; int count;
                    while ((count = input.read(chunk)) != -1) {
                        out.write(chunk, 0, count);
                        if (out.size() > 2_000_000) throw new IllegalArgumentException("Animation too large");
                    }
                    bytes = out.toByteArray();
                }
                connection.disconnect();
                runOnUiThread(() -> {
                    animationCache.put(path, bytes);
                    decodeAnimation(view, path, bytes, id, back);
                });
            } catch (Exception error) {
                Log.w("ShowdownNative", "Animated sprite unavailable: " + path, error);
                runOnUiThread(() -> fallbackSprite(view, path, id, back));
            }
        }, "Animated sprite " + id).start();
    }

    private void decodeAnimation(ImageView view, String path, byte[] bytes, String id, boolean back) {
        if (!path.equals(view.getTag())) return;
        try {
            Drawable drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)));
            Log.i("ShowdownNative", "Sprite decoded " + path + " as " + drawable.getClass().getSimpleName());
            view.setImageDrawable(drawable);
            view.setAlpha(1f);
            view.setTranslationY(0f);
            if (drawable instanceof AnimatedImageDrawable) {
                AnimatedImageDrawable moving = (AnimatedImageDrawable) drawable;
                moving.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                moving.start();
            }
        } catch (Exception error) {
            Log.w("ShowdownNative", "Animated sprite decode failed: " + path, error);
            fallbackSprite(view, path, id, back);
        }
    }

    private void fallbackSprite(ImageView view, String animation, String id, boolean back) {
        missingAnimations.add(animation);
        if (animation.equals(view.getTag())) showStaticSprite(view, id, back);
    }

    private void showStaticSprite(ImageView view, String id, boolean back) {
        String path = (back ? "gen5-back/" : "gen5/") + id + ".png";
        if (path.equals(view.getTag())) return;
        view.setTag(path);
        view.setTranslationY(0f);
        Bitmap cached = spriteCache.get(path);
        if (cached != null) { view.setImageBitmap(cached); view.setAlpha(1f); return; }
        view.setImageDrawable(null);
        new Thread(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL("https://play.pokemonshowdown.com/sprites/" + path).openConnection();
                connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
                Bitmap bitmap;
                try (java.io.InputStream input = connection.getInputStream()) { bitmap = BitmapFactory.decodeStream(input); }
                connection.disconnect();
                if (bitmap != null) runOnUiThread(() -> {
                    spriteCache.put(path, bitmap);
                    if (path.equals(view.getTag())) { view.setImageBitmap(bitmap); view.setAlpha(1f); }
                });
            } catch (Exception error) { Log.w("ShowdownNative", "Static sprite unavailable: " + path, error); }
        }, "Sprite " + id).start();
    }

    private TextView battleLabel(String label) {
        TextView view = text(label, 14, Color.WHITE);
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(8), 0, dp(8), 0);
        view.setBackgroundColor(0xb022303f);
        return view;
    }

    private String battleBackgroundPath() {
        String weather = currentWeather.toLowerCase(java.util.Locale.ROOT);
        if (weather.contains("snow") || weather.contains("hail")) return "bg-gen4-snow.png";
        if (weather.contains("sand")) return "bg-gen3-sand.png";
        if (weather.contains("rain")) return "bg-gen4-water.png";
        return "bg-meadow.png";
    }

    private void updateBattleBackground() {
        if (backdrop != null) backdrop.setWeather(currentWeather);
        loadBattleBackground();
    }

    private void loadBattleBackground() {
        ImageView target = backgroundArt;
        if (target == null) return;
        String path = battleBackgroundPath();
        if (path.equals(target.getTag())) return;
        target.setTag(path);
        Bitmap cached = backgroundCache.get(path);
        if (cached != null) { target.setImageBitmap(cached); return; }
        target.setImageDrawable(null);
        new Thread(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL("https://play.pokemonshowdown.com/fx/" + path).openConnection();
                connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
                Bitmap image;
                try (java.io.InputStream input = connection.getInputStream()) { image = BitmapFactory.decodeStream(input); }
                connection.disconnect();
                if (image != null) runOnUiThread(() -> {
                    backgroundCache.put(path, image);
                    if (path.equals(target.getTag())) target.setImageBitmap(image);
                });
            } catch (Exception error) { Log.w("ShowdownNative", "Background unavailable: " + path, error); }
        }, "Battle background").start();
    }
    private static String shortName(String ident) {
        int colon = ident.indexOf(':');
        return colon >= 0 ? ident.substring(colon + 1).trim() : ident;
    }
    private static String statusName(String code) {
        switch (code) {
            case "brn": return "灼伤";
            case "par": return "麻痹";
            case "slp": return "睡眠";
            case "frz": return "冰冻";
            case "psn": return "中毒";
            case "tox": return "剧毒";
            case "fnt": return "濒死";
            default: return code;
        }
    }
    private static String statName(String code) {
        switch (code) {
            case "atk": return "攻击";
            case "def": return "防御";
            case "spa": return "特攻";
            case "spd": return "特防";
            case "spe": return "速度";
            case "accuracy": return "命中";
            case "evasion": return "闪避";
            default: return code;
        }
    }
    private String effectName(String effect) {
        return effect.startsWith("move: ") ? dexNames.move(effect.substring(6)) : effect;
    }

    private void renderChoices() {
        if (actions == null || tab != 0) return;
        actions.removeAllViews();
        updateBattleControls();
        if (choiceRequest == null || choiceRequest.optBoolean("wait")) return;
        JSONArray sidePokemon = choiceRequest.optJSONObject("side") == null ? null : choiceRequest.optJSONObject("side").optJSONArray("pokemon");
        if (choiceRequest.optBoolean("teamPreview")) {
            addButton(actions, "选择首发宝可梦", () -> chooseLead(sidePokemon));
            return;
        }
        JSONArray active = choiceRequest.optJSONArray("active");
        boolean forced = choiceRequest.optJSONArray("forceSwitch") != null && choiceRequest.optJSONArray("forceSwitch").optBoolean(0);
        if (!forced && active != null && active.length() == 1) {
            JSONObject one = active.optJSONObject(0);
            if (one != null) {
                if (one.optBoolean("canMegaEvo")) mechanicButton("Mega 进化", "mega");
                if (one.optBoolean("canMegaEvoX")) mechanicButton("Mega X", "megax");
                if (one.optBoolean("canMegaEvoY")) mechanicButton("Mega Y", "megay");
                if (one.optBoolean("canDynamax")) mechanicButton("极巨化 Max", "max");
                if (one.has("canTerastallize")) mechanicButton("太晶化", "terastalize");
                JSONArray moves = one.optJSONArray("moves");
                LinearLayout moveRow = null;
                if (moves != null) for (int i = 0; i < moves.length(); i++) {
                    if (i % 2 == 0) { moveRow = row(); actions.addView(moveRow); }
                    final int slot = i + 1;
                    JSONObject move = moves.optJSONObject(i);
                    if (move == null) continue;
                    String english = move.optString("move");
                    String chinese = dexNames.move(english);
                    String display = chinese.equals(english) ? english : chinese + " / " + english;
                    Button button = addButton(moveRow, display + "  PP " + move.optInt("pp") + "/" + move.optInt("maxpp"),
                            () -> choose("move " + slot + (battleMechanic.isEmpty() ? "" : " " + battleMechanic)));
                    button.setEnabled(!move.optBoolean("disabled") && move.optInt("pp", 1) > 0);
                    button.setOnLongClickListener(v -> { requestMoveInfo(move.optString("move")); return true; });
                }
            }
        }
        if (sidePokemon != null) {
            LinearLayout switches = row();
            actions.addView(switches);
            addButton(switches, forced ? "必须换人" : "切换宝可梦", () -> chooseSwitch(sidePokemon));
            addButton(switches, "撤销选择", () -> send(room, "/undo"));
        }
    }

    private void mechanicButton(String label, String code) {
        addButton(actions, label + (code.equals(battleMechanic) ? " ✓" : ""), () -> {
            battleMechanic = code.equals(battleMechanic) ? "" : code;
            renderChoices();
        });
    }

    private void updateMyPokemon() {
        JSONObject side = choiceRequest.optJSONObject("side");
        JSONArray pokemon = side == null ? null : side.optJSONArray("pokemon");
        if (pokemon == null) return;
        for (int i = 0; i < pokemon.length(); i++) {
            JSONObject one = pokemon.optJSONObject(i);
            if (one != null && one.optBoolean("active")) {
                int index = sideOf(side.optString("id"));
                if (index >= 0) {
                    sideDetails[index] = one.optString("details", shortName(one.optString("ident")));
                    sideHealth[index] = one.optString("condition");
                    renderCombatants();
                }
                break;
            }
        }
    }

    private void chooseSwitch(JSONArray pokemon) {
        List<Integer> slots = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < pokemon.length(); i++) {
            JSONObject one = pokemon.optJSONObject(i);
            if (one == null || one.optBoolean("active") || one.optString("condition").contains("fnt")) continue;
            slots.add(i + 1);
            labels.add(shortName(one.optString("ident")) + "  " + one.optString("condition"));
        }
        if (slots.isEmpty()) { toast("没有可以切换的宝可梦"); return; }
        new AlertDialog.Builder(this).setTitle("选择上场宝可梦")
                .setItems(labels.toArray(new String[0]), (dialog, index) -> choose("switch " + slots.get(index))).show();
    }

    private void chooseLead(JSONArray pokemon) {
        if (pokemon == null) return;
        String[] labels = new String[pokemon.length()];
        for (int i = 0; i < labels.length; i++) {
            JSONObject one = pokemon.optJSONObject(i);
            labels[i] = one == null ? "未知" : shortName(one.optString("ident"));
        }
        new AlertDialog.Builder(this).setTitle("选择首发")
                .setItems(labels, (dialog, index) -> {
                    StringBuilder order = new StringBuilder().append(index + 1);
                    for (int i = 0; i < labels.length; i++) if (i != index) order.append(i + 1);
                    choose("team " + order);
                }).show();
    }

    private void choose(String decision) {
        if (room.isEmpty() || choiceRequest == null) return;
        int rqid = choiceRequest.optInt("rqid", 0);
        send(room, "/choose " + decision + (rqid > 0 ? "|" + rqid : ""));
        append("已选择：" + decision);
        actions.removeAllViews();
        addButton(actions, "撤销选择", () -> { send(room, "/undo"); renderChoices(); });
    }

    private void requestMoveInfo(String move) {
        moveInfo.setLength(0);
        String chinese = dexNames.move(move);
        moveInfo.append(chinese.equals(move) ? move : chinese + " / " + move).append("\n\n");
        if (infoPopup != null) handler.removeCallbacks(infoPopup);
        infoPopup = () -> {
            alert(moveInfo.toString().trim().equals(move) || moveInfo.toString().trim().equals(chinese + " / " + move)
                    ? "暂无招式资料：" + moveInfo.toString().trim() : moveInfo.toString().trim());
            infoPopup = null;
        };
        send("", "/dt " + move);
        handler.postDelayed(infoPopup, 2200);
    }

    private void receiveMoveInfo(String raw) {
        if (infoPopup == null) return;
        String clean = Html.fromHtml(raw, Html.FROM_HTML_MODE_COMPACT).toString().trim();
        if (!clean.isEmpty()) moveInfo.append(clean).append('\n');
    }

    private void showRoomList(String json) {
        try {
            JSONObject data = new JSONObject(json).optJSONObject("rooms");
            if (data == null || data.length() == 0) { toast("暂时没有公开对战"); return; }
            List<String> ids = new ArrayList<>(), labels = new ArrayList<>();
            java.util.Iterator<String> keys = data.keys();
            while (keys.hasNext() && ids.size() < 30) {
                String id = keys.next();
                JSONObject info = data.optJSONObject(id);
                ids.add(id);
                labels.add(info == null ? id : info.optString("p1") + " vs " + info.optString("p2"));
            }
            new AlertDialog.Builder(this).setTitle("公开对战 · 点击观战")
                    .setItems(labels.toArray(new String[0]), (dialog, index) -> {
                        String id = ids.get(index);
                        battle(id);
                        activateBattle(id);
                        send("", "/join " + id);
                    }).show();
        } catch (Exception e) { toast("对战列表读取失败"); }
    }

    private void leaveToLobby() {
        room = "";
        battleHistory.clear();
        choiceRequest = null;
        timerOn = false;
        battleEnded = false;
        currentWeather = "";
        mySideId = "";
        sideDetails[0] = sideDetails[1] = "";
        sideHealth[0] = sideHealth[1] = "";
        showBattle();
    }

    private void loginDialog() {
        if (challenge.isEmpty()) { toast("等待服务器发出登录挑战"); return; }
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), 0, dp(16), 0);
        EditText name = field("Showdown 用户名", "", false);
        EditText password = field("密码（不会保存）", "", false);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(name); form.addView(password);
        new AlertDialog.Builder(this).setTitle("登录官方 Showdown 账号")
                .setView(form)
                .setPositiveButton("登录", (dialog, which) -> login(name.getText().toString().trim(), password.getText().toString()))
                .setNegativeButton("取消", null).show();
    }

    private void accountMenu() {
        if (challenge.isEmpty()) { toast("等待服务器发出登录挑战"); return; }
        new AlertDialog.Builder(this).setTitle("Showdown 身份")
                .setItems(new String[]{"设置临时昵称", "登录已有账号"},
                        (dialog, which) -> { if (which == 0) guestNameDialog(); else loginDialog(); })
                .show();
    }

    private void guestNameDialog() {
        EditText name = field("临时昵称（最多 18 字符）", "", false);
        name.setPadding(dp(20), dp(12), dp(20), dp(12));
        new AlertDialog.Builder(this).setTitle("设置临时昵称")
                .setView(name)
                .setPositiveButton("确定", (dialog, which) -> setGuestName(name.getText().toString().trim()))
                .setNegativeButton("取消", null).show();
    }

    private void setGuestName(String name) {
        if (name.isEmpty() || name.length() > 18 || !name.matches("[A-Za-z0-9 ]+")) {
            alert("昵称需为 1 到 18 个英文字母、数字或空格"); return;
        }
        String id = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
        final String challengeAtLogin = challenge;
        new Thread(() -> {
            try {
                String query = "userid=" + URLEncoder.encode(id, "UTF-8") + "&challstr=" + URLEncoder.encode(challengeAtLogin, "UTF-8");
                HttpURLConnection connection = (HttpURLConnection) new URL("https://play.pokemonshowdown.com/api/getassertion?" + query).openConnection();
                connection.setConnectTimeout(15000); connection.setReadTimeout(15000);
                StringBuilder response = new StringBuilder();
                try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    for (String line; (line = in.readLine()) != null;) response.append(line);
                }
                connection.disconnect();
                String assertion = response.toString();
                if (assertion.startsWith(";;")) throw new Exception(assertion.substring(2));
                if (assertion.isEmpty()) throw new Exception("无法获取临时昵称验证");
                runOnUiThread(() -> send("", "/trn " + name + ",0," + assertion));
            } catch (Exception e) { runOnUiThread(() -> alert("设置昵称失败：" + e.getMessage())); }
        }, "Showdown guest name").start();
    }

    private void login(String name, String password) {
        if (name.isEmpty() || password.isEmpty()) { toast("请输入用户名和密码"); return; }
        final String challengeAtLogin = challenge;
        new Thread(() -> {
            try {
                URL url = new URL("https://play.pokemonshowdown.com/api/login");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(15000); connection.setReadTimeout(15000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                String body = "name=" + URLEncoder.encode(name, "UTF-8") + "&pass=" + URLEncoder.encode(password, "UTF-8")
                        + "&challstr=" + URLEncoder.encode(challengeAtLogin, "UTF-8");
                try (OutputStream out = connection.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
                StringBuilder response = new StringBuilder();
                try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    for (String line; (line = in.readLine()) != null;) response.append(line);
                }
                connection.disconnect();
                String raw = response.toString();
                if (raw.startsWith("]")) raw = raw.substring(1);
                JSONObject result = new JSONObject(raw);
                String assertion = result.optString("assertion");
                if (assertion.isEmpty() || assertion.equals(";")) throw new Exception(result.optString("error", "登录失败"));
                runOnUiThread(() -> send("", "/trn " + name + ",0," + assertion));
            } catch (Exception e) { runOnUiThread(() -> alert("登录失败：" + e.getMessage())); }
        }, "Showdown login").start();
    }

    private void updateConnection() {
        if (connectionLabel != null) connectionLabel.setText((connected ? "● " : "○ ")
                + (username.isEmpty() ? "连接中" : username)
                + (searchFormat.isEmpty() ? "" : " · 匹配中 " + searchFormat)
                + (connected ? "" : " · 正在重连"));
    }
    private void append(String line) {
        if (line.isEmpty()) return;
        log.append(line).append('\n');
        if (log.length() > 12000) log.delete(0, log.length() - 10000);
        if (tab == 0 && logView != null) {
            logView.setText(log.toString());
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        }
    }
    private void alert(String text) { new AlertDialog.Builder(this).setMessage(text).setPositiveButton("知道了", null).show(); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
    private TextView text(String content, int sp, int color) {
        TextView view = new TextView(this); view.setText(content); view.setTextSize(sp); view.setTextColor(color); return view;
    }
    private EditText field(String hint, String value, boolean multiline) {
        EditText view = new EditText(this); view.setHint(hint); view.setText(value); view.setSingleLine(!multiline);
        if (multiline) { view.setGravity(Gravity.TOP); view.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE); }
        return view;
    }
    private LinearLayout row() { LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); return row; }
    private Button addButton(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setTextSize(12); button.setAllCaps(false);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = parent.getOrientation() == LinearLayout.HORIZONTAL
                ? new LinearLayout.LayoutParams(0, dp(48), 1)
                : new LinearLayout.LayoutParams(-1, dp(48));
        parent.addView(button, params);
        return button;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override public void onBackPressed() {
        if (tab == 1 && officialTeambuilder != null) {
            officialTeambuilder.navigateBack(handled -> { if (!handled) leaveToLobby(); });
        } else if (tab == 3) showTeams();
        else if (tab == 1 || tab == 2 || !room.isEmpty()) leaveToLobby();
        else super.onBackPressed();
    }
    private void closeOfficialBattle() {
        if (officialBattle == null) return;
        officialBattle.stopLoading();
        officialBattle.destroy();
        officialBattle = null;
    }
    private void closeTeamCatalog() {
        if (teamCatalog == null) return;
        root.removeView(teamCatalog);
        teamCatalog.stopLoading();
        teamCatalog.destroy();
        teamCatalog = null;
    }
    @Override protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacks(searchTicker);
        closeOfficialBattle();
        closeTeamCatalog();
        if (officialTeambuilder != null) officialTeambuilder.close();
        outgoing.shutdownNow();
        if (socket != null) new Thread(socket::close, "Showdown close").start();
        super.onDestroy();
    }
}
