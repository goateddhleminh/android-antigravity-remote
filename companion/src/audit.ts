import fs from "node:fs";
import path from "node:path";

export interface AuditEntry {
  ts: string;
  event: string;
  ip?: string;
  deviceId?: string;
  email?: string;
  sessionId?: string;
  detail?: string;
}

export class AuditLogger {
  private readonly logPath: string;
  private readonly quiet: boolean;

  constructor(dataDir: string, quiet = false) {
    fs.mkdirSync(dataDir, { recursive: true });
    this.logPath = path.join(dataDir, "audit.log");
    this.quiet = quiet;
  }

  log(event: string, meta: Omit<AuditEntry, "ts" | "event"> = {}): void {
    const entry: AuditEntry = {
      ts: new Date().toISOString(),
      event,
      ...meta,
    };
    const line = JSON.stringify(entry);
    try {
      fs.appendFileSync(this.logPath, line + "\n", { mode: 0o600 });
    } catch {
      // Never crash server on log write failure
    }
    if (!this.quiet) {
      const parts = [entry.event];
      if (entry.ip) parts.push(`ip=${entry.ip}`);
      if (entry.deviceId) parts.push(`device=${entry.deviceId}`);
      if (entry.sessionId) parts.push(`session=${entry.sessionId}`);
      if (entry.detail) parts.push(entry.detail);
      console.log(`[audit] ${entry.ts} ${parts.join(" ")}`);
    }
  }

  getLogPath(): string {
    return this.logPath;
  }
}
