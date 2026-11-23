import WebSocket from "ws";
import type { ClientMessage, ServerMessage } from "./protocol.js";

function parseArgs(argv: string[]) {
  const args = {
    url: process.env.AGY_WS_URL || "ws://127.0.0.1:8443/ws",
    token: process.env.AGY_TOKEN || "",
    sessionId: undefined as string | undefined,
    name: undefined as string | undefined,
    command: undefined as string | undefined,
    smokeTest: false,
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--url" && argv[i + 1]) args.url = argv[++i];
    else if (a === "--token" && argv[i + 1]) args.token = argv[++i];
    else if (a === "--session" && argv[i + 1]) args.sessionId = argv[++i];
    else if (a === "--name" && argv[i + 1]) args.name = argv[++i];
    else if (a === "--cmd" && argv[i + 1]) args.command = argv[++i];
    else if (a === "--smoke-test") args.smokeTest = true;
  }
  return args;
}

function send(ws: WebSocket, msg: ClientMessage) {
  ws.send(JSON.stringify(msg));
}

function wsHeaders(token: string): Record<string, string> {
  return token ? { Authorization: `Bearer ${token}` } : {};
}

async function runSmokeTest(url: string, token: string) {
  console.log(`[smoke-test] Connecting to ${url}...`);

  const connect = async () => {
    const ws = new WebSocket(url, { rejectUnauthorized: false, headers: wsHeaders(token) });
    const queue: ServerMessage[] = [];
    const waiters: Array<(m: ServerMessage) => void> = [];
    ws.on("message", (raw) => {
      const msg = JSON.parse(raw.toString("utf8")) as ServerMessage;
      const w = waiters.shift();
      if (w) w(msg);
      else queue.push(msg);
    });
    await new Promise<void>((resolve, reject) => {
      ws.once("open", () => resolve());
      ws.once("error", reject);
    });
    const next = (timeoutMs = 5000) =>
      queue.length > 0
        ? Promise.resolve(queue.shift()!)
        : new Promise<ServerMessage>((resolve, reject) => {
            const t = setTimeout(() => reject(new Error("Timeout waiting for WS message")), timeoutMs);
            waiters.push((m) => {
              clearTimeout(t);
              resolve(m);
            });
          });
    const waitFor = async <T extends ServerMessage["type"]>(type: T, timeoutMs = 5000) => {
      const deadline = Date.now() + timeoutMs;
      while (true) {
        const rem = deadline - Date.now();
        if (rem <= 0) throw new Error(`Timeout waiting for ${type}`);
        const m = await next(rem);
        if (m.type === type) return m as Extract<ServerMessage, { type: T }>;
      }
    };
    return { ws, next, waitFor };
  };

  const c1 = await connect();
  const hello = await c1.waitFor("hello");
  console.log(`[smoke-test] Hello from ${hello.deviceName} (${hello.platform})`);

  send(c1.ws, { type: "create_session", name: "smoke", cols: 80, rows: 24 });
  const created = await c1.waitFor("session_created");
  const sessionId = created.session.id;

  send(c1.ws, { type: "attach", sessionId, cols: 80, rows: 24 });
  await c1.waitFor("attached");

  const marker = `AGY_SMOKE_OK_${Date.now()}`;
  send(c1.ws, { type: "input", sessionId, data: `echo ${marker}\n` });

  let captured = "";
  const deadline = Date.now() + 5000;
  while (!captured.includes(marker)) {
    const m = await c1.next(Math.max(100, deadline - Date.now()));
    if (m.type === "output" && m.sessionId === sessionId) {
      captured += m.data;
    }
  }
  console.log(`[smoke-test] Verified live PTY output contains marker: ${marker}`);

  send(c1.ws, { type: "resize", sessionId, cols: 120, rows: 40 });
  c1.ws.close();
  await new Promise((r) => setTimeout(r, 200));

  const c2 = await connect();
  await c2.waitFor("hello");
  send(c2.ws, { type: "attach", sessionId, cols: 120, rows: 40 });
  const reattached = await c2.waitFor("attached");

  if (!reattached.replay.includes(marker)) {
    throw new Error("Scrollback replay did not contain previous session output!");
  }
  console.log(
    `[smoke-test] Reconnected! Session alive=${reattached.session.alive}, replayLen=${reattached.replay.length}, lastSeq=${reattached.lastSeq}`
  );

  send(c2.ws, { type: "kill_session", sessionId });
  await c2.waitFor("session_killed");
  c2.ws.close();
  console.log("[smoke-test] PASS — Authenticated PTY, resize, persistence, and replay verified.");
}

async function runInteractive(opts: ReturnType<typeof parseArgs>) {
  const ws = new WebSocket(opts.url, { rejectUnauthorized: false, headers: wsHeaders(opts.token) });
  let activeSessionId: string | undefined = opts.sessionId;

  ws.on("open", () => {
    const cols = process.stdout.columns || 100;
    const rows = process.stdout.rows || 30;
    if (activeSessionId) {
      send(ws, { type: "attach", sessionId: activeSessionId, cols, rows });
    } else {
      send(ws, { type: "create_session", name: opts.name, cols, rows, command: opts.command });
    }
  });

  ws.on("message", (raw) => {
    const msg = JSON.parse(raw.toString("utf8")) as ServerMessage;
    switch (msg.type) {
      case "hello":
        console.error(`[client] Connected to ${msg.deviceName} (${msg.platform}). Press Ctrl+] to detach.`);
        break;
      case "session_created": {
        activeSessionId = msg.session.id;
        send(ws, {
          type: "attach",
          sessionId: activeSessionId,
          cols: process.stdout.columns || 100,
          rows: process.stdout.rows || 30,
        });
        break;
      }
      case "attached": {
        if (msg.replay) process.stdout.write(msg.replay);
        if (process.stdin.isTTY) process.stdin.setRawMode(true);
        process.stdin.resume();
        process.stdin.on("data", (chunk: Buffer) => {
          if (chunk.length === 1 && chunk[0] === 0x1d) {
            if (activeSessionId) send(ws, { type: "detach", sessionId: activeSessionId });
            cleanupAndExit(0);
            return;
          }
          if (activeSessionId) {
            send(ws, { type: "input", sessionId: activeSessionId, data: chunk.toString("utf8") });
          }
        });
        process.stdout.on("resize", () => {
          if (activeSessionId && process.stdout.columns && process.stdout.rows) {
            send(ws, {
              type: "resize",
              sessionId: activeSessionId,
              cols: process.stdout.columns,
              rows: process.stdout.rows,
            });
          }
        });
        break;
      }
      case "output":
        process.stdout.write(msg.data);
        break;
      case "session_exit":
        console.error(`\n[client] Remote session exited (code=${msg.exitCode})`);
        cleanupAndExit(0);
        break;
      case "error":
        console.error(`\n[client] Server error (${msg.code}): ${msg.message}`);
        break;
    }
  });

  ws.on("close", () => cleanupAndExit(0));
  ws.on("error", (err) => {
    console.error("[client] WebSocket error:", err.message);
    cleanupAndExit(1);
  });

  function cleanupAndExit(code: number) {
    if (process.stdin.isTTY) {
      try {
        process.stdin.setRawMode(false);
      } catch {
        // ignore
      }
    }
    ws.close();
    process.exit(code);
  }
}

const args = parseArgs(process.argv.slice(2));
if (args.smokeTest) {
  runSmokeTest(args.url, args.token).catch((err) => {
    console.error("[smoke-test] FAIL:", err);
    process.exit(1);
  });
} else {
  runInteractive(args).catch((err) => {
    console.error("[client] Fatal:", err);
    process.exit(1);
  });
}
