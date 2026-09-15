package biz.brumm.infrastructure.adapter.out.channel;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests für {@link NostrCrypto} (secp256k1 + BIP-340). Der Signatur-Test nutzt
 * den offiziellen BIP-340-Testvektor 0 (aux = 32 Null-Bytes) als unabhängige
 * Referenz für Pubkey-Ableitung und Signatur.
 */
class NostrCryptoTest {

    private static final String VECTOR_SECKEY =
            "0000000000000000000000000000000000000000000000000000000000000003";
    private static final String VECTOR_PUBKEY =
            "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9";
    private static final String VECTOR_MSG =
            "0000000000000000000000000000000000000000000000000000000000000000";
    private static final String VECTOR_SIG =
            "e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca8215"
                    + "25f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0";

    @Test
    void derivesPublicKeyFromPrivateKey() {
        assertThat(NostrCrypto.derivePublicKeyHex(VECTOR_SECKEY))
                .isEqualTo(VECTOR_PUBKEY);
    }

    @Test
    void signsBip340TestVector() {
        byte[] signature = NostrCrypto.sign(VECTOR_SECKEY, NostrCrypto.unhex(VECTOR_MSG));
        assertThat(NostrCrypto.hex(signature)).isEqualTo(VECTOR_SIG);
    }

    @Test
    void verifiesSignatureAndRejectsTampered() {
        byte[] msg = NostrCrypto.unhex(VECTOR_MSG);
        byte[] pub = NostrCrypto.unhex(VECTOR_PUBKEY);
        byte[] sig = NostrCrypto.unhex(VECTOR_SIG);
        assertThat(NostrCrypto.verify(msg, pub, sig)).isTrue();

        byte[] wrongMsg = new byte[32];
        wrongMsg[0] = 1;
        assertThat(NostrCrypto.verify(wrongMsg, pub, sig)).isFalse();

        byte[] shortPub = new byte[31];
        assertThat(NostrCrypto.verify(msg, shortPub, sig)).isFalse();
    }

    @Test
    void rejectsOutOfRangePrivateKey() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> NostrCrypto.derivePublicKeyHex("00000000000000000000000000000000"
                        + "00000000000000000000000000000000"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> NostrCrypto.derivePublicKeyHex("zz".repeat(32)));
    }

    @Test
    void eventIdIsSha256OfSerializedArray() throws Exception {
        String pubkey = VECTOR_PUBKEY.toLowerCase();
        long createdAt = 1700000000L;
        java.util.List<java.util.List<String>> tags = java.util.List.of(
                java.util.List.of("h", "room-1"));
        String content = "Hello Nostr";

        String id = NostrCrypto.computeEventId(pubkey, createdAt, 9, tags, content);
        assertThat(id).hasSize(64).isLowerCase();

        String expected = sha256Hex("[0,\"" + pubkey + "\"," + createdAt + ",9,"
                + "[[\"h\",\"room-1\"]],\"Hello Nostr\"]");
        assertThat(id).isEqualTo(expected);
    }

    @Test
    void eventIdIsDeterministic() {
        String pubkey = VECTOR_PUBKEY.toLowerCase();
        java.util.List<java.util.List<String>> tags = java.util.List.of(java.util.List.of("h", "room-1"));
        String first = NostrCrypto.computeEventId(pubkey, 1700000000L, 9, tags, "Hi");
        String second = NostrCrypto.computeEventId(pubkey, 1700000000L, 9, tags, "Hi");
        assertThat(first).isEqualTo(second);
        assertThat(NostrCrypto.computeEventId(pubkey, 1700000001L, 9, tags, "Hi"))
                .isNotEqualTo(first);
    }

    private static String sha256Hex(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(digest);
    }
}