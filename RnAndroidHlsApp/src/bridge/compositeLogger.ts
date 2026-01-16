import type { Logger } from './logger';

export class CompositeLogger implements Logger {
  constructor(private readonly loggers: Logger[]) {}

  info(message: string, context?: Record<string, unknown>): void {
    this.loggers.forEach(logger => logger.info(message, context));
  }

  warn(message: string, context?: Record<string, unknown>): void {
    this.loggers.forEach(logger => logger.warn(message, context));
  }

  error(message: string, context?: Record<string, unknown>): void {
    this.loggers.forEach(logger => logger.error(message, context));
  }
}
