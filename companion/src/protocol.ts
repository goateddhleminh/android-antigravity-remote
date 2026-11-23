export interface SessionInfo {
  id: string;
  name: string;
  cwd: string;
  shell: string;
  cols: number;
  rows: number;
  createdAt: string;
  lastActivityAt: string;
  alive: boolean;
  exitCode: number | null;
  attachedClients: number;
}

// Client -> Server WebSocket messages
export type ClientMessage =
  | { type: "list_sessions" }
  | {
      type: "create_session";
      name?: string;
      cwd?: string;
      cols?: number;
      rows?: number;
      command?: string;
    }
  | {
      type: "attach";
      sessionId: string;
      cols?: number;
      rows?: number;
      sinceSeq?: number;
    }
  | { type: "detach"; sessionId: string }
  | { type: "input"; sessionId: string; data: string }
  | { type: "resize"; sessionId: string; cols: number; rows: number }
  | { type: "kill_session"; sessionId: string }
  | { type: "ping"; ts?: number };

// Server -> Client WebSocket messages
export type ServerMessage =
  | { type: "hello"; deviceName: string; platform: string; version: string }
  | { type: "sessions"; sessions: SessionInfo[] }
  | { type: "session_created"; session: SessionInfo }
  | { type: "attached"; session: SessionInfo; replay: string; lastSeq: number }
  | { type: "detached"; sessionId: string }
  | { type: "output"; sessionId: string; data: string; seq: number }
  | { type: "session_exit"; sessionId: string; exitCode: number | null; signal?: number }
  | { type: "session_killed"; sessionId: string }
  | { type: "error"; code: string; message: string }
  | { type: "pong"; ts?: number };
