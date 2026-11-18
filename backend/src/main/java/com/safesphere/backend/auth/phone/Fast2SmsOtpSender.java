package com.safesphere.backend.auth.phone;

import com.safesphere.backend.common.sms.Fast2SmsClient;
import com.safesphere.backend.common.sms.SmsGateway.SmsRefusedException;

/**
 * Phase 17B (changed 2026-11-09; N6: the HTTP call moved to the shared common/sms/Fast2SmsClient): delivers codes through
 * Fast2SMS's Quick SMS route. It only DELIVERS: the code is made, stored, expired and checked by this server. Never logs
 * the code or the number; a refusal carries Fast2SMS's status code only.
 */
public class Fast2SmsOtpSender implements OtpSender {

    /** Plain ASCII on purpose: one English SMS holds 160 characters, one Unicode part only 70 (each part is billed). */
    static final String MESSAGE_TEMPLATE = "%s is your IRL verification code. Do not share it with anyone.";

    private final Fast2SmsClient client;

    public Fast2SmsOtpSender(Fast2SmsClient client) {
        this.client = client;
    }

    @Override
    public void send(String phoneE164, String code) {
        if (code == null || !code.matches("^[0-9]{4,10}$")) throw new OtpDeliveryException("code not numeric");
        if (!PhoneNumbers.isE164(phoneE164)) throw new OtpDeliveryException("not a stored number");
        try {
            client.send(phoneE164, MESSAGE_TEMPLATE.formatted(code));
        } catch (SmsRefusedException e) {
            throw new OtpDeliveryException(e.getMessage());
        }
    }

    @Override
    public String describe() {
        return client.describe();
    }
}
