package com.safesphere.backend.evidence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.safesphere.backend.evidence.tools.SsevFormat;
import com.safesphere.backend.evidence.tools.VaultKeyTool;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The offline vault-key tool: passphrase-protected private keys and the "does this key belong to that one" check. */
class VaultKeyToolTest {

    private static KeyPair pair;
    private static KeyPair other;

    @TempDir Path folder;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        pair = generator.generateKeyPair();
        other = generator.generateKeyPair();
    }

    @Test
    void anEncryptedPrivateKey_isUnreadableWithoutThePassphrase_andWorksWithIt() throws Exception {
        String pem = VaultKeyTool.privateKeyPem(pair.getPrivate(), "a long vault passphrase".toCharArray());
        assertTrue(pem.startsWith("-----BEGIN ENCRYPTED PRIVATE KEY-----"));
        Path file = folder.resolve("vault.pem");
        Files.writeString(file, pem, StandardCharsets.US_ASCII);
        // The raw private key bytes do not appear in the file.
        String plainBody = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()).substring(0, 40);
        assertFalse(pem.replaceAll("\\s", "").contains(plainBody));

        PrivateKey read = VaultKeyTool.readPrivateKey(file, () -> "a long vault passphrase".toCharArray());
        assertArrayEquals(pair.getPrivate().getEncoded(), read.getEncoded());

        assertThrows(GeneralSecurityException.class,
                () -> VaultKeyTool.readPrivateKey(file, () -> "the wrong passphrase!".toCharArray()));
        assertThrows(IllegalStateException.class, () -> VaultKeyTool.readPrivateKey(file));
    }

    @Test
    void aShortPassphraseIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> VaultKeyTool.privateKeyPem(pair.getPrivate(), "short".toCharArray()));
    }

    @Test
    void anUnencryptedKeyStillReads() throws Exception {
        Path file = folder.resolve("plain.pem");
        Files.writeString(file, VaultKeyTool.privateKeyPem(pair.getPrivate(), null), StandardCharsets.US_ASCII);
        assertArrayEquals(pair.getPrivate().getEncoded(), VaultKeyTool.readPrivateKey(file).getEncoded());
    }

    @Test
    void check_acceptsTheMatchingPair_andRefusesAnotherKey() throws Exception {
        String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        assertEquals(SsevFormat.sha256Hex(pair.getPublic().getEncoded()), VaultKeyTool.check(pair.getPrivate(), publicKey));

        GeneralSecurityException wrong = assertThrows(GeneralSecurityException.class,
                () -> VaultKeyTool.check(other.getPrivate(), publicKey));
        assertEquals("The private key does NOT belong to this vault key", wrong.getMessage());
    }
}
