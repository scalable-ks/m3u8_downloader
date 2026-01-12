export type ErrorCode =
  | "network"
  | "storage"
  | "decryption"
  | "ffmpeg"
  | "validation"
  | "unexpected";

export class HlsError extends Error {
  readonly code: ErrorCode;
  readonly detail?: string;

  constructor(code: ErrorCode, message: string, detail?: string) {
    super(message);
    this.code = code;
    this.detail = detail;
  }
}
