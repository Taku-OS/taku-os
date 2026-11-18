import React, {useEffect, useState} from 'react';
import {ActivityIndicator, Platform, StyleSheet, Text, View} from 'react-native';
import {Redirect} from 'expo-router';
import ComponentGallery from '../gallery/ComponentGallery';
import {FeedShot} from '../gallery/FeedGallery';
import {colors, typography} from '../theme';
import {useTakuFonts} from '../theme/useTakuFonts';

export default function ComponentsPage() {
  const [fonts, fontError] = useTakuFonts();
  const [assetBase] = useState(() => Platform.OS === 'web' ? window.location.origin : '');
  useEffect(() => {
    if (Platform.OS === 'web') document.title = 'Taku · Component catalog';
  }, []);
  if (Platform.OS !== 'web') return <Redirect href="/"/>;
  if (fontError) return <View style={styles.loading}><Text style={typography('body', true)}>Component fonts failed to load: {fontError.message}</Text></View>;
  if (!fonts || !assetBase) return <View style={styles.loading}><ActivityIndicator color={colors.ink}/><Text style={typography('body', true)}>Loading component catalog…</Text></View>;
  const feedShot = new URLSearchParams(window.location.search).get('feed');
  if (feedShot) return <FeedShot assetBase={assetBase} cardId={feedShot}/>;
  return <ComponentGallery assetBase={assetBase}/>;
}

const styles = StyleSheet.create({loading: {flex: 1, alignItems: 'center', justifyContent: 'center', gap: 16, backgroundColor: colors.page}});
