package com.safesphere.backend.evidence.tools;

import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.function.Supplier;
import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;
import javax.crypto.spec.PSource;

/**
 * OFFLINE tool for the vault recovery key. It is NOT part of the running server and is never called by it.
 *
 *   generate <private-key.pem> [--no-passphrase]
 *       creates a new RSA-3072 key pair and writes the PRIVATE key to the given file, ENCRYPTED with a passphrase
 *       (PKCS#8 "ENCRYPTED PRIVATE KEY", PBES2: PBKDF2-HMAC-SHA256 with 600 000 iterations + AES-256-CBC). The passphrase
 *       is asked on the console, or read from VAULT_KEY_PASSPHRASE. Prints the public key for
 *       app.evidence.vault-public-key (EVIDENCE_VAULT_PUBLIC_KEY) and its key id. Keep the file OFF the server
 *       (encrypted USB / password manager / printed); whoever holds it AND the passphrase can open escrowed evidence.
 *
 *   check <private-key.pem> [public-key-base64]
 *       proves that the private key really belongs to the public key the server publishes (default: the value of
 *       EVIDENCE_VAULT_PUBLIC_KEY) by sealing and opening a random test key. Run it before deploying a key and in
 *       every recovery drill: a mismatched pair would make every escrow copy useless, silently.
 *
 *   decrypt <private-key.pem> <escrow-wrapped-key-base64> <evidence.ssev> <output-file>
 *       opens one downloaded item: unwraps its key with the private key and decrypts the SSEV1 file. Proves that
 *       escrowed evidence is recoverable without the phone.
 *
 * Run: mvn -q compile, then
 *   java -cp target/classes com.safesphere.backend.evidence.tools.VaultKeyTool generate C:\safe\vault-private.pem
 */
public final class VaultKeyTool {

    public static final OAEPParameterSpec OAEP = new OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
    public static final String RSA_OAEP = "RSA/ECB/OAEPPadding";

    /** PBES2 = PBKDF2-HMAC-SHA256 + AES-256-CBC (readable by OpenSSL too). */
    static final String PBE = "PBEWithHmacSHA256AndAES_256";
    static final int PBE_ITERATIONS = 600_000;
    static final int MIN_PASSPHRASE = 12;
    private static final String ENV_PASSPHRASE = "VAULT_KEY_PASSPHRASE";

    private VaultKeyTool() {
    }

    public static void main(String[] args) throws Exception {
        if ((args.length == 2 || args.length == 3) && args[0].equals("generate")) {
            boolean plain = args.length == 3 && args[2].equals("--no-passphrase");
            if (args.length == 3 && !plain) usage();
            generate(Path.of(args[1]), plain ? null : newPassphrase());
        } else if ((args.length == 2 || args.length == 3) && args[0].equals("check")) {
            String publicKey = args.length == 3 ? args[2] : System.getenv("EVIDENCE_VAULT_PUBLIC_KEY");
            if (publicKey == null || publicKey.isBlank()) {
                System.err.println("No public key given and EVIDENCE_VAULT_PUBLIC_KEY is not set.");
                System.exit(2);
            }
            String keyId = check(readPrivateKey(Path.of(args[1]), VaultKeyTool::existingPassphrase), publicKey);
            System.out.println("OK: the private key matches the vault key " + keyId);
        } else if (args.length == 5 && args[0].equals("decrypt")) {
            decrypt(Path.of(args[1]), args[2], Path.of(args[3]), Path.of(args[4]));
        } else {
            usage();
        }
    }

    private static void usage() {
        System.err.println("usage: generate <private-key.pem> [--no-passphrase] | check <private-key.pem> [public-key-b64]"
                + " | decrypt <private-key.pem> <wrapped-key-b64> <in.ssev> <out>");
        System.exit(2);
    }

    static void generate(Path privateKeyFile, char[] passphrase) throws Exception {
        if (Files.exists(privateKeyFile)) {
            throw new IllegalStateException("Refusing to overwrite " + privateKeyFile);
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        KeyPair pair = generator.generateKeyPair();
        Files.writeString(privateKeyFile, privateKeyPem(pair.getPrivate(), passphrase), StandardCharsets.US_ASCII);
        if (passphrase != null) Arrays.fill(passphrase, '\0');
        byte[] der = pair.getPublic().getEncoded();
        System.out.println("Private key written to " + privateKeyFile.toAbsolutePath()
                + (passphrase == null ? " WITHOUT a passphrase" : " (encrypted with your passphrase)")
                + ". Keep it OFF the server.");
        System.out.println("Key id: " + keyId(der));
        System.out.println("EVIDENCE_VAULT_PUBLIC_KEY=" + Base64.getEncoder().encodeToString(der));
    }

    /** PKCS#8 PEM; encrypted (PBES2) when a passphrase is given. */
    public static String privateKeyPem(PrivateKey key, char[] passphrase) throws GeneralSecurityException, IOException {
        if (passphrase == null) return pem("PRIVATE KEY", key.getEncoded());
        if (passphrase.length < MIN_PASSPHRASE) {
            throw new IllegalArgumentException("The passphrase must have at least " + MIN_PASSPHRASE + " characters");
        }
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[16];
        byte[] iv = new byte[16];
        random.nextBytes(salt);
        random.nextBytes(iv);
        SecretKey pbeKey = SecretKeyFactory.getInstance(PBE).generateSecret(new PBEKeySpec(passphrase));
        Cipher cipher = Cipher.getInstance(PBE);
        cipher.init(Cipher.ENCRYPT_MODE, pbeKey, new PBEParameterSpec(salt, PBE_ITERATIONS, new IvParameterSpec(iv)));
        byte[] encrypted = cipher.doFinal(key.getEncoded());
        // Re-labelled as the generic PBES2 identifier (OID 1.2.840.113549.1.5.13), which EncryptedPrivateKeyInfo and
        // OpenSSL understand; the encoded parameters (PBKDF2-HMAC-SHA256 + AES-256-CBC) are the same.
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("PBES2");
        parameters.init(cipher.getParameters().getEncoded());
        return pem("ENCRYPTED PRIVATE KEY", new EncryptedPrivateKeyInfo(parameters, encrypted).getEncoded());
    }

    /** Proves the pair: seals a random 32-byte key with the public key and opens it with the private key. */
    public static String check(PrivateKey privateKey, String publicKeyBase64) throws GeneralSecurityException {
        byte[] der = Base64.getDecoder().decode(publicKeyBase64.replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "")
                .replaceAll("\\s", ""));
        PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        if (privateKey instanceof RSAPrivateCrtKey crt && publicKey instanceof RSAPublicKey rsa
                && !crt.getModulus().equals(rsa.getModulus())) {
            throw new GeneralSecurityException("The private key does NOT belong to this vault key");
        }
        byte[] probe = new byte[32];
        new SecureRandom().nextBytes(probe);
        byte[] opened;
        try {
            opened = unwrap(privateKey, wrap(publicKey, probe));
        } catch (GeneralSecurityException e) {
            throw new GeneralSecurityException("The private key does NOT belong to this vault key");
        }
        if (!MessageDigest.isEqual(probe, opened)) {
            throw new GeneralSecurityException("The private key does NOT belong to this vault key");
        }
        return keyId(der);
    }

