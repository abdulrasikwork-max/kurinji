package com.safesphere.backend.evidence;

import static com.safesphere.backend.support.TestAccounts.PASSWORD;
import static com.safesphere.backend.support.TestAccounts.bearer;
import static com.safesphere.backend.support.TestAccounts.registerAndGetToken;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import com.safesphere.backend.evidence.service.EvidenceStorage;
import com.safesphere.backend.evidence.service.EvidenceStorageReconciler;
import com.safesphere.backend.evidence.tools.SsevFormat;
import com.safesphere.backend.evidence.tools.VaultKeyTool;
import com.safesphere.backend.safetycontact.repository.SafetyContactRepository;
import com.safesphere.backend.user.repository.UserRepository;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Phase 5 end-to-end: declare -> resumable upload -> server-verified seal -> download -> escrow recovery with the
 * offline key -> password-protected deletion, the hash-chained custody ledger, limits, and two-user ownership.
 * The test vault key pair is TEST-ONLY (src/test/resources).
 */
@SpringBootTest
@AutoConfigureMockMvc
class EvidenceApiIntegrationTest {

    private static final int CHUNK = 4096; // app.evidence.chunk-bytes in the test configuration
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private SafetyContactRepository contactRepository;
    @Autowired private EmergencyEventRepository emergencyRepository;
    @Autowired private EvidenceItemRepository evidenceRepository;
    @Autowired private CustodyEventRepository custodyRepository;
    @Autowired private EvidenceStorage storage;
    @Autowired private EvidenceStorageReconciler reconciler;
    @Autowired private JdbcTemplate jdbc;
    @Value("${app.evidence.vault-public-key}") private String vaultPublicKey;

    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setUp() throws Exception {
        custodyRepository.deleteAll();
        evidenceRepository.deleteAll();
        contactRepository.deleteAll();
        emergencyRepository.deleteAll();
        userRepository.deleteAll();
        tokenA = registerAndGetToken(mockMvc, objectMapper, "user.a@example.com");
        tokenB = registerAndGetToken(mockMvc, objectMapper, "user.b@example.com");
    }

    // ---- the whole life of an item -----------------------------------------------------------------

