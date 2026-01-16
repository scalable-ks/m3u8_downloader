import * as Sentry from '@sentry/react-native';
import type { Logger } from './logger';

export class SentryLogger implements Logger {
  info(message: string, context?: Record<string, unknown>): void {
    Sentry.addBreadcrumb({
      message,
      level: 'info',
      data: context,
    });
    console.info(message, context);
  }

  warn(message: string, context?: Record<string, unknown>): void {
    Sentry.addBreadcrumb({
      message,
      level: 'warning',
      data: context,
    });
    console.warn(message, context);
  }

  error(message: string, context?: Record<string, unknown>): void {
    Sentry.addBreadcrumb({
      message,
      level: 'error',
      data: context,
    });

    // Capture as a Sentry event with context
    Sentry.captureMessage(message, {
      level: 'error',
      contexts: {
        custom: context || {},
      },
    });

    console.error(message, context);
  }
}
