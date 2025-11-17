package w3cp.identity;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import w3cp.cp.logic.handler.IdentityChallengeHandler;
import w3cp.model.identity.discovery.IdentityDiscovery;
import w3cp.model.identity.discovery.IdentityReport;
import w3cp.model.W3CPMessage;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class IdentityReportTest {

  @Inject
  IdentityChallengeHandler handler;

  @Test
  public void testIdentityReportGeneration() {
    // Create IdentityDiscovery request
    UUID correlationId = UUID.randomUUID();
    IdentityDiscovery discovery = new IdentityDiscovery(correlationId, Instant.now());

    // Generate IdentityReport
    W3CPMessage<IdentityReport> message = handler.handle(discovery).await().indefinitely();

    // Verify message structure
    assertNotNull(message);
    assertNotNull(message.payload());
    assertNull(message.payloadSignature()); // Should be null as per implementation
    assertNull(message.payloadSha256Hash()); // Should be null as per implementation

    // Verify IdentityReport content
    IdentityReport report = message.payload();
    assertEquals(correlationId, report.correlationId());
    assertNotNull(report.timestamp());

    // Should have 1 public key (bare-key identity)
    assertEquals(1, report.publicKeys().size());
    assertNotNull(report.publicKeys().getFirst().publicKey());
    
    // Should have 2 Web3 identities (KILT and Polkadot)
    assertEquals(2, report.web3Identities().size());
    
    // Verify KILT identity
    var kiltIdentity = report.web3Identities().get(0);
    assertEquals("kilt", kiltIdentity.method().name());
    assertNotNull(kiltIdentity.did());
    assertTrue(kiltIdentity.did().startsWith("did:kilt:light:00"));
    
    // Verify Polkadot identity
    var polkadotIdentity = report.web3Identities().get(1);
    assertEquals("polkadot", polkadotIdentity.method().name());
    assertNotNull(polkadotIdentity.did());
    assertTrue(polkadotIdentity.did().startsWith("did:w3cp:"));
    
    // Should have no X509 certificates
    assertTrue(report.x509Certificates().isEmpty());
  }
}
