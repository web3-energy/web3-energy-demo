package w3cp.cp.logic;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import w3cp.cp.CPConnection;
import w3cp.cp.logic.handler.IdentityChallengeHandler;
import w3cp.cp.logic.state.CPState;
import w3cp.cp.util.WebSocketConnectionUtil;
import w3cp.model.ConnectionStatus;
import w3cp.model.W3CPMessageType;
import w3cp.model.identity.IdentityChallenge;
import w3cp.model.identity.discovery.IdentityDiscovery;


@Slf4j
@ApplicationScoped
public class W3CPMessageHandler {

  @Inject
  IdentityChallengeHandler identityChallengeHandler;
  @Inject
  CPConnection connection;
  @Inject
  CPState cpState;

  public void handle(String rawMessage) {
    try {
      JsonObject json = new JsonObject(rawMessage);
      String typeString = json.getString("type");
      Object payloadObj = json.getValue("payload");

      if (typeString == null || payloadObj == null) {
        log.warn("Invalid W3CP message: missing type or payload: {}", rawMessage);
        return;
      }

      W3CPMessageType type = W3CPMessageType.valueOf(typeString);

      switch (type) {
        case identityChallenge -> handleIdentityChallenge((JsonObject) payloadObj);
        case identityDiscovery -> handleIdentityDiscovery((JsonObject) payloadObj);
        case connectionStatus -> handleConnectionStatus((JsonObject) payloadObj);
        default -> log.warn("Unhandled W3CP message type: {}", type);
      }

    } catch (Exception e) {
      log.error("Failed to handle incoming message: {}", rawMessage, e);
    }
  }

  private void handleIdentityChallenge(JsonObject payload) {
    try {
      // Deserialize from JsonObject map
      IdentityChallenge challenge = W3CPJson.MAPPER.convertValue(payload.getMap(), IdentityChallenge.class);

      identityChallengeHandler.handle(challenge)
          .onItem().transformToUni(response -> {
            try {
              String responseJson = W3CPJson.MAPPER.writeValueAsString(response);
              return connection.send(responseJson);
            } catch (JsonProcessingException e) {
              log.error("Failed to serialize IdentityProofMessage", e);
              return connection.disconnect().replaceWithVoid();
            }
          })
          .subscribe().with(
              success -> log.debug("IdentityProof sent successfully."),
              failure -> log.error("Failed to process IdentityChallenge.", failure)
          );

    } catch (Exception e) {
      log.error("Invalid IdentityChallenge payload", e);
      WebSocketConnectionUtil.disconnectSafely(connection).subscribe().with(
          unused -> log.warn("Disconnected due to invalid challenge.")
      );
    }
  }

  private void handleIdentityDiscovery(JsonObject payload) {
    try {
      IdentityDiscovery discovery = W3CPJson.MAPPER.convertValue(payload.getMap(), IdentityDiscovery.class);

      identityChallengeHandler.handle(discovery)
          .onItem().transformToUni(response -> {
            try {
              String responseJson = W3CPJson.MAPPER.writeValueAsString(response);
              return connection.send(responseJson);
            } catch (JsonProcessingException e) {
              log.error("Failed to serialize IdentityReport", e);
              return connection.disconnect().replaceWithVoid();
            }
          })
          .subscribe().with(
              success -> log.debug("IdentityReport sent successfully."),
              failure -> log.error("Failed to process IdentityDiscovery.", failure)
          );

    } catch (Exception e) {
      log.error("Invalid IdentityDiscovery payload", e);
    }
  }

  private void handleConnectionStatus(JsonObject payload) {
    try {
      ConnectionStatus status = W3CPJson.MAPPER.convertValue(payload.getMap(), ConnectionStatus.class);
      log.info("Received connectionStatus: {}", status);

      if (status.status() == ConnectionStatus.Status.verified) {
        cpState.markBackendConnectionVerified()
            .chain(() -> cpState.sendCurrentStatus())
            .subscribe().with(
                success -> log.info("✅ Sent initial CP status after verification"),
                error -> log.error("❌ Failed to send CP status after verification", error)
            );
      } else {
        log.warn("Backend responded with non-verified connection status: {}", status.status());
        // No action needed; backend likely disconnects
      }

    } catch (Exception e) {
      log.error("Failed to handle connectionStatus message", e);
    }
  }

}

