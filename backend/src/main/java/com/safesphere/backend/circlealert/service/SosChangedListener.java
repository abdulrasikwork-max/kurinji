package com.safesphere.backend.circlealert.service;

import com.safesphere.backend.emergency.event.SosChanged;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** N6: after the SOS change is committed, the alert queue runs at once (asynchronously; it never delays the SOS). */
@Component
public class SosChangedListener {

    private final AlertDispatcher dispatcher;

    public SosChangedListener(AlertDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(SosChanged event) {
        dispatcher.kick();
    }
}
