import { describe, it, expect, afterEach } from "vitest";
import os from "node:os";
import path from "node:path";
import fs from "node:fs";
import WebSocket from "ws";
import { loadConfig } from "../src/config.js";
import { startCompanionServer, type CompanionServerHandle } from "../src/server.js";
import type { ServerMessage } from "../src/protocol.js";

class WsHarness {
  readonly ws: WebSocket;
  private readonly queue: ServerMessage[] = [];
  private readonly waiters: Array<(msg: ServerMessage) => void> = [];

  private constructor(ws: WebSocket) {
    this.ws = ws;
    ws.on("message", (raw) => {
      const msg = JSON.parse(raw.toString("utf8")) as ServerMessage;
      const waiter = this.waiters.shift();
      if (waiter) waiter(msg);
      else this.queue.push(msg);
    });
  }

  static async connect(url: string, token: string): Promise<WsHarness> {
    const ws = new WebSocket(url, { headers: { Authorization: `Bearer ${token}` } });
    const harness = new WsHarness(ws);
    await new Promise<void>((res, rej) => {
      ws.once("open", () => res());
      ws.once("error", rej);
    });
    return harness;
  }

  send(msg: unknown) {
    this.ws.send(JSON.stringify(msg));
  }

  async next(timeoutMs = 5000): Promise<ServerMessage> {
    if (this.queue.length > 0) return this.queue.shift()!;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("Timeout waiting for WS message")), timeoutMs);
      this.waiters.push((msg) => {
        clearTimeout(timer);
        resolve(msg);
      });
    });
  }

  async waitFor<T extends ServerMessage["type"]>(
    type: T,
    timeoutMs = 5000
  ): Promise<Extract<ServerMessage, { type: T }>> {
    const deadline = Date.now() + timeoutMs;
    while (true) {
      const remaining = deadline - Date.now();
      if (remaining <= 0) throw new Error(`Timeout waiting for ${type}`);
      const msg = await this.next(remaining);
      if (msg.type === type) return msg as Extract<ServerMessage, { type: T }>;
    }
  }

  close() {
    this.ws.close();
  }
}

describe("Companion PTY over authenticated WebSocket", () => {
  let handle: CompanionServerHandle | undefined;
  let tmpDir: string | undefined;

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

  it("spawns a PTY session, streams output, persists across disconnect, and replays delta scrollback", async () => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "agy-pty-test-"));
    const config = { ...loadConfig(), host: "127.0.0.1", port: 0, dataDir: tmpDir };
    handle = await startCompanionServer(config, { quietAudit: true });

    const { token: pairToken } = handle.auth.createPairingToken();
    const { accessToken } = handle.auth.pairWithToken(pairToken, "Test Phone");

    const c1 = await WsHarness.connect(handle.wsUrl, accessToken);
    const hello = await c1.waitFor("hello");
    expect(hello.deviceName).toBeTruthy();

    c1.send({ type: "create_session", name: "test-1", cols: 80, rows: 24 });
    const created = await c1.waitFor("session_created");
    const sid = created.session.id;

    c1.send({ type: "attach", sessionId: sid, cols: 80, rows: 24 });
    await c1.waitFor("attached");

    const token1 = "PERSIST_TOKEN_ONE";
    c1.send({ type: "input", sessionId: sid, data: `echo ${token1}\n` });

    let lastSeq = 0;
    let buf = "";
    const deadline = Date.now() + 5000;
    while (!buf.includes(token1)) {
      const msg = await c1.next(Math.max(100, deadline - Date.now()));
      if (msg.type === "output" && msg.sessionId === sid) {
        buf += msg.data;
        lastSeq = msg.seq;
      }
    }

    c1.send({ type: "resize", sessionId: sid, cols: 110, rows: 35 });
    c1.close();
    await new Promise((r) => setTimeout(r, 150));

    const token2 = "BACKGROUND_AGENT_OUTPUT";
    handle.ptyManager.write(sid, `echo ${token2}\n`);
    await new Promise((r) => setTimeout(r, 400));

    const c2 = await WsHarness.connect(handle.wsUrl, accessToken);
    await c2.waitFor("hello");

    c2.send({ type: "attach", sessionId: sid, sinceSeq: lastSeq });
    const reattached = await c2.waitFor("attached");

    expect(reattached.session.alive).toBe(true);
    expect(reattached.session.cols).toBe(110);
    expect(reattached.session.rows).toBe(35);
    expect(reattached.replay).toContain(token2);
    expect(reattached.lastSeq).toBeGreaterThan(lastSeq);

    c2.send({ type: "kill_session", sessionId: sid });
    const killed = await c2.waitFor("session_killed");
    expect(killed.sessionId).toBe(sid);

    c2.close();
  });
});
