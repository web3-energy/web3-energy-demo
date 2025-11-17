package w3cp.cp.util;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import w3cp.cp.identity.ChargepointIdentity;
import w3cp.model.identity.W3CPPrivateKey;
import w3cp.model.identity.W3CPPublicKey;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;

@Slf4j
public class DigitalSignatureUtil {

  private static final ObjectMapper canonicalMapper = new ObjectMapper();

  static {
    canonicalMapper.registerModule(new JavaTimeModule()); // Support for java.time.Instant, etc.
    canonicalMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); // Use ISO-8601 format

    canonicalMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    canonicalMapper.setConfig(
        canonicalMapper.getSerializationConfig()
            .with(PropertyNamingStrategies.LOWER_CAMEL_CASE)
            .with(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
    );
  }

  public static String sign(Object payload, W3CPPrivateKey privateKey) throws Exception {
    byte[] hash = computeSHA256BytesOnPayload(payload);
    return signHash(hash, W3CPKeyUtil.asPrivateKey(privateKey));
  }

  public static boolean verify(Object payload, String base64Signature, W3CPPublicKey publicKey) throws Exception {
    byte[] hash = computeSHA256BytesOnPayload(payload);
    return verifyHash(hash, base64Signature, publicKey);
  }

  public static String signHash(byte[] hash, W3CPPrivateKey privateKey) throws Exception {
    return signHash(hash, W3CPKeyUtil.asPrivateKey(privateKey));
  }

  public static boolean verifyHash(byte[] hash, String base64Signature, W3CPPublicKey publicKey) throws Exception {
    return verifyHash(hash, base64Signature, W3CPKeyUtil.asPublicKey(publicKey));
  }

  public static String computeSHA256HashOnPayload(Object payload) throws Exception {
    byte[] canonicalJson = canonicalizeJson(payload);
    return computeSHA256HashOnBytes(canonicalJson);
  }

  public static byte[] computeSHA256BytesOnPayload(Object payload) throws Exception {
    byte[] canonicalJson = canonicalizeJson(payload);
    return computeSHA256Bytes(canonicalJson);
  }

  public static String computeSHA256HashOnBytes(byte[] data) throws Exception {
    byte[] hash = computeSHA256Bytes(data);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
  }

  public static byte[] computeSHA256Bytes(byte[] data) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    return digest.digest(data);
  }

  /**
   * Signs the given SHA-256 hash using a raw {@link PrivateKey}.
   * ⚠️ WARNING:
   * This method directly uses an in-memory private key, which is insecure and
   * MUST NOT be used in production environments.
   * In real deployments, use a {@link ChargepointIdentity} backed by secure key storage
   * (e.g., TPM, HSM, smartcard, ssh-agent) to ensure private key isolation and prevent
   * exposure in memory. This method is only suitable for testing, simulation, or controlled demo setups.
   */
  public static String signHash(byte[] hash, PrivateKey privateKey) throws Exception {
    Signature signature = Signature.getInstance(signatureAlgorithm(privateKey));
    signature.initSign(privateKey);
    signature.update(hash);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
  }

  public static boolean verifyHash(byte[] hash, String base64Signature, PublicKey publicKey) throws Exception {
    Signature signature = Signature.getInstance(signatureAlgorithm(publicKey));
    signature.initVerify(publicKey);
    signature.update(hash);
    return signature.verify(Base64.getUrlDecoder().decode(base64Signature));
  }

  private static byte[] canonicalizeJson(Object payload) throws Exception {
    String json = canonicalMapper.writeValueAsString(payload);
    log.debug("Canonical JSON: " + json);
    log.debug("Bytes: " + Arrays.toString(json.getBytes(StandardCharsets.UTF_8)));
    return json.getBytes(StandardCharsets.UTF_8);
  }

  private static String signatureAlgorithm(Key key) {
    if (key instanceof RSAPrivateKey || key instanceof RSAPublicKey) return "SHA256withRSA";
    if (key instanceof ECPrivateKey || key instanceof ECPublicKey) return "SHA256withECDSA";
    if ("Ed25519".equals(key.getAlgorithm()) || "EdDSA".equals(key.getAlgorithm())) return "Ed25519";
    throw new IllegalArgumentException("Unsupported key type: " + key.getAlgorithm());
  }
}
