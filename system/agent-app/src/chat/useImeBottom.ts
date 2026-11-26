import {useEffect, useState} from 'react';
import {Keyboard, Platform, useWindowDimensions} from 'react-native';
import {onImeInsets} from '../native/agent';
import {sheetSpace, type ImeGeometry} from '../model/sheetGeometry';

/**
 * How many dp of this edge-to-edge window the keyboard covers, from the same native IME geometry the
 * chat composer follows (Android 11+), or the keyboard frame on older systems; 0 while it is hidden.
 */
export function useImeBottom(): number {
  const {height} = useWindowDimensions();
  const nativeIme = Platform.OS === 'android' && Number(Platform.Version) >= 30;
  const [geometry, setGeometry] = useState<ImeGeometry | null>(null);
  const [legacy, setLegacy] = useState(0);
  useEffect(() => {
    if (nativeIme) {
      const subscription = onImeInsets(setGeometry);
      return () => subscription.remove();
    }
    const show = Keyboard.addListener('keyboardDidShow', event => setLegacy(event.endCoordinates.height));
    const hide = Keyboard.addListener('keyboardDidHide', () => setLegacy(0));
    return () => { show.remove(); hide.remove(); };
  }, [nativeIme]);
  return nativeIme ? sheetSpace(geometry, height, 0).bottom : legacy;
}
