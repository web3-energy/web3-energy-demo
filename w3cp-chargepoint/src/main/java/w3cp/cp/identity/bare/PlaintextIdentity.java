package w3cp.cp.identity.bare;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import w3cp.cp.identity.ChargepointIdentity;
import w3cp.cp.util.DigitalSignatureUtil;
import w3cp.cp.util.W3CPKeyUtil;
import w3cp.model.identity.W3CPPrivateKey;
import w3cp.model.identity.W3CPPublicKey;

@Slf4j
@ApplicationScoped
public class PlaintextIdentity implements ChargepointIdentity {

  private final W3CPPrivateKey privateKey;
  private final W3CPPublicKey publicKey;

  @Inject
  public PlaintextIdentity(IdentityConfig config) {
    this.privateKey = new W3CPPrivateKey(
        config.type(),
        W3CPPublicKey.KeyEncoding.base64url,
        config.privateKey()
    );

    this.publicKey = new W3CPPublicKey(
        config.type(),
        W3CPPublicKey.KeyEncoding.base64url,
        config.publicKey()
    );

    if (!W3CPKeyUtil.isValid(publicKey)) {
      throw new IllegalArgumentException("Invalid public key: " + W3CPKeyUtil.describe(publicKey));
    }

    // ✅ Validate key pair match
    try {
      byte[] testHash = new byte[32]; // SHA-256 of 0x00..00
      String signature = DigitalSignatureUtil.signHash(testHash, privateKey);
      boolean valid = DigitalSignatureUtil.verifyHash(testHash, signature, publicKey);
      if (!valid) {
        throw new IllegalArgumentException("Private and public keys do not match.");
      }
    } catch (Exception e) {
      throw new RuntimeException("Key pair validation failed", e);
    }
  }

  @Override
  public String signSha256(byte[] sha256Hash) {
    try {
      return DigitalSignatureUtil.signHash(sha256Hash, privateKey);
    } catch (Exception e) {
      throw new RuntimeException("Signing failed", e);
    }
  }

  @Override
  public W3CPPublicKey getPublicKey() {
    return publicKey;
  }
}