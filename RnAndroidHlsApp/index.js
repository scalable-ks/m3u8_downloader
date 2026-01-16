/**
 * @format
 */

import * as Sentry from '@sentry/react-native';
import { AppRegistry } from 'react-native';
import App from './App';
import { name as appName } from './app.json';

// Initialize Sentry BEFORE registering the app component
Sentry.init({
  dsn: 'https://6cd2a148004f57764226c7fb5a12b4af@o4510716831399936.ingest.de.sentry.io/4510716835070032',

  // Performance Monitoring
  tracesSampleRate: __DEV__ ? 1.0 : 0.1, // 100% in dev, 10% in production
  profilesSampleRate: 1.0, // Profile 100% of traced transactions

  // Session Replay - DISABLED due to Android crash bug in 7.8.0
  // See: https://github.com/getsentry/sentry-react-native/issues/3990
  // _experiments: {
  //   replaysSessionSampleRate: 0.1,
  //   replaysOnErrorSampleRate: 1.0,
  // },
  // integrations: [
  //   Sentry.mobileReplayIntegration(),
  // ],

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
