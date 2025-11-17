package w3cp.cp;

import io.smallrye.mutiny.Uni;

import java.util.function.Consumer;

public interface CPConnection {
  Uni<Void> connect();
  Uni<Void> disconnect();
  Uni<Void> send(String message);
  void setMessageHandler(Consumer<String> handler);
  boolean isConnected();

}
