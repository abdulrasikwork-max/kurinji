package com.safesphere.backend.circlealert.service;

import com.safesphere.backend.emergency.event.SosChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * N6: after the SOS change is committed, the alert queue runs at once (asynchronously; it never delays the SOS).
 * N7: an ended SOS also sends its one update to the Circle members who got it in the app.
 */
@Component
public class SosChangedListener {

    private static final Logger log = LoggerFactory.getLogger(SosChangedListener.class);

    private final AlertDispatcher dispatcher;
    private final AlertQueue queue;

    public SosChangedListener(AlertDispatcher dispatcher, AlertQueue queue) {
        this.dispatcher = dispatcher;
        this.queue = queue;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(SosChanged event) {
        dispatcher.kick();
        try {
            queue.sosEnded(event.emergencyId());
        } catch (RuntimeException e) {
            log.warn("The SOS end update could not be planned ({})", e.getClass().getSimpleName());
        }
    }
}
