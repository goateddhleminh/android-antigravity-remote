import qrcode from "qrcode";
import { loadConfig } from "./config.js";
import { startCompanionServer } from "./server.js";

async function main() {
  const config = loadConfig();
  const handle = await startCompanionServer(config);

  console.log("============================================================");
  console.log(`  Antigravity Remote Companion v1.0.0`);
  console.log("============================================================");
  console.log(`  Listening:   ${handle.url}`);
  console.log(`  WebSocket:   ${handle.wsUrl}`);
  console.log(`  Device:      ${config.deviceName} (${process.platform})`);
  console.log(`  Shell:       ${config.shell} ${config.shellArgs.join(" ")}`);
  console.log(`  Default CWD: ${config.defaultCwd}`);
  console.log(`  Admin Email: ${handle.auth.getAdminEmail()}`);
  if (handle.auth.generatedPassword) {
    console.log(`  Admin Pass:  ${handle.auth.generatedPassword}  (SAVE THIS — shown once!)`);
  }
  console.log(`  Audit Log:   ${handle.audit.getLogPath()}`);
  console.log("============================================================");

  // Print a fresh 5-minute QR pairing code on startup (or when --pair is passed)
  const pair = handle.auth.createPairingToken();
  const qrPayload = JSON.stringify({
    url: config.publicUrl || handle.url,
    deviceName: config.deviceName,
    pairToken: pair.token,
  });

  console.log("\nScan this QR code in the Antigravity Remote Android app to pair (expires in 5 min):\n");
  const qrAscii = await qrcode.toString(qrPayload, { type: "terminal", small: true });
  console.log(qrAscii);
  if (!config.publicUrl) {
    console.log("Tip: Set AGY_PUBLIC_URL=https://<your-pc>.<tailnet>.ts.net so the QR code embeds your Tailscale HTTPS URL.\n");
  }
  console.log(`Manual pairing token: ${pair.token}\n`);

  const shutdown = async (signal: string) => {
    console.log(`\n[agy-remote] Received ${signal}, shutting down...`);
    await handle.close();
    process.exit(0);
  };

  process.on("SIGINT", () => void shutdown("SIGINT"));
  process.on("SIGTERM", () => void shutdown("SIGTERM"));
}

main().catch((err) => {
  console.error("[agy-remote] Fatal error:", err);
  process.exit(1);
});
