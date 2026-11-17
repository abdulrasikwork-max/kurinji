package com.safesphere.backend.social.service;

import com.safesphere.backend.social.entity.Moment;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/**
 * Phase N5: what happens to a plan's conversation when its people change (N-90, N-92). Called inside the write that
 * changed them. The plan's conversation arrives in unit B2; until then there is no conversation to write to.
 */
@Component
public class PlanEvents {

    /** Someone was added: the plan opens with the first one, and says "… is in". */
    public void added(Moment plan, long userId, LocalDateTime now) {
    }

    /** Someone who was in left: "… can't make it". */
    public void left(Moment plan, long userId, LocalDateTime now) {
    }

    /** The author removed someone: nobody else is told (no line). */
    public void removed(Moment plan, long userId, LocalDateTime now) {
    }

    /** The author called it off: the plan says so and closes now. */
    public void calledOff(Moment plan, LocalDateTime now) {
    }
}
