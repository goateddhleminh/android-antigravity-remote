import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import bcrypt from "bcryptjs";
import jwt from "jsonwebtoken";
import { v4 as uuidv4 } from "uuid";
import type { CompanionConfig } from "./config.js";

export interface PairedDevice {
  id: string;
  name: string;
  createdAt: string;
  lastSeenAt: string;
  revoked: boolean;
  refreshTokenHash: string;
  refreshExpiresAt: number;
}

interface PersistedState {
  jwtSecret: string;
  adminEmail: string;
  adminPasswordHash: string;
  devices: PairedDevice[];
}

interface PendingPairToken {
  token: string;
  expiresAt: number;
  used: boolean;
}

export interface AccessTokenPayload {
  sub: string; // deviceId
  email: string;
  type: "access";
  iat?: number;
  exp?: number;
}

export interface AuthTokenBundle {
  deviceId: string;
  deviceName: string;
  accessToken: string;
  refreshToken: string;
  expiresInSec: number;
}

function sha256(input: string): string {
  return crypto.createHash("sha256").update(input).digest("hex");
}

export class AuthService {
  private readonly statePath: string;
  private readonly config: CompanionConfig;
  private state: PersistedState;
  private readonly pendingPairs = new Map<string, PendingPairToken>();
  readonly generatedPassword?: string;

  constructor(config: CompanionConfig) {
    this.config = config;
    fs.mkdirSync(config.dataDir, { recursive: true });
    this.statePath = path.join(config.dataDir, "state.json");

    if (fs.existsSync(this.statePath)) {
      const raw = fs.readFileSync(this.statePath, "utf8");
      this.state = JSON.parse(raw) as PersistedState;
      // Allow env override for admin password if explicitly set
      if (config.adminPassword) {
        this.state.adminEmail = config.adminEmail;
        this.state.adminPasswordHash = bcrypt.hashSync(config.adminPassword, 10);
        this.save();
      }
    } else {
      const initialPassword = config.adminPassword || crypto.randomBytes(12).toString("base64url");
      if (!config.adminPassword) {
        this.generatedPassword = initialPassword;
      }
      this.state = {
        jwtSecret: crypto.randomBytes(32).toString("hex"),
        adminEmail: config.adminEmail,
        adminPasswordHash: bcrypt.hashSync(initialPassword, 10),
        devices: [],
      };
      this.save();
    }
  }

  private save(): void {
    fs.writeFileSync(this.statePath, JSON.stringify(this.state, null, 2), { mode: 0o600 });
  }

  getAdminEmail(): string {
    return this.state.adminEmail;
  }

  createPairingToken(): { token: string; expiresAt: number } {
    const token = crypto.randomBytes(24).toString("base64url");
    const expiresAt = Date.now() + this.config.pairTokenTtlSec * 1000;
    this.pendingPairs.set(token, { token, expiresAt, used: false });
    this.pruneExpiredPairTokens();
    return { token, expiresAt };
  }

  private pruneExpiredPairTokens(): void {
    const now = Date.now();
    for (const [key, entry] of this.pendingPairs.entries()) {
      if (entry.used || entry.expiresAt <= now) {
        this.pendingPairs.delete(key);
      }
    }
  }

  pairWithToken(pairToken: string, clientDeviceName?: string): AuthTokenBundle {
    this.pruneExpiredPairTokens();
    const pending = this.pendingPairs.get(pairToken);
    if (!pending || pending.used || pending.expiresAt <= Date.now()) {
      throw new Error("Invalid or expired pairing token");
    }
    pending.used = true;
    this.pendingPairs.delete(pairToken);
    return this.registerDevice(clientDeviceName || "Android Device");
  }

  loginWithPassword(email: string, password: string, clientDeviceName?: string): AuthTokenBundle {
    const emailMatch = email.trim().toLowerCase() === this.state.adminEmail.toLowerCase();
    const passMatch = bcrypt.compareSync(password, this.state.adminPasswordHash);
    if (!emailMatch || !passMatch) {
      throw new Error("Invalid email or password");
    }
    return this.registerDevice(clientDeviceName || "Android Login");
  }

  private registerDevice(name: string): AuthTokenBundle {
    const deviceId = uuidv4();
    const refreshToken = crypto.randomBytes(32).toString("base64url");
    const now = new Date().toISOString();
    const device: PairedDevice = {
      id: deviceId,
      name: name.trim().slice(0, 64) || "Android Device",
      createdAt: now,
      lastSeenAt: now,
      revoked: false,
      refreshTokenHash: sha256(refreshToken),
      refreshExpiresAt: Date.now() + this.config.refreshTokenTtlSec * 1000,
    };
    this.state.devices.push(device);
    this.save();

    const accessToken = this.signAccessToken(deviceId);
    return {
      deviceId,
      deviceName: device.name,
      accessToken,
      refreshToken,
      expiresInSec: this.config.accessTokenTtlSec,
    };
  }

  refreshTokens(deviceId: string, refreshToken: string): AuthTokenBundle {
    const device = this.state.devices.find((d) => d.id === deviceId);
    if (!device || device.revoked) {
      throw new Error("Device is revoked or unknown");
    }
    if (device.refreshExpiresAt <= Date.now()) {
      throw new Error("Refresh token expired");
    }
    const candidateHash = sha256(refreshToken);
    const expectedBuf = Buffer.from(device.refreshTokenHash, "hex");
    const actualBuf = Buffer.from(candidateHash, "hex");
    if (expectedBuf.length !== actualBuf.length || !crypto.timingSafeEqual(expectedBuf, actualBuf)) {
      throw new Error("Invalid refresh token");
    }

    // Rotate refresh token on every refresh
    const nextRefresh = crypto.randomBytes(32).toString("base64url");
    device.refreshTokenHash = sha256(nextRefresh);
    device.refreshExpiresAt = Date.now() + this.config.refreshTokenTtlSec * 1000;
    device.lastSeenAt = new Date().toISOString();
    this.save();

    return {
      deviceId: device.id,
      deviceName: device.name,
      accessToken: this.signAccessToken(device.id),
      refreshToken: nextRefresh,
      expiresInSec: this.config.accessTokenTtlSec,
    };
  }

  private signAccessToken(deviceId: string): string {
    const payload: AccessTokenPayload = {
      sub: deviceId,
      email: this.state.adminEmail,
      type: "access",
    };
    return jwt.sign(payload, this.state.jwtSecret, {
      expiresIn: this.config.accessTokenTtlSec,
      algorithm: "HS256",
    });
  }

  verifyAccessToken(token: string): AccessTokenPayload {
    const decoded = jwt.verify(token, this.state.jwtSecret, {
      algorithms: ["HS256"],
    }) as AccessTokenPayload;
    if (decoded.type !== "access" || !decoded.sub) {
      throw new Error("Invalid token type");
    }
    const device = this.state.devices.find((d) => d.id === decoded.sub);
    if (!device || device.revoked) {
      throw new Error("Device revoked");
    }
    device.lastSeenAt = new Date().toISOString();
    return decoded;
  }

  listDevices(): Array<Omit<PairedDevice, "refreshTokenHash">> {
    return this.state.devices.map(({ refreshTokenHash: _h, ...rest }) => rest);
  }

  revokeDevice(deviceId: string): boolean {
    const device = this.state.devices.find((d) => d.id === deviceId);
    if (!device) return false;
    device.revoked = true;
    device.refreshTokenHash = "";
    this.save();
    return true;
  }
}
