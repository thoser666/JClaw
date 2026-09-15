package biz.brumm.infrastructure.adapter.out.channel;

import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Nostr-Kryptografie-Helfer (NIP-01/NIP-42) auf Basis von secp256k1/BIP-340 (Schnorr).
 * <p>
 * Benötigt für den Buzz (Nostr)-Channel-Adapter: Ableitung des x-only-Public-Keys,
 * Event-Hash (SHA-256 der serialisierten {@code [0,pubkey,created_at,kind,tags,content]}-Liste)
 * und die deterministische BIP-340-Signatur über den Event-Hash (msg = Event-Id-Bytes).
 * Basis ist der Standard-Punkt {@code G} der Kurve secp256k1 (BouncyCastle).
 */
final class NostrCrypto {

    private NostrCrypto() {
    }

    private static final X9ECParameters PARAMS = CustomNamedCurves.getByName("secp256k1");
    private static final BigInteger N = PARAMS.getN();
    private static final ECPoint G = PARAMS.getG();
    private static final BigInteger FIELD_P = PARAMS.getCurve().getField().getCharacteristic();
    private static final BigInteger FOUR = BigInteger.valueOf(4);

    /**
     * Leitet den x-only-Public-Key (64 Hex-Zeichen, NIP-01-Pubkey) aus dem privaten
     * Schlüssel ab. {@code privateKeyHex} muss 64 Hex-Zeichen sein und im Bereich
     * {@code [1, n-1]} liegen.
     */
    static String derivePublicKeyHex(String privateKeyHex) {
        BigInteger d = secret(privateKeyHex);
        ECPoint p = G.multiply(d).normalize();
        return hex(bytes32(p.getAffineXCoord().toBigInteger()));
    }

    /**
     * BIP-340-Schnorr-Signatur über {@code msg} (bei Nostr: die Event-Id).
     * Deterministisch: {@code aux_rand} = 32 Null-Bytes (BIP-340-Vektor 0).
     */
    static byte[] sign(String privateKeyHex, byte[] msg) {
        return signAux(privateKeyHex, msg, new byte[32]);
    }

    /**
     * BIP-340-Schnorr-Signatur mit explizitem {@code aux_rand} (für Testvektoren).
     */
    static byte[] signAux(String privateKeyHex, byte[] msg, byte[] auxRand) {
        BigInteger d0 = secret(privateKeyHex);
        ECPoint p = G.multiply(d0).normalize();
        BigInteger d = hasEvenY(p) ? d0 : N.subtract(d0);
        byte[] pubKeyX = bytes32(p.getAffineXCoord().toBigInteger());

        byte[] t = xor(bytes32(d), taggedHash("BIP0340/aux", auxRand));
        BigInteger k0 = toInt(taggedHash("BIP0340/nonce", concat(t, pubKeyX, msg))).mod(N);
        if (k0.signum() == 0) {
            throw new IllegalStateException("BIP-340: nonce == 0.");
        }
        ECPoint r = G.multiply(k0).normalize();
        BigInteger k = hasEvenY(r) ? k0 : N.subtract(k0);
        BigInteger e = toInt(taggedHash("BIP0340/challenge",
                concat(bytes32(r.getAffineXCoord().toBigInteger()), pubKeyX, msg))).mod(N);
        BigInteger s = k.add(e.multiply(d)).mod(N);

        byte[] sig = new byte[64];
        System.arraycopy(bytes32(r.getAffineXCoord().toBigInteger()), 0, sig, 0, 32);
        System.arraycopy(bytes32(s), 0, sig, 32, 32);
        return sig;
    }

    /**
     * BIP-340-Verifikation ({@code pubKeyX} = 32 x-only-Bytes, {@code sig} = 64 Bytes).
     * Nur für Tests/Diagnose.
     */
    static boolean verify(byte[] msg, byte[] pubKeyX, byte[] sig) {
        if (pubKeyX == null || pubKeyX.length != 32 || sig == null || sig.length != 64) {
            return false;
        }
        BigInteger x = toInt(pubKeyX);
        if (x.signum() == 0 || x.compareTo(FIELD_P) >= 0) {
            return false;
        }
        ECPoint p = liftXEvenY(x);
        if (p == null) {
            return false;
        }
        BigInteger r = toInt(slice(sig, 0, 32));
        if (r.compareTo(FIELD_P) >= 0) {
            return false;
        }
        BigInteger s = toInt(slice(sig, 32, 64));
        if (s.compareTo(N) >= 0) {
            return false;
        }
        BigInteger e = toInt(taggedHash("BIP0340/challenge",
                concat(slice(sig, 0, 32), pubKeyX, msg))).mod(N);
        ECPoint rPoint = G.multiply(s).add(p.multiply(e).negate()).normalize();
        if (rPoint.isInfinity() || !hasEvenY(rPoint)) {
            return false;
        }
        return rPoint.getAffineXCoord().toBigInteger().equals(r);
    }

