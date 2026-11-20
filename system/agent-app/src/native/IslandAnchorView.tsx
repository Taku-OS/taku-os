import {requireNativeView} from 'expo';
import {Platform, type NativeSyntheticEvent, type ViewProps} from 'react-native';

export type IslandAnchor = {x: number; y: number};

type IslandAnchorViewProps = ViewProps & {
  onAnchorChanged: (event: NativeSyntheticEvent<IslandAnchor>) => void;
};

export const IslandAnchorView = Platform.OS === 'android'
  ? requireNativeView<IslandAnchorViewProps>('TakuHome', 'IslandAnchorView')
  : null;
