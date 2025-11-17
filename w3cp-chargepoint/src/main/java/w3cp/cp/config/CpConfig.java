package w3cp.cp.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithName;

import java.util.List;
import java.util.Optional;

@ConfigMapping(prefix = "w3cp.cp")
public interface CpConfig {
  @WithName("cp-id")
  String cpId();

  @WithName("identity-type")
  String identityType();

  List<EvseConfig> evses();

  interface EvseConfig {
    @WithName("evse-id")
    String evseId();

    FeederConfig feeder();
  }

  interface FeederConfig {
    String type();                           // dummy or remote

    Optional<String> source();              // URL or null

    @WithName("api-key")
    Optional<String> apiKey();              // optional
  }
}


