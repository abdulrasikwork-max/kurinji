package com.safesphere.backend.evidence;

/**
 * A fixed SSEV1 test vector shared with the Android tests (EvidenceCipherTest has the same three values): key 00..1f,
 * nonce prefix a0..a6, 16-byte segments. Both implementations must produce and accept exactly these bytes.
 */
final class CrossVector {

    static final String KEY_HEX = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
    static final String PLAINTEXT = "SafeSphere SSEV1 cross-implementation vector!";
    static final String CIPHERTEXT_HEX = "535345560100000010a0a1a2a3a4a5a659b07accc5242d581e46e4d3ca45005ad488111455b25fcb"
            + "d884fdae9639e2df718165a8fe4fdbe68dfdd211a047c044e0d60fada2f590a8d82d0f88265e075980d9e88198ce6921bcbbd3b1"
            + "9906a3db80d48888bdcca71097f5549e1d";

    private CrossVector() {
    }
}
