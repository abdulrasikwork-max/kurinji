package com.safesphere.backend.evidence;

import static com.safesphere.backend.support.TestAccounts.PASSWORD;
import static com.safesphere.backend.support.TestAccounts.bearer;
import static com.safesphere.backend.support.TestAccounts.registerAndGetToken;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.emergency.repository.EmergencyEventRepository;
import com.safesphere.backend.evidence.repository.CustodyEventRepository;
import com.safesphere.backend.evidence.repository.EvidenceItemRepository;
import com.safesphere.backend.evidence.repository.RecoveryEnvelopeRepository;
import com.safesphere.backend.evidence.repository.RecoveryFailureRepository;
import com.safesphere.backend.evidence.tools.SsevFormat;
import com.safesphere.backend.safetycontact.repository.SafetyContactRepository;
import com.safesphere.backend.user.repository.UserRepository;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Restore on a new phone (V11). The test plays the PHONE's part with plain JCE (PBKDF2 + AES-GCM, the same formats as
 * the Android RecoveryKeyCrypto) to prove the full round trip: passphrase -> envelope -> recovery key -> item key ->
 * SSEV1 plaintext, while the server only ever stores and returns opaque bytes.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EvidenceRecoveryApiIntegrationTest {

    private static final int CHUNK = 4096;
    private static final int ITERATIONS = 600_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private SafetyContactRepository contactRepository;
    @Autowired private EmergencyEventRepository emergencyRepository;
    @Autowired private EvidenceItemRepository evidenceRepository;
    @Autowired private CustodyEventRepository custodyRepository;
    @Autowired private RecoveryEnvelopeRepository envelopeRepository;
    @Autowired private RecoveryFailureRepository failureRepository;
    @Autowired private JdbcTemplate jdbc;

    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setUp() throws Exception {
        custodyRepository.deleteAll();
        evidenceRepository.deleteAll();
        envelopeRepository.deleteAll();
        failureRepository.deleteAll();
        contactRepository.deleteAll();
        emergencyRepository.deleteAll();
        userRepository.deleteAll();
        tokenA = registerAndGetToken(mockMvc, objectMapper, "recovery.a@example.com");
        tokenB = registerAndGetToken(mockMvc, objectMapper, "recovery.b@example.com");
    }

    @Test
    void fullRestore_passphraseOpensTheEnvelope_theRecoveryKeyOpensTheItemKey_andTheItemDecrypts() throws Exception {
        byte[] rk = randomBytes(32);
        byte[] salt = randomBytes(16);
        char[] passphrase = "correct horse battery staple river".toCharArray();
        setRecovery(tokenA, PASSWORD, null, 1, salt, wrapEnvelope(passphrase, salt, rk)).andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.rkVersion").value(1))
                .andExpect(jsonPath("$.envelopeVersion").value(1))
                .andExpect(jsonPath("$.lastDownloadedAt").value(nullValue()));

        long emergency = startEmergency(tokenA);
        byte[] plaintext = randomBytes(7_000);
        byte[] itemKey = randomBytes(32);
        byte[] ciphertext = ssev(itemKey, plaintext);
        Map<String, Object> request = declaration(ciphertext, plaintext);
        String clientRef = (String) request.get("clientRef");
        request.put("recoveryRkVersion", 1);
        request.put("recoveryWrappedKey", b64(wrapItemKey(rk, itemKey, clientRef, SsevFormat.sha256Hex(ciphertext))));
        long id = json(declare(tokenA, emergency, request).andExpect(status().isCreated())
                .andExpect(jsonPath("$.recoverable").value(true))
                .andExpect(jsonPath("$.recoveryWrappedKey").doesNotExist())
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        uploadAll(tokenA, id, ciphertext);
        mockMvc.perform(post("/api/evidence/" + id + "/seal").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.status").value("SEALED"));

        // ---- the NEW phone: signs in, knows only the passphrase ----
        JsonNode envelope = json(fetchEnvelope(tokenA, PASSWORD).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.kdf").value("PBKDF2-SHA256"))
                .andExpect(jsonPath("$.kdfIterations").value(ITERATIONS))
                .andReturn().getResponse().getContentAsString());
        byte[] openedRk = openEnvelope(passphrase, Base64.getDecoder().decode(envelope.get("kdfSalt").asText()),
                Base64.getDecoder().decode(envelope.get("wrappedRecoveryKey").asText()), 1);
        assertArrayEquals(rk, openedRk);

        JsonNode page = json(mockMvc.perform(get("/api/evidence/recovery/items").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andReturn().getResponse().getContentAsString());
        JsonNode entry = page.get("items").get(0);
        byte[] restoredItemKey = openItemKey(openedRk, Base64.getDecoder().decode(entry.get("recoveryWrappedKey").asText()),
                entry.get("item").get("clientRef").asText(), entry.get("item").get("ciphertextSha256").asText());
        byte[] downloaded = mockMvc.perform(get("/api/evidence/" + id + "/content").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(plaintext, SsevFormat.decrypt(restoredItemKey, downloaded));

        mockMvc.perform(get("/api/evidence/recovery").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.lastDownloadedAt").isNotEmpty())
                .andExpect(jsonPath("$.wrappedRecoveryKey").doesNotExist())
                .andExpect(jsonPath("$.kdfSalt").doesNotExist());
    }

    @Test
    void aWrongPassphraseCannotOpenTheEnvelope() throws Exception {
        byte[] salt = randomBytes(16);
        byte[] envelope = wrapEnvelope("right passphrase words here".toCharArray(), salt, randomBytes(32));
        org.junit.jupiter.api.Assertions.assertThrows(javax.crypto.AEADBadTagException.class,
                () -> openEnvelope("wrong passphrase words here".toCharArray(), salt, envelope, 1));
    }

    @Test
    void settingAndFetching_needTheAccountPassword_andRepeatedWrongPasswordsLockRecovery() throws Exception {
        byte[] salt = randomBytes(16);
        setRecovery(tokenA, "not-my-password", null, 1, salt, randomBytes(60)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Password is incorrect"));
        mockMvc.perform(get("/api/evidence/recovery").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.configured").value(false));
        setRecovery(tokenA, PASSWORD, null, 1, salt, randomBytes(60)).andExpect(status().isOk());

        for (int i = 0; i < 4; i++) {
            fetchEnvelope(tokenA, "guess-" + i).andExpect(status().isConflict());
        }
        fetchEnvelope(tokenA, PASSWORD).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many wrong passwords. Recovery is locked; try again in 15 minutes"));
        assertEquals(5, failureRepository.count(), "1 + 4 wrong passwords recorded (committed although they failed)");
        // B is not affected.
        fetchEnvelope(tokenB, PASSWORD).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("No recovery passphrase is set up"));
    }

    @Test
    void envelopeFormats_arePinned() throws Exception {
        setRecovery(tokenA, PASSWORD, null, 1, randomBytes(16), randomBytes(59)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Wrapped recovery key has the wrong length"));
        setRecovery(tokenA, PASSWORD, null, 1, randomBytes(8), randomBytes(60)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Salt must be 16 to 32 bytes"));
        setRecovery(tokenA, PASSWORD, null, 2, randomBytes(16), randomBytes(60)).andExpect(status().isBadRequest());

        Map<String, Object> weak = envelopeBody(PASSWORD, null, 1, randomBytes(16), randomBytes(60));
        weak.put("kdfIterations", 100_000);
        putRecovery(tokenA, weak).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Iterations must be between 600000 and 10000000"));
        Map<String, Object> otherKdf = envelopeBody(PASSWORD, null, 1, randomBytes(16), randomBytes(60));
        otherKdf.put("kdf", "MD5");
        putRecovery(tokenA, otherKdf).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unsupported key derivation"));
        assertEquals(0, envelopeRepository.count());
    }

    @Test
    void changingThePassphrase_needsTheCurrentVersion_andKeepsTheRecoveryKeyGeneration() throws Exception {
        setRecovery(tokenA, PASSWORD, null, 1, randomBytes(16), randomBytes(60)).andExpect(status().isOk());
        // A second "first set-up" (e.g. another phone that did not know) must not silently overwrite it.
        setRecovery(tokenA, PASSWORD, null, 1, randomBytes(16), randomBytes(60)).andExpect(status().isConflict());
        setRecovery(tokenA, PASSWORD, 7, 1, randomBytes(16), randomBytes(60)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The recovery passphrase was changed elsewhere; check it again"));
        setRecovery(tokenA, PASSWORD, 1, 2, randomBytes(16), randomBytes(60)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The recovery key cannot be replaced"));
        setRecovery(tokenA, PASSWORD, 1, 1, randomBytes(16), randomBytes(60)).andExpect(status().isOk())
                .andExpect(jsonPath("$.envelopeVersion").value(2))
                .andExpect(jsonPath("$.rkVersion").value(1));
    }

    @Test
    void attach_backFillsOlderItems_isWriteOnce_andChecksVersionAndLength() throws Exception {
        long emergency = startEmergency(tokenA);
        byte[] ciphertext = ssev(randomBytes(32), randomBytes(500));
        long id = json(declare(tokenA, emergency, declaration(ciphertext, randomBytes(1)))
                .andExpect(jsonPath("$.recoverable").value(false))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();

        attach(tokenA, id, 1, randomBytes(60)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("No recovery passphrase is set up"));
        setRecovery(tokenA, PASSWORD, null, 1, randomBytes(16), randomBytes(60)).andExpect(status().isOk());
        attach(tokenA, id, 2, randomBytes(60)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The recovery key has changed; fetch it again"));
        attach(tokenA, id, 1, randomBytes(30)).andExpect(status().isBadRequest());

        byte[] first = randomBytes(60);
        attach(tokenA, id, 1, first).andExpect(status().isOk()).andExpect(jsonPath("$.recoverable").value(true));
        attach(tokenA, id, 1, randomBytes(60)).andExpect(status().isOk()); // a retry: the first copy stays
        assertArrayEquals(first, jdbc.queryForObject("SELECT recovery_wrapped_key FROM evidence_items WHERE id = ?",
                byte[].class, id));

        attach(tokenB, id, 1, randomBytes(60)).andExpect(status().isNotFound());
    }

    @Test
    void declare_recoveryFieldsGoTogether_andMustUseTheCurrentKey() throws Exception {
        long emergency = startEmergency(tokenA);
        byte[] ciphertext = ssev(randomBytes(32), randomBytes(100));
        Map<String, Object> half = declaration(ciphertext, randomBytes(1));
        half.put("recoveryRkVersion", 1);
        declare(tokenA, emergency, half).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Recovery key version and recovery key go together"));

        Map<String, Object> noEnvelope = declaration(ciphertext, randomBytes(1));
        noEnvelope.put("recoveryRkVersion", 1);
        noEnvelope.put("recoveryWrappedKey", b64(randomBytes(60)));
        declare(tokenA, emergency, noEnvelope).andExpect(status().isConflict());
        assertEquals(0, evidenceRepository.count(), "a refused declaration stores nothing");
    }

    @Test
    void deleteEverywhere_erasesTheRecoveryCopy_andRestoreNeverListsDeletedOrForeignItems() throws Exception {
        setRecovery(tokenA, PASSWORD, null, 1, randomBytes(16), randomBytes(60)).andExpect(status().isOk());
        long emergency = startEmergency(tokenA);
        byte[] ciphertext = ssev(randomBytes(32), randomBytes(300));
        Map<String, Object> request = declaration(ciphertext, randomBytes(1));
        request.put("recoveryRkVersion", 1);
        request.put("recoveryWrappedKey", b64(randomBytes(60)));
        long id = json(declare(tokenA, emergency, request).andReturn().getResponse().getContentAsString()).get("id").asLong();
        uploadAll(tokenA, id, ciphertext);
        mockMvc.perform(post("/api/evidence/" + id + "/seal").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)));

        mockMvc.perform(get("/api/evidence/recovery/items").header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
                .andExpect(jsonPath("$.items", hasSize(0)));
        mockMvc.perform(get("/api/evidence/recovery/items").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.items", hasSize(1)));

        mockMvc.perform(put("/api/emergencies/" + emergency + "/resolve").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.update("UPDATE emergency_events SET started_at = ?, ended_at = ? WHERE id = ?",
                now.minusHours(26), now.minusHours(25), emergency);
        mockMvc.perform(post("/api/evidence/" + id + "/delete").header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("password", PASSWORD))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recoverable").value(false));

        assertNull(jdbc.queryForObject("SELECT recovery_wrapped_key FROM evidence_items WHERE id = ?", byte[].class, id),
                "the recovery copy is erased (crypto-shredding)");
        assertNull(jdbc.queryForObject("SELECT recovery_rk_version FROM evidence_items WHERE id = ?", Integer.class, id));
        mockMvc.perform(get("/api/evidence/recovery/items").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.items", hasSize(0)));
        attach(tokenA, id, 1, randomBytes(60)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This evidence was deleted"));
    }

    @Test
    void everyRecoveryEndpoint_needsALogin() throws Exception {
        mockMvc.perform(get("/api/evidence/recovery")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/evidence/recovery/items")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/evidence/recovery/envelope").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/evidence/1/recovery-key").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // ---- the phone's crypto, in plain JCE (same formats as android core/crypto/RecoveryKeyCrypto) ----------------

    private static byte[] kek(char[] passphrase, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, ITERATIONS, 256);
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    }

    private byte[] wrapEnvelope(char[] passphrase, byte[] salt, byte[] rk) throws Exception {
        return gcm(Cipher.ENCRYPT_MODE, kek(passphrase, salt), randomBytes(12), envelopeAad(1), rk);
    }

    private byte[] openEnvelope(char[] passphrase, byte[] salt, byte[] wrapped, int rkVersion) throws Exception {
        return gcmOpen(kek(passphrase, salt), wrapped, envelopeAad(rkVersion));
    }

    private byte[] wrapItemKey(byte[] rk, byte[] itemKey, String clientRef, String sha) throws Exception {
        return gcm(Cipher.ENCRYPT_MODE, rk, randomBytes(12), itemAad(clientRef, sha), itemKey);
    }

    private byte[] openItemKey(byte[] rk, byte[] wrapped, String clientRef, String sha) throws Exception {
        return gcmOpen(rk, wrapped, itemAad(clientRef, sha));
    }

    /** Binds the envelope to its RK generation (the server never checks AADs; only the phone can). */
    private static byte[] envelopeAad(int rkVersion) {
        return ("SSRK1|" + rkVersion).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] itemAad(String clientRef, String sha) {
        return ("SSRW1|" + clientRef + "|" + sha).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] gcm(int mode, byte[] key, byte[] iv, byte[] aad, byte[] data) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(aad);
        byte[] out = cipher.doFinal(data);
        byte[] result = Arrays.copyOf(iv, iv.length + out.length);
        System.arraycopy(out, 0, result, iv.length, out.length);
        return result;
    }

    private static byte[] gcmOpen(byte[] key, byte[] wrapped, byte[] aad) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, wrapped, 0, 12));
        cipher.updateAAD(aad);
        return cipher.doFinal(wrapped, 12, wrapped.length - 12);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private ResultActions setRecovery(String token, String password, Integer expected, int rkVersion, byte[] salt,
                                      byte[] wrapped) throws Exception {
        return putRecovery(token, envelopeBody(password, expected, rkVersion, salt, wrapped));
    }

    private Map<String, Object> envelopeBody(String password, Integer expected, int rkVersion, byte[] salt, byte[] wrapped) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("password", password);
        body.put("expectedEnvelopeVersion", expected);
        body.put("rkVersion", rkVersion);
        body.put("kdf", "PBKDF2-SHA256");
        body.put("kdfIterations", ITERATIONS);
        body.put("kdfSalt", b64(salt));
        body.put("wrappedRecoveryKey", b64(wrapped));
        return body;
    }

    private ResultActions putRecovery(String token, Map<String, Object> body) throws Exception {
        return mockMvc.perform(put("/api/evidence/recovery").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions fetchEnvelope(String token, String password) throws Exception {
        return mockMvc.perform(post("/api/evidence/recovery/envelope").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("password", password))));
    }

    private ResultActions attach(String token, long id, int rkVersion, byte[] wrapped) throws Exception {
        return mockMvc.perform(put("/api/evidence/" + id + "/recovery-key").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("rkVersion", rkVersion, "wrappedKey", b64(wrapped)))));
    }

    private Map<String, Object> declaration(byte[] ciphertext, byte[] plaintext) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientRef", UUID.randomUUID().toString());
        body.put("kind", "AUDIO");
        body.put("mediaType", "audio/aac");
        body.put("sequenceNo", 0);
        body.put("sizeBytes", ciphertext.length);
        body.put("ciphertextSha256", SsevFormat.sha256Hex(ciphertext));
        body.put("plaintextSha256", SsevFormat.sha256Hex(plaintext));
        body.put("encryption", "SSEV1");
        body.put("capturedStartedAt", Instant.now().minusSeconds(30).toString());
        body.put("capturedEndedAt", Instant.now().toString());
        return body;
    }

    private ResultActions declare(String token, long emergency, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/emergencies/" + emergency + "/evidence").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private void uploadAll(String token, long id, byte[] bytes) throws Exception {
        for (int offset = 0; offset < bytes.length; offset += CHUNK) {
            mockMvc.perform(put("/api/evidence/" + id + "/content?offset=" + offset)
                            .header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_OCTET_STREAM)
                            .content(Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + CHUNK))))
                    .andExpect(status().isOk());
        }
    }

    private long startEmergency(String token) throws Exception {
        return json(mockMvc.perform(post("/api/emergencies").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private static byte[] ssev(byte[] key, byte[] plaintext) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SsevFormat.encrypt(key, new ByteArrayInputStream(plaintext), out, 1024, RANDOM);
        byte[] bytes = out.toByteArray();
        assertFalse(bytes.length == 0);
        return bytes;
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private JsonNode json(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
