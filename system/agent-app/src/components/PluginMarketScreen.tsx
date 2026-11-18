import React, {useCallback, useEffect, useState} from 'react';
import {ActivityIndicator, Alert, Pressable, ScrollView, StyleSheet, Text, TextInput, View} from 'react-native';
import {useLocales} from 'expo-localization';
import {useRouter} from 'expo-router';
import {SafeAreaView} from 'react-native-safe-area-context';
import Svg, {Circle, Path} from 'react-native-svg';
import {Icon} from './Icon';
import {languageFor} from '../i18n';
import {pluginTextFor} from '../i18n/plugins';
import {colors, layout, typography} from '../theme';
import {useTakuFonts} from '../theme/useTakuFonts';
import {catalogResults, pluginErrorKey, type Catalog, type PluginDetails, type PluginOperationResult, type PluginRepository, type PluginSource, type PluginSummary, type PluginUpdate} from '../plugins/contracts';

type Tab = 'discover' | 'installed' | 'updates' | 'sources';
type Data = {catalog: Catalog; installed: PluginSummary[]; updates: PluginUpdate[]; sources: PluginSource[]};
type ActionOptions = {primary?: boolean; selected?: boolean; disabled?: boolean; compact?: boolean};

const blank: Data = {catalog: {items: [], offline: false}, installed: [], updates: [], sources: []};
const pluginTints = [colors.sage, colors.peach, colors.lavender, colors.mist, colors.rose, colors.sand];

function tintFor(value: string): string {
  let hash = 0;
  for (const character of value) hash = (hash * 31 + character.charCodeAt(0)) >>> 0;
  return pluginTints[hash % pluginTints.length];
}

function SearchIcon({color = colors.secondary}: {color?: string}) {
  return <Svg width={19} height={19} viewBox="0 0 24 24" accessibilityElementsHidden>
    <Circle cx="10.5" cy="10.5" r="6.5" fill="none" stroke={color} strokeWidth="1.8"/>
    <Path d="m15.5 15.5 4.5 4.5" fill="none" stroke={color} strokeWidth="1.8" strokeLinecap="round"/>
  </Svg>;
}

