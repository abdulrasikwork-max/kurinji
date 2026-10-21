package com.safesphere.backend.intelligence.dto;

import com.safesphere.backend.community.entity.SignalCategory;
import com.safesphere.backend.community.entity.SignalCategory.SignalGroup;
import com.safesphere.backend.community.service.SignalPolicy.Confidence;
import com.safesphere.backend.community.service.SignalPolicy.Freshness;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.CellPicture;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.Concern;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.Information;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.Outlook;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.Picture;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.Reason;
import java.util.List;

/**
 * Phase 8 response bodies. Explicit FIELD ALLOW-LISTS like every community projection: codes and bands only. Nothing
 * here can carry an id of a person or a signal, a count, a weight, a trust tier, a timestamp or a pattern value.
 */
public final class IntelligenceDtos {

    private IntelligenceDtos() {
    }

    /**
     * The neighbourhood picture added to GET /api/community/area. [highestConcernCell] is one of the 6-character area
     * cells in [areas] (no extra precision), or null when nothing is confirmed. [areas] lists all nine cells.
     */
    public record AreaPicture(String modelVersion, Outlook outlook, Concern concern, Information information,
                              List<Reason> reasons, List<SignalCategory> concernCategories, boolean conflicting,
                              String highestConcernCell, List<AreaCellPicture> areas) {

        public static AreaPicture of(String modelVersion, Picture picture) {
            return new AreaPicture(modelVersion, picture.outlook(), picture.concern(), picture.information(),
                    picture.reasons(), picture.concernCategories(), picture.conflicting(),
                    picture.highestConcernCell(), picture.areas().stream().map(AreaCellPicture::of).toList());
        }
    }

    /** One ~1 km area cell, enough for the radar's shading (information) and outline (outlook). */
    public record AreaCellPicture(String cell, Outlook outlook, Information information) {

        public static AreaCellPicture of(CellPicture picture) {
            return new AreaCellPicture(picture.cell(), picture.outlook(), picture.information());
        }
    }

    // ---- Phase 8D: region pack for "Check the way" -----------------------------------------------------------------

    /**
     * GET /api/intelligence/region. [areas] lists only cells the community knows something about: an ABSENT cell means
     * NO_INFORMATION (not "safe"). [signals] are CONFIRMED signals only (already public at block precision), without
     * ids; single reports never appear individually. [complete] false = too many to send: the phone shows no brief.
     */
    public record RegionPack(String region, String modelVersion, boolean complete, List<RegionArea> areas,
                             List<RegionSignal> signals) {
    }

    public record RegionArea(String cell, Outlook outlook, Concern concern, Information information,
                             List<Reason> reasons, List<SignalCategory> concernCategories, boolean conflicting) {

        public static RegionArea of(CellPicture picture) {
            return new RegionArea(picture.cell(), picture.outlook(), picture.concern(), picture.information(),
                    picture.reasons(), picture.concernCategories(), picture.conflicting());
        }
    }

    /** [freshness] is the INDEPENDENT freshness; [confidence] is MODERATE or HIGH. */
    public record RegionSignal(SignalCategory category, SignalGroup group, String cell, Confidence confidence,
                               Freshness freshness, boolean disputed) {
    }
}