    static void decrypt(Path privateKeyFile, String wrappedKeyBase64, Path input, Path output) throws Exception {
        PrivateKey privateKey = readPrivateKey(privateKeyFile, VaultKeyTool::existingPassphrase);
        byte[] itemKey = unwrap(privateKey, Base64.getDecoder().decode(wrappedKeyBase64));
        try (InputStream in = Files.newInputStream(input); OutputStream out = Files.newOutputStream(output)) {
            SsevFormat.decrypt(itemKey, in, out);
        } finally {
            Arrays.fill(itemKey, (byte) 0);
        }
        System.out.println("Decrypted to " + output.toAbsolutePath() + ", plaintext SHA-256 "
                + SsevFormat.sha256Hex(Files.readAllBytes(output)));
    }

    /** Reads an UNENCRYPTED PKCS#8 PEM (the test key). Encrypted files need {@link #readPrivateKey(Path, Supplier)}. */
    public static PrivateKey readPrivateKey(Path file) throws Exception {
        return readPrivateKey(file, () -> {
            throw new IllegalStateException("This private key is encrypted; a passphrase is needed");
        });
    }

    /** Reads a PKCS#8 PEM, plain or encrypted; [passphrase] is asked only for an encrypted one. */
    public static PrivateKey readPrivateKey(Path file, Supplier<char[]> passphrase) throws Exception {
        String text = Files.readString(file, StandardCharsets.US_ASCII);
        boolean encrypted = text.contains("-----BEGIN ENCRYPTED PRIVATE KEY-----");
        byte[] der = Base64.getDecoder().decode(text.replaceAll("-----(BEGIN|END) (ENCRYPTED )?PRIVATE KEY-----", "")
                .replaceAll("\\s", ""));
        if (encrypted) {
            char[] secret = passphrase.get();
            try {
                EncryptedPrivateKeyInfo info = new EncryptedPrivateKeyInfo(der);
                SecretKey pbeKey = SecretKeyFactory.getInstance(PBE).generateSecret(new PBEKeySpec(secret));
                Cipher cipher = Cipher.getInstance(PBE);
                cipher.init(Cipher.DECRYPT_MODE, pbeKey, info.getAlgParameters());
                der = cipher.doFinal(info.getEncryptedData());
            } catch (GeneralSecurityException e) {
                throw new GeneralSecurityException("Wrong passphrase or damaged key file");
            } finally {
                Arrays.fill(secret, '\0');
            }
        }
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    public static byte[] wrap(PublicKey publicKey, byte[] itemKey) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(RSA_OAEP);
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, OAEP);
        return cipher.doFinal(itemKey);
    }

    public static byte[] unwrap(PrivateKey privateKey, byte[] wrapped) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(RSA_OAEP);
        cipher.init(Cipher.DECRYPT_MODE, privateKey, OAEP);
        return cipher.doFinal(wrapped);
    }

    static String keyId(byte[] publicKeyDer) throws GeneralSecurityException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(publicKeyDer));
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    private static char[] newPassphrase() {
        String fromEnv = System.getenv(ENV_PASSPHRASE);
        if (fromEnv != null && !fromEnv.isEmpty()) return fromEnv.toCharArray();
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException("No console: set " + ENV_PASSPHRASE + ", or use --no-passphrase (not recommended)");
        }
        char[] first = console.readPassword("Passphrase for the vault private key (at least %d characters): ", MIN_PASSPHRASE);
        char[] second = console.readPassword("Repeat the passphrase: ");
        if (first == null || !Arrays.equals(first, second)) throw new IllegalStateException("The passphrases differ");
        Arrays.fill(second, '\0');
        return first;
    }

    private static char[] existingPassphrase() {
        String fromEnv = System.getenv(ENV_PASSPHRASE);
        if (fromEnv != null && !fromEnv.isEmpty()) return fromEnv.toCharArray();
        Console console = System.console();
        if (console == null) throw new IllegalStateException("No console: set " + ENV_PASSPHRASE);
        return console.readPassword("Passphrase of the vault private key: ");
    }
}
