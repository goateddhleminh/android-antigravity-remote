import os from "node:os";
import path from "node:path";
import fs from "node:fs";

export interface CompanionConfig {
  host: string;
  port: number;
  publicUrl?: string;
  defaultCwd: string;
  shell: string;
  shellArgs: string[];
  scrollbackBytes: number;
  maxSessions: number;
  tlsCertPath?: string;
  tlsKeyPath?: string;
  deviceName: string;
  dataDir: string;
  adminEmail: string;
  adminPassword?: string;
  accessTokenTtlSec: number;
  refreshTokenTtlSec: number;
  pairTokenTtlSec: number;
  loginRateLimitMax: number;
  loginRateLimitWindowMs: number;
}

export function detectDefaultShell(): { shell: string; args: string[] } {
  if (process.env.AGY_SHELL) {
    return { shell: process.env.AGY_SHELL, args: [] };
  }

  if (process.platform === "win32") {
    const candidates = [
      path.join(process.env.ProgramFiles || "C:\\Program Files", "PowerShell", "7", "pwsh.exe"),
      path.join(process.env.SystemRoot || "C:\\Windows", "System32", "WindowsPowerShell", "v1.0", "powershell.exe"),
      process.env.COMSPEC || "cmd.exe",
    ];
    for (const candidate of candidates) {
      if (fs.existsSync(candidate)) {
        const isPowershell = candidate.toLowerCase().includes("pwsh") || candidate.toLowerCase().includes("powershell");
        return { shell: candidate, args: isPowershell ? ["-NoLogo"] : [] };
      }
    }
    return { shell: "powershell.exe", args: ["-NoLogo"] };
  }

  const unixShell = process.env.SHELL || "/bin/bash";
  return { shell: unixShell, args: [] };
}

export function loadConfig(): CompanionConfig {
  const { shell, args } = detectDefaultShell();
  const defaultCwd = process.env.AGY_DEFAULT_CWD || process.cwd();
  const dataDir = process.env.AGY_DATA_DIR || path.join(os.homedir(), ".agy-remote");

  return {
    host: process.env.AGY_HOST || "127.0.0.1",
    port: parseInt(process.env.AGY_PORT || "8443", 10),
    publicUrl: process.env.AGY_PUBLIC_URL || undefined,
    defaultCwd: fs.existsSync(defaultCwd) ? defaultCwd : os.homedir(),
    shell,
    shellArgs: args,
    scrollbackBytes: parseInt(process.env.AGY_SCROLLBACK_BYTES || String(128 * 1024), 10),
    maxSessions: parseInt(process.env.AGY_MAX_SESSIONS || "16", 10),
    tlsCertPath: process.env.AGY_TLS_CERT || undefined,
    tlsKeyPath: process.env.AGY_TLS_KEY || undefined,
    deviceName: process.env.AGY_DEVICE_NAME || os.hostname(),
    dataDir,
    adminEmail: process.env.AGY_ADMIN_EMAIL || "admin@local",
    adminPassword: process.env.AGY_ADMIN_PASSWORD || undefined,
    accessTokenTtlSec: parseInt(process.env.AGY_ACCESS_TTL_SEC || String(15 * 60), 10),
    refreshTokenTtlSec: parseInt(process.env.AGY_REFRESH_TTL_SEC || String(30 * 24 * 3600), 10),
    pairTokenTtlSec: parseInt(process.env.AGY_PAIR_TTL_SEC || String(5 * 60), 10),
    loginRateLimitMax: parseInt(process.env.AGY_LOGIN_RATE_MAX || "10", 10),
    loginRateLimitWindowMs: parseInt(process.env.AGY_LOGIN_RATE_WINDOW_MS || String(15 * 60 * 1000), 10),
  };
}
