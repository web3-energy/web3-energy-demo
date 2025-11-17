package w3cp.cp.util;

import w3cp.model.identity.W3CPPrivateKey;
import w3cp.model.identity.W3CPPublicKey;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public class W3CPKeyUtil {

  public static boolean isValid(W3CPPublicKey key) {
    try {
      // If we can decode to a JCA PublicKey using the runtime path, it's valid.
      return asPublicKey(key) != null;
    } catch (Exception e) {
      return false;
    }
  }

  public static PublicKey asPublicKey(W3CPPublicKey key) throws Exception {
    if (key.encoding() != W3CPPublicKey.KeyEncoding.base64url
        || key.value() == null || key.value().isBlank()) {
      throw new IllegalArgumentException("Invalid or missing key encoding/value.");
    }

    byte[] b = Base64.getUrlDecoder().decode(key.value());

    switch (key.type()) {
      case ecP256: {
        // EC must be X.509 SPKI
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(b));
      }
      case ed25519: {
        // Accept either:
        //  - RAW32 (32 bytes)  -> wrap into RFC 8410 SPKI
        //  - X.509 SPKI (DER starting with 0x30)
        byte[] spki;
        if (b.length == 32) {
          byte[] prefix = new byte[]{0x30,0x2a,0x30,0x05,0x06,0x03,0x2b,0x65,0x70,0x03,0x21,0x00};
          spki = new byte[prefix.length + 32];
          System.arraycopy(prefix, 0, spki, 0, prefix.length);
          System.arraycopy(b, 0, spki, prefix.length, 32);
        } else {
          spki = b; // assume SPKI
        }
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
      }
      default:
        throw new IllegalStateException("Unsupported key type: " + key.type());
    }
  }


  public static String describe(W3CPPublicKey key) {
    return "%s key using %s encoding".formatted(key.type(), key.encoding());
  }

  public static PrivateKey asPrivateKey(W3CPPrivateKey key) throws Exception {
    if (key.encoding() != W3CPPublicKey.KeyEncoding.base64url) {
      throw new IllegalArgumentException("Unsupported encoding: " + key.encoding());
    }

    byte[] decoded = Base64.getUrlDecoder().decode(key.value());
    PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(decoded);

    return switch (key.type()) {
      case ecP256 -> KeyFactory.getInstance("EC").generatePrivate(keySpec);
      case ed25519 -> KeyFactory.getInstance("Ed25519").generatePrivate(keySpec);
      default -> throw new IllegalStateException("Unsupported key type: " + key.type());
    };
  }

}
