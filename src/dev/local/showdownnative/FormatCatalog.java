package dev.local.showdownnative;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Format flags are supplied by Showdown's |formats| protocol message. */
final class FormatCatalog {
    static final class Option {
        final String id, name, section;
        final boolean searchable, preset;
        Option(String id, String name, String section, boolean searchable, boolean preset) {
            this.id = id; this.name = name; this.section = section;
            this.searchable = searchable; this.preset = preset;
        }
    }

    private final List<Option> options = new ArrayList<>();

    List<Option> all() { return Collections.unmodifiableList(options); }

    void parse(String line) {
        if (!line.startsWith("|formats|")) return;
        List<Option> parsed = new ArrayList<>();
        String section = "其他规则";
        boolean nextSection = false;
        String[] parts = line.substring("|formats|".length()).split("\\|", -1);
        for (String part : parts) {
            if (nextSection) { section = part; nextSection = false; continue; }
            if (part.isEmpty() || part.matches(",[0-9]+")) { nextSection = true; continue; }
            if (part.equals(",LL")) continue;
            String name = part;
            boolean searchable = true, preset = false;
            int comma = part.lastIndexOf(',');
            if (comma >= 0) {
                try {
                    int flags = Integer.parseInt(part.substring(comma + 1), 16);
                    name = part.substring(0, comma);
                    searchable = (flags & 2) != 0;
                    preset = (flags & 1) != 0;
                } catch (NumberFormatException ignored) { }
            }
            if (name.isEmpty()) continue;
            parsed.add(new Option(id(name), name, section, searchable, preset));
        }
        if (!parsed.isEmpty()) { options.clear(); options.addAll(parsed); }
    }

    Option byId(String id) {
        for (Option option : options) if (option.id.equals(id)) return option;
        return null;
    }

    static String id(String name) { return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""); }

    static String displayName(Option option) {
        String name = option.name.replaceFirst("^\\[Gen ([0-9]+)\\] ", "第 $1 世代 · ");
        return name.replace("Random Doubles Battle", "随机双打")
                .replace("Unrated Random Battle", "不计分随机对战")
                .replace("Random Battle", "随机对战")
                .replace("Doubles OU", "双打 OU")
                .replace("Doubles Ubers", "双打 Ubers")
                .replace("Custom Game", "自定义对战");
    }

    static String help(Option option) {
        String name = option.name;
        String lower = name.toLowerCase(Locale.ROOT);
        StringBuilder help = new StringBuilder();
        java.util.regex.Matcher generation = java.util.regex.Pattern.compile("\\[Gen (\\d+)").matcher(name);
        if (generation.find()) help.append("第 ").append(generation.group(1))
                .append(" 世代：使用这一代对应的宝可梦、招式和战斗机制。\\n\\n");
        if (option.preset || lower.contains("random")) help.append("随机队伍：服务器为双方生成队伍，无需自己配队。\\n\\n");
        else help.append("自建队伍：需要选择符合该规则的队伍，服务器会检查合法性。\\n\\n");
        if (lower.contains("doubles") || lower.contains("vgc")) help.append("双打：双方通常同时派出两只宝可梦。\\n\\n");
        else if (lower.contains("free-for-all")) help.append("自由对战：多位玩家同场对战。\\n\\n");
        else help.append("单打：双方通常各派一只宝可梦在场。\\n\\n");
        if (lower.contains("ubers")) help.append("Ubers：允许更多高强度宝可梦，仍有本规则自己的限制。\\n\\n");
        else if (lower.contains(" ou")) help.append("OU：常用的标准竞技分级，按当前规则限制可用宝可梦。\\n\\n");
        else if (lower.contains(" uu") || lower.contains(" ru") || lower.contains(" nu") || lower.contains(" pu"))
            help.append("UU、RU、NU、PU：不同使用率分级，各有可用范围和禁用规则。\\n\\n");
        if (lower.matches(".*\\blc\\b.*")) help.append("LC（Little Cup）：使用符合规则的未进化宝可梦，通常有等级与种族限制。\\n\\n");
        if (lower.contains("national dex")) help.append("National Dex：允许使用更广的历代宝可梦和机制组合，具体以该格式规则为准。\\n\\n");
        if (lower.contains("[gen 6]") || lower.contains("[gen 7]") || lower.contains("national dex"))
            help.append("Mega 进化：为普通形态配对应进化石，在对战选招时启动；具体宝可梦和石头仍受本规则限制。\\n\\n");
        if (lower.contains("[gen 8]"))
            help.append("极巨化 Max：第八世代机制，但 OU 等规则可能禁止；能否使用以本规则和对战按钮为准。\\n\\n");
        if (lower.contains("[gen 9]") && !lower.contains("national dex"))
            help.append("普通第九世代规则使用太晶化，不提供旧世代的 Mega 进化石。\\n\\n");
        if (lower.contains("monotype")) help.append("Monotype：同一支队伍的宝可梦需要共享一种属性。\\n\\n");
        if (lower.contains("anything goes")) help.append("Anything Goes：限制比常见竞技分级宽松，但仍须遵守该格式的基本规则。\\n\\n");
        if (lower.contains("vgc")) help.append("VGC：按照当期官方双打赛事规则组队，规则会随赛季变化。\\n\\n");
        if (!option.searchable) help.append("这个格式目前不开放天梯匹配。\\n\\n");
        help.append("常见缩写：\n"
                + "Ubers：高强度宝可梦分级。\n"
                + "OU：标准常用竞技分级。\n"
                + "UU / RU / NU / PU：按使用率继续细分的分级。\n"
                + "LC：未进化宝可梦的特殊分级。\n"
                + "VGC：官方赛事双打规则。\n\n"
                + "具体禁用名单会更新，以 Showdown 当前规则和官方合法性校验为准。");
        return help.toString().replace("\\n", "\n");
    }
}
