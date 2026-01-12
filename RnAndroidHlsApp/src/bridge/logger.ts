export interface Logger {
  info(message: string, context?: Record<string, unknown>): void;
  warn(message: string, context?: Record<string, unknown>): void;
  error(message: string, context?: Record<string, unknown>): void;
}

export class ConsoleLogger implements Logger {
  info(message: string, context?: Record<string, unknown>): void {
    if (context) {
      console.info(message, context);
    } else {
      console.info(message);
    }
  }

  warn(message: string, context?: Record<string, unknown>): void {
    if (context) {
      console.warn(message, context);
    } else {
      console.warn(message);
    }
  }

  error(message: string, context?: Record<string, unknown>): void {
    if (context) {
      console.error(message, context);
    } else {
      console.error(message);
    }
  }
}
