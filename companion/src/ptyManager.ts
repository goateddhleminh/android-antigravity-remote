import * as pty from "node-pty";
import { v4 as uuidv4 } from "uuid";
import fs from "node:fs";
import type { CompanionConfig } from "./config.js";
import type { SessionInfo } from "./protocol.js";

export interface OutputListener {
  onData: (data: string, seq: number) => void;
  onExit: (exitCode: number | null, signal?: number) => void;
}

interface ScrollbackChunk {
  seq: number;
  data: string;
  bytes: number;
}

class PtySession {
  readonly id: string;
  name: string;
  readonly cwd: string;
  readonly shell: string;
  cols: number;
  rows: number;
  readonly createdAt: Date;
  lastActivityAt: Date;
  alive = true;
  exitCode: number | null = null;

  private readonly ptyProcess: pty.IPty;
  private readonly listeners = new Map<string, OutputListener>();
  private readonly buffer: ScrollbackChunk[] = [];
  private bufferBytes = 0;
  private readonly maxBufferBytes: number;
  private seq = 0;

  constructor(opts: {
    id: string;
    name: string;
    cwd: string;
    shell: string;
    shellArgs: string[];
    cols: number;
    rows: number;
    maxBufferBytes: number;
    initialCommand?: string;
  }) {
    this.id = opts.id;
    this.name = opts.name;
    this.cwd = opts.cwd;
    this.shell = opts.shell;
    this.cols = opts.cols;
    this.rows = opts.rows;
    this.maxBufferBytes = opts.maxBufferBytes;
    this.createdAt = new Date();
    this.lastActivityAt = this.createdAt;

    const env: Record<string, string> = {};
    for (const [k, v] of Object.entries(process.env)) {
      if (v !== undefined) env[k] = v;
    }
    env.TERM = env.TERM || "xterm-256color";
    env.COLORTERM = "truecolor";

    this.ptyProcess = pty.spawn(opts.shell, opts.shellArgs, {
      name: "xterm-256color",
      cols: opts.cols,
      rows: opts.rows,
      cwd: opts.cwd,
      env,
      useConpty: process.platform === "win32",
    });

    this.ptyProcess.onData((data: string) => {
      this.lastActivityAt = new Date();
      this.seq += 1;
      const chunkBytes = Buffer.byteLength(data, "utf8");
      this.buffer.push({ seq: this.seq, data, bytes: chunkBytes });
      this.bufferBytes += chunkBytes;

      while (this.bufferBytes > this.maxBufferBytes && this.buffer.length > 1) {
        const removed = this.buffer.shift();
        if (removed) this.bufferBytes -= removed.bytes;
      }

      for (const listener of this.listeners.values()) {
        listener.onData(data, this.seq);
      }
    });

    this.ptyProcess.onExit(({ exitCode, signal }) => {
      this.alive = false;
      this.exitCode = exitCode;
      this.lastActivityAt = new Date();
      for (const listener of this.listeners.values()) {
        listener.onExit(exitCode, signal);
      }
    });

    if (opts.initialCommand && opts.initialCommand.trim().length > 0) {
      const eol = process.platform === "win32" ? "\r\n" : "\n";
      this.ptyProcess.write(opts.initialCommand.trim() + eol);
    }
  }

  attach(clientId: string, listener: OutputListener, sinceSeq?: number): { replay: string; lastSeq: number } {
    this.listeners.set(clientId, listener);
    const replayChunks =
      sinceSeq !== undefined
        ? this.buffer.filter((c) => c.seq > sinceSeq)
        : this.buffer;
    return {
      replay: replayChunks.map((c) => c.data).join(""),
      lastSeq: this.seq,
    };
  }

  detach(clientId: string): void {
    this.listeners.delete(clientId);
  }

  write(data: string): void {
    if (!this.alive) return;
    this.lastActivityAt = new Date();
    this.ptyProcess.write(data);
  }

