import React from 'react';
import {requireNativeView} from 'expo';
import {Platform, View, type ViewProps} from 'react-native';

type Props = ViewProps & {kind?: 'wave' | 'light'; target?: 'listen' | 'live'; level?: number; flat?: boolean};
const NativeEffect = Platform.OS === 'android' ? requireNativeView<Props>('TakuHome', 'VoiceEffectExpoView') : null;
/** Exact bundled designer renderer, shared by native hold-to-talk and the chat. */
export function VoiceEffect(props: Props) {
  return NativeEffect ? <NativeEffect {...props} pointerEvents="none"/> : <View style={props.style}/>;
}
