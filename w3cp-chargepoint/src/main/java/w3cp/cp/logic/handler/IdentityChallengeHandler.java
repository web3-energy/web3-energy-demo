package w3cp.cp.logic.handler;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import w3cp.cp.config.CpConfig;
import w3cp.cp.config.error.W3CPChargepointException;
import w3cp.cp.identity.ChargepointIdentity;
import w3cp.cp.identity.bare.PlaintextIdentity;
import w3cp.cp.identity.kilt.KiltIdentity;
import w3cp.cp.identity.polkadot.PolkadotIdentity;
import w3cp.cp.util.DigitalSignatureUtil;
import w3cp.model.W3CPMessage;
import w3cp.model.W3CPMessageType;
import w3cp.model.identity.IdentityChallenge;
import w3cp.model.identity.IdentityProof;
import w3cp.model.identity.discovery.IdentityReport;
import w3cp.model.identity.discovery.IdentityDiscovery;
import w3cp.model.identity.IdentityType;
import w3cp.model.identity.web3.Web3Identity;
import w3cp.model.identity.key.PublicKeyIdentity;
import java.util.List;
import java.util.ArrayList;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Slf4j
@ApplicationScoped
public class IdentityChallengeHandler {

  @Inject
  PlaintextIdentity bareKeyIdentity;
  @Inject
  KiltIdentity kiltIdentity;
  @Inject
  PolkadotIdentity polkadotIdentity;
  @Inject
  CpConfig cpConfig;

  private ChargepointIdentity getPrimaryIdentity() {
    return switch (cpConfig.identityType()) {
      case "bare-key" -> bareKeyIdentity;
      case "kilt" -> kiltIdentity;
      case "polkadot" -> polkadotIdentity;
      default -> throw new IllegalArgumentException("Unknown identity type: " + cpConfig.identityType());
    };
  }

  public Uni<W3CPMessage<IdentityProof>> handle(IdentityChallenge challenge) {
    return Uni.createFrom().item(() -> {
      log.info("Using CP ID: {}", cpConfig.cpId());
      int difficulty = challenge.difficulty();
      long powNonce = 1;

      Web3Identity web3Identity = getWeb3Identity();
      IdentityType identityType = getIdentityType();
      IdentityProof proof = new IdentityProof(cpConfig.cpId(), Instant.now(), challenge.nonce(), identityType, web3Identity, 1);
      String hash;

      do {
        if (powNonce == 0) {
          proof.setTimestamp(Instant.now());
        }

        proof.setPowNonce(powNonce++);
        try {
          hash = DigitalSignatureUtil.computeSHA256HashOnPayload(proof);
        } catch (Exception e) {
          throw new W3CPChargepointException("Failed to compute hash for identityProof.", e);
        }

      } while (!meetsDifficulty(hash.getBytes(StandardCharsets.UTF_8), difficulty));

      ChargepointIdentity primaryIdentity = getPrimaryIdentity();
      return new W3CPMessage<>(
          W3CPMessageType.identityProof,
          proof,
          primaryIdentity.signSha256(hash.getBytes(StandardCharsets.UTF_8)),
          hash);
    });
  }

  private boolean meetsDifficulty(byte[] hash, int difficulty) {
    if (difficulty <= 0) return true;
    int zeroBits = 0;
    for (int i = hash.length - 1; i >= 0 && zeroBits < difficulty; i--) {
      int b = hash[i] & 0xFF;
      for (int j = 0; j < 8; j++) {
        if ((b & 1) == 0) {
          zeroBits++;
          b >>= 1;
        } else {
          return false;
        }
      }
    }
    return zeroBits >= difficulty;
  }

  public Uni<W3CPMessage<IdentityReport>> handle(IdentityDiscovery discovery) {
    return Uni.createFrom().item(() -> {
      // Create public key identities list
      List<PublicKeyIdentity> publicKeys = new ArrayList<>();
      publicKeys.add(new PublicKeyIdentity(bareKeyIdentity.getPublicKey()));
      
      // Create Web3 identities list
      List<Web3Identity> web3Identities = new ArrayList<>();
      web3Identities.add(new Web3Identity(
          Web3Identity.Web3IdentityMethod.kilt,
          kiltIdentity.getDid(),
          kiltIdentity.getKid()
      ));
      web3Identities.add(new Web3Identity(
          Web3Identity.Web3IdentityMethod.polkadot,
          polkadotIdentity.getDid(),
          polkadotIdentity.getKid()
      ));
      
      // Create report
      IdentityReport report = new IdentityReport(
          discovery.correlationId(),
          Instant.now(),
          publicKeys,
          new ArrayList<>(),  // empty X509 certificates
          web3Identities
      );
      
      return new W3CPMessage<>(
          W3CPMessageType.identityReport,
          report,
          null,
          null
      );
    });
  }

  private IdentityType getIdentityType() {
    return switch (cpConfig.identityType()) {
      case "bare-key" -> IdentityType.publicKey;
      case "kilt", "polkadot" -> IdentityType.web3;
      default -> throw new IllegalArgumentException("Unknown identity type: " + cpConfig.identityType());
    };
  }

  private Web3Identity getWeb3Identity() {
    return switch (cpConfig.identityType()) {
      case "bare-key" -> null;
      case "kilt" -> new Web3Identity(
          Web3Identity.Web3IdentityMethod.kilt,
          kiltIdentity.getDid(),
          kiltIdentity.getKid()
      );
      case "polkadot" -> new Web3Identity(
          Web3Identity.Web3IdentityMethod.polkadot,
          polkadotIdentity.getDid(),
          polkadotIdentity.getKid()
      );
      default -> throw new IllegalArgumentException("Unknown identity type: " + cpConfig.identityType());
    };
  }
}
