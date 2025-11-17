package w3cp.cp.identity.bare;

import io.smallrye.config.ConfigMapping;
import w3cp.model.identity.W3CPPublicKey;

@ConfigMapping(prefix = "w3cp.identity.bare-key")
public interface IdentityConfig {
  String privateKey();    // Base64
  String publicKey();     // Base64
  W3CPPublicKey.KeyType type();
}