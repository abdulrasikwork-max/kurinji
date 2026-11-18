package com.safesphere.backend.push.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** N7 (D28, D31): the push API's bodies. No user id anywhere: the caller and the sign-in come from the token. */
public final class PushDtos {

    private PushDtos() {
    }

    /** This phone's FCM token for the CURRENT sign-in. */
    public record DeviceRequest(
            @NotBlank(message = "Give the token") @Size(max = 512, message = "The token is too long") String token,
            @Size(max = 32, message = "The app version is too long") String appVersion) {

        /** Never prints the token. */
        @Override
        public String toString() {
            return "DeviceRequest[appVersion=" + appVersion + "]";
        }
    }

    /** The person's switches (D31). Staff is only offered to staff, but anyone may store it. */
    public record Settings(@NotNull Boolean replies, @NotNull Boolean plans, @NotNull Boolean planMessages,
                           @NotNull Boolean people, @NotNull Boolean staff) {
    }
}
