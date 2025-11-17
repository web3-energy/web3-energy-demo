package w3cp.cp.identity;

import w3cp.model.identity.W3CPPublicKey;

public interface ChargepointIdentity {

  /**
   * Returns the CP's public key in W3CP protocol format.
   */
  W3CPPublicKey getPublicKey();

  /**
   * Signs a precomputed SHA-256 hash.
   *
   * @param sha256Hash 32-byte SHA-256 hash
   * @return raw signature bytes, encoded base64url without padding
   */
  String signSha256(byte[] sha256Hash);
}
