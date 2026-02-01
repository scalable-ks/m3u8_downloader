// Mock AsyncStorage using the official mock from the package
jest.mock('@react-native-async-storage/async-storage', () =>
  require('@react-native-async-storage/async-storage/jest/async-storage-mock')
);

// Mock react-native-safe-area-context
jest.mock('react-native-safe-area-context', () => {
  const inset = { top: 0, right: 0, bottom: 0, left: 0 };
  return {
    SafeAreaProvider: ({ children }) => children,
    SafeAreaView: ({ children }) => children,
    useSafeAreaInsets: () => inset,
    useSafeAreaFrame: () => ({ x: 0, y: 0, width: 390, height: 844 }),
  };
});

// Mock Sentry
jest.mock('@sentry/react-native', () => ({
  init: jest.fn(),
  wrap: (component) => component,
  captureException: jest.fn(),
  captureMessage: jest.fn(),
  addBreadcrumb: jest.fn(),
}));

// Mock the native bridge to avoid NativeModules access
jest.mock('./src/bridge/nativeBridge', () => ({
  NativeDownloaderBridge: jest.fn().mockImplementation(() => ({
    startJob: jest.fn(() => Promise.resolve({ id: 'mock-id', state: 'PENDING', progress: 0 })),
    pauseJob: jest.fn(() => Promise.resolve({ id: 'mock-id', state: 'PAUSED', progress: 0 })),
    resumeJob: jest.fn(() => Promise.resolve({ id: 'mock-id', state: 'RUNNING', progress: 0 })),
    cancelJob: jest.fn(() => Promise.resolve({ id: 'mock-id', state: 'CANCELED', progress: 0 })),
    getJobStatus: jest.fn(() => Promise.resolve({ id: 'mock-id', state: 'PENDING', progress: 0 })),
    listJobs: jest.fn(() => Promise.resolve([])),
  })),
}));

// Mock SAF (Storage Access Framework)
jest.mock('./src/bridge/saf', () => ({
  pickDirectory: jest.fn(() => Promise.resolve('/mock/directory')),
}));
