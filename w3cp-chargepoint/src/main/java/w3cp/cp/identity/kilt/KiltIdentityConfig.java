package w3cp.cp.identity.kilt;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Optional;

@ConfigMapping(prefix = "w3cp.identity.kilt")
public interface KiltIdentityConfig {
  String mnemonic();      // BIP39 mnemonic phrase
  String passphrase();    // Optional BIP39 passphrase
  Optional<String> kid(); // Optional key fragment (e.g., "#key-1")
}