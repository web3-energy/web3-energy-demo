package w3cp.cp.logic;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import w3cp.cp.ws.WebSocketCPConnection;

@ApplicationScoped
public class W3CPFirmwareInitializer {

  private final WebSocketCPConnection connection;
  private final W3CPMessageHandler messageHandler;

  @Inject
  public W3CPFirmwareInitializer(WebSocketCPConnection connection, W3CPMessageHandler messageHandler) {
    this.connection = connection;
    this.messageHandler = messageHandler;
  }

  @PostConstruct
  void initialize() {
    connection.setMessageHandler(messageHandler::handle);
  }
}
