import { describe, it, expect, afterEach } from "vitest";
import os from "node:os";
import path from "node:path";
import fs from "node:fs";
import WebSocket from "ws";
import { loadConfig } from "../src/config.js";
import { startCompanionServer, type CompanionServerHandle } from "../src/server.js";

describe("Companion Auth, Pairing, Rate Limiting, Revocation & Audit", () => {
  let handle: CompanionServerHandle | undefined;
  let tmpDir: string | undefined;

  async function boot(overrides: Partial<ReturnType<typeof loadConfig>> = {}) {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "agy-auth-test-"));
    const config = {
      ...loadConfig(),
      host: "127.0.0.1",
      port: 0,
      dataDir: tmpDir,
      adminEmail: "dev@example.com",
      adminPassword: "StrongPassword!123",
      loginRateLimitMax: 5,
      ...overrides,
    };
    handle = await startCompanionServer(config, { quietAudit: true });
    return handle;
  }

  afterEach(async () => {
    if (handle) {
      await handle.close();
      handle = undefined;
    }
    if (tmpDir) {
      fs.rmSync(tmpDir, { recursive: true, force: true });
      tmpDir = undefined;
    }
  });

  it("rejects unauthenticated REST and WebSocket requests", async () => {
    const srv = await boot();

    const restRes = await fetch(`${srv.url}/api/sessions`);
    expect(restRes.status).toBe(401);

    const wsErr = await new Promise<Error>((resolve) => {
      const ws = new WebSocket(srv.wsUrl);
      ws.once("error", (err) => resolve(err));
    });
    expect(wsErr.message).toContain("401");
  });

  it("supports single-use QR pairing, token refresh rotation, and device revocation", async () => {
    const srv = await boot();
    const { token: pairToken } = srv.auth.createPairingToken();

    // First pairing succeeds
    const pairRes = await fetch(`${srv.url}/api/auth/pair`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ pairToken, deviceName: "Pixel 9" }),
    });
    expect(pairRes.status).toBe(200);
    const paired = (await pairRes.json()) as {
      deviceId: string;
      accessToken: string;
      refreshToken: string;
    };
    expect(paired.accessToken).toBeTruthy();

    // Reusing the same QR pairing token fails
    const replayRes = await fetch(`${srv.url}/api/auth/pair`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ pairToken, deviceName: "Attacker" }),
    });
    expect(replayRes.status).toBe(401);

    // Refresh rotates refreshToken
    const refreshRes = await fetch(`${srv.url}/api/auth/refresh`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ deviceId: paired.deviceId, refreshToken: paired.refreshToken }),
    });
    expect(refreshRes.status).toBe(200);
    const refreshed = (await refreshRes.json()) as {
      accessToken: string;
      refreshToken: string;
    };
    expect(refreshed.refreshToken).not.toBe(paired.refreshToken);

    // Old refresh token is now invalid
    const staleRefresh = await fetch(`${srv.url}/api/auth/refresh`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ deviceId: paired.deviceId, refreshToken: paired.refreshToken }),
    });
    expect(staleRefresh.status).toBe(401);

    // Open an authenticated WebSocket for the device
    const ws = new WebSocket(srv.wsUrl, {
      headers: { Authorization: `Bearer ${refreshed.accessToken}` },
    });
    await new Promise<void>((res, rej) => {
      ws.once("open", () => res());
      ws.once("error", rej);
    });

    const wsClosed = new Promise<number>((resolve) => {
      ws.once("close", (code) => resolve(code));
    });

    // Revoke the device -> active WS closes immediately with 4003 & further REST calls fail
    const revokeRes = await fetch(`${srv.url}/api/devices/${paired.deviceId}`, {
      method: "DELETE",
      headers: { Authorization: `Bearer ${refreshed.accessToken}` },
    });
    expect(revokeRes.status).toBe(200);
    expect(await wsClosed).toBe(4003);

    const afterRevoke = await fetch(`${srv.url}/api/sessions`, {
      headers: { Authorization: `Bearer ${refreshed.accessToken}` },
    });
    expect(afterRevoke.status).toBe(401);

    // Verify audit log recorded events
    const auditLog = fs.readFileSync(srv.audit.getLogPath(), "utf8");
    expect(auditLog).toContain("auth.pair_success");
    expect(auditLog).toContain("auth.device_revoked");
  });

  it("authenticates with email/password and rate-limits repeated failures", async () => {
    const srv = await boot({ loginRateLimitMax: 3 });

    const okRes = await fetch(`${srv.url}/api/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email: "dev@example.com", password: "StrongPassword!123" }),
    });
    expect(okRes.status).toBe(200);

    // Trigger rate limit
    await fetch(`${srv.url}/api/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email: "dev@example.com", password: "wrong" }),
    });
    await fetch(`${srv.url}/api/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email: "dev@example.com", password: "wrong" }),
    });
    const limited = await fetch(`${srv.url}/api/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email: "dev@example.com", password: "wrong" }),
    });
    expect(limited.status).toBe(429);
  });
});
