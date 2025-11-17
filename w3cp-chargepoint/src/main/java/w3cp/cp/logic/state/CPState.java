package w3cp.cp.logic.state;

import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import w3cp.cp.CPConnection;
import w3cp.cp.config.CpConfig;
import w3cp.cp.logic.W3CPJson;
import w3cp.cp.util.NetworkDetectorUtil;
import w3cp.model.ChargePointStatus;
import w3cp.model.W3CPMessage;
import w3cp.model.W3CPMessageType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Slf4j
@ApplicationScoped
public class CPState {

  private final CPConnection connection;
  private final CpConfig cpConfig;
  private final AtomicReference<ChargePointStatus> currentStatus = new AtomicReference<>();
  private final ExecutorService executor = Executors.newSingleThreadExecutor(); // 🔁 Single-threaded executor to serialize state changes

  @Inject
  public CPState(CPConnection connection, CpConfig cpConfig) {
    this.connection = connection;
    this.cpConfig = cpConfig;

    // 🔁 Initialize status directly to ensure ready state
    executor.submit(() -> {
      ChargePointStatus status = new ChargePointStatus();
      List<ChargePointStatus.Evse> evseList = new ArrayList<>();
      for (CpConfig.EvseConfig evseConfig : cpConfig.evses()) {
        ChargePointStatus.Evse evse = new ChargePointStatus.Evse();
        evse.setEvseId(evseConfig.evseId());
        evseList.add(evse);
      }
      status.setEvses(evseList);
      status.setTimestamp(Instant.now());
      currentStatus.set(status);
      log.info("✅ Initialized CP state with {} EVSEs", evseList.size());
    });
  }

  public ChargePointStatus getCurrentStatus() {
    return currentStatus.get();
  }

  public void updateStatus(ChargePointStatus status, boolean sendNow) {
    // 🔁 Submit status update to executor
    executor.submit(() -> {
      currentStatus.set(status);
      if (sendNow) {
        sendStatus(status)
            .subscribe().with(
                unused -> log.debug("Status sent"),
                error -> log.error("Failed to send status", error)
            );
      }
    });
  }

  public Uni<Void> sendCurrentStatus() {
    ChargePointStatus status = currentStatus.get();
    return status == null ? Uni.createFrom().voidItem() : sendStatus(status);
  }

  public Uni<Void> markBackendConnectionVerified() {
    return Uni.createFrom().emitter(emitter -> {
      // 🔁 Submit backend verification logic to executor
      executor.submit(() -> {
        currentStatus.updateAndGet(current -> {
          if (current == null) {
            log.warn("CP status is null in markBackendConnectionVerified. Skipping update.");
            return null;
          }

          current.setOnlineSince(Instant.now());
          current.setTimestamp(Instant.now());
          current.setConnectionType(NetworkDetectorUtil.detectConnectionType());
          return current;
        });

        emitter.complete(null);
      });
    });
  }

  public Uni<Void> updateEvse(String evseId, Consumer<ChargePointStatus.Evse> patch, boolean sendNow) {
    return Uni.createFrom().emitter(emitter -> {
      // 🔁 Submit EVSE update logic to executor
      executor.submit(() -> {
        boolean updated = currentStatus.updateAndGet(current -> {
          if (current == null || current.getEvses() == null) return current;

          ChargePointStatus.Evse evse = current.getEvses().stream()
              .filter(e -> evseId.equals(e.getEvseId()))
              .findFirst()
              .orElse(null);

          if (evse == null) {
            log.warn("EVSE with id {} not found in current status, ignoring update", evseId);
            return current;
          }

          patch.accept(evse);
          current.setTimestamp(Instant.now());
          return current;
        }) != null;

        if (updated && sendNow) {
          sendCurrentStatus()
              .subscribe().with(emitter::complete, emitter::fail);
        } else {
          emitter.complete(null);
        }
      });
    });
  }

  public Uni<Void> evseFullUpdate(String evseId, ChargePointStatus.Evse newEvse, boolean sendNow) {
    return Uni.createFrom().emitter(emitter -> {
      // 🔁 Submit EVSE full replacement logic to executor
      executor.submit(() -> {
        boolean updated = currentStatus.updateAndGet(current -> {
          if (current == null || current.getEvses() == null) return current;

          List<ChargePointStatus.Evse> evses = current.getEvses();
          for (int i = 0; i < evses.size(); i++) {
            if (evseId.equals(evses.get(i).getEvseId())) {
              evses.set(i, newEvse);
              current.setTimestamp(Instant.now());
              return current;
            }
          }

          log.warn("EVSE with id {} not found, cannot replace", evseId);
          return current;
        }) != null;

        if (updated && sendNow) {
          sendCurrentStatus()
              .subscribe().with(emitter::complete, emitter::fail);
        } else {
          emitter.complete(null);
        }
      });
    });
  }

  private Uni<Void> sendStatus(ChargePointStatus status) {
    status.setTimestamp(Instant.now());

    W3CPMessage<ChargePointStatus> message = new W3CPMessage<>(
        W3CPMessageType.chargepointStatus,
        status,
        null, null
    );

    try {
      String json = W3CPJson.MAPPER.writeValueAsString(message);
      return connection.send(json);
    } catch (Exception e) {
      return Uni.createFrom().failure(e);
    }
  }
}
