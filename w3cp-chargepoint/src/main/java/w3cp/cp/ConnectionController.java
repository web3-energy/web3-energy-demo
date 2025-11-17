package w3cp.cp;

import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import w3cp.cp.logic.W3CPMessageHandler;
import w3cp.cp.ws.WebSocketCPConnection;

@ApplicationScoped
@Data
@Slf4j
public class ConnectionController {

  private final CPConnection connection;
  private final W3CPMessageHandler w3CPMessageHandler;

  private boolean autoReconnect = true;

  @Inject
  public ConnectionController(WebSocketCPConnection connection, W3CPMessageHandler w3CPMessageHandler) {
    this.connection = connection;
    this.w3CPMessageHandler = w3CPMessageHandler;
  }

  @PostConstruct
  void logStartup() {
    log.info("ConnectionController initialized");
  }

  public Uni<Void> connect() {
    return connection.connect();
  }

  public Uni<Void> disconnect() {
    return connection.disconnect();
  }

  public boolean isConnected() {
    return connection.isConnected();
  }

  @Scheduled(every = "10s")
  void checkAndReconnect() {
    if (autoReconnect && !connection.isConnected()) {
      log.info("Detected CP is offline. Attempting reconnect...");
      connection.setMessageHandler(w3CPMessageHandler::handle);
      connection.connect().subscribe().with(
          unused -> {
          },
          failure -> log.error("Reconnect failed.", failure)
      );
    }
  }

}