    /**
     * SHA-256-Hex über die serialisierten Event-Daten {@code [0,pubkey,created_at,kind,tags,content]}.
     */
    static String computeEventId(String pubKeyHex, long createdAt, int kind,
                                 java.util.List<java.util.List<String>> tags, String content) {
        Object[] array = {0, pubKeyHex, createdAt, kind, tags, content};
        String serialized;
        try {
            serialized = new tools.jackson.databind.ObjectMapper().writeValueAsString(array);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("Event-Serialisierung fehlgeschlagen.", e);
        }
        return sha256Hex(serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    static byte[] unhex(String hexString) {
        return HexFormat.of().parseHex(hexString);
    }

    // --- Hilfe ---

    private static BigInteger secret(String privateKeyHex) {
        if (privateKeyHex == null || privateKeyHex.length() != 64) {
            throw new IllegalArgumentException("Privater Nostr-Schluessel muss 64 Hex-Zeichen haben.");
        }
        BigInteger d;
        try {
            d = new BigInteger(1, HexFormat.of().parseHex(privateKeyHex));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Ungueltiger privater Nostr-Schluessel.", e);
        }
        if (d.signum() <= 0 || d.compareTo(N) >= 0) {
            throw new IllegalArgumentException("Privater Nostr-Schluessel liegt ausserhalb [1, n-1].");
        }
        return d;
    }

    private static boolean hasEvenY(ECPoint point) {
        return !point.getAffineYCoord().toBigInteger().testBit(0);
    }

    private static byte[] bytes32(BigInteger value) {
        byte[] b = value.toByteArray();
        byte[] out = new byte[32];
        if (b.length == 33 && b[0] == 0) {
            System.arraycopy(b, 1, out, 0, 32);
        } else if (b.length > 32) {
            System.arraycopy(b, b.length - 32, out, 0, 32);
        } else {
            System.arraycopy(b, 0, out, 32 - b.length, b.length);
        }
        return out;
    }

    private static byte[] bytes32(byte[] in) {
        byte[] out = new byte[32];
        System.arraycopy(in, 0, out, 0, Math.min(32, in.length));
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b, byte[] c) {
        byte[] out = new byte[a.length + b.length + c.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        System.arraycopy(c, 0, out, a.length + b.length, c.length);
        return out;
    }

    private static byte[] xor(byte[] a, byte[] b) {
        byte[] out = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = (byte) (a[i] ^ b[i]);
        }
        return out;
    }

    private static byte[] taggedHash(String tag, byte[] data) {
        byte[] tagHash = sha256(tag.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] buf = byteArray(tagHash.length * 2 + data.length);
        System.arraycopy(tagHash, 0, buf, 0, tagHash.length);
        System.arraycopy(tagHash, 0, buf, tagHash.length, tagHash.length);
        System.arraycopy(data, 0, buf, tagHash.length * 2, data.length);
        return sha256(buf);
    }

    private static byte[] byteArray(int size) {
        return new byte[size];
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nicht verfuegbar.", e);
        }
    }

    private static String sha256Hex(byte[] data) {
        return hex(sha256(data));
    }

    private static BigInteger toInt(byte[] bytes) {
        return new BigInteger(1, bytes);
    }

    private static byte[] slice(byte[] in, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(in, from, out, 0, out.length);
        return out;
    }

    /**
     * BIP-340 lift_x: findet den Punkt mit geradem Y zu x ({@code null}, wenn x²+7 kein QR ist).
     */
    private static ECPoint liftXEvenY(BigInteger x) {
        BigInteger xx = x.multiply(x).mod(FIELD_P);
        BigInteger y2 = xx.multiply(x).add(BigInteger.valueOf(7)).mod(FIELD_P);
        BigInteger y = y2.modPow(FIELD_P.add(BigInteger.ONE).divide(FOUR), FIELD_P);
        if (!y.multiply(y).mod(FIELD_P).equals(y2)) {
            return null;
        }
        if (y.testBit(0)) {
            y = FIELD_P.subtract(y);
        }
        return PARAMS.getCurve().createPoint(x, y).normalize();
    }
}