package com.safesphere.backend.moderation.assessment;

import com.safesphere.backend.config.ModerationAiProperties.ModelMode;
import com.safesphere.backend.moderation.assessment.Evidence.Category;
import com.safesphere.backend.moderation.assessment.Evidence.Item;
import com.safesphere.backend.social.entity.PostEnums.ModerationReason;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 13E policy-1 (docs/PHASE13E_PLAN.md E5, E7, E15-E19, E23; design §8.2): turns the service's evidence into what
 * happens to a post or perspective. PURE and deterministic, like AreaIntelligencePolicy: no I/O, no clock, no counter.
 * The same rules are written once more in Python (policy_reference.py, offline evaluation only); both are pinned by
 * moderation-service/data/vectors/policy-vectors.tsv (DecisionPolicyTest).
 *
 * First match wins:
 *   1. the check was unavailable: a new account (probation) waits (HOLD, AWAITING_CHECK); everyone else is published;
 *   2. unquoted, targeted, severity 3 THREAT / SEXUAL_HARASSMENT / HATE / DOXXING -> HOLD (SEVERE);
 *      unquoted severity 3 SPAM -> HOLD (SPAM);
 *   3. a new account's post for everyone with photos -> HOLD for a moderator (NEW_ACCOUNT_PHOTOS, §8.5);
 *   4. unquoted, targeted, severity >= 2 HARASSMENT / SEXUAL_HARASSMENT / HATE / THREAT -> COLLAPSE (TARGETED);
 *   5. model ACTIVE and a score >= 0.9 -> COLLAPSE (MODEL): a model alone never holds;
 *   6. otherwise PUBLISH (QUOTED when only quoted evidence would have acted; STRONG_LANGUAGE; CLEAN).
 * Never a silent rejection, never a removal, never anything about the person: only humans restrict people.
 */
public final class DecisionPolicy {

    public static final String VERSION = "policy-1";
    static final int HOLD_SEVERITY = 3;
    static final int COLLAPSE_SEVERITY = 2;
    static final double MODEL_SCORE = 0.9;

    public enum Action { PUBLISH, PUBLISH_COLLAPSED, HOLD_FOR_REVIEW }

    public enum Rule { CLEAN, STRONG_LANGUAGE, QUOTED, TARGETED, SEVERE, SPAM, NEW_ACCOUNT_PHOTOS, CHECK_PENDING, MODEL }

    /** What is known besides the words: was the check available, a new account, a public photo post, the model mode. */
    public record Context(boolean available, boolean probation, boolean publicPhotos, ModelMode modelMode) {
    }

    /** [reason] is null exactly for PUBLISH (V24 ck_content_assessments_reason_action). */
    public record Decision(Action action, Rule rule, ModerationReason reason) {
    }

    private static final Set<Category> HOLD = EnumSet.of(Category.THREAT, Category.SEXUAL_HARASSMENT, Category.HATE,
            Category.DOXXING);
    private static final Set<Category> COLLAPSE = EnumSet.of(Category.HARASSMENT, Category.SEXUAL_HARASSMENT,
            Category.HATE, Category.THREAT);
    /** Which reason names a mixed finding: the most serious kind first. */
    private static final List<Category> RANK = List.of(Category.THREAT, Category.SEXUAL_HARASSMENT, Category.HATE,
            Category.DOXXING, Category.SPAM, Category.HARASSMENT);

    private DecisionPolicy() {
    }

    public static Decision decide(List<Item> evidence, boolean strongLanguage, Map<Category, Double> modelScores,
                                  Context context) {
        if (!context.available()) {
            return context.probation()
                    ? new Decision(Action.HOLD_FOR_REVIEW, Rule.CHECK_PENDING, ModerationReason.AWAITING_CHECK)
                    : new Decision(Action.PUBLISH, Rule.CHECK_PENDING, null);
        }
        List<Item> live = evidence.stream().filter(e -> !e.quoted()).toList();
        List<Item> hold = live.stream().filter(DecisionPolicy::holds).toList();
        if (!hold.isEmpty()) {
            Category top = top(hold);
            return new Decision(Action.HOLD_FOR_REVIEW, top == Category.SPAM ? Rule.SPAM : Rule.SEVERE, reason(top));
        }
        if (context.probation() && context.publicPhotos()) {
            return new Decision(Action.HOLD_FOR_REVIEW, Rule.NEW_ACCOUNT_PHOTOS, ModerationReason.NEEDS_REVIEW);
        }
        List<Item> collapse = live.stream().filter(DecisionPolicy::collapses).toList();
        if (!collapse.isEmpty()) return new Decision(Action.PUBLISH_COLLAPSED, Rule.TARGETED, reason(top(collapse)));
        if (context.modelMode() == ModelMode.ACTIVE && modelScores != null && !modelScores.isEmpty()) {
            Category best = modelScores.entrySet().stream()
                    .filter(e -> RANK.contains(e.getKey()))
                    .max(Map.Entry.<Category, Double>comparingByValue()
                            .thenComparing(e -> e.getKey().name(), Comparator.reverseOrder()))
                    .map(Map.Entry::getKey).orElse(null);
            if (best != null && modelScores.get(best) >= MODEL_SCORE) {
                return new Decision(Action.PUBLISH_COLLAPSED, Rule.MODEL, reason(best));
            }
        }
        boolean quotedWouldAct = evidence.stream().anyMatch(e -> e.quoted() && (holds(e) || collapses(e)));
        if (quotedWouldAct) return new Decision(Action.PUBLISH, Rule.QUOTED, null);
        return new Decision(Action.PUBLISH, strongLanguage ? Rule.STRONG_LANGUAGE : Rule.CLEAN, null);
    }

    static boolean holds(Item e) {
        return (HOLD.contains(e.category()) && e.targeted() && e.severity() >= HOLD_SEVERITY)
                || (e.category() == Category.SPAM && e.severity() >= HOLD_SEVERITY);
    }

    static boolean collapses(Item e) {
        return COLLAPSE.contains(e.category()) && e.targeted() && e.severity() >= COLLAPSE_SEVERITY;
    }

    private static Category top(List<Item> items) {
        return items.stream().min(Comparator.comparingInt((Item e) -> RANK.indexOf(e.category()))
                .thenComparing(Comparator.comparingInt(Item::severity).reversed())).orElseThrow().category();
    }

    /** The 13D reason code of a category (the same names). PROFANITY never becomes a reason. */
    static ModerationReason reason(Category category) {
        return ModerationReason.valueOf(category.name());
    }
}
