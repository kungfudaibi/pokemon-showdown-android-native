package dev.local.showdownnative;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local team drafts, stored in human-readable Showdown export format. */
final class TeamStore {
    static final class Team {
        String name;
        String format;
        String export;
        String packed;
        Team(String name, String format, String export) {
            this(name, format, export, "");
        }
        Team(String name, String format, String export, String packed) {
            this.name = name; this.format = format; this.export = export; this.packed = packed;
        }
    }

    private final SharedPreferences preferences;
    private final List<Team> teams = new ArrayList<>();

    TeamStore(Context context) {
        preferences = context.getSharedPreferences("teams", Context.MODE_PRIVATE);
        try {
            JSONArray stored = new JSONArray(preferences.getString("list", "[]"));
            for (int i = 0; i < stored.length(); i++) {
                JSONObject row = stored.getJSONObject(i);
                teams.add(new Team(row.optString("name"), row.optString("format"), row.optString("export"), row.optString("packed")));
            }
        } catch (Exception ignored) { }
    }

    List<Team> all() { return teams; }
    void put(int index, Team team) {
        if (index < 0) teams.add(team); else teams.set(index, team);
        save();
    }
    void remove(int index) { teams.remove(index); save(); }
    void replaceAll(List<Team> updated) {
        teams.clear();
        teams.addAll(updated);
        save();
    }
    private void save() {
        JSONArray rows = new JSONArray();
        for (Team team : teams) {
            JSONObject row = new JSONObject();
            try {
                row.put("name", team.name);
                row.put("format", team.format);
                row.put("export", team.export);
                row.put("packed", team.packed);
                rows.put(row);
            } catch (Exception ignored) { }
        }
        preferences.edit().putString("list", rows.toString()).apply();
    }

    static String pack(String export) {
        String normalized = export.replace("\r\n", "\n").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("请粘贴至少一只宝可梦的配队文本");
        String[] blocks = normalized.split("\n\\s*\n");
        if (blocks.length > 6) throw new IllegalArgumentException("一支队伍最多六只宝可梦");
        List<String> packed = new ArrayList<>();
        for (String block : blocks) packed.add(packSet(block));
        return String.join("]", packed);
    }

    private static String packSet(String block) {
        String[] lines = block.split("\n");
        String header = lines[0].trim();
        String item = "";
        int at = header.indexOf(" @ ");
        if (at >= 0) { item = header.substring(at + 3).trim(); header = header.substring(0, at).trim(); }
        String gender = "";
        if (header.endsWith(" (M)") || header.endsWith(" (F)")) {
            gender = header.substring(header.length() - 2, header.length() - 1);
            header = header.substring(0, header.length() - 4).trim();
        }
        String name = header, species = header;
        Matcher namedSpecies = Pattern.compile("^(.*?) \\(([^()]*)\\)$").matcher(header);
        if (namedSpecies.matches()) { name = namedSpecies.group(1); species = namedSpecies.group(2); }
        if (species.isEmpty()) throw new IllegalArgumentException("缺少宝可梦名称");
        if (name.contains("|") || name.contains("]") || species.contains("|") || species.contains("]"))
            throw new IllegalArgumentException("名称中不能包含 | 或 ]");
        String ability = "", nature = "", evs = "", ivs = "", shiny = "", level = "", happiness = "", teraType = "";
        List<String> moves = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("Ability: ")) ability = line.substring(9).trim();
            else if (line.startsWith("EVs: ")) evs = stats(line.substring(5), 0);
            else if (line.startsWith("IVs: ")) ivs = stats(line.substring(5), 31);
            else if (line.endsWith(" Nature")) nature = line.substring(0, line.length() - 7).trim();
            else if (line.startsWith("Tera Type: ")) teraType = line.substring(11).trim();
            else if (line.startsWith("Level: ")) level = line.substring(7).trim();
            else if (line.startsWith("Happiness: ")) happiness = line.substring(11).trim();
            else if (line.equalsIgnoreCase("Shiny: Yes")) shiny = "S";
            else if (line.startsWith("- ")) moves.add(id(line.substring(2)));
        }
        if (moves.isEmpty() || moves.size() > 4) throw new IllegalArgumentException(species + " 需要 1 到 4 个招式");
        String nickname = name.isEmpty() ? species : name;
        String speciesField = nickname.equalsIgnoreCase(species) ? "" : id(species);
        return nickname + "|" + speciesField + "|" + id(item) + "|" + id(ability) + "|"
                + String.join(",", moves) + "|" + nature + "|" + evs + "|" + gender + "|" + ivs
                + "|" + shiny + "|" + level + "|" + happiness + (teraType.isEmpty() ? "" : ",,,,," + teraType);
    }

    private static String stats(String text, int defaultValue) {
        String[] names = {"HP", "Atk", "Def", "SpA", "SpD", "Spe"};
        String[] values = new String[6];
        java.util.Arrays.fill(values, "");
        for (String part : text.split(" / ")) {
            Matcher match = Pattern.compile("^(\\d+)\\s+(HP|Atk|Def|SpA|SpD|Spe)$", Pattern.CASE_INSENSITIVE).matcher(part.trim());
            if (!match.matches()) throw new IllegalArgumentException("无法解析能力值: " + part);
            int value = Integer.parseInt(match.group(1));
            if (value < 0 || value > (defaultValue == 31 ? 31 : 252)) throw new IllegalArgumentException("能力值超出范围: " + part);
            for (int i = 0; i < names.length; i++) if (names[i].equalsIgnoreCase(match.group(2)))
                values[i] = value == defaultValue ? "" : Integer.toString(value);
        }
        return String.join(",", values);
    }

    private static String id(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
