package w3cp.cp.util;


import io.smallrye.mutiny.Uni;
import lombok.extern.slf4j.Slf4j;
import w3cp.cp.CPConnection;

@Slf4j
public class WebSocketConnectionUtil {

  public static Uni<Void> disconnectSafely(CPConnection connection) {
    return connection.disconnect()
        .onFailure().invoke(e -> log.error("Failed to disconnect cleanly", e))
        .replaceWithVoid();
  }
}
