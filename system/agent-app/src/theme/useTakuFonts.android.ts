/** expo-font embeds the existing UI families and Markdown fonts at build time.
 * No require() here: Metro must not package a second copy of the native fonts. */
export function useTakuFonts(): [boolean, Error | null] {
  return [true, null];
}
