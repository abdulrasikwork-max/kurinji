package com.safesphere.backend.moderation.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import com.safesphere.backend.config.ModerationAiProperties.ModelMode;
import com.safesphere.backend.moderation.assessment.DecisionPolicy.Action;
import com.safesphere.backend.moderation.assessment.DecisionPolicy.Context;
import com.safesphere.backend.moderation.assessment.DecisionPolicy.Decision;
import com.safesphere.backend.moderation.assessment.DecisionPolicy.Rule;
import com.safesphere.backend.moderation.assessment.Evidence.Category;
import com.safesphere.backend.moderation.assessment.Evidence.Item;
import com.safesphere.backend.social.entity.PostEnums.ModerationReason;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Phase 13E: policy-1. The shared vectors (moderation-service/data/vectors/policy-vectors.tsv) pin this class to the
 * Python reference used by the offline evaluation, so the numbers in docs/PHASE13E.md describe the policy that runs.
 */
class DecisionPolicyTest {

    static final Path VECTORS = Path.of("..", "moderation-service", "data", "vectors", "policy-vectors.tsv");
    static final Context OK = new Context(true, false, false, ModelMode.OFF);

    @Test
    void sharedVectors_matchTheJavaPolicy() throws IOException {
        List<String> lines = Files.readAllLines(VECTORS, StandardCharsets.UTF_8).stream()
                .filter(l -> !l.startsWith("#") && !l.isBlank()).toList();
        assertThat(lines).hasSizeGreaterThan(35);
        for (String line : lines) {
            String[] f = line.split("\t");
            Map<Category, Double> scores = new EnumMap<>(Category.class);
            if (!f[6].equals("-")) {
                for (String p : f[6].split(",")) {
                    String[] kv = p.split("=");
                    scores.put(Category.valueOf(kv[0]), Double.parseDouble(kv[1]));
                }
            }
            Decision d = DecisionPolicy.decide(items(f[0]), f[1].equals("1"), scores,
                    new Context(f[2].equals("1"), f[3].equals("1"), f[4].equals("1"), ModelMode.valueOf(f[5])));
            assertThat(d.action().name() + " " + d.rule().name() + " " + (d.reason() == null ? "-" : d.reason().name()))
                    .as(line).isEqualTo(f[7] + " " + f[8] + " " + f[9]);
        }
    }

    @Test
    void anOutage_neverSilencesAnyone_newAccountsWait() {
        Context down = new Context(false, false, false, ModelMode.OFF);
        assertThat(DecisionPolicy.decide(List.of(), false, Map.of(), down).action()).isEqualTo(Action.PUBLISH);
        Decision wait = DecisionPolicy.decide(List.of(), false, Map.of(), new Context(false, true, false, ModelMode.OFF));
        assertThat(wait).isEqualTo(new Decision(Action.HOLD_FOR_REVIEW, Rule.CHECK_PENDING,
                ModerationReason.AWAITING_CHECK));
    }

    @Test
    void quotedEvidence_neverActs_butIsNamed() {
        Decision d = DecisionPolicy.decide(List.of(new Item(Category.THREAT, 3, true, true, 0, 5)), false, Map.of(), OK);
        assertThat(d).isEqualTo(new Decision(Action.PUBLISH, Rule.QUOTED, null));
    }

    @Test
    void untargetedProfanity_andMildWords_neverAct() {
        assertThat(DecisionPolicy.decide(List.of(new Item(Category.PROFANITY, 3, false, false, 0, 4),
                new Item(Category.HARASSMENT, 1, true, false, 5, 9)), true, Map.of(), OK))
                .isEqualTo(new Decision(Action.PUBLISH, Rule.STRONG_LANGUAGE, null));
    }

    @Test
    void anInsult_onlyEverCollapses_aThreatHolds() {
        assertThat(DecisionPolicy.decide(List.of(new Item(Category.HARASSMENT, 3, true, false, 0, 4)), false, Map.of(),
                OK).action()).isEqualTo(Action.PUBLISH_COLLAPSED);
        assertThat(DecisionPolicy.decide(List.of(new Item(Category.THREAT, 3, true, false, 0, 4)), false, Map.of(), OK))
                .isEqualTo(new Decision(Action.HOLD_FOR_REVIEW, Rule.SEVERE, ModerationReason.THREAT));
    }

    @Test
    void aModel_actsOnlyWhenActive_andNeverHoldsAlone() {
        Map<Category, Double> sure = Map.of(Category.THREAT, 0.99);
        assertThat(DecisionPolicy.decide(List.of(), false, sure, OK).action()).isEqualTo(Action.PUBLISH);
        assertThat(DecisionPolicy.decide(List.of(), false, sure, new Context(true, false, false, ModelMode.SHADOW))
                .action()).isEqualTo(Action.PUBLISH);
        assertThat(DecisionPolicy.decide(List.of(), false, sure, new Context(true, false, false, ModelMode.ACTIVE)))
                .isEqualTo(new Decision(Action.PUBLISH_COLLAPSED, Rule.MODEL, ModerationReason.THREAT));
    }

    @Test
    void theReasonIsNullExactlyForPublish() {
        for (String line : List.of("THREAT:3:T:-", "HARASSMENT:2:T:-", "PROFANITY:1:U:-", "")) {
            for (boolean available : new boolean[] {true, false}) {
                for (boolean probation : new boolean[] {true, false}) {
                    Decision d = DecisionPolicy.decide(items(line), false, Map.of(),
                            new Context(available, probation, probation, ModelMode.OFF));
                    assertThat(d.reason() == null).as(line).isEqualTo(d.action() == Action.PUBLISH);
                }
            }
        }
    }

    static List<Item> items(String codes) {
        List<Item> out = new ArrayList<>();
        if (codes.isEmpty() || codes.equals("-")) return out;
        for (String code : codes.split(";")) {
            String[] p = code.split(":");
            out.add(new Item(Category.valueOf(p[0]), Integer.parseInt(p[1]), p[2].equals("T"), p[3].equals("Q"), 0, 1));
        }
        return out;
    }
}