    @Test
    void fullLifecycle_declareUploadSealDownload_andTheOfflineVaultKeyRecoversThePlaintext() throws Exception {
        long emergency = startEmergency(tokenA);
        byte[] plaintext = randomBytes(10_000);
        Sealed item = sealedItem(plaintext, true);

        JsonNode declared = declare(tokenA, emergency, item.request(0))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UPLOADING"))
                .andExpect(jsonPath("$.receivedBytes").value(0))
                .andExpect(jsonPath("$.escrowed").value(true))
                .andExpect(jsonPath("$.uploadChunkBytes").value(CHUNK))
                .andExpect(jsonPath("$.escrowWrappedKey").doesNotExist())
                .andExpect(jsonPath("$.storageKey").doesNotExist())
                .andReturn().getResponse().getContentAsString().transform(this::json);
        long id = declared.get("id").asLong();

        uploadAll(tokenA, id, item.ciphertext());
        seal(tokenA, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.sealedAt").isNotEmpty());
        seal(tokenA, id).andExpect(status().isOk()); // repeating is harmless

        byte[] downloaded = mockMvc.perform(get("/api/evidence/" + id + "/content").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Evidence-Sha256", item.ciphertextSha256()))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(item.ciphertext(), downloaded);

        // Recovery WITHOUT the phone: the escrow copy of the item key (stored in the database) + the offline private key.
        byte[] wrapped = jdbc.queryForObject("SELECT escrow_wrapped_key FROM evidence_items WHERE id = ?", byte[].class, id);
        byte[] itemKey = VaultKeyTool.unwrap(testPrivateKey(), wrapped);
        assertArrayEquals(plaintext, SsevFormat.decrypt(itemKey, downloaded));

        custody(tokenA, emergency).andExpect(jsonPath("$.chainIntact").value(true))
                .andExpect(jsonPath("$.entries", hasSize(3)))
                .andExpect(jsonPath("$.entries[0].action").value("DECLARED"))
                .andExpect(jsonPath("$.entries[1].action").value("SEALED"))
                .andExpect(jsonPath("$.entries[2].action").value("DOWNLOADED"));
    }

    @Test
    void theServerStoresOnlyCiphertext_neverThePlaintext() throws Exception {
        long emergency = startEmergency(tokenA);
        byte[] plaintext = "a very recognisable spoken sentence ".repeat(50).getBytes();
        Sealed item = sealedItem(plaintext, false);
        long id = declareId(tokenA, emergency, item.request(0));
        uploadAll(tokenA, id, item.ciphertext());
        seal(tokenA, id).andExpect(status().isOk());

        String storageKey = evidenceRepository.findById(id).orElseThrow().getStorageKey();
        byte[] onDisk = Files.readAllBytes(storage.sealedFile(storageKey));
        assertArrayEquals(item.ciphertext(), onDisk);
        assertFalse(new String(onDisk, java.nio.charset.StandardCharsets.ISO_8859_1).contains("recognisable"));
        // File names carry no ids: the key is a random version-4 UUID. (Phase 8 fix: the former check "the key does not
        // contain the id's digits" failed by chance whenever a small id such as 3 appeared inside the random UUID.)
        assertEquals(4, java.util.UUID.fromString(storageKey).version(), "file names are random, never derived from ids");
        assertFalse(storageKey.equals(String.valueOf(id)));
    }

    @Test
    void declare_isIdempotentByClientRef_andReusingTheReferenceForOtherContentIs409() throws Exception {
        long emergency = startEmergency(tokenA);
        Sealed item = sealedItem(randomBytes(500), false);
        Map<String, Object> request = item.request(0);

        long id = declareId(tokenA, emergency, request);
        declare(tokenA, emergency, request).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));

        Map<String, Object> other = sealedItem(randomBytes(500), false).request(1);
        other.put("clientRef", request.get("clientRef"));
        declare(tokenA, emergency, other).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This evidence reference is already used"));

        assertEquals(1, evidenceRepository.count());
        // A second phone of the same person may reuse a sequence number: accepted (capture times order the items).
        Map<String, Object> secondPhone = sealedItem(randomBytes(500), false).request(0);
        declare(tokenA, emergency, secondPhone).andExpect(status().isCreated());
    }

    // ---- upload --------------------------------------------------------------------------------------

    @Test
    void upload_isResumable_andAWrongOffsetTellsWhereToContinue() throws Exception {
        long emergency = startEmergency(tokenA);
        Sealed item = sealedItem(randomBytes(9_000), false);
        long id = declareId(tokenA, emergency, item.request(0));
        byte[] bytes = item.ciphertext();

        uploadPiece(tokenA, id, 0, Arrays.copyOfRange(bytes, 0, CHUNK)).andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedBytes").value(CHUNK));
        // The phone lost the answer and re-sends the same piece: refused with the point to resume from.
        uploadPiece(tokenA, id, 0, Arrays.copyOfRange(bytes, 0, CHUNK)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Upload must continue at byte " + CHUNK));
        // Skipping ahead is refused too.
        uploadPiece(tokenA, id, CHUNK * 2L, Arrays.copyOfRange(bytes, CHUNK, CHUNK * 2)).andExpect(status().isConflict());

        mockMvc.perform(get("/api/evidence/" + id).header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.receivedBytes").value(CHUNK));
        uploadAllFrom(tokenA, id, bytes, CHUNK);
        seal(tokenA, id).andExpect(jsonPath("$.status").value("SEALED"));
        uploadPiece(tokenA, id, bytes.length, new byte[] {1}).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This evidence is already sealed"));
    }

    @Test
    void upload_refusesPiecesLargerThanTheChunkOrBeyondTheDeclaredSize_andEmptyPieces() throws Exception {
        long emergency = startEmergency(tokenA);
        Sealed item = sealedItem(randomBytes(9_000), false);
        long id = declareId(tokenA, emergency, item.request(0));

        uploadPiece(tokenA, id, 0, Arrays.copyOf(item.ciphertext(), CHUNK + 1)).andExpect(status().isBadRequest());
        uploadPiece(tokenA, id, 0, new byte[0]).andExpect(status().isBadRequest());

        Sealed small = sealedItem(randomBytes(10), false);
        long smallId = declareId(tokenA, emergency, small.request(1));
        byte[] tooMuch = Arrays.copyOf(small.ciphertext(), small.ciphertext().length + 5);
        uploadPiece(tokenA, smallId, 0, tooMuch).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/evidence/" + smallId).header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.receivedBytes").value(0));
    }

