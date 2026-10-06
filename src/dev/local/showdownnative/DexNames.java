package dev.local.showdownnative;

import android.content.Context;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Display names and visual metadata; protocol commands use server English IDs. */
final class DexNames {
    static final class MoveInfo {
        final String english, chinese, type;
        final int category, power;
        MoveInfo(String english, String chinese, String type, int category, int power) {
            this.english = english; this.chinese = chinese; this.type = type;
            this.category = category; this.power = power;
        }
    }
    static final class Species {
        final String english, chinese, formEnglish, requiredItem;
        Species(String english, String chinese) { this(english, chinese, "", ""); }
        Species(String english, String chinese, String formEnglish, String requiredItem) {
            this.english = english; this.chinese = chinese;
            this.formEnglish = formEnglish; this.requiredItem = requiredItem;
        }
        String label() { return chinese + " · " + (formEnglish.isEmpty() ? english : formEnglish); }
    }

    private final Map<String, String> moves = new HashMap<>();
    private final Map<String, MoveInfo> moveInfo = new HashMap<>();
    private final Map<String, String> englishMoves = new HashMap<>();
    private final Map<String, String> moveIds = new HashMap<>();
    private final List<Species> species = new ArrayList<>();

    DexNames(Context context) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("dex_names.tsv"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split("\t", -1);
                if (fields.length < 3) continue;
                if (fields[0].equals("M")) {
                    moves.put(id(fields[1]), fields[2]);
                    englishMoves.put(fields[2], fields[1]);
                    moveIds.put(id(fields[1]), fields[1]);
                    if (fields.length >= 6) {
                        try { moveInfo.put(id(fields[1]), new MoveInfo(fields[1], fields[2],
                                fields[3], Integer.parseInt(fields[4]), Integer.parseInt(fields[5]))); }
                        catch (NumberFormatException ignored) { }
                    }
                }
                else if (fields[0].equals("P")) species.add(new Species(fields[1], fields[2]));
            }
        } catch (Exception ignored) { }
    }

    String move(String english) { return moves.getOrDefault(id(english), english); }
    MoveInfo moveInfo(String english) {
        MoveInfo found = moveInfo.get(id(english));
        return found == null ? new MoveInfo(english, move(english), "Normal", 3, 60) : found;
    }
    String englishMove(String name) { return englishMoves.getOrDefault(name, name); }
    String moveEnglish(String moveId) { return moveIds.getOrDefault(id(moveId), moveId); }
    List<Species> findSpecies(String query) {
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        List<Species> result = new ArrayList<>();
        for (Species one : species) {
            if (normalized.isEmpty() || one.english.toLowerCase(Locale.ROOT).contains(normalized)
                    || one.chinese.contains(normalized)) result.add(one);
            if (result.size() >= 80) break;
        }
        return result;
    }
    Species speciesByEnglish(String english) {
        for (Species one : species) if (one.english.equalsIgnoreCase(english)) return one;
        return new Species(english, english);
    }
    private static String id(String value) { return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""); }
}
