import {useFonts} from 'expo-font';

/** Same licensed font files as the official native design-system import. */
export function useTakuFonts() {
  return useFonts({
    TakuCode: require('./fonts/jetbrains_mono_regular.ttf'),
    TakuBody: require('./fonts/hanken_grotesk_regular.ttf'),
    TakuBodyMedium: require('./fonts/hanken_grotesk_medium.ttf'),
    TakuDisplay: require('./fonts/space_grotesk_regular.ttf'),
    TakuChinese: require('./fonts/noto_sans_sc_regular.ttf'),
    TakuChineseMedium: require('./fonts/noto_sans_sc_medium.ttf'),
  });
}