    // ---- seal / integrity -------------------------------------------------------------------------------

    @Test
    void seal_beforeEveryByteArrived_is409() throws Exception {
        long emergency = startEmergency(tokenA);
        Sealed item = sealedItem(randomBytes(9_000), false);
        long id = declareId(tokenA, emergency, item.request(0));
        uploadPiece(tokenA, id, 0, Arrays.copyOfRange(item.ciphertext(), 0, CHUNK));

        seal(tokenA, id).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Upload is not complete: " + CHUNK + " of "
                        + item.ciphertext().length + " bytes received"));
    }

    @Test
    void seal_withBytesThatDoNotMatchTheHash_isRefused_startsOver_andIsRecordedInCustody() throws Exception {
        long emergency = startEmergency(tokenA);
        Sealed item = sealedItem(randomBytes(5_000), false);
        long id = declareId(tokenA, emergency, item.request(0));
        byte[] tampered = item.ciphertext().clone();
        tampered[100] ^= 0x01;
        uploadAll(tokenA, id, tampered);

        seal(tokenA, id).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The uploaded bytes do not match the evidence hash; upload it again"));
        mockMvc.perform(get("/api/evidence/" + id).header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.status").value("UPLOADING"))
                .andExpect(jsonPath("$.receivedBytes").value(0));

        uploadAll(tokenA, id, item.ciphertext());
        seal(tokenA, id).andExpect(jsonPath("$.status").value("SEALED"));
        custody(tokenA, emergency).andExpect(jsonPath("$.chainIntact").value(true))
                .andExpect(jsonPath("$.entries[1].action").value("INTEGRITY_FAILED"))
                .andExpect(jsonPath("$.entries[2].action").value("SEALED"));
    }

    // ---- validation -----------------------------------------------------------------------------------------

    @Test
    void captureTimes_mustLieInsideTheEmergency() throws Exception {
        long emergency = startEmergency(tokenA);
        Map<String, Object> before = sealedItem(randomBytes(100), false).request(0);
        before.put("capturedStartedAt", Instant.now().minus(Duration.ofHours(1)).toString());
        declare(tokenA, emergency, before).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Evidence was captured before the emergency started"));

        Map<String, Object> future = sealedItem(randomBytes(100), false).request(0);
        future.put("capturedEndedAt", Instant.now().plus(Duration.ofHours(1)).toString());
        declare(tokenA, emergency, future).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Capture time cannot be in the future"));

        Map<String, Object> backwards = sealedItem(randomBytes(100), false).request(0);
        backwards.put("capturedEndedAt", Instant.now().minus(Duration.ofMinutes(2)).toString());
        declare(tokenA, emergency, backwards).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Capture end is before its start"));

        // After the emergency ended: still uploadable (offline phone), but only for captures made during it.
        resolve(tokenA, emergency);
        jdbc.update("UPDATE emergency_events SET started_at = ?, ended_at = ? WHERE id = ?",
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(2),
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(1), emergency);
        Map<String, Object> late = sealedItem(randomBytes(100), false).request(0);
        declare(tokenA, emergency, late).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Evidence was captured after the emergency ended"));
        Map<String, Object> during = sealedItem(randomBytes(100), false).request(1);
        during.put("capturedStartedAt", Instant.now().minus(Duration.ofMinutes(90)).toString());
        during.put("capturedEndedAt", Instant.now().minus(Duration.ofMinutes(89)).toString());
        declare(tokenA, emergency, during).andExpect(status().isCreated());
    }

    @Test
    void kindAndMediaType_mustMatch_andOnlyTheKnownFormatIsAccepted() throws Exception {
        long emergency = startEmergency(tokenA);
        Map<String, Object> wrongType = sealedItem(randomBytes(100), false).request(0);
        wrongType.put("mediaType", "application/x-msdownload");
        declare(tokenA, emergency, wrongType).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Media type does not match the kind of evidence"));

        Map<String, Object> wrongFormat = sealedItem(randomBytes(100), false).request(0);
        wrongFormat.put("encryption", "NONE");
        declare(tokenA, emergency, wrongFormat).andExpect(status().isBadRequest());

        Map<String, Object> badHash = sealedItem(randomBytes(100), false).request(0);
        badHash.put("ciphertextSha256", "ABC");
        declare(tokenA, emergency, badHash).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.ciphertextSha256").exists());

        Map<String, Object> unknownKind = sealedItem(randomBytes(100), false).request(0);
        unknownKind.put("kind", "DOCUMENT");
        declare(tokenA, emergency, unknownKind).andExpect(status().isBadRequest());
    }

    @Test
    void escrow_mustUseTheCurrentVaultKey_andHaveTheRightLength() throws Exception {
        long emergency = startEmergency(tokenA);
        JsonNode key = json(mockMvc.perform(get("/api/evidence/vault-key").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.algorithm").value("RSA-OAEP-SHA256"))
                .andReturn().getResponse().getContentAsString());
        assertEquals(64, key.get("keyId").asText().length());

        Map<String, Object> oldKey = sealedItem(randomBytes(100), true).request(0);
        oldKey.put("escrowKeyId", "0".repeat(64));
        declare(tokenA, emergency, oldKey).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The vault recovery key has changed; fetch it again"));

        Map<String, Object> half = sealedItem(randomBytes(100), true).request(0);
        half.remove("escrowWrappedKey");
        declare(tokenA, emergency, half).andExpect(status().isBadRequest());

        Map<String, Object> shortKey = sealedItem(randomBytes(100), true).request(0);
        shortKey.put("escrowWrappedKey", Base64.getEncoder().encodeToString(new byte[10]));
        declare(tokenA, emergency, shortKey).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Escrow key has the wrong length for the vault key"));
    }

    @Test
    void quota_itemSize_andTheVaultCanFillUp() throws Exception {
        long emergency = startEmergency(tokenA);
        Map<String, Object> huge = sealedItem(randomBytes(100), false).request(0);
        huge.put("sizeBytes", 1_048_577); // max-item-bytes is 1 MiB in tests
        declare(tokenA, emergency, huge).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Evidence item is too large"));

        for (int i = 0; i < 3; i++) {
            Map<String, Object> big = sealedItem(randomBytes(100), false).request(i);
            big.put("sizeBytes", 1_048_576);
            declare(tokenA, emergency, big).andExpect(status().isCreated());
        }
        Map<String, Object> more = sealedItem(randomBytes(100), false).request(3);
        declare(tokenA, emergency, more).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Your evidence vault is full"));
        // The quota is per person.
        declare(tokenB, startEmergency(tokenB), sealedItem(randomBytes(100), false).request(0)).andExpect(status().isCreated());
    }

    // ---- ownership -----------------------------------------------------------------------------------------

    @Test
    void anotherUser_cannotDeclareListReadUploadSealDownloadOrDelete() throws Exception {
        long emergencyA = startEmergency(tokenA);
        Sealed item = sealedItem(randomBytes(3_000), false);
        long id = declareId(tokenA, emergencyA, item.request(0));

        declare(tokenB, emergencyA, sealedItem(randomBytes(10), false).request(1)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/emergencies/" + emergencyA + "/evidence").header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
                .andExpect(status().isNotFound());
        custody(tokenB, emergencyA).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/evidence/" + id).header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
                .andExpect(status().isNotFound());
        uploadPiece(tokenB, id, 0, item.ciphertext()).andExpect(status().isNotFound());
        seal(tokenB, id).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/evidence/" + id + "/content").header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
                .andExpect(status().isNotFound());
        deleteItem(tokenB, id, PASSWORD).andExpect(status().isNotFound());

        // Nothing changed for the owner.
        mockMvc.perform(get("/api/evidence/" + id).header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.receivedBytes").value(0)).andExpect(jsonPath("$.status").value("UPLOADING"));
    }

    @Test
    void everyEvidenceEndpoint_needsALogin() throws Exception {
        mockMvc.perform(get("/api/evidence/vault-key")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/evidence/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/evidence/1/content?offset=0").contentType(MediaType.APPLICATION_OCTET_STREAM).content(new byte[1]))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/emergencies/1/evidence")).andExpect(status().isUnauthorized());
    }

    // ---- deletion ------------------------------------------------------------------------------------------

    @Test
    void delete_isRefusedWhileActive_needsThePassword_thenLeavesATombstone_andRemovesTheFile() throws Exception {
        long emergency = startEmergency(tokenA);
        Sealed item = sealedItem(randomBytes(2_000), true);
        long id = declareId(tokenA, emergency, item.request(0));
        uploadAll(tokenA, id, item.ciphertext());
        seal(tokenA, id);
        String storageKey = evidenceRepository.findById(id).orElseThrow().getStorageKey();

        deleteItem(tokenA, id, PASSWORD).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Evidence cannot be deleted while the emergency is active"));

        resolve(tokenA, emergency);
        // Just after "I'm safe": still protected, and the password is not even looked at.
        deleteItem(tokenA, id, PASSWORD).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Evidence can only be deleted 24 hours after the emergency ended"));
        endedHoursAgo(emergency, 25);

        deleteItem(tokenA, id, "not-my-password").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Password is incorrect"));
        assertTrue(storage.sealedExists(storageKey));

        deleteItem(tokenA, id, PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELETED"))
                .andExpect(jsonPath("$.deletedAt").isNotEmpty())
                .andExpect(jsonPath("$.ciphertextSha256").value(item.ciphertextSha256())); // tombstone keeps the hash
        assertFalse(storage.sealedExists(storageKey), "the ciphertext file is gone");
        assertNull(jdbc.queryForObject("SELECT escrow_wrapped_key FROM evidence_items WHERE id = ?", byte[].class, id),
                "the escrow key copy is erased (crypto-shredding)");
        deleteItem(tokenA, id, PASSWORD).andExpect(status().isOk()); // idempotent

        mockMvc.perform(get("/api/evidence/" + id + "/content").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("This evidence was deleted"));
        // The wrong-password attempt is part of the record (committed although that request failed).
        custody(tokenA, emergency).andExpect(jsonPath("$.chainIntact").value(true))
                .andExpect(jsonPath("$.entries[2].action").value("DELETE_REFUSED"))
                .andExpect(jsonPath("$.entries[3].action").value("DELETED"));
    }

    @Test
    void delete_isLockedAfterRepeatedWrongPasswords_evenWithTheRightOne_andOnlyForThatPerson() throws Exception {
        long emergency = startEmergency(tokenA);
        long id = declareId(tokenA, emergency, sealedItem(randomBytes(200), false).request(0));
        resolve(tokenA, emergency);
        endedHoursAgo(emergency, 30);

        for (int i = 0; i < 5; i++) {
            deleteItem(tokenA, id, "guess-" + i).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Password is incorrect"));
        }
        deleteItem(tokenA, id, PASSWORD).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many wrong passwords. Deleting evidence is locked; try again in 15 minutes"));
        assertEquals("UPLOADING", evidenceRepository.findById(id).orElseThrow().getStatus().name());
        assertEquals(5, custodyRepository.findByUserIdOrderBySequenceNoAsc(userId("user.a@example.com")).stream()
                .filter(e -> e.getAction().name().equals("DELETE_REFUSED")).count(), "attempts while locked are not recorded");
        custody(tokenA, emergency).andExpect(jsonPath("$.chainIntact").value(true));

        // Person B is not affected by A's lock.
        long emergencyB = startEmergency(tokenB);
        long idB = declareId(tokenB, emergencyB, sealedItem(randomBytes(200), false).request(0));
        resolve(tokenB, emergencyB);
        endedHoursAgo(emergencyB, 30);
        deleteItem(tokenB, idB, PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DELETED"));
    }

    // ---- server-side re-check of the stored copy ----------------------------------------------------------

    @Test
    void verify_reHashesTheStoredCopy_recordsVERIFIED_andADamagedCopyIsReopenedForUploadAndCanBeRepaired() throws Exception {
        long emergency = startEmergency(tokenA);
        Sealed item = sealedItem(randomBytes(6_000), false);
        long id = declareId(tokenA, emergency, item.request(0));
        verify(tokenA, id).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This evidence is not sealed yet"));
        uploadAll(tokenA, id, item.ciphertext());
        seal(tokenA, id);

        verify(tokenA, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.intact").value(true))
                .andExpect(jsonPath("$.verifiedAt").isNotEmpty())
                .andExpect(jsonPath("$.item.status").value("SEALED"));
        verify(tokenB, id).andExpect(status().isNotFound());

        // One bit flips on the server's disk (failing disk, or someone with file access).
        Path file = storage.sealedFile(evidenceRepository.findById(id).orElseThrow().getStorageKey());
        byte[] onDisk = Files.readAllBytes(file);
        onDisk[1234] ^= 0x10;
        Files.write(file, onDisk);

        verify(tokenA, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.intact").value(false))
                .andExpect(jsonPath("$.item.status").value("UPLOADING"))
                .andExpect(jsonPath("$.item.receivedBytes").value(0))
                .andExpect(jsonPath("$.item.sealedAt").value(org.hamcrest.Matchers.nullValue()));
        mockMvc.perform(get("/api/evidence/" + id + "/content").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isConflict());

        // The phone still has the original: it uploads it again and the item is verified again.
        uploadAll(tokenA, id, item.ciphertext());
        seal(tokenA, id).andExpect(jsonPath("$.status").value("SEALED"));
        verify(tokenA, id).andExpect(jsonPath("$.intact").value(true));
        custody(tokenA, emergency).andExpect(jsonPath("$.chainIntact").value(true))
                .andExpect(jsonPath("$.entries[2].action").value("VERIFIED"))
                .andExpect(jsonPath("$.entries[3].action").value("INTEGRITY_FAILED"))
                .andExpect(jsonPath("$.entries[4].action").value("SEALED"))
                .andExpect(jsonPath("$.entries[5].action").value("VERIFIED"));
    }

    @Test
    void verify_aMissingStoredFileIsReportedNotIntact() throws Exception {
        long emergency = startEmergency(tokenA);
        Sealed item = sealedItem(randomBytes(500), false);
        long id = declareId(tokenA, emergency, item.request(0));
        uploadAll(tokenA, id, item.ciphertext());
        seal(tokenA, id);
        Files.delete(storage.sealedFile(evidenceRepository.findById(id).orElseThrow().getStorageKey()));

        verify(tokenA, id).andExpect(status().isOk()).andExpect(jsonPath("$.intact").value(false))
                .andExpect(jsonPath("$.item.status").value("UPLOADING"));
    }

    // ---- custody ledger ------------------------------------------------------------------------------------

    @Test
    void custodyLedger_isHashChained_andDetectsAnEditedOrRemovedEntry() throws Exception {
        long emergency = startEmergency(tokenA);
        for (int i = 0; i < 3; i++) {
            Sealed item = sealedItem(randomBytes(300), false);
            long id = declareId(tokenA, emergency, item.request(i));
            uploadAll(tokenA, id, item.ciphertext());
            seal(tokenA, id);
        }
        JsonNode report = json(custody(tokenA, emergency).andExpect(jsonPath("$.chainIntact").value(true))
                .andExpect(jsonPath("$.chainLength").value(6))
                .andReturn().getResponse().getContentAsString());
        assertEquals(report.get("entries").get(5).get("entryHash").asText(), report.get("latestEntryHash").asText());

        // Someone with database access "adjusts" the time of entry 3.
        jdbc.update("UPDATE evidence_custody_events SET occurred_at = occurred_at - INTERVAL '1' HOUR WHERE sequence_no = 3");
        custody(tokenA, emergency).andExpect(jsonPath("$.chainIntact").value(false))
                .andExpect(jsonPath("$.brokenAtSequence").value(3));
    }

    @Test
    void custodyLedger_detectsARemovedEntry() throws Exception {
        long emergency = startEmergency(tokenA);
        for (int i = 0; i < 2; i++) {
            Sealed item = sealedItem(randomBytes(300), false);
            long id = declareId(tokenA, emergency, item.request(i));
            uploadAll(tokenA, id, item.ciphertext());
            seal(tokenA, id);
        }
        jdbc.update("DELETE FROM evidence_custody_events WHERE sequence_no = 2");
        custody(tokenA, emergency).andExpect(jsonPath("$.chainIntact").value(false))
                .andExpect(jsonPath("$.brokenAtSequence").value(3));
    }

    // ---- context packet + storage reconciliation ------------------------------------------------------------

    @Test
    void contextPacket_ownerSeesEvidenceCounts_theSafetyCircleProjectionHasNoEvidence() throws Exception {
        long emergency = startEmergency(tokenA);
        Sealed item = sealedItem(randomBytes(300), false);
        long id = declareId(tokenA, emergency, item.request(0));
        uploadAll(tokenA, id, item.ciphertext());
        seal(tokenA, id);
        declareId(tokenA, emergency, sealedItem(randomBytes(300), false).request(1));

        mockMvc.perform(get("/api/emergencies/" + emergency + "/context").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.evidence.items").value(2))
                .andExpect(jsonPath("$.evidence.sealed").value(1))
                .andExpect(jsonPath("$.evidence.uploading").value(1));
        mockMvc.perform(get("/api/emergencies/" + emergency + "/context?audience=SAFETY_CIRCLE")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.evidence").doesNotExist());
    }

    @Test
    void reconciler_removesOldOrphanFiles_butKeepsReferencedAndFreshOnes() throws Exception {
        long emergency = startEmergency(tokenA);
        long id = declareId(tokenA, emergency, sealedItem(randomBytes(100), false).request(0));
        String known = evidenceRepository.findById(id).orElseThrow().getStorageKey();

        Path oldOrphan = storage.root().resolve(UUID.randomUUID() + ".ssev");
        Path freshOrphan = storage.root().resolve(UUID.randomUUID() + ".part");
        Files.write(oldOrphan, new byte[] {1});
        Files.write(freshOrphan, new byte[] {1});
        Files.setLastModifiedTime(oldOrphan, FileTime.from(Instant.now().minus(Duration.ofHours(2))));
        Path knownFile = storage.root().resolve(known + ".part");
        Files.setLastModifiedTime(knownFile, FileTime.from(Instant.now().minus(Duration.ofHours(2))));

        reconciler.reconcile();

        assertFalse(Files.exists(oldOrphan));
        assertTrue(Files.exists(freshOrphan), "may belong to a declaration still in progress");
        assertTrue(Files.exists(knownFile));
        Files.deleteIfExists(freshOrphan);
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    /** An item exactly as the phone would produce it: SSEV1 ciphertext, both hashes, optional escrow copy of the key. */
    private record Sealed(byte[] ciphertext, String ciphertextSha256, String plaintextSha256, String escrowKeyId,
                          String escrowWrappedKey) {
        Map<String, Object> request(int sequence) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("clientRef", UUID.randomUUID().toString());
            body.put("kind", "AUDIO");
            body.put("mediaType", "audio/aac");
            body.put("sequenceNo", sequence);
            body.put("recordingRef", UUID.randomUUID().toString());
            body.put("sizeBytes", ciphertext.length);
            body.put("ciphertextSha256", ciphertextSha256);
            body.put("plaintextSha256", plaintextSha256);
            body.put("encryption", "SSEV1");
            body.put("capturedStartedAt", Instant.now().minusSeconds(30).toString());
            body.put("capturedEndedAt", Instant.now().toString());
            if (escrowKeyId != null) {
                body.put("escrowKeyId", escrowKeyId);
                body.put("escrowWrappedKey", escrowWrappedKey);
            }
            return body;
        }
    }

    private Sealed sealedItem(byte[] plaintext, boolean escrow) throws Exception {
        byte[] key = randomBytes(32);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SsevFormat.encrypt(key, new ByteArrayInputStream(plaintext), out, 1024, RANDOM);
        byte[] ciphertext = out.toByteArray();
        String keyId = null;
        String wrapped = null;
        if (escrow) {
            byte[] der = Base64.getDecoder().decode(vaultPublicKey);
            PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            keyId = SsevFormat.sha256Hex(der);
            wrapped = Base64.getEncoder().encodeToString(VaultKeyTool.wrap(publicKey, key));
        }
        return new Sealed(ciphertext, SsevFormat.sha256Hex(ciphertext), SsevFormat.sha256Hex(plaintext), keyId, wrapped);
    }

    private static PrivateKey testPrivateKey() throws Exception {
        return VaultKeyTool.readPrivateKey(Path.of("src", "test", "resources", "test-vault-private-key.TEST-ONLY.txt"));
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private void uploadAll(String token, long id, byte[] bytes) throws Exception {
        uploadAllFrom(token, id, bytes, 0);
    }

    private void uploadAllFrom(String token, long id, byte[] bytes, int from) throws Exception {
        for (int offset = from; offset < bytes.length; offset += CHUNK) {
            uploadPiece(token, id, offset, Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + CHUNK)))
                    .andExpect(status().isOk());
        }
    }

    private ResultActions uploadPiece(String token, long id, long offset, byte[] piece) throws Exception {
        return mockMvc.perform(put("/api/evidence/" + id + "/content?offset=" + offset)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(piece));
    }

    private ResultActions seal(String token, long id) throws Exception {
        return mockMvc.perform(post("/api/evidence/" + id + "/seal").header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    private ResultActions deleteItem(String token, long id, String password) throws Exception {
        return mockMvc.perform(post("/api/evidence/" + id + "/delete")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("password", password))));
    }

    private ResultActions verify(String token, long id) throws Exception {
        return mockMvc.perform(post("/api/evidence/" + id + "/verify").header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    /** Moves the emergency into the past (its times are not part of the custody hashes). */
    private void endedHoursAgo(long emergency, int hours) {
        java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
        jdbc.update("UPDATE emergency_events SET started_at = ?, ended_at = ? WHERE id = ?",
                now.minusHours(hours + 1L), now.minusHours(hours), emergency);
    }

    private long userId(String email) {
        return userRepository.findAll().stream().filter(u -> u.getEmail().equals(email)).findFirst().orElseThrow().getId();
    }

    private ResultActions custody(String token, long emergency) throws Exception {
        return mockMvc.perform(get("/api/emergencies/" + emergency + "/evidence/custody")
                .header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    private ResultActions declare(String token, long emergency, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/emergencies/" + emergency + "/evidence")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private long declareId(String token, long emergency, Map<String, Object> body) throws Exception {
        return json(declare(token, emergency, body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private long startEmergency(String token) throws Exception {
        return json(mockMvc.perform(post("/api/emergencies").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private void resolve(String token, long emergency) throws Exception {
        mockMvc.perform(put("/api/emergencies/" + emergency + "/resolve").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());
    }

    private JsonNode json(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
