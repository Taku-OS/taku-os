const path = require('path');
const { getDefaultConfig } = require('expo/metro-config');
const config = getDefaultConfig(__dirname);
// Consume the pinned design team's token file; do not fork a second palette.
config.watchFolders = [path.resolve(__dirname, '../assistant/design-system')];
module.exports = config;
