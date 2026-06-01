package co.kuznetsov.mailkick.keygen;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.util.Base64;

/**
 * CLI tool that generates an Ed25519 keypair for MailKick authentication.
 *
 * <p>The public key is intended to be stored in AWS Secrets Manager under the
 * {@code MAILKICK_PUBLIC_KEY} entry. The private key is printed to stdout only
 * and must be pasted into the Thunderbird extension settings — it is never written
 * to disk by this tool.
 *
 * <p>Usage: {@code java -jar mailkick-keygen.jar}
 */
public final class KeyGenMain {

    private static final String ALGORITHM = "Ed25519";
    private static final String TEST_PAYLOAD = "mailkick-keypair-selftest";
    private static final PrintStream OUT = System.out;

    private KeyGenMain() {
    }

    /**
     * Entry point. Generates an Ed25519 keypair, performs a self-test sign/verify,
     * and prints both keys in Base64-encoded DER format.
     *
     * @param args command-line arguments (not used)
     */
    public static void main(String[] args) {
        try {
            KeyPair keyPair = generateKeyPair();
            selfTest(keyPair);

            String publicKeyBase64 = Base64.getEncoder()
                .encodeToString(keyPair.getPublic().getEncoded());
            String privateKeyBase64 = Base64.getEncoder()
                .encodeToString(keyPair.getPrivate().getEncoded());

            OUT.println("Ed25519 keypair generated and verified successfully.");
            OUT.println();
            OUT.println("MAILKICK_PUBLIC_KEY (store in AWS Secrets Manager):");
            OUT.println(publicKeyBase64);
            OUT.println();
            OUT.println("Private key (paste into Thunderbird extension settings):");
            OUT.println(privateKeyBase64);
            OUT.println();
            OUT.println("WARNING: Store the private key securely.");
            OUT.println("It is not saved anywhere — this is your only chance to copy it.");

        } catch (Exception e) {
            OUT.println("ERROR: Failed to generate keypair: " + e.getMessage());
            System.exit(1);
        }
    }

    private static KeyPair generateKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM);
        return generator.generateKeyPair();
    }

    private static void selfTest(KeyPair keyPair) throws Exception {
        byte[] payload = TEST_PAYLOAD.getBytes(StandardCharsets.UTF_8);

        Signature signer = Signature.getInstance(ALGORITHM);
        signer.initSign(keyPair.getPrivate());
        signer.update(payload);
        byte[] signature = signer.sign();

        Signature verifier = Signature.getInstance(ALGORITHM);
        verifier.initVerify(keyPair.getPublic());
        verifier.update(payload);
        boolean valid = verifier.verify(signature);

        if (!valid) {
            throw new IllegalStateException(
                "Keypair self-test failed: signature did not verify"
            );
        }
    }
}
