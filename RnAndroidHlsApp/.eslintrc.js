module.exports = {
  root: true,
  extends: '@react-native',
  env: {
    node: true,
    jest: true,
  },
  globals: {
    __DEV__: 'readonly',
  },
};
