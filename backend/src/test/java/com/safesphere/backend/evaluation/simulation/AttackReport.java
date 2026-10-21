package com.safesphere.backend.evaluation.simulation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects what each Phase 9A scenario measured and writes it to target/attack-simulation/[section].md after the
 * test class. The file is a test OUTPUT (regenerated on every run, never committed); docs/PHASE9.md records the results
 * of a named run. Every file starts with the SIMULATED banner: these are synthetic accounts on an in-memory database,
 * not real-world data, and must never be quoted as real performance.
 */
final class AttackReport {

    static final String BANNER = "> **SIMULATED — not real-world data.** Synthetic accounts and places (open ocean), "
            + "in-memory test database, virtual clock. The rules, limits and API are the real ones "
            + "(active rules-1.1 and the rules-2 candidate, Phase 6/7 community, trust and moderation, the 9C "
            + "coordination check and the 9D neighbour join).";

    /** One measured line: what was tried, what it cost the attacker, what the server did, what a viewer saw. */
    record Row(String scenario, String question, String effort, String time, String response, String viewerSees) {
    }

    private static final Map<String, List<Row>> SECTIONS = new LinkedHashMap<>();
    private static final Map<String, List<String>> NOTES = new LinkedHashMap<>();

    private AttackReport() {
    }

    static synchronized void add(String section, Row row) {
        SECTIONS.computeIfAbsent(section, k -> new ArrayList<>()).add(row);
    }

    static synchronized void note(String section, String line) {
        NOTES.computeIfAbsent(section, k -> new ArrayList<>()).add(line);
    }

    /** Banner for the pure measurements (no database, no clock). */
    static final String PURE_BANNER = "> **SIMULATED — not real-world data.** Pure computation with the real GeoCell / "
            + "AreaIntelligencePolicy code and a fixed random seed; the inputs are generated, not observed.";

    static synchronized void write(String section, String title) throws IOException {
        write(section, title, BANNER);
    }

    static synchronized void write(String section, String title, String banner) throws IOException {
        StringBuilder md = new StringBuilder("# ").append(title).append("\n\n").append(banner).append("\n\n");
        List<Row> rows = SECTIONS.getOrDefault(section, List.of());
        if (!rows.isEmpty()) {
            md.append("| Scenario | Question | Attacker effort | Simulated time | What the server did | What a viewer sees |\n");
            md.append("|---|---|---|---|---|---|\n");
            for (Row r : rows) {
                md.append("| ").append(cell(r.scenario())).append(" | ").append(cell(r.question())).append(" | ")
                        .append(cell(r.effort())).append(" | ").append(cell(r.time())).append(" | ")
                        .append(cell(r.response())).append(" | ").append(cell(r.viewerSees())).append(" |\n");
            }
            md.append('\n');
        }
        for (String line : NOTES.getOrDefault(section, List.of())) md.append("- ").append(line).append('\n');
        Path dir = Path.of("target", "attack-simulation");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(section + ".md"), md.toString(), StandardCharsets.UTF_8);
    }

    private static String cell(String text) {
        return text == null ? "" : text.replace("|", "\\|").replace("\n", " ");
    }
}
