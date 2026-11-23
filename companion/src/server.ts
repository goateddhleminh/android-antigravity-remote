import http from "node:http";
import https from "node:https";
import fs from "node:fs";
import express, { type Request, type Response, type NextFunction } from "express";
import rateLimit from "express-rate-limit";
import { WebSocketServer, WebSocket } from "ws";
import { v4 as uuidv4 } from "uuid";
import type { CompanionConfig } from "./config.js";
import { PtyManager } from "./ptyManager.js";
import { AuthService, type AccessTokenPayload } from "./auth.js";
import { AuditLogger } from "./audit.js";
import type { ClientMessage, ServerMessage } from "./protocol.js";

const VERSION = "1.0.0";

export interface CompanionServerHandle {
  server: http.Server | https.Server;
  wss: WebSocketServer;
  ptyManager: PtyManager;
  auth: AuthService;
  audit: AuditLogger;
  url: string;
  wsUrl: string;
  close: () => Promise<void>;
}

interface AuthedRequest extends Request {
  auth?: AccessTokenPayload;
}

function sendJson(ws: WebSocket, msg: ServerMessage): void {
  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(msg));
  }
}

function getClientIp(req: http.IncomingMessage): string {
  const fwd = req.headers["x-forwarded-for"];
  if (typeof fwd === "string" && fwd.length > 0) {
    return fwd.split(",")[0].trim();
  }
  return req.socket.remoteAddress || "unknown";
}

function extractBearer(header?: string): string | null {
  if (!header) return null;
  const parts = header.split(" ");
  if (parts.length === 2 && parts[0].toLowerCase() === "bearer") {
    return parts[1];
  }
  return null;
}

