import React from 'react';
import {Platform, View, type ViewProps} from 'react-native';
import {requireNativeView} from 'expo';

/**
 * Progressive edge blur over its children (Android 13+, `EdgeBlurView` in modules/taku-agent):
 * the top zone is sharp at `topSharp` and fully blurred and faded at `topFull`; the bottom zone
 * is sharp at `bottomSharp` and full at `bottomFull`. Positions are dp from the view's top; a
 * negative sharp edge turns that zone off. Elsewhere it is a plain View.
 */
export type EdgeBlurProps = ViewProps & {
  topSharp?: number; topFull?: number; bottomSharp?: number; bottomFull?: number;
  /** Largest σ in dp, at the full edges. */
  sigma?: number;
  fade?: boolean;
  /** A uniform blur (σ dp) over everything, e.g. under a long-press menu; 0 for none. */
  uniformSigma?: number;
};

const NativeEdgeBlur: React.ComponentType<EdgeBlurProps> | null =
  Platform.OS === 'android' ? requireNativeView<EdgeBlurProps>('TakuAgent', 'EdgeBlurView') : null;

export function EdgeBlur(props: EdgeBlurProps) {
  if (!NativeEdgeBlur) {
    const {topSharp: _a, topFull: _b, bottomSharp: _c, bottomFull: _d, sigma: _e, fade: _f, uniformSigma: _g, ...rest} = props;
    return <View {...rest}/>;
  }
  return <NativeEdgeBlur {...props}/>;
}