  resize(cols: number, rows: number): void {
    if (!this.alive) return;
    const safeCols = Math.max(10, Math.min(500, Math.floor(cols)));
    const safeRows = Math.max(4, Math.min(200, Math.floor(rows)));
    this.cols = safeCols;
    this.rows = safeRows;
    this.ptyProcess.resize(safeCols, safeRows);
  }

  kill(): void {
    if (this.alive) {
      this.ptyProcess.kill();
      this.alive = false;
    }
    this.listeners.clear();
  }

  toInfo(): SessionInfo {
    return {
      id: this.id,
      name: this.name,
      cwd: this.cwd,
      shell: this.shell,
      cols: this.cols,
      rows: this.rows,
      createdAt: this.createdAt.toISOString(),
      lastActivityAt: this.lastActivityAt.toISOString(),
      alive: this.alive,
      exitCode: this.exitCode,
      attachedClients: this.listeners.size,
    };
  }
}

export class PtyManager {
  private readonly sessions = new Map<string, PtySession>();
  private readonly config: CompanionConfig;
  private counter = 0;

  constructor(config: CompanionConfig) {
    this.config = config;
  }

  listSessions(): SessionInfo[] {
    return Array.from(this.sessions.values()).map((s) => s.toInfo());
  }

  getSession(sessionId: string): PtySession | undefined {
    return this.sessions.get(sessionId);
  }

  createSession(opts?: {
    name?: string;
    cwd?: string;
    cols?: number;
    rows?: number;
    command?: string;
  }): SessionInfo {
    const aliveCount = Array.from(this.sessions.values()).filter((s) => s.alive).length;
    if (aliveCount >= this.config.maxSessions) {
      throw new Error(`Maximum active sessions (${this.config.maxSessions}) reached`);
    }

    this.counter += 1;
    const id = uuidv4().slice(0, 8);
    const cwd = opts?.cwd && fs.existsSync(opts.cwd) ? opts.cwd : this.config.defaultCwd;
    const name = opts?.name?.trim() || `session-${this.counter}`;

    const session = new PtySession({
      id,
      name,
      cwd,
      shell: this.config.shell,
      shellArgs: this.config.shellArgs,
      cols: opts?.cols || 100,
      rows: opts?.rows || 30,
      maxBufferBytes: this.config.scrollbackBytes,
      initialCommand: opts?.command,
    });

    this.sessions.set(id, session);
    return session.toInfo();
  }

  attach(
    sessionId: string,
    clientId: string,
    listener: OutputListener,
    opts?: { cols?: number; rows?: number; sinceSeq?: number }
  ): { session: SessionInfo; replay: string; lastSeq: number } {
    const session = this.sessions.get(sessionId);
    if (!session) {
      throw new Error(`Session not found: ${sessionId}`);
    }
    if (opts?.cols && opts?.rows) {
      session.resize(opts.cols, opts.rows);
    }
    const { replay, lastSeq } = session.attach(clientId, listener, opts?.sinceSeq);
    return { session: session.toInfo(), replay, lastSeq };
  }

  detach(sessionId: string, clientId: string): void {
    this.sessions.get(sessionId)?.detach(clientId);
  }

  detachClientFromAll(clientId: string): void {
    for (const session of this.sessions.values()) {
      session.detach(clientId);
    }
  }

  write(sessionId: string, data: string): void {
    const session = this.sessions.get(sessionId);
    if (!session) throw new Error(`Session not found: ${sessionId}`);
    session.write(data);
  }

  resize(sessionId: string, cols: number, rows: number): void {
    const session = this.sessions.get(sessionId);
    if (!session) throw new Error(`Session not found: ${sessionId}`);
    session.resize(cols, rows);
  }

  killSession(sessionId: string): boolean {
    const session = this.sessions.get(sessionId);
    if (!session) return false;
    session.kill();
    this.sessions.delete(sessionId);
    return true;
  }

  killAll(): void {
    for (const session of this.sessions.values()) {
      session.kill();
    }
    this.sessions.clear();
  }
}
