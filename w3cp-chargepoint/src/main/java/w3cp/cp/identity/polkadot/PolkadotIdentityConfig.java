package w3cp.cp.identity.polkadot;

import io.smallrye.config.ConfigMapping;
import java.util.Optional;

@ConfigMapping(prefix = "w3cp.identity.polkadot")
public interface PolkadotIdentityConfig {
  String mnemonic();      // BIP39 mnemonic phrase
  String passphrase();    // Optional BIP39 passphrase
  Optional<String> kid(); // Optional key fragment (e.g., "#key-1")
}