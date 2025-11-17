package w3cp.cp.logic.state.input.dummy;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import w3cp.cp.config.CpConfig;
import w3cp.cp.logic.state.CPState;
import w3cp.model.ChargePointStatus;
import w3cp.model.identity.IdentityType;

import java.time.Instant;
import java.util.List;

@Slf4j
@ApplicationScoped
@Startup
public class DummyStaticFeeder {

  public static final String FEEDER_TYPE = "dummy-static";

  @Inject
  CPState cpState;

  @Inject
  CpConfig cpConfig;

  @PostConstruct
  void init() {
    cpConfig.evses().stream()
        .filter(cfg -> FEEDER_TYPE.equals(cfg.feeder().type()))
        .forEach(cfg -> {
          ChargePointStatus.Evse evse = generate(cfg.evseId());
          cpState.evseFullUpdate(cfg.evseId(), evse, false)
              .subscribe().with(
                  success -> log.info("✅ DummyStaticFeeder initialized EVSE: {}", cfg.evseId()),
                  failure -> log.error("❌ Failed to initialize EVSE: {}", cfg.evseId(), failure)
              );
        });
  }

  private void waitForCPStateInitialization() {
    long start = System.currentTimeMillis();
    while ((cpState.getCurrentStatus() == null || cpState.getCurrentStatus().getEvses() == null) &&
        (System.currentTimeMillis() - start < 2000)) {
      try {
        Thread.sleep(50);
      } catch (InterruptedException ignored) {
      }
    }

    if (cpState.getCurrentStatus() == null || cpState.getCurrentStatus().getEvses() == null) {
      log.warn("DummyStaticFeeder proceeding without confirmed CP state initialization");
    }
  }

  public static ChargePointStatus.Evse generate(String evseId) {
    ChargePointStatus.Evse evse = new ChargePointStatus.Evse();
    evse.setEvseId(evseId);
    evse.setStatus(ChargePointStatus.ConnectorStatus.charging);
    evse.setPluggedIn(true);
    evse.setPluggedConnector("Type2");
    evse.setLocked(true);
    evse.setConnectors(List.of("Type2"));
    evse.setMeter(15.7);
    evse.setVoltage(230.0);
    evse.setCurrent(List.of(16.0, 15.5, 16.2));
    evse.setPower(1123.5);

    ChargePointStatus.LatestTransaction tx = new ChargePointStatus.LatestTransaction();
    tx.setSessionId("tx-123456");
    tx.setStartedAt(Instant.now().minusSeconds(600));
    tx.setMeterStart(0.0);
    tx.setEnergyDelivered(15.7);
    tx.setTransactionState(ChargePointStatus.LatestTransaction.TransactionState.ongoing);
    evse.setLatestTransaction(tx);

    ChargePointStatus.ThermalInfo thermals = new ChargePointStatus.ThermalInfo();
    ChargePointStatus.TemperatureValue temp = new ChargePointStatus.TemperatureValue();
    temp.setValue(35.5);
    temp.setUnit(ChargePointStatus.TemperatureUnit.celsius);
    thermals.setConnector(temp);
    thermals.setCable(temp);
    evse.setThermalInfo(thermals);

    ChargePointStatus.VehicleIdentity vid = new ChargePointStatus.VehicleIdentity();
    vid.setType(IdentityType.web3);
    vid.setId("did:example:abc123");
    evse.setVehicleIdentity(vid);

    ChargePointStatus.VehicleState vs = new ChargePointStatus.VehicleState();
    vs.setVehicleId("VIN123456789");
    vs.setVehicleBrand("ExampleEV");
    vs.setVehicleSoc(80);
    vs.setVehicleSocTarget(90);
    vs.setCurrentCapacityWh(40000);
    vs.setTotalCapacityWh(50000);
    vs.setEvProtocol(ChargePointStatus.EvProtocol.ISO15118);
    vs.setCarBatteryState("80:40000:50000:tx-123456");
    evse.setVehicleState(vs);

    return evse;
  }
}
