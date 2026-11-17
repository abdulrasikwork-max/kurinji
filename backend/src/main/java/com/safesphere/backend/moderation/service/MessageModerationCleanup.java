package com.safesphere.backend.moderation.service;

import com.safesphere.backend.moderation.assessment.ContentAssessmentRepository;
import com.safesphere.backend.moderation.repository.ContentCaseRepository;
import com.safesphere.backend.moderation.repository.ContentReportRepository;
import com.safesphere.backend.social.service.ResponseData;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Phase N5: when messages go (with their moment, or 7 days after its end), their reports, cases and automatic checks
 * go first. MySQL also cascades them; this keeps every database tidy. Runs inside the caller's transaction. The purge
 * never reaches a message an open case needs (MomentRepository.NO_MESSAGE_IN_REVIEW); a deleted moment takes its
 * messages' cases with it, as it takes its own (13D).
 */
@Component
public class MessageModerationCleanup implements ResponseData.MessageCleanup {

    private final ContentReportRepository reports;
    private final ContentCaseRepository cases;
    private final ContentAssessmentRepository assessments;

    public MessageModerationCleanup(ContentReportRepository reports, ContentCaseRepository cases,
                                    ContentAssessmentRepository assessments) {
        this.reports = reports;
        this.cases = cases;
        this.assessments = assessments;
    }

    @Override
    public void messagesGoing(Collection<Long> messageIds) {
        List<Long> caseIds = cases.idsByMessageIds(messageIds);
        if (!caseIds.isEmpty()) reports.deleteByCaseIds(caseIds);
        reports.deleteByMessageIds(messageIds);
        cases.deleteByMessageIds(messageIds);
        assessments.deleteByMessageIds(messageIds);
    }
}
