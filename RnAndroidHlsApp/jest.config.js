module.exports = {
  preset: 'react-native',
  setupFilesAfterEnv: ['<rootDir>/jest.setup.js'],
  transformIgnorePatterns: [
    'node_modules/(?!(@react-native|react-native|@react-native-async-storage|@sentry/react-native)/)',
  ],
  modulePathIgnorePatterns: [
    '<rootDir>/android/',
  ],
};
