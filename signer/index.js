import express from 'express';
import { ApiPromise, WsProvider } from '@polkadot/api';
import { Keyring } from '@polkadot/keyring';
import { cryptoWaitReady, base58Decode } from '@polkadot/util-crypto';

const MNEMONIC  = process.env.W3CP_ATTESTER_MNEMONIC;
const EXPECTED  = process.env.W3CP_SIGNER_PUB_KEY;
const WS        = process.env.W3CP_BLOCKCHAIN_ENDPOINT || "wss://westend-rpc.polkadot.io";

if (!MNEMONIC || !EXPECTED) {
  console.error("Missing env vars. Need W3CP_ATTESTER_MNEMONIC + W3CP_SIGNER_PUB_KEY");
  process.exit(1);
}

console.log("🚀 Starting W3CP Signer...");
console.log("→ Chain endpoint:", WS);

await cryptoWaitReady();
console.log("→ WASM crypto ready");

const keyring = new Keyring({ type: 'sr25519' });
const attester = keyring.addFromUri(MNEMONIC);

console.log("→ Loaded address:", attester.address);

if (attester.address !== EXPECTED) {
  console.error("❌ INVALID KEY LOADED!");
  console.error("Expected:", EXPECTED);
  console.error("Got     :", attester.address);
  process.exit(1);
}

console.log("✔ Correct attester key loaded");

const provider = new WsProvider(WS);
const api = await ApiPromise.create({ provider });

// ------------------------
// 🔒 Robust connection state
// ------------------------
let chainReady = true; // we just awaited create(), so it's ready now

api.on('connected', () => {
  console.log("✅ API connected");
});

api.on('ready', () => {
  chainReady = true;
  console.log("✅ API ready");
});

api.on('disconnected', () => {
  chainReady = false;
  console.warn("⚠ API disconnected, will auto-reconnect");
});

api.on('error', (err) => {
  chainReady = false;
  console.error("❌ API error:", err);
});

console.log("✔ Connected to blockchain");

const app = express();
app.use(express.json());

// ---------------------------------------------------
// 1) Global cache: stores "<cpId>::<did>" strings
// ---------------------------------------------------
const attestationCache = new Set();

// ---------------------------------------------------
// 2) DID validator
// ---------------------------------------------------
function isValidW3cpDid(did) {
  if (typeof did !== "string") return false;
  if (!did.startsWith("did:w3cp:")) return false;

  const payload = did.replace("did:w3cp:", "");

  const BASE58 = /^[1-9A-HJ-NP-Za-km-z]+$/;
  if (!BASE58.test(payload)) return false;

  try {
    const decoded = base58Decode(payload);
    return decoded.length === 32;
  } catch {
    return false;
  }
}

// ---------------------------------------------------
// 3) Startup cache (no chain scanning)
// ---------------------------------------------------

async function buildAttestationCache() {
  console.log("⚠ Skipping chain history scan (Westend has undecodable blocks).");
  console.log("⚠ Starting with empty cache. Lifts will populate cache at runtime.");
  // attestationCache stays empty until new lifts happen.
}

await buildAttestationCache();

// ---------------------------------------------------
// 4) O(1) duplicate detection
// ---------------------------------------------------
function alreadyAttested(cpId, did) {
  return attestationCache.has(`${cpId}::${did}`);
}

// Health check
app.get('/health', (req, res) => {
  res.json({ status: "ok", attester: attester.address, cacheSize: attestationCache.size });
});

// ---------------------------------------------------
// SIGN & ATTEST
// ---------------------------------------------------
app.post('/chain/lift', async (req, res) => {
  // 🔒 NEW: refuse while chain is down / reconnecting
  if (!chainReady) {
    console.error("❌ Refusing lift – blockchain not connected/ready");
    return res.status(503).json({ error: "Blockchain not connected" });
  }

  const { cpId, did } = req.body;

  console.log("Verifying Request…");

  if (!cpId || !did) {
    console.error("❌ Missing cpId or did");
    return res.status(400).json({ error: "cpId and did are required" });
  }

  if (!isValidW3cpDid(did)) {
    console.error("❌ Invalid DID format:", did);
    return res.status(400).json({
      error: "Invalid DID format. Must be did:w3cp:<base58(32-byte-pubkey)>"
    });
  }

  if (alreadyAttested(cpId, did)) {
    console.error("❌ Duplicate attestation detected in cache", cpId, did);
    return res.status(409).json({
      error: "Already attested on-chain",
      cpId,
      did
    });
  }

  console.log(`✔ Request ok cpId=${cpId}, did=${did}`);

  try {
    const payload = {
      v: 1,
      cpId,
      did,
      ts: Math.floor(Date.now() / 1000)
    };

    const remarkString = 'W3CP_BIND:' + JSON.stringify(payload);
    const remarkHex = '0x' + Buffer.from(remarkString, 'utf8').toString('hex');

    const tx = api.tx.system.remark(remarkHex);

    console.log("✔ Waiting for chain confirmation…");

    const unsub = await tx.signAndSend(attester, async (result) => {
      if (result.status.isInBlock) {
        const blockHash = result.status.asInBlock.toHex();

        let blockNumber = null;
        try {
          const signedBlock = await api.rpc.chain.getBlock(blockHash);
          blockNumber = signedBlock.block.header.number.toNumber();
        } catch (e) {
          console.warn("⚠ Could not decode full block (known Westend behavior):", e.message);
        }

        const txHash = result.txHash?.toHex
          ? result.txHash.toHex()
          : tx.hash.toHex();

        const subscanUrl = `https://westend.subscan.io/extrinsic/${txHash}`;

        // -------- UPDATE CACHE AFTER SUCCESS --------
        attestationCache.add(`${cpId}::${did}`);

        const response = {
          network: "westend",
          txHash,
          subscanUrl,
          blockHash,
          blockNumber,
          attester: attester.address,
          liftedAt: new Date().toISOString(),
          payload
        };

        console.log("✔ Lift done successfully.", response);

        res.json(response);
        unsub();
      }

      if (result.isError) {
        console.error("❌ Tx failed", result.toString());
        res.status(500).json({ error: "tx failed", detail: result.toString() });
        unsub();
      }
    });
  } catch (err) {
    console.error("❌ Exception in signing:", err);
    res.status(500).json({ error: err.message || "signing failed" });
  }
});

const PORT = 9999;
app.listen(PORT, () => {
  console.log(`🔥 W3CP Signer running on http://localhost:${PORT}`);
});
