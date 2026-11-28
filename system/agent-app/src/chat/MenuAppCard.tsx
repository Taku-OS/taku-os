import React, {useEffect, useRef, useState} from 'react';
import {Image, Pressable, StyleSheet, Text, View} from 'react-native';
import type {Language} from '../i18n';
import {openMenuApp, readMenuApp, type MenuAppInfo} from '../native/agent';
import {containsChinese} from '../theme';
import {chatText} from './chatText';
import {chatColors, chatType, themedStyles} from './chatTheme';

export function MenuAppCard({packageName, language}: {packageName: string; language: Language}) {
  const t = chatText(language), chinese = language === 'zh';
  const [app, setApp] = useState<MenuAppInfo | null>();
  const [coverFailed, setCoverFailed] = useState(false);
  const [launchState, setLaunchState] = useState<'idle' | 'opening' | 'failed'>('idle');
  const opening = useRef(false);

  useEffect(() => {
    let current = true;
    void readMenuApp(packageName).then(value => {if (current) setApp(value);}, () => {if (current) setApp(null);});
    return () => {current = false;};
  }, [packageName]);

  async function open() {
    if (opening.current) return;
    opening.current = true;
    setLaunchState('opening');
    const launched = await openMenuApp(packageName).catch(() => false);
    opening.current = false;
    setLaunchState(launched ? 'idle' : 'failed');
  }

  const busy = launchState === 'opening';
  return <View style={styles.card} testID="menu-app-card">
    <View style={styles.body}>
      <View style={styles.copy}>
        <Text style={[chatType.bodySmMedium(chinese), styles.secondary]}>{t('menuApp')}</Text>
        {!!app?.title && <Text style={chatType.headingXl(containsChinese(app.title))}>{app.title}</Text>}
        <Text accessibilityLiveRegion={app ? 'none' : 'polite'} style={[chatType.bodyMd(chinese), styles.secondary]}>
          {app ? t('menuAppDescription') : t(app === null ? 'menuAppUnavailable' : 'loading')}
        </Text>
      </View>
      {!!app?.coverUri && !coverFailed && <Image source={{uri: app.coverUri}} style={styles.cover}
        onError={() => setCoverFailed(true)} accessible={false}/>}
    </View>
    {app && <Pressable accessibilityRole="button" accessibilityState={{busy, disabled: busy}} disabled={busy} onPress={open}
      testID="open-menu-app" style={({pressed}) => [styles.open, (pressed || busy) && styles.pressed]}>
      <Text style={[chatType.bodyMdMedium(chinese), styles.openText]}>{t('openMenuApp')}</Text>
    </Pressable>}
    {launchState === 'failed' && <Text accessibilityLiveRegion="polite" style={[chatType.bodySm(chinese), styles.error]}>{t('menuAppOpenFailed')}</Text>}
  </View>;
}

const styles = themedStyles(() => StyleSheet.create({
  card: {maxWidth: 380, backgroundColor: chatColors.surfaceRaised, borderColor: chatColors.hairline,
    borderWidth: StyleSheet.hairlineWidth, borderRadius: 16, padding: 12, gap: 8},
  body: {flexDirection: 'row', gap: 12, alignItems: 'center'},
  copy: {flex: 1, gap: 6},
  secondary: {color: chatColors.textSecondary},
  cover: {width: 88, height: 88, borderRadius: 10},
  open: {alignSelf: 'flex-end', minHeight: 48, justifyContent: 'center', paddingHorizontal: 16,
    borderRadius: 24, backgroundColor: chatColors.stopFill},
  openText: {color: chatColors.stopGlyph},
  pressed: {opacity: 0.72},
  error: {color: chatColors.textError},
}));
