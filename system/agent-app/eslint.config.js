const {defineConfig} = require('eslint/config');
const expo = require('eslint-config-expo/flat');

module.exports = defineConfig([
  expo,
  {
    // Node-only CommonJS tooling (install/build scripts, config plugins, tests).
    files: ['**/*.cjs', 'plugins/**/*.js', '*.config.js'],
    languageOptions: {sourceType: 'commonjs', globals: {__dirname: 'readonly', __filename: 'readonly'}},
  },
  {ignores: ['android/**', 'ios/**', 'out/**', 'dist/**', 'src/theme/icons.json', 'modules/taku-home/voice-effects/effects/**']},
]);
