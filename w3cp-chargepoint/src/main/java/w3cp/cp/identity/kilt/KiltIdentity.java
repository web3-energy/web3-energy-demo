package w3cp.cp.identity.kilt;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import w3cp.cp.identity.ChargepointIdentity;
import w3cp.cp.util.DigitalSignatureUtil;
import w3cp.cp.util.W3CPKeyUtil;
import w3cp.model.identity.W3CPPrivateKey;
import w3cp.model.identity.W3CPPublicKey;

import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Slf4j
@ApplicationScoped
public class KiltIdentity implements ChargepointIdentity {

  @Getter
  private final String did;           // canonical: did:kilt:light:00<base58>
  @Getter
  private final String kid;           // normalized
  private final W3CPPrivateKey privateKey;    // PKCS#8 (base64url)
  private final W3CPPublicKey publicKey;      // X.509 SPKI (base64url)

  // WARNING (PoC ONLY):
  // This maps (mnemonic, passphrase) -> Ed25519 keypair by seeding a deterministic PRNG (SHA1PRNG).
  // It is NOT a secure wallet or KDF. Do NOT use this pattern in production.
  // For real deployments use a proper KDF (PBKDF2/Argon2/scrypt) and secure key storage (HSM/TPM/etc.).
  @Inject
  public KiltIdentity(KiltIdentityConfig config) {
    try {
      // 1) Derive 32-byte seed from mnemonic + passphrase
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      md.update(config.mnemonic().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      if (config.passphrase() != null && !config.passphrase().isEmpty()) {
        md.update(config.passphrase().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      byte[] seed = md.digest(); // 32 bytes

      // ⚠️ PoC: deterministic RNG from seed, not secure for production
      SecureRandom sr = SecureRandom.getInstance("SHA1PRNG");
      sr.setSeed(seed);

      // 2) Ed25519 keypair from deterministic RNG
      KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
      kpg.initialize(255, sr);
      KeyPair kp = kpg.generateKeyPair();

      // 3) Encodings
      byte[] spki = kp.getPublic().getEncoded();   // X.509 SPKI
      byte[] pkcs8 = kp.getPrivate().getEncoded();  // PKCS#8
      byte[] raw32 = raw32FromEd25519Spki(spki);    // for DID only

      // 4) Build W3CP keys
      this.privateKey = new W3CPPrivateKey(
          W3CPPublicKey.KeyType.ed25519,
          W3CPPublicKey.KeyEncoding.base64url,
          b64u(pkcs8)
      );
      this.publicKey = new W3CPPublicKey(
          W3CPPublicKey.KeyType.ed25519,
          W3CPPublicKey.KeyEncoding.base64url,
          b64u(spki)  // SPKI, not raw32
      );

      // 5) Light DID from RAW32(pub)
      this.did = "did:kilt:light:00" + base58(raw32);

      // 6) kid normalize
      String kidOpt = config.kid().orElse("#key-1");
      this.kid = kidOpt.startsWith("#") ? kidOpt : "#" + kidOpt;

      // 7) Self-check (same as you have now)
      if (!W3CPKeyUtil.isValid(publicKey)) {
        throw new IllegalArgumentException("Invalid public key " + W3CPKeyUtil.describe(publicKey));
      }
      byte[] zeroHash = new byte[32];
      String sig = DigitalSignatureUtil.signHash(zeroHash, privateKey);
      PublicKey jcaPub = jcaFromRaw32(raw32);
      if (!DigitalSignatureUtil.verifyHash(zeroHash, sig, jcaPub)) {
        throw new IllegalStateException("KILT key self-check failed");
      }

      log.info("KILT identity ready (PoC, deterministic from mnemonic): {}", this.did);
    } catch (Exception e) {
      throw new RuntimeException("Failed to initialize KILT identity", e);
    }
  }


  @Override
  public String signSha256(byte[] sha256Hash) {
    try {
      // Debug the key conversion
      PrivateKey jcaPrivateKey = W3CPKeyUtil.asPrivateKey(privateKey);
      log.info("KILT private key algorithm: {}", jcaPrivateKey.getAlgorithm());
      log.info("KILT private key format: {}", jcaPrivateKey.getFormat());

      String signature = DigitalSignatureUtil.signHash(sha256Hash, privateKey);
      log.info("KILT signature length: {}, content: {}", signature.length(), signature);

      // Validate signature format
      if (!signature.matches("^[A-Za-z0-9_-]+$")) {
        log.error("KILT signature contains invalid Base64URL characters: {}", signature);
      }
      if (signature.length() < 64 || signature.length() > 1024) {
        log.error("KILT signature length {} is out of bounds (64-1024)", signature.length());
      }

      return signature;
    } catch (Exception e) {
      throw new RuntimeException("KILT signing failed", e);
    }
  }

  @Override
  public W3CPPublicKey getPublicKey() {
    return publicKey;
  }

  /* ---------- helpers ---------- */

  private static String b64u(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static PublicKey jcaFromRaw32(byte[] raw32) throws Exception {
    byte[] prefix = new byte[]{0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};
    byte[] spki = new byte[prefix.length + raw32.length];
    System.arraycopy(prefix, 0, spki, 0, prefix.length);
    System.arraycopy(raw32, 0, spki, prefix.length, raw32.length);
    return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
  }

  private static byte[] raw32FromEd25519Spki(byte[] spki) {
    for (int i = 0; i <= spki.length - 35; i++) {
      if (spki[i] == 0x03) {
        int len = spki[i + 1] & 0xFF, off = i + 2;
        if (len == 0x81) {
          len = spki[i + 2] & 0xFF;
          off = i + 3;
        } else if (len == 0x82) {
          len = ((spki[i + 2] & 0xFF) << 8) | (spki[i + 3] & 0xFF);
          off = i + 4;
        }
        if (off + len <= spki.length && len == 0x21 && spki[off] == 0x00) {
          byte[] raw = new byte[32];
          System.arraycopy(spki, off + 1, raw, 0, 32);
          return raw;
        }
      }
    }
    throw new IllegalArgumentException("Invalid Ed25519 SPKI: raw32 not found");
  }

  // Minimal Base58 (encode-only)
  private static String base58(byte[] input) {
    if (input == null || input.length == 0) return "";
    final String ALPH = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    int zeros = 0;
    while (zeros < input.length && input[zeros] == 0) zeros++;
    byte[] num = java.util.Arrays.copyOf(input, input.length);
    StringBuilder out = new StringBuilder(input.length * 2);
    int start = zeros;
    while (!allZero(num, start)) {
      int rem = divmod(num, start);
      if (num[start] == 0) start++;
      out.append(ALPH.charAt(rem));
    }
    for (int i = 0; i < zeros; i++) out.append('1');
    return out.reverse().toString();
  }

  private static boolean allZero(byte[] a, int s) {
    for (int i = s; i < a.length; i++) if (a[i] != 0) return false;
    return true;
  }

  private static int divmod(byte[] n, int s) {
    final int B = 58;
    int r = 0;
    for (int i = s; i < n.length; i++) {
      int t = (r << 8) | (n[i] & 0xFF);
      n[i] = (byte) (t / B);
      r = t % B;
    }
    return r;
  }
}
