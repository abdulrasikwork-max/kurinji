package com.safesphere.backend.moderation.assessment;

import java.util.List;
import java.util.Map;

/**
 * Phase 13E: what the moderation service says about one text (moderation-service/kurinji_moderation/detect.py). It is
 * EVIDENCE, never a decision: DecisionPolicy decides. Self-harm is a flag only, never a category, never stored.
 */
public final class Evidence {

    private Evidence() {
    }

    /** The categories the service may name (anything else it sends is dropped). PROFANITY never acts by itself. */
    public enum Category { PROFANITY, HARASSMENT, SEXUAL_HARASSMENT, HATE, THREAT, DOXXING, SPAM }

    /**
     * One finding: [severity] 0-3, [targeted] (aimed at a person or a group), [quoted] (inside quotation marks or reported
     * speech: someone describing what was said), the flagged phrase as code-point offsets [start, end) of the text.
     */
    public record Item(Category category, int severity, boolean targeted, boolean quoted, int start, int end) {

        /** The stored form CATEGORY:severity:T|U:Q|- (V24 ck_content_assessments_evidence). */
        public String code() {
            return category.name() + ":" + severity + ":" + (targeted ? "T" : "U") + ":" + (quoted ? "Q" : "-");
        }
    }

    /** One answer of the service: its versions, the findings and the flags (model scores only if a model is loaded). */
    public record Answer(String normaliserVersion, String lexiconVersion, String modelVersion, List<Item> items,
                         boolean strongLanguage, boolean selfHarm, Map<Category, Double> modelScores) {
    }
}
