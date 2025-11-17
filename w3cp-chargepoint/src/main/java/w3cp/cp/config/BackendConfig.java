package w3cp.cp.config;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.Data;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;

@ApplicationScoped
@RegisterForReflection
@Data
public class BackendConfig {

  @ConfigProperty(name = "w3cp.backend.websocket.url")
  String websocketUrl;

  public URI uri() {
    return URI.create(websocketUrl);
  }
}