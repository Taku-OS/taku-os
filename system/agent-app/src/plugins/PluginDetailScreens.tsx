/**
 * Plugin details (Figma 4-2, node 1482:2091) and plugin settings (4-3, node 1482:2190), both read from
 * the plugin manager's record. Details shows the skills and information the manifest provides; the
 * Settings button opens settings, where the plugin is switched on or off and its declared
 * permissions and skills are listed. Fields a manifest does not carry are left out.
 */
import React, {useState} from 'react';
import {ActivityIndicator, Pressable, ScrollView, StyleSheet, Switch, Text, View} from 'react-native';
import {useLocalSearchParams, useRouter} from 'expo-router';
import {useLocales} from 'expo-localization';
import {useSafeAreaInsets} from 'react-native-safe-area-context';
import {GlassMenu, PageHeader} from '../chat/ChatOverlays';
import {chatColors, chatType, themedStyles, useChatAppearance} from '../chat/chatTheme';
import {chatText} from '../chat/chatText';
import {useChatBackdrop} from '../chat/useChatBackdrop';
import {languageFor} from '../i18n';
import {containsChinese} from '../theme';
import {useTakuFonts} from '../theme/useTakuFonts';
import {PluginTile, SectionTitle, usePluginActions} from './PluginUi';
import {usePluginDetails} from './repository';
import type {PluginDetails} from './contracts';

const SKILL_PREVIEW = 10;

function usePluginPage() {
  useChatAppearance();
  useChatBackdrop();
  const router = useRouter();
  const {pluginId = ''} = useLocalSearchParams<{pluginId?: string}>();
  const language = languageFor(useLocales()[0]?.languageTag);
  const insets = useSafeAreaInsets();
  const [fonts, fontError] = useTakuFonts();
  const plugin = usePluginDetails(pluginId);
  const back = () => { if (router.canGoBack()) router.back(); else router.replace('/plugin-market'); };
  return {router, pluginId, language, t: chatText(language), insets, ready: !!fonts || !!fontError, plugin, back};
}

function Identity({plugin, language, card}: {plugin: PluginDetails; language: 'en' | 'zh'; card?: boolean}) {
  return <View style={[styles.identity, card && styles.card]}>
    <PluginTile plugin={plugin} kind="hero"/>
    <View style={styles.identityText}>
      <Text style={[chatType.headingXl(containsChinese(plugin.name)), styles.primary]}>{plugin.name}</Text>
      {!!plugin.description && <Text style={[chatType.bodySm(language === 'zh' || containsChinese(plugin.description)), styles.tertiary]}>{plugin.description}</Text>}
    </View>
  </View>;
}

function PageState({plugin, language}: {plugin: ReturnType<typeof usePluginDetails>; language: 'en' | 'zh'}) {
  const t = chatText(language);
  if (plugin.loading) return <ActivityIndicator color={chatColors.textTertiary} style={styles.state}/>;
  return <Text style={[chatType.bodyMd(language === 'zh'), styles.error, styles.state]}>{plugin.error === 'NOT_FOUND' ? t('pluginMissing') : t('pluginsLoadFailed')}</Text>;
}

/** Figma 4-2: identity, skill chips (ten, then +N), and Information rows. */
export function PluginDetailScreen() {
  const {router, language, t, insets, ready, plugin, back} = usePluginPage();
  const [allSkills, setAllSkills] = useState(false);
  if (!ready) return <View style={styles.root}/>;
  const details = plugin.details;
  const skills = details ? (allSkills ? details.skills : details.skills.slice(0, SKILL_PREVIEW)) : [];
  const information = details ? [[t('developers'), details.author], [t('version'), details.version], [t('license'), details.license]]
    .filter(([, value]) => !!value && value !== 'unknown') : [];
  return <View style={styles.root}>
    <ScrollView testID="plugin-detail" contentContainerStyle={[styles.content, {paddingTop: insets.top + 64 + 8, paddingBottom: insets.bottom + 32}]}>
      {details ? <>
        <Identity plugin={details} language={language}/>
        <View style={styles.section}>
          <SectionTitle title={t('skills')} language={language}/>
          {skills.length ? <View style={styles.chips}>
            {skills.map(skill => <View key={skill} style={styles.chip}><Text style={[chatType.bodyLg(containsChinese(skill)), styles.secondary]}>{skill}</Text></View>)}
            {!allSkills && details.skills.length > SKILL_PREVIEW && <Pressable accessibilityRole="button" accessibilityLabel={t('seeAll')} onPress={() => setAllSkills(true)}
              style={({pressed}) => [styles.chip, pressed && styles.pressed]}>
              <Text style={[chatType.bodyLg(), styles.secondary]}>+{details.skills.length - SKILL_PREVIEW}</Text>
            </Pressable>}
          </View> : <Text style={[chatType.bodyMd(language === 'zh'), styles.tertiary]}>{t('noSkills')}</Text>}
        </View>
        {information.length > 0 && <View style={styles.section}>
          <SectionTitle title={t('information')} language={language}/>
          <View style={styles.infoRows}>{information.map(([label, value], index) => <React.Fragment key={label}>
            {index > 0 && <View style={styles.divider}/>}
            <View style={styles.infoRow}>
              <Text style={[chatType.bodyLg(language === 'zh'), styles.tertiary]}>{label}</Text>
              <Text selectable numberOfLines={1} style={[chatType.bodyLg(containsChinese(value)), styles.secondary, styles.infoValue]}>{value}</Text>
            </View>
          </React.Fragment>)}</View>
        </View>}
      </> : <PageState plugin={plugin} language={language}/>}
    </ScrollView>
    <PageHeader top={insets.top} title={details?.name ?? ''} left={{icon: 'chevron', label: t('back'), onPress: back, testID: 'plugin-back'}}
      right={details ? {icon: 'settings', label: t('pluginSettings'), testID: 'plugin-settings',
        onPress: () => router.push({pathname: '/plugin-settings', params: {pluginId: details.id}})} : undefined}/>
  </View>;
}

/** Figma 4-3: identity card, the Enabled switch and declared permissions (read-only), skills; More holds Reload / Uninstall. */
export function PluginSettingsScreen() {
  const {router, language, t, insets, ready, plugin, back} = usePluginPage();
  const actions = usePluginActions({language, onChanged: () => { void plugin.refresh(); }, onUninstalled: () => router.replace('/plugin-market')});
  const [menu, setMenu] = useState(false);
  if (!ready) return <View style={styles.root}/>;
  const details = plugin.details;
  return <View style={styles.root}>
    <ScrollView testID="plugin-settings-page" contentContainerStyle={[styles.content, styles.settings, {paddingTop: insets.top + 64 + 8, paddingBottom: insets.bottom + 32}]}>
      {details ? <>
        <Identity plugin={details} language={language} card/>
        <View style={[styles.card, styles.switchRow]}>
          <Text style={[chatType.bodyLgMedium(language === 'zh'), styles.tertiary, styles.grow]}>{t('pluginEnabled')}</Text>
          <Switch testID="plugin-enabled" accessibilityLabel={t('pluginEnabled')} value={details.enabled === true} disabled={actions.busy}
            onValueChange={enabled => { void actions.setEnabled(details, enabled); }}/>
        </View>
        {!!actions.notice && <Text accessibilityLiveRegion="polite" style={[chatType.bodyMd(language === 'zh'), styles.error]}>{actions.notice}</Text>}
        <ListCard title={t('permissions')} items={details.permissions} empty={t('noPermissions')} language={language}/>
        <ListCard title={t('skills')} items={details.skills} empty={t('noSkills')} language={language}/>
      </> : <PageState plugin={plugin} language={language}/>}
    </ScrollView>
    <PageHeader top={insets.top} title={details?.name ?? ''} left={{icon: 'chevron', label: t('back'), onPress: back, testID: 'plugin-settings-back'}}
      right={details ? {icon: 'more', label: t('moreActions'), onPress: () => setMenu(true), testID: 'plugin-settings-more'} : undefined}/>
    {menu && details && <>
      <Pressable style={StyleSheet.absoluteFill} accessibilityLabel={t('done')} onPress={() => setMenu(false)}/>
      <GlassMenu testID="plugin-settings-menu" style={[styles.menu, {top: insets.top + 12}]} items={[
        {key: 'chat', label: t('chatWithTaku'), icon: 'aiEdit', disabled: details.enabled !== true, onPress: () => { setMenu(false); actions.chat(details); }},
        {key: 'reload', label: t('reload'), onPress: () => { setMenu(false); void actions.reload(details); }},
        {key: 'uninstall', label: t('uninstall'), icon: 'trash', destructive: true, dividerBefore: true, onPress: () => { setMenu(false); actions.uninstall(details); }},
      ]}/>
    </>}
    {actions.dialog}
  </View>;
}

/** A Surface/50 card of read-only rows under a Space Grotesk title. */
function ListCard({title, items, empty, language}: {title: string; items: readonly string[]; empty: string; language: 'en' | 'zh'}) {
  return <View style={styles.section}>
    <SectionTitle title={title} language={language}/>
    <View style={[styles.card, styles.listCard]}>
      {items.length ? items.map((item, index) => <React.Fragment key={item}>
        {index > 0 && <View style={styles.divider}/>}
        <Text style={[chatType.bodyLg(containsChinese(item)), styles.secondary]}>{item}</Text>
      </React.Fragment>) : <Text style={[chatType.bodyLg(language === 'zh'), styles.tertiary]}>{empty}</Text>}
    </View>
  </View>;
}

const styles = themedStyles(() => StyleSheet.create({
  root: {flex: 1},
  content: {paddingHorizontal: 16, gap: 32},
  settings: {gap: 24},
  section: {gap: 16},
  identity: {gap: 16},
  identityText: {gap: 8},
  // Figma 4-3 cards: Surface/50, 24 dp corners, 12 dp padding.
  card: {backgroundColor: chatColors.reactionFill, borderRadius: 24, padding: 12},
  switchRow: {flexDirection: 'row', alignItems: 'center', minHeight: 48},
  listCard: {gap: 16},
  chips: {flexDirection: 'row', flexWrap: 'wrap', gap: 8},
  chip: {borderWidth: 1, borderColor: chatColors.fillControl, borderRadius: 9999, paddingHorizontal: 16, paddingVertical: 8},
  infoRows: {gap: 8},
  infoRow: {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 16, paddingVertical: 12},
  infoValue: {flexShrink: 1, textAlign: 'right'},
  divider: {height: 1, backgroundColor: chatColors.fillControl},
  menu: {position: 'absolute', right: 16},
  state: {paddingVertical: 24, alignSelf: 'center'},
  pressed: {opacity: 0.7},
  grow: {flex: 1},
  primary: {color: chatColors.textPrimary},
  secondary: {color: chatColors.textSecondary},
  tertiary: {color: chatColors.textTertiary},
  error: {color: chatColors.textError},
}));