export function PluginMarketScreen({repository}: {repository: PluginRepository}) {
  const router = useRouter();
  const language = languageFor(useLocales()[0]?.languageTag), t = pluginTextFor(language);
  const [fonts, fontError] = useTakuFonts();
  const [tab, setTab] = useState<Tab>('discover');
  const [data, setData] = useState<Data>(blank);
  const [loading, setLoading] = useState(true), [busy, setBusy] = useState(false);
  const [error, setError] = useState<{code: string; message: string} | null>(null);
  const [query, setQuery] = useState(''), [category, setCategory] = useState('all'), [sourceUrl, setSourceUrl] = useState('');
  const [detail, setDetail] = useState<PluginDetails | null>(null), [technical, setTechnical] = useState(false);
  const type = {
    heading: typography('heading', language === 'zh'), context: typography('context', language === 'zh'),
    body: typography('body', language === 'zh'), label: typography('label', language === 'zh'),
    meta: typography('meta', language === 'zh'), caption: typography('caption', language === 'zh'),
  };

  const load = useCallback(async () => {
    setLoading(true); setError(null);
    try {
      const [catalog, installed, updates, sources] = await Promise.all([repository.listCatalog(), repository.listInstalled(), repository.listUpdates(), repository.listSources()]);
      const failed = [catalog, installed, updates, sources].find(result => !result.ok);
      if (failed && !failed.ok) setError(failed);
      setData({catalog: catalog.ok ? catalog.value : blank.catalog, installed: installed.ok ? installed.value : [], updates: updates.ok ? updates.value : [], sources: sources.ok ? sources.value : []});
    } catch (cause) { setError({code: 'OPERATION_FAILED', message: String(cause)}); }
    finally { setLoading(false); }
  }, [repository]);
  // Loads the catalog from the native plugin repository (external system) when the repository changes.
  // eslint-disable-next-line react-hooks/set-state-in-effect
  useEffect(() => { void load(); }, [load]);

  const run = async (operation: () => Promise<PluginOperationResult>, refreshDetail = false) => {
    setBusy(true); setError(null);
    try {
      const result = await operation();
      if (!result.ok) { setError(result); return; }
      await load();
      if (refreshDetail && detail) { const next = await repository.getDetails(detail.id); setDetail(next.ok ? next.value : null); }
    } catch (cause) { setError({code: 'OPERATION_FAILED', message: String(cause)}); }
    finally { setBusy(false); }
  };
  const open = async (id: string) => {
    setBusy(true); setError(null);
    try { const result = await repository.getDetails(id); if (result.ok) {setDetail(result.value); setTechnical(false);} else setError(result); }
    catch (cause) { setError({code: 'OPERATION_FAILED', message: String(cause)}); }
    finally { setBusy(false); }
  };
  const confirm = (message: string, action: () => void) => Alert.alert(t('confirm'), message, [{text: t('cancel'), style: 'cancel'}, {text: t('confirm'), style: 'destructive', onPress: action}]);
  const actionButton = (label: string, onPress: () => void, options: ActionOptions = {}) => {
    const unavailable = Boolean(options.disabled || busy);
    return <Pressable key={`${label}-${options.primary ? 'primary' : options.selected ? 'selected' : 'secondary'}`}
      accessibilityRole="button" accessibilityState={{disabled: unavailable, selected: Boolean(options.selected)}}
      disabled={unavailable} onPress={onPress}
      style={({pressed}) => [styles.action, options.compact && styles.actionCompact, options.primary && styles.actionPrimary,
        options.selected && styles.actionSelected, unavailable && styles.dim, pressed && styles.pressed]}>
      <Text style={[type.meta, styles.actionText, options.primary && styles.actionPrimaryText, options.selected && styles.actionSelectedText]}>{label}</Text>
    </Pressable>;
  };
  const pill = (label: string, tone: 'neutral' | 'success' | 'brand' = 'neutral') =>
    <View key={`${tone}-${label}`} style={[styles.pill, tone === 'success' && styles.pillSuccess, tone === 'brand' && styles.pillBrand]}>
      {tone !== 'neutral' && <View style={[styles.pillDot, tone === 'success' ? styles.dotSuccess : styles.dotBrand]}/>}
      <Text style={[type.caption, styles.pillText]}>{label}</Text>
    </View>;
  const detailRow = (label: string, value?: string) =>
    <View style={styles.detailRow}><Text style={[type.caption, styles.detailLabel]}>{label}</Text><Text selectable={label === t('fingerprint')} style={[type.meta, styles.detailValue]}>{value || t('unknown')}</Text></View>;
  const pluginMark = (item: Pick<PluginSummary, 'id' | 'name'>, large = false) =>
    <View style={[styles.pluginMark, large && styles.pluginMarkLarge, {backgroundColor: tintFor(item.id)}]}>
      <View style={styles.markCutout}/>
      <Text style={[type.context, styles.markLetter, large && styles.markLetterLarge]}>{item.name.trim().slice(0, 1).toUpperCase()}</Text>
    </View>;
  const statusPill = (item: PluginSummary) => pill(
    item.installed ? t(item.enabled === undefined ? 'unknown' : item.enabled ? 'enabled' : 'disabled') : t('notInstalled'),
    item.installed && item.enabled ? 'success' : 'neutral',
  );
  const pluginCard = (item: PluginSummary) => {
    const source = data.sources.find(candidate => candidate.id === item.sourceId)?.name || item.sourceId;
    const capabilities = item.capabilities.length ? item.capabilities.slice(0, 2).join(' · ') : t('none');
    return <Pressable key={item.id} accessibilityRole="button" accessibilityLabel={`${item.name}. ${item.description}`}
      onPress={() => void open(item.id)} style={({pressed}) => [styles.pluginCard, pressed && styles.cardPressed]}>
      <View style={styles.pluginHeader}>
        {pluginMark(item)}
        <View style={styles.pluginIdentity}>
          <View style={styles.pluginTitleRow}><Text numberOfLines={1} style={[type.label, styles.pluginName]}>{item.name}</Text>{statusPill(item)}</View>
          <Text numberOfLines={1} style={[type.caption, styles.pluginMeta]}>{source} · v{item.version}</Text>
        </View>
        <View style={styles.chevron}><Icon name="chevron_right" size={18} color={colors.secondary}/></View>
      </View>
      <Text numberOfLines={2} style={[type.body, styles.pluginDescription]}>{item.description}</Text>
      <View style={styles.chipRow}>{pill(capabilities)}{pill(t(item.trust))}</View>
    </Pressable>;
  };
  const emptyState = (title: string, body: string, icon: 'check' | 'tree' | 'refresh', action?: React.ReactNode) =>
    <View style={styles.emptyCard}>
      <View style={styles.emptyIcon}><Icon name={icon} size={22} color={colors.ink}/></View>
      <Text style={[type.context, styles.emptyTitle]}>{title}</Text>
      <Text style={[type.meta, styles.emptyBody]}>{body}</Text>
      {action}
    </View>;
  const sectionTitle = (title: string, body?: string, action?: React.ReactNode) =>
    <View style={styles.sectionTitleRow}><View style={styles.sectionCopy}><Text style={[type.context, styles.sectionTitle]}>{title}</Text>{!!body && <Text style={[type.meta, styles.sectionBody]}>{body}</Text>}</View>{action}</View>;
  const detailActions = (item: PluginSummary) => <View style={styles.detailActions}>
    {item.enabled !== undefined && actionButton(t(item.enabled ? 'disable' : 'enable'), () => void run(() => item.enabled ? repository.disable(item.id) : repository.enable(item.id), true), {primary: true})}
    {actionButton(t('reload'), () => void run(() => repository.reload(item.id), true))}
    {actionButton(t('rollback'), () => undefined, {disabled: true})}
    {actionButton(t('remove'), () => confirm(t('confirmRemove'), () => void run(() => repository.remove(item.id), true)))}
  </View>;

  const categories = ['all', ...new Set(data.catalog.items.flatMap(item => [item.category, ...item.tags]).filter(Boolean))];
  const filtered = catalogResults(data.catalog.items, query, category);
  const recommended = !query.trim() && category === 'all' ? filtered.filter(item => item.recommended) : [];
  const running = data.installed.filter(item => item.enabled).length;
  if (!fonts && !fontError) return <View style={styles.center}><ActivityIndicator color={colors.ink}/></View>;

  const hero = <View style={styles.hero}>
    <View style={styles.heroOrangeOrb}/><View style={styles.heroSageOrb}/><View style={styles.heroLavenderOrb}/>
    <Text style={[type.caption, styles.heroEyebrow]}>{t('heroFeature')}</Text>
    <Text style={[type.heading, styles.heroTitle]}>{t('heroTitle')}</Text>
    <Text style={[type.meta, styles.heroBody]}>{t('heroBody')}</Text>
    <View style={styles.heroMeta}><View style={styles.heroLiveDot}/><Text style={[type.caption, styles.heroMetaText]}>{running} {t('enabled').toLowerCase()} · {data.installed.length} {t('installed').toLowerCase()}</Text></View>
  </View>;

  const tabs = <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.tabsViewport} contentContainerStyle={styles.tabs}>
    {(['discover', 'installed', 'updates', 'sources'] as Tab[]).map(value => actionButton(t(value), () => setTab(value), {selected: tab === value, compact: true}))}
  </ScrollView>;

  const discover = <>
    {hero}
    <View style={styles.searchBox}><SearchIcon/><TextInput style={[type.body, styles.searchInput]} value={query} onChangeText={setQuery}
      placeholder={t('search')} placeholderTextColor={colors.secondary} accessibilityLabel={t('search')}/>
      {!!query && <Pressable accessibilityRole="button" accessibilityLabel={t('close')} onPress={() => setQuery('')} style={styles.clearSearch}><Icon name="close" size={16} color={colors.secondary}/></Pressable>}
    </View>
    {categories.length > 1 && <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.chips}>{categories.map(value => actionButton(value === 'all' ? t('all') : value, () => setCategory(value), {selected: category === value, compact: true}))}</ScrollView>}
    {recommended.length > 0 && <>{sectionTitle(t('recommended'))}<View style={styles.cardStack}>{recommended.map(pluginCard)}</View></>}
    {filtered.length > 0 ? <>{sectionTitle(t('explore'))}<View style={styles.cardStack}>{filtered.filter(item => !recommended.includes(item)).map(pluginCard)}</View></> : !error ? <>
      {data.installed.length > 0 && <>
        {sectionTitle(t('readyTitle'), t('readyBody'), actionButton(t('seeAll'), () => setTab('installed'), {compact: true}))}
        <View style={styles.cardStack}>{data.installed.slice(0, 2).map(pluginCard)}</View>
      </>}
      {sectionTitle(t('communityTitle'), t('communityBody'))}
      <View style={styles.communityCard}>
        <View style={styles.communityArt}><View style={styles.communityArtBack}/><View style={styles.communityArtFront}><Icon name="tree" size={28} color={colors.white}/></View></View>
        <View style={styles.communityCopy}><Text style={[type.label, styles.communityTitle]}>{query || category !== 'all' ? t('noMatches') : t('communityEmptyTitle')}</Text><Text style={[type.meta, styles.communityBody]}>{t('communityEmptyBody')}</Text></View>
        <Icon name="chevron_right" size={18} color={colors.secondary}/>
      </View>
      <Text style={[type.caption, styles.trustHint]}>{t('trustHint')}</Text>
    </> : null}
  </>;

  const installed = <>
    {sectionTitle(t('installedTitle'), `${data.installed.length} ${t('installed').toLowerCase()} · ${running} ${t('enabled').toLowerCase()}`)}
    <View style={styles.cardStack}>{data.installed.map(pluginCard)}</View>
    {!data.installed.length && !error && emptyState(t('noInstalledTitle'), t('noInstalledBody'), 'tree')}
  </>;

  const updates = data.updates.length ? <>
    {sectionTitle(t('updates'))}
    <View style={styles.cardStack}>{data.updates.map(update => <View key={update.pluginId} style={styles.detailCard}>
      <Text style={[type.label, styles.pluginName]}>{update.name}</Text>
      {detailRow(t('current'), update.installedVersion)}{detailRow(t('candidate'), update.candidateVersion)}
      {update.sourceChanged && <Text style={[type.meta, styles.error]}>{t('changedSource')}</Text>}
      {update.fingerprintChanged && <Text style={[type.meta, styles.error]}>{t('changedFingerprint')}</Text>}
      <View style={styles.inlineActions}>{actionButton(t('upgrade'), () => void run(() => repository.install(update.pluginId, update.candidateVersion)), {primary: true})}{actionButton(t('rollback'), () => undefined, {disabled: true})}</View>
    </View>)}</View>
  </> : emptyState(t('updatesEmptyTitle'), t('updatesEmptyBody'), 'check');

  const sources = <>
    {sectionTitle(t('sourcesTitle'), t('sourcesBody'))}
    <View style={styles.sourceInputRow}><TextInput style={[type.body, styles.sourceInput]} value={sourceUrl} onChangeText={setSourceUrl}
      placeholder={t('sourcePlaceholder')} placeholderTextColor={colors.secondary} autoCapitalize="none" accessibilityLabel={t('addSource')}/>
      {actionButton(t('addSource'), () => void run(() => repository.addSource(sourceUrl.trim())), {primary: true, compact: true})}</View>
    <View style={styles.inlineActions}>{actionButton(t('sync'), () => void run(() => repository.syncSources()))}{actionButton(t('importLocal'), () => void run(() => repository.importLocal()))}</View>
    <View style={styles.cardStack}>{data.sources.map(source => <View key={source.id} style={styles.sourceCard}>
      <View style={styles.sourceIcon}><Icon name="tree" size={20} color={colors.ink}/></View>
      <View style={styles.sourceCopy}><View style={styles.pluginTitleRow}><Text style={[type.label, styles.pluginName]}>{source.name}</Text>{pill(t(source.trust), source.trust === 'local' ? 'success' : 'neutral')}</View>
        <Text style={[type.meta, styles.pluginMeta]}>{source.id === 'local' ? t('localSourceBody') : source.url}</Text>
        {!!source.error && <Text style={[type.caption, styles.error]}>{source.error}</Text>}</View>
      {source.id !== 'local' && actionButton(t('remove'), () => confirm(t('confirmSource'), () => void run(() => repository.removeSource(source.id))), {compact: true})}
    </View>)}</View>
    <Text style={[type.caption, styles.sourceHint]}>{t('sourceHint')}</Text>
  </>;

  const pluginDetail = detail && <>
    <View style={styles.detailHero}>
      <View style={styles.detailHeroTop}>{pluginMark(detail, true)}<View style={styles.detailIdentity}><Text style={[type.heading, styles.detailTitle]}>{detail.name}</Text>
        <Text style={[type.meta, styles.pluginMeta]}>v{detail.version} · {data.sources.find(source => source.id === detail.sourceId)?.name || detail.sourceId}</Text></View></View>
      <Text style={[type.body, styles.detailDescription]}>{detail.description}</Text>
      <View style={styles.chipRow}>{statusPill(detail)}{pill(t(detail.trust))}</View>
    </View>
    <View style={styles.detailCard}>
      {detailRow(t('source'), data.sources.find(source => source.id === detail.sourceId)?.name || detail.sourceId)}
      {detailRow(t('license'), detail.license)}
      {detailRow(t('fingerprint'), detail.fingerprint)}
    </View>
    {detailActions(detail)}
    <Text style={[type.caption, styles.rollbackHint]}>{t('rollbackNext')}</Text>
    <View style={styles.detailGrid}>
      <View style={styles.infoTile}><Text style={[type.caption, styles.detailLabel]}>{t('capabilities')}</Text><Text style={[type.meta, styles.infoValue]}>{detail.capabilities.join(', ') || t('none')}</Text></View>
      <View style={styles.infoTile}><Text style={[type.caption, styles.detailLabel]}>{t('permissions')}</Text><Text style={[type.meta, styles.infoValue]}>{detail.permissions.join(', ') || t('none')}</Text></View>
    </View>
    {actionButton(t(technical ? 'hideTechnical' : 'showTechnical'), () => setTechnical(!technical))}
    {technical && <View style={styles.technicalCard}>{detailRow(t('tools'), detail.tools.join(', ') || t('none'))}{detailRow(t('skills'), detail.skills.join(', ') || t('none'))}{detailRow(t('hooks'), detail.hooks.join(', ') || t('none'))}<Text style={[type.label, styles.technicalTitle]}>{t('manifest')}</Text><Text selectable style={[type.caption, styles.mono]}>{JSON.stringify(detail.manifest, null, 2)}</Text></View>}
  </>;

  return <SafeAreaView style={styles.screen}>
    <View style={styles.topbar}>
      <Pressable accessibilityRole="button" accessibilityLabel={t('back')} onPress={() => {if (detail) setDetail(null); else if (router.canGoBack()) router.back(); else router.replace('/');}}
        style={({pressed}) => [styles.navButton, pressed && styles.pressed]}><Icon name="arrow_left" size={20} color={colors.ink}/></Pressable>
      <View style={styles.topbarTitle}><Text style={[type.caption, styles.eyebrow]}>{t('heroEyebrow')}</Text><Text numberOfLines={1} style={[type.heading, styles.heading]}>{detail ? t('details') : t('title')}</Text></View>
      <Pressable accessibilityRole="button" accessibilityLabel={t('refresh')} disabled={busy} onPress={() => void load()}
        style={({pressed}) => [styles.navButton, pressed && styles.pressed, busy && styles.dim]}><Icon name="refresh" size={18} color={colors.ink}/></Pressable>
    </View>
    {!detail && tabs}
    <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
      {!!error && <View style={styles.errorBox}><View style={styles.errorCopy}><Text style={[type.label, styles.errorTitle]}>{t(pluginErrorKey(error.code))} · {error.code}</Text>{!!error.message && error.message !== error.code && <Text style={[type.meta, styles.error]}>{error.message}</Text>}</View>{actionButton(t('retry'), () => void load(), {compact: true})}</View>}
      {busy && <View style={styles.progressRow}><ActivityIndicator size="small" color={colors.ink}/><Text style={[type.meta, styles.progressText]}>{t('pending')}</Text></View>}
      {loading ? <View style={styles.loading}><ActivityIndicator color={colors.ink}/></View> : detail ? pluginDetail : tab === 'discover' ? discover : tab === 'installed' ? installed : tab === 'updates' ? updates : sources}
      {!detail && !!data.catalog.offline && <Text style={[type.meta, styles.error]}>{t('offline')}</Text>}
    </ScrollView>
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  screen: {flex: 1, backgroundColor: colors.page},
  center: {flex: 1, justifyContent: 'center', backgroundColor: colors.page},
  topbar: {flexDirection: 'row', alignItems: 'center', paddingHorizontal: layout.gutter, paddingTop: 8, paddingBottom: 12, gap: 12},
  navButton: {width: 44, height: 44, borderRadius: 22, alignItems: 'center', justifyContent: 'center', backgroundColor: colors.control},
  topbarTitle: {flex: 1, minWidth: 0, gap: 2},
  eyebrow: {color: colors.secondary, letterSpacing: 1.4, textTransform: 'uppercase'},
  heading: {fontSize: 25, lineHeight: 30},
  tabsViewport: {height: 52, flexGrow: 0, flexShrink: 0},
  tabs: {paddingHorizontal: layout.gutter, paddingBottom: 12, gap: 8, alignItems: 'center'},
  content: {paddingHorizontal: layout.gutter, paddingBottom: 44, gap: 18},
  hero: {backgroundColor: colors.ink, borderRadius: 26, padding: 22, paddingTop: 24, minHeight: 206, justifyContent: 'flex-end', overflow: 'hidden', gap: 8},
  heroOrangeOrb: {position: 'absolute', width: 150, height: 150, borderRadius: 75, top: -92, right: -26, backgroundColor: colors.brand, opacity: 0.92},
  heroSageOrb: {position: 'absolute', width: 78, height: 78, borderRadius: 39, top: -58, right: 88, backgroundColor: colors.sage},
  heroLavenderOrb: {position: 'absolute', width: 58, height: 58, borderRadius: 29, top: -62, right: 50, backgroundColor: colors.lavender, opacity: 0.72},
  heroEyebrow: {color: 'rgba(255,255,255,0.66)', letterSpacing: 1.8, textTransform: 'uppercase'},
  heroTitle: {color: colors.white, fontSize: 33, lineHeight: 37, maxWidth: 310},
  heroBody: {color: 'rgba(255,255,255,0.76)', maxWidth: 320},
  heroMeta: {marginTop: 8, flexDirection: 'row', alignItems: 'center', gap: 7},
  heroLiveDot: {width: 7, height: 7, borderRadius: 4, backgroundColor: colors.success},
  heroMetaText: {color: 'rgba(255,255,255,0.72)'},
  searchBox: {minHeight: 54, borderRadius: 18, borderWidth: 1, borderColor: colors.border, backgroundColor: colors.surface, paddingHorizontal: 16, flexDirection: 'row', alignItems: 'center', gap: 10},
  searchInput: {flex: 1, paddingVertical: 14, color: colors.ink},
  clearSearch: {width: 32, height: 32, borderRadius: 16, backgroundColor: colors.control, alignItems: 'center', justifyContent: 'center'},
  chips: {gap: 8},
  sectionTitleRow: {flexDirection: 'row', alignItems: 'flex-end', gap: 12},
  sectionCopy: {flex: 1, gap: 3},
  sectionTitle: {fontSize: 20, lineHeight: 25},
  sectionBody: {color: colors.secondary},
  cardStack: {gap: 12},
  pluginCard: {backgroundColor: colors.surface, borderColor: colors.border, borderWidth: 1, borderRadius: 20, padding: 16, gap: 13, shadowColor: '#000', shadowOpacity: 0.035, shadowRadius: 14, shadowOffset: {width: 0, height: 7}, elevation: 1},
  cardPressed: {opacity: 0.74, transform: [{scale: 0.995}]},
  pluginHeader: {flexDirection: 'row', alignItems: 'center', gap: 12},
  pluginIdentity: {flex: 1, minWidth: 0, gap: 4},
  pluginTitleRow: {flexDirection: 'row', alignItems: 'center', gap: 8},
  pluginName: {flexShrink: 1},
  pluginMeta: {color: colors.secondary},
  pluginDescription: {color: colors.secondary},
  chevron: {width: 28, height: 28, borderRadius: 14, backgroundColor: colors.control, alignItems: 'center', justifyContent: 'center'},
  pluginMark: {width: 54, height: 54, borderRadius: 16, alignItems: 'center', justifyContent: 'center', overflow: 'hidden'},
  pluginMarkLarge: {width: 72, height: 72, borderRadius: 22},
  markCutout: {position: 'absolute', width: 44, height: 44, borderRadius: 22, right: -17, bottom: -17, backgroundColor: 'rgba(255,255,255,0.38)'},
  markLetter: {fontFamily: 'TakuDisplay', fontSize: 23, lineHeight: 27, color: colors.ink},
  markLetterLarge: {fontSize: 31, lineHeight: 36},
  statusPill: {flexDirection: 'row', alignItems: 'center'},
  pill: {minHeight: 27, borderRadius: 14, paddingHorizontal: 9, backgroundColor: colors.control, flexDirection: 'row', alignItems: 'center', gap: 5, alignSelf: 'flex-start'},
  pillSuccess: {backgroundColor: colors.sage},
  pillBrand: {backgroundColor: colors.peach},
  pillDot: {width: 6, height: 6, borderRadius: 3},
  dotSuccess: {backgroundColor: colors.success},
  dotBrand: {backgroundColor: colors.brand},
  chipRow: {flexDirection: 'row', flexWrap: 'wrap', gap: 6, alignItems: 'center'},
  pillText: {color: colors.ink},
  action: {minHeight: 48, borderRadius: 14, paddingHorizontal: 16, alignItems: 'center', justifyContent: 'center', backgroundColor: colors.control},
  actionCompact: {minHeight: 38, borderRadius: 19, paddingHorizontal: 14},
  actionPrimary: {backgroundColor: colors.ink},
  actionSelected: {backgroundColor: colors.ink},
  actionText: {color: colors.ink},
  actionPrimaryText: {color: colors.white},
  actionSelectedText: {color: colors.white},
  pressed: {opacity: 0.72},
  dim: {opacity: 0.38},
  communityCard: {borderRadius: 20, backgroundColor: colors.sage, padding: 16, flexDirection: 'row', alignItems: 'center', gap: 13},
  communityArt: {width: 64, height: 64, justifyContent: 'center', alignItems: 'center'},
  communityArtBack: {position: 'absolute', width: 48, height: 48, borderRadius: 16, backgroundColor: colors.peach, transform: [{rotate: '-11deg'}]},
  communityArtFront: {width: 48, height: 48, borderRadius: 16, backgroundColor: colors.ink, alignItems: 'center', justifyContent: 'center', transform: [{rotate: '8deg'}]},
  communityCopy: {flex: 1, gap: 4},
  communityTitle: {fontSize: 17},
  communityBody: {color: 'rgba(0,0,0,0.62)'},
  trustHint: {color: colors.secondary, marginTop: -8},
  emptyCard: {alignItems: 'center', paddingVertical: 38, paddingHorizontal: 24, borderRadius: 22, backgroundColor: colors.control, gap: 9},
  emptyIcon: {width: 46, height: 46, borderRadius: 23, backgroundColor: colors.surface, alignItems: 'center', justifyContent: 'center', marginBottom: 3},
  emptyTitle: {textAlign: 'center'},
  emptyBody: {textAlign: 'center', color: colors.secondary, maxWidth: 280},
  detailHero: {backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border, borderRadius: 24, padding: 20, gap: 16},
  detailHeroTop: {flexDirection: 'row', alignItems: 'center', gap: 14},
  detailIdentity: {flex: 1, minWidth: 0, gap: 4},
  detailTitle: {fontSize: 27, lineHeight: 32},
  detailDescription: {color: colors.secondary},
  detailCard: {backgroundColor: colors.surface, borderColor: colors.border, borderWidth: 1, borderRadius: 20, paddingHorizontal: 16, paddingVertical: 5},
  detailRow: {paddingVertical: 12, borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: colors.border, gap: 4},
  detailLabel: {color: colors.secondary, textTransform: 'uppercase', letterSpacing: 0.7},
  detailValue: {color: colors.ink},
  detailActions: {flexDirection: 'row', flexWrap: 'wrap', gap: 8},
  rollbackHint: {color: colors.secondary, marginTop: -10},
  detailGrid: {flexDirection: 'row', gap: 10},
  infoTile: {flex: 1, minHeight: 90, borderRadius: 18, backgroundColor: colors.control, padding: 14, gap: 7},
  infoValue: {color: colors.ink},
  technicalCard: {backgroundColor: colors.control, borderRadius: 18, padding: 14, gap: 2},
  technicalTitle: {marginTop: 10, marginBottom: 5},
  mono: {fontFamily: 'TakuCode', color: colors.secondary},
  inlineActions: {flexDirection: 'row', flexWrap: 'wrap', gap: 8},
  sourceInputRow: {flexDirection: 'row', alignItems: 'center', gap: 8},
  sourceInput: {flex: 1, minHeight: 48, paddingHorizontal: 14, borderRadius: 14, borderWidth: 1, borderColor: colors.border, backgroundColor: colors.surface},
  sourceCard: {flexDirection: 'row', alignItems: 'center', gap: 12, backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border, borderRadius: 18, padding: 14},
  sourceIcon: {width: 44, height: 44, borderRadius: 14, backgroundColor: colors.sage, alignItems: 'center', justifyContent: 'center'},
  sourceCopy: {flex: 1, gap: 4},
  sourceHint: {color: colors.secondary},
  errorBox: {backgroundColor: colors.errorSurface, borderRadius: 18, padding: 14, flexDirection: 'row', alignItems: 'center', gap: 12},
  errorCopy: {flex: 1, gap: 3},
  errorTitle: {color: colors.error},
  error: {color: colors.error},
  progressRow: {flexDirection: 'row', alignItems: 'center', gap: 8},
  progressText: {color: colors.secondary},
  loading: {paddingVertical: 60, alignItems: 'center'},
});
