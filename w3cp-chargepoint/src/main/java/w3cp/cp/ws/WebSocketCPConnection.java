package w3cp.cp.ws;

import io.smallrye.mutiny.Uni;
import io.vertx.core.http.WebsocketVersion;
import io.vertx.mutiny.core.MultiMap;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.http.HttpClient;
import io.vertx.mutiny.core.http.WebSocket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import w3cp.cp.CPConnection;
import w3cp.cp.config.BackendConfig;

import java.net.URI;
import java.util.Collections;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@ApplicationScoped
@Slf4j
public class WebSocketCPConnection implements CPConnection {

  private final Vertx vertx;
  private final URI backendUri;
  private final AtomicReference<WebSocket> webSocketRef = new AtomicReference<>();
  private final Queue<String> sendQueue = new ConcurrentLinkedQueue<>();
  private final AtomicBoolean isDraining = new AtomicBoolean(false);
  private Consumer<String> messageHandler;

  @Inject
  public WebSocketCPConnection(BackendConfig backendConfig, Vertx vertx) {
    this.backendUri = backendConfig.uri();   // e.g. wss://w3cp.web3-energy.com/w3cp
    this.vertx = vertx;
  }

  @Override
  public Uni<Void> connect() {
    // Mutiny client
    HttpClient client = vertx.createHttpClient();

    // Prepare headers (including Origin, to satisfy Quarkus CORS)
    MultiMap headers = MultiMap.newInstance(io.vertx.core.MultiMap.caseInsensitiveMultiMap());

    // wss -> https, ws -> http
    String scheme = backendUri.getScheme();
    String originScheme;
    if ("wss".equalsIgnoreCase(scheme)) {
      originScheme = "https";
    } else if ("ws".equalsIgnoreCase(scheme)) {
      originScheme = "http";
    } else {
      // Fallback – should not really happen
      originScheme = "https";
    }

    StringBuilder origin = new StringBuilder(originScheme + "://" + backendUri.getHost());
    int port = backendUri.getPort();
    if (port != -1 && port != 80 && port != 443) {
      origin.append(":").append(port);
    }

    String originValue = origin.toString();
    headers.add("Origin", originValue);

    log.info("Connecting to backend {} with Origin={}", backendUri, originValue);

    return client.webSocketAbs(
            backendUri.toString(),
            headers,
            WebsocketVersion.V13,
            Collections.emptyList()
        )
        .onItem().invoke(ws -> {
          webSocketRef.set(ws);
          log.info("WebSocket connected to {}", backendUri);

          ws.textMessageHandler(msg -> {
            log.info("Received message: {}", msg);
            if (messageHandler != null) {
              messageHandler.accept(msg);
            } else {
              log.warn("No handler set; dropping message.");
            }
          });

          ws.getDelegate().closeHandler(v -> {
            webSocketRef.set(null);
            log.info("WebSocket closed");
          });
        })
        .onFailure().invoke(e -> log.error("Error connecting to backend WebSocket", e))
        .replaceWithVoid();
  }

  @Override
  public Uni<Void> disconnect() {
    WebSocket ws = webSocketRef.getAndSet(null);
    if (ws != null && !ws.isClosed()) {
      return ws.close().replaceWithVoid();
    }
    return Uni.createFrom().voidItem();
  }

  @Override
  public Uni<Void> send(String message) {
    sendQueue.add(message);
    return drainQueue();
  }

  private Uni<Void> drainQueue() {
    if (!isDraining.compareAndSet(false, true)) {
      return Uni.createFrom().voidItem(); // Already draining
    }
    return Uni.createFrom().voidItem().chain(this::processNext);
  }

  private Uni<Void> processNext() {
    String next = sendQueue.poll();
    if (next == null) {
      isDraining.set(false);
      return Uni.createFrom().voidItem();
    }

    WebSocket ws = webSocketRef.get();
    if (ws == null || ws.isClosed()) {
      log.warn("WebSocket not connected — dropping message: {}", next);
      isDraining.set(false);
      return Uni.createFrom().voidItem();
    }

    return ws.writeTextMessage(next)
        .replaceWithVoid()
        .onFailure().invoke(e -> log.error("Failed to send message", e))
        .onTermination().call(this::processNext);
  }

  @Override
  public void setMessageHandler(Consumer<String> handler) {
    this.messageHandler = handler;
  }

  @Override
  public boolean isConnected() {
    WebSocket ws = webSocketRef.get();
    return ws != null && !ws.isClosed();
  }
}