export async function startCompanionServer(
  config: CompanionConfig,
  opts?: { quietAudit?: boolean }
): Promise<CompanionServerHandle> {
  const app = express();
  app.set("trust proxy", 1);
  app.use(express.json({ limit: "64kb" }));

  const ptyManager = new PtyManager(config);
  const auth = new AuthService(config);
  const audit = new AuditLogger(config.dataDir, opts?.quietAudit ?? false);

  // Track active WebSockets per deviceId so revoking a device closes them immediately
  const deviceSockets = new Map<string, Set<WebSocket>>();

  const authRateLimiter = rateLimit({
    windowMs: config.loginRateLimitWindowMs,
    max: config.loginRateLimitMax,
    standardHeaders: true,
    legacyHeaders: false,
    handler: (req, res) => {
      audit.log("auth.rate_limited", { ip: getClientIp(req), detail: req.path });
      res.status(429).json({ error: "Too many authentication attempts. Try again later." });
    },
  });

  const requireAuth = (req: AuthedRequest, res: Response, next: NextFunction) => {
    const token = extractBearer(req.headers.authorization);
    if (!token) {
      res.status(401).json({ error: "Missing Bearer token" });
      return;
    }
    try {
      req.auth = auth.verifyAccessToken(token);
      next();
    } catch (err) {
      audit.log("auth.jwt_rejected", { ip: getClientIp(req), detail: (err as Error).message });
      res.status(401).json({ error: "Invalid or expired token" });
    }
  };

  // Public health endpoint (minimal metadata, no sensitive data)
  app.get("/health", (_req, res) => {
    res.json({
      status: "ok",
      deviceName: config.deviceName,
      platform: process.platform,
      version: VERSION,
    });
  });

  // Auth endpoints (rate-limited)
  app.post("/api/auth/login", authRateLimiter, (req, res) => {
    const ip = getClientIp(req);
    const { email, password, deviceName } = req.body || {};
    if (typeof email !== "string" || typeof password !== "string") {
      res.status(400).json({ error: "email and password are required" });
      return;
    }
    try {
      const bundle = auth.loginWithPassword(email, password, deviceName);
      audit.log("auth.login_success", { ip, email, deviceId: bundle.deviceId });
      res.json({ ...bundle, hostDeviceName: config.deviceName, platform: process.platform });
    } catch (err) {
      audit.log("auth.login_failed", { ip, email, detail: (err as Error).message });
      res.status(401).json({ error: "Invalid credentials" });
    }
  });

  app.post("/api/auth/pair", authRateLimiter, (req, res) => {
    const ip = getClientIp(req);
    const { pairToken, deviceName } = req.body || {};
    if (typeof pairToken !== "string" || !pairToken.trim()) {
      res.status(400).json({ error: "pairToken is required" });
      return;
    }
    try {
      const bundle = auth.pairWithToken(pairToken.trim(), deviceName);
      audit.log("auth.pair_success", { ip, deviceId: bundle.deviceId, detail: bundle.deviceName });
      res.json({ ...bundle, hostDeviceName: config.deviceName, platform: process.platform });
    } catch (err) {
      audit.log("auth.pair_failed", { ip, detail: (err as Error).message });
      res.status(401).json({ error: (err as Error).message });
    }
  });

  app.post("/api/auth/refresh", authRateLimiter, (req, res) => {
    const ip = getClientIp(req);
    const { deviceId, refreshToken } = req.body || {};
    if (typeof deviceId !== "string" || typeof refreshToken !== "string") {
      res.status(400).json({ error: "deviceId and refreshToken are required" });
      return;
    }
    try {
      const bundle = auth.refreshTokens(deviceId, refreshToken);
      audit.log("auth.refresh_success", { ip, deviceId });
      res.json(bundle);
    } catch (err) {
      audit.log("auth.refresh_failed", { ip, deviceId, detail: (err as Error).message });
      res.status(401).json({ error: (err as Error).message });
    }
  });

  // Protected device management endpoints
  app.get("/api/devices", requireAuth, (_req, res) => {
    res.json({ devices: auth.listDevices() });
  });

  app.post("/api/pair/create", requireAuth, (req: AuthedRequest, res) => {
    const pair = auth.createPairingToken();
    audit.log("auth.pair_token_created", { ip: getClientIp(req), deviceId: req.auth?.sub });
    res.json({
      pairToken: pair.token,
      expiresAt: pair.expiresAt,
      deviceName: config.deviceName,
      url: config.publicUrl,
    });
  });

  app.delete("/api/devices/:id", requireAuth, (req: AuthedRequest, res) => {
    const targetId = String(req.params.id);
    const revoked = auth.revokeDevice(targetId);
    if (!revoked) {
      res.status(404).json({ error: "Device not found" });
      return;
    }
    // Immediately close any active WebSockets for this device
    const sockets = deviceSockets.get(targetId);
    if (sockets) {
      for (const s of sockets) {
        s.close(4003, "Device revoked");
      }
      deviceSockets.delete(targetId);
    }
    audit.log("auth.device_revoked", { ip: getClientIp(req), deviceId: targetId });
    res.json({ revoked: true, deviceId: targetId });
  });

  // Protected session REST endpoints
  app.get("/api/sessions", requireAuth, (_req, res) => {
    res.json({ sessions: ptyManager.listSessions() });
  });

  app.post("/api/sessions", requireAuth, (req: AuthedRequest, res) => {
    try {
      const { name, cwd, cols, rows, command } = req.body || {};
      const session = ptyManager.createSession({ name, cwd, cols, rows, command });
      audit.log("session.created", { ip: getClientIp(req), deviceId: req.auth?.sub, sessionId: session.id });
      res.status(201).json({ session });
    } catch (err) {
      res.status(400).json({ error: (err as Error).message });
    }
  });

  app.delete("/api/sessions/:id", requireAuth, (req: AuthedRequest, res) => {
    const sessionId = String(req.params.id);
    const killed = ptyManager.killSession(sessionId);
    if (!killed) {
      res.status(404).json({ error: "Session not found" });
      return;
    }
    audit.log("session.killed", { ip: getClientIp(req), deviceId: req.auth?.sub, sessionId });
    res.json({ killed: true, sessionId });
  });

  const useTls = Boolean(config.tlsCertPath && config.tlsKeyPath);
  const server = useTls
    ? https.createServer(
        {
          cert: fs.readFileSync(config.tlsCertPath!),
          key: fs.readFileSync(config.tlsKeyPath!),
        },
        app
      )
    : http.createServer(app);

  // Use noServer so we can authenticate HTTP upgrade requests before upgrading to WS
  const wss = new WebSocketServer({ noServer: true });

  server.on("upgrade", (req, socket, head) => {
    const ip = getClientIp(req);
    const urlObj = new URL(req.url || "/", "http://localhost");
    if (urlObj.pathname !== "/ws") {
      socket.write("HTTP/1.1 404 Not Found\r\n\r\n");
      socket.destroy();
      return;
    }

    const token = extractBearer(req.headers.authorization) || urlObj.searchParams.get("token");
    if (!token) {
      audit.log("ws.auth_missing", { ip });
      socket.write("HTTP/1.1 401 Unauthorized\r\n\r\n");
      socket.destroy();
      return;
    }

    let payload: AccessTokenPayload;
    try {
      payload = auth.verifyAccessToken(token);
    } catch (err) {
      audit.log("ws.auth_failed", { ip, detail: (err as Error).message });
      socket.write("HTTP/1.1 401 Unauthorized\r\n\r\n");
      socket.destroy();
      return;
    }

    wss.handleUpgrade(req, socket, head, (ws) => {
      wss.emit("connection", ws, req, payload);
    });
  });

  wss.on("connection", (ws: WebSocket, req: http.IncomingMessage, payload: AccessTokenPayload) => {
    const clientId = uuidv4();
    const deviceId = payload.sub;
    const ip = getClientIp(req);
    const attachedSessions = new Set<string>();

    let set = deviceSockets.get(deviceId);
    if (!set) {
      set = new Set();
      deviceSockets.set(deviceId, set);
    }
    set.add(ws);

    audit.log("ws.connected", { ip, deviceId });

    sendJson(ws, {
      type: "hello",
      deviceName: config.deviceName,
      platform: process.platform,
      version: VERSION,
    });

    ws.on("message", (raw) => {
      let msg: ClientMessage;
      try {
        msg = JSON.parse(raw.toString("utf8")) as ClientMessage;
      } catch {
        sendJson(ws, { type: "error", code: "INVALID_JSON", message: "Message must be valid JSON" });
        return;
      }

      try {
        switch (msg.type) {
          case "ping": {
            sendJson(ws, { type: "pong", ts: msg.ts });
            break;
          }
          case "list_sessions": {
            sendJson(ws, { type: "sessions", sessions: ptyManager.listSessions() });
            break;
          }
          case "create_session": {
            const session = ptyManager.createSession({
              name: msg.name,
              cwd: msg.cwd,
              cols: msg.cols,
              rows: msg.rows,
              command: msg.command,
            });
            audit.log("session.created", { ip, deviceId, sessionId: session.id });
            sendJson(ws, { type: "session_created", session });
            break;
          }
          case "attach": {
            const { session, replay, lastSeq } = ptyManager.attach(
              msg.sessionId,
              clientId,
              {
                onData: (data, seq) => {
                  sendJson(ws, { type: "output", sessionId: msg.sessionId, data, seq });
                },
                onExit: (exitCode, signal) => {
                  sendJson(ws, { type: "session_exit", sessionId: msg.sessionId, exitCode, signal });
                },
              },
              { cols: msg.cols, rows: msg.rows, sinceSeq: msg.sinceSeq }
            );
            attachedSessions.add(msg.sessionId);
            sendJson(ws, { type: "attached", session, replay, lastSeq });
            break;
          }
          case "detach": {
            ptyManager.detach(msg.sessionId, clientId);
            attachedSessions.delete(msg.sessionId);
            sendJson(ws, { type: "detached", sessionId: msg.sessionId });
            break;
          }
          case "input": {
            ptyManager.write(msg.sessionId, msg.data);
            break;
          }
          case "resize": {
            ptyManager.resize(msg.sessionId, msg.cols, msg.rows);
            break;
          }
          case "kill_session": {
            const killed = ptyManager.killSession(msg.sessionId);
            if (!killed) {
              sendJson(ws, { type: "error", code: "NOT_FOUND", message: `Session not found: ${msg.sessionId}` });
            } else {
              attachedSessions.delete(msg.sessionId);
              audit.log("session.killed", { ip, deviceId, sessionId: msg.sessionId });
              sendJson(ws, { type: "session_killed", sessionId: msg.sessionId });
            }
            break;
          }
          default: {
            sendJson(ws, { type: "error", code: "UNKNOWN_TYPE", message: "Unknown message type" });
          }
        }
      } catch (err) {
        sendJson(ws, { type: "error", code: "INTERNAL_ERROR", message: (err as Error).message });
      }
    });

    ws.on("close", () => {
      for (const sid of attachedSessions) {
        ptyManager.detach(sid, clientId);
      }
      attachedSessions.clear();
      deviceSockets.get(deviceId)?.delete(ws);
      audit.log("ws.disconnected", { ip, deviceId });
    });
  });

  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(config.port, config.host, () => {
      server.off("error", reject);
      resolve();
    });
  });

  const addr = server.address();
  const actualPort = typeof addr === "object" && addr ? addr.port : config.port;
  const httpProto = useTls ? "https" : "http";
  const wsProto = useTls ? "wss" : "ws";
  const url = `${httpProto}://${config.host}:${actualPort}`;
  const wsUrl = `${wsProto}://${config.host}:${actualPort}/ws`;

  const close = async () => {
    ptyManager.killAll();
    for (const client of wss.clients) {
      client.terminate();
    }
    await new Promise<void>((resolve) => wss.close(() => resolve()));
    await new Promise<void>((resolve) => server.close(() => resolve()));
  };

  return { server, wss, ptyManager, auth, audit, url, wsUrl, close };
}
