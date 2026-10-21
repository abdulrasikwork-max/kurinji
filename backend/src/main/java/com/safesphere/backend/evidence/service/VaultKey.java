package com.safesphere.backend.evidence.service;

import com.safesphere.backend.config.EvidenceProperties;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The PUBLIC half of the vault recovery key (app.evidence.vault-public-key).
 *
 * Why it exists: an item's key lives in the phone's Keystore. If the phone is taken or destroyed, the uploaded
 * ciphertext would be unreadable forever. So the phone can also seal the item key with this RSA public key (escrow).
 * The PRIVATE half is generated and kept OFFLINE by whoever operates SafeSphere (see VaultKeyTool and
 * docs/PHASE5.md); the running server never has it, so a server or database breach cannot decrypt any evidence.
 * Recovery (for the owner, later a legal pathway) is a deliberate offline step with that private key.
 *
 * Not configured = no escrow: the phone then uploads ciphertext that only it can open, and says so.
 */
@Component
public class VaultKey {

    public static final String ALGORITHM = "RSA-OAEP-SHA256";
    static final int MIN_BITS = 3072;

    private static final Logger log = LoggerFactory.getLogger(VaultKey.class);

    private final Loaded loaded;

    public VaultKey(EvidenceProperties properties) {
        this.loaded = load(properties.vaultPublicKey());
        if (loaded == null) {
            log.info("Evidence vault recovery key not configured: uploaded evidence is readable only on the recording phone");
        } else {
            log.info("Evidence vault recovery key loaded ({} bits, id {})", loaded.bits(), loaded.keyId());
        }
    }

    public Optional<Loaded> current() {
        return Optional.ofNullable(loaded);
    }

    private static Loaded load(String configured) {
        if (configured == null || configured.isBlank()) return null;
        String base64 = configured.replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "").replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(base64);
            RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            int bits = key.getModulus().bitLength();
            if (bits < MIN_BITS) {
                throw new IllegalStateException("app.evidence.vault-public-key must be at least " + MIN_BITS + " bits");
            }
            return new Loaded(Sha256.ofBytes(der), Base64.getEncoder().encodeToString(der), bits, (bits + 7) / 8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("app.evidence.vault-public-key is not a valid RSA public key", e);
        }
    }

    /**
     * @param keyId          SHA-256 of the DER encoding: phones send it back, and may pin it
     * @param publicKeyBase64 X.509 DER, base64
     * @param wrappedKeyBytes exact length of an RSA-OAEP output for this key
     */
    public record Loaded(String keyId, String publicKeyBase64, int bits, int wrappedKeyBytes) {
    }
}
