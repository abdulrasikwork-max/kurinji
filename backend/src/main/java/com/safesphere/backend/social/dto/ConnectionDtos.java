package com.safesphere.backend.social.dto;

import com.safesphere.backend.social.dto.ProfileDtos.PersonSummary;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** Phase 13A: connection requests, connections and invite codes. No answer carries a count. */
public final class ConnectionDtos {

    private ConnectionDtos() {
    }

    /** Body of POST /api/connections/requests: the person's public profile id. */
    public record ConnectRequest(@NotBlank(message = "Choose a person") @Size(max = 36) String profileId) {
    }

    /** REQUESTED = waiting for the other person; CONNECTED = connected now (they had asked you too, or an invite). */
    public enum ConnectStatus { REQUESTED, CONNECTED }

    public record ConnectResult(ConnectStatus status, PersonSummary person) {
    }

    /** A request in the caller's incoming or outgoing list. */
    public record RequestItem(String requestId, PersonSummary person) {
    }

    /** The code is in this answer ONLY (it is never stored and cannot be shown again). */
    public record InviteCreated(String id, String code, Instant expiresAt) {

        /** Never prints the code. */
        @Override
        public String toString() {
            return "InviteCreated[id=" + id + ", expiresAt=" + expiresAt + "]";
        }
    }

    public record InviteItem(String id, Instant expiresAt) {
    }

    /** Body of POST /api/connections/invites/redeem. */
    public record RedeemRequest(@NotBlank(message = "Enter the invite code") @Size(max = 64) String code) {

        /** Never prints the code. */
        @Override
        public String toString() {
            return "RedeemRequest[]";
        }
    }
}
