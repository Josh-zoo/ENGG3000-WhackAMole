package whackamole;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Keeps the top name+score entries across runs, persisted to a small text
 * file next to wherever the game is launched from. One "name,score" line
 * per entry, highest score first.
 */
public class HighScoreManager {
    private static final String FILE_NAME = "highscores.txt";
    private static final int MAX_ENTRIES = 5;

    public record Entry(String name, int score) {}

    private final List<Entry> entries = new ArrayList<>();

    public HighScoreManager() {
        load();
    }

    public List<Entry> getTopScores() {
        return Collections.unmodifiableList(entries);
    }

    /** Adds an entry, keeps only the top MAX_ENTRIES, and persists to disk. Returns true if it made the list. */
    public boolean addScore(String name, int score) {
        String safeName = sanitize(name);
        entries.add(new Entry(safeName, score));
        entries.sort((a, b) -> Integer.compare(b.score(), a.score()));
        boolean madeList = entries.size() <= MAX_ENTRIES || entries.indexOf(new Entry(safeName, score)) < MAX_ENTRIES;
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.size() - 1);
        }
        save();
        return madeList;
    }

    /** Blank/whitespace-only input becomes "Guest"; names are trimmed and capped at 12 characters. */
    private String sanitize(String name) {
        if (name == null) return "Guest";
        String cleaned = name.replace(",", " ").replace("\n", " ").trim();
        if (cleaned.isEmpty()) return "Guest";
        return cleaned.length() > 12 ? cleaned.substring(0, 12) : cleaned;
    }

    private void load() {
        File file = new File(FILE_NAME);
        if (!file.exists()) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int comma = line.lastIndexOf(',');
                if (comma < 0) continue;
                String name = line.substring(0, comma).trim();
                String scoreStr = line.substring(comma + 1).trim();
                try {
                    int score = Integer.parseInt(scoreStr);
                    entries.add(new Entry(name.isEmpty() ? "Guest" : name, score));
                } catch (NumberFormatException ignored) {
                    // skip a malformed line rather than failing the whole load
                }
            }
            entries.sort((a, b) -> Integer.compare(b.score(), a.score()));
        } catch (IOException e) {
            // no saved scores yet, or unreadable file — start fresh
        }
    }

    private void save() {
        try (PrintWriter writer = new PrintWriter(new FileWriter(FILE_NAME))) {
            for (Entry e : entries) {
                writer.println(e.name() + "," + e.score());
            }
        } catch (IOException e) {
            // best-effort persistence; a failed save shouldn't crash the game
        }
    }
}