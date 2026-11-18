import {AppRegistry, Platform} from 'react-native';
import 'expo-router/entry';
import FeedCardRoot from './src/feed/FeedCardRoot';
import PanelRoot from './src/panels/PanelRoot';
import {feedGenerationTask} from './src/feed/feedGenerationTask';

// Taku Home hosts one React surface per feed card and per panel page (modules/taku-home/CONTRACT.md).
AppRegistry.registerComponent('TakuFeedCard', () => FeedCardRoot);
AppRegistry.registerComponent('TakuPanel', () => PanelRoot);
if (Platform.OS === 'android') {
  AppRegistry.registerHeadlessTask('TakuFeedGeneration', () => feedGenerationTask);
}
