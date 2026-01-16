/**
 * @format
 */

import * as Sentry from '@sentry/react-native';
import { AppRegistry } from 'react-native';
import App from './App';
import { name as appName } from './app.json';

// Initialize Sentry BEFORE registering the app component
Sentry.init({
  dsn: process.env.SENTRY_DSN || '__SENTRY_DSN_PLACEHOLDER__',

  // Performance Monitoring
  tracesSampleRate: __DEV__ ? 1.0 : 0.1, // 100% in dev, 10% in production
  profilesSampleRate: 1.0, // Profile 100% of traced transactions

  // Session Replay
  _experiments: {
    replaysSessionSampleRate: 0.1, // 10% of sessions
    replaysOnErrorSampleRate: 1.0, // 100% of errors
  },
  integrations: [
    Sentry.mobileReplayIntegration(),
  ],

  // Environment and Release Tracking
  environment: __DEV__ ? 'development' : 'production',
  enableAutoSessionTracking: true,

  // Breadcrumbs
  maxBreadcrumbs: 100,

  // Debug logging (disable in production)
  debug: __DEV__,

  // Attach stack traces to messages
  attachStacktrace: true,
});

// Wrap the App component with Sentry for error boundary
const SentryWrappedApp = Sentry.wrap(App);

AppRegistry.registerComponent(appName, () => SentryWrappedApp);
