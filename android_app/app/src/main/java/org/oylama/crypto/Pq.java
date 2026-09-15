package org.oylama.crypto;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.SecretWithEncapsulation;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyGenerationParameters;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyPairGenerator;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAParameters;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPrivateKeyParameters;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPublicKeyParameters;
import org.bouncycastle.pqc.crypto.mldsa.MLDSASigner;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters;

import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class Pq {
    private static final SecureRandom RNG = new SecureRandom();

    private Pq() {
    }

    public static byte[][] kemKeygen() {
        MLKEMKeyPairGenerator g = new MLKEMKeyPairGenerator();
        g.init(new MLKEMKeyGenerationParameters(RNG, MLKEMParameters.ml_kem_768));
        AsymmetricCipherKeyPair kp = g.generateKeyPair();
        return new byte[][]{((MLKEMPublicKeyParameters) kp.getPublic()).getEncoded(), ((MLKEMPrivateKeyParameters) kp.getPrivate()).getEncoded()};
    }

    public static byte[][] kemEncaps(byte[] pk) {
        MLKEMGenerator gen = new MLKEMGenerator(RNG);
        SecretWithEncapsulation swe = gen.generateEncapsulated(new MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_768, pk));
        return new byte[][]{swe.getSecret(), swe.getEncapsulation()};
    }

    public static byte[] kemDecaps(byte[] sk, byte[] ct) {
        MLKEMExtractor ex = new MLKEMExtractor(new MLKEMPrivateKeyParameters(MLKEMParameters.ml_kem_768, sk));
        return ex.extractSecret(ct);
    }

    public static byte[][] sigKeygen() {
        MLDSAKeyPairGenerator g = new MLDSAKeyPairGenerator();
        g.init(new MLDSAKeyGenerationParameters(RNG, MLDSAParameters.ml_dsa_65));
        AsymmetricCipherKeyPair kp = g.generateKeyPair();
        return new byte[][]{((MLDSAPublicKeyParameters) kp.getPublic()).getEncoded(), ((MLDSAPrivateKeyParameters) kp.getPrivate()).getEncoded()};
    }

    public static byte[] sign(byte[] sk, byte[] msg) {
        try {
            MLDSASigner s = new MLDSASigner();
            s.init(true, new MLDSAPrivateKeyParameters(MLDSAParameters.ml_dsa_65, sk));
            s.update(msg, 0, msg.length);
            return s.generateSignature();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean verify(byte[] pk, byte[] msg, byte[] sig) {
        try {
            MLDSASigner s = new MLDSASigner();
            s.init(false, new MLDSAPublicKeyParameters(MLDSAParameters.ml_dsa_65, pk));
            s.update(msg, 0, msg.length);
            return s.verifySignature(sig);
        } catch (Exception e) {
            return false;
        }
    }

    public static byte[] kdf(byte[] shared, String label) {
        return Hash.h(shared, label, "OYLAMA/kdf");
    }

    public static byte[] seal(byte[] key, byte[] plaintext, byte[] aad) {
        try {
            byte[] nonce = new byte[12];
            RNG.nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            c.updateAAD(aad);
            byte[] out = c.doFinal(plaintext);
            int ctLen = out.length - 16;
            byte[] blob = new byte[12 + 16 + ctLen];
            System.arraycopy(nonce, 0, blob, 0, 12);
            System.arraycopy(out, ctLen, blob, 12, 16);
            System.arraycopy(out, 0, blob, 28, ctLen);
            return blob;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] unseal(byte[] key, byte[] blob, byte[] aad) {
        try {
            byte[] nonce = java.util.Arrays.copyOfRange(blob, 0, 12);
            int ctLen = blob.length - 28;
            byte[] joined = new byte[ctLen + 16];
            System.arraycopy(blob, 28, joined, 0, ctLen);
            System.arraycopy(blob, 12, joined, ctLen, 16);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            c.updateAAD(aad);
            return c.doFinal(joined);
        } catch (Exception e) {
            throw new IllegalStateException("sealed envelope could not be opened", e);
        }
    }
}
