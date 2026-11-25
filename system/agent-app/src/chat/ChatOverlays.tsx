/**
 * Shared Chat Polish surfaces: the glass action menu (3-1-2, 3-2-2), centred dialogs (1-2 rename,
 * 3-3 delete) and the 40 dp pill buttons they and the panel detail (3-2-1) use.
 */
import React, {useState, type ReactNode} from 'react';
import {ActivityIndicator, KeyboardAvoidingView, Modal, Pressable, StyleSheet, Text, TextInput, View, type StyleProp, type ViewStyle} from 'react-native';
import {ChromeIcon, type ChromeIconName} from './ChromeIcon';
import {chatColors, chatType, themedStyles} from './chatTheme';
import {chatText} from './chatText';
import {containsChinese} from '../theme';

type Language = 'en' | 'zh';

export type MenuItem = {key: string; label: string; icon: ChromeIconName; destructive?: boolean; dividerBefore?: boolean; disabled?: boolean; onPress: () => void};

/** 182 dp glass card, 8 dp padding, 24 dp corners; 20 dp icon + Body/lg rows, a Surface/200 rule before Delete. */
export function GlassMenu({items, style, testID}: {items: readonly MenuItem[]; style?: StyleProp<ViewStyle>; testID?: string}) {
  return <View testID={testID} accessibilityRole="menu" style={[styles.menu, style]}>
    {items.map(item => <React.Fragment key={item.key}>
      {item.dividerBefore && <View style={styles.divider}/>}
      <Pressable testID={`menu-${item.key}`} accessibilityRole="menuitem" accessibilityState={{disabled: !!item.disabled}} disabled={item.disabled}
        onPress={item.onPress} style={({pressed}) => [styles.menuRow, pressed && styles.pressed, item.disabled && styles.disabled]}>
        <ChromeIcon name={item.icon} size={20}/>
        <Text numberOfLines={1} style={[chatType.bodyLg(containsChinese(item.label)), item.destructive ? styles.destructive : styles.primary]}>{item.label}</Text>
      </Pressable>
    </React.Fragment>)}
  </View>;
}

/** A 40 dp glass circle around a 24 dp icon (title rows, sidebar). */
export function RoundButton({icon, label, onPress, size = 40, testID, disabled}: {icon: ChromeIconName; label: string; onPress: () => void; size?: number; testID?: string; disabled?: boolean}) {
  return <Pressable testID={testID} accessibilityRole="button" accessibilityLabel={label} accessibilityState={{disabled: !!disabled}} disabled={disabled}
    onPress={onPress} hitSlop={4} style={({pressed}) => [styles.round, {width: size, height: size, borderRadius: size / 2}, pressed && styles.pillPressed, disabled && styles.disabled]}>
    <ChromeIcon name={icon}/>
  </Pressable>;
}

/** The 64 dp title row of the Panels pages (3-1, 3-2-1): a round button each side, Body/lg title centred. */
export function PageHeader({top, title, left, right, children}: {
  top: number; title: string; left: {icon: ChromeIconName; label: string; onPress: () => void; testID?: string};
  right?: {icon: ChromeIconName; label: string; onPress: () => void; testID?: string}; children?: ReactNode;
}) {
  return <View style={[styles.header, {paddingTop: top, height: top + 64}]} pointerEvents="box-none">
    <Text accessibilityRole="header" numberOfLines={1} style={[chatType.bodyLg(containsChinese(title)), styles.primary, styles.headerTitle, {top: top + 20}]}>{title}</Text>
    <RoundButton {...left}/>
    {right ? <RoundButton {...right}/> : <View style={styles.headerSpacer}/>}
    {children}
  </View>;
}

export type PillVariant = 'secondary' | 'primary' | 'destructive';

/** Figma "Medium" button: 40 dp pill; Surface/200 (secondary), Main/Surface/Default (primary), red text (destructive). */
export function PillButton({label, variant = 'secondary', icon, busy, disabled, onPress, style, testID}: {
  label: string; variant?: PillVariant; icon?: ChromeIconName; busy?: boolean; disabled?: boolean; onPress: () => void; style?: StyleProp<ViewStyle>; testID?: string;
}) {
  const primary = variant === 'primary';
  const color = primary ? chatColors.pillText : variant === 'destructive' ? chatColors.destructive : chatColors.textPrimary;
  return <Pressable testID={testID} accessibilityRole="button" accessibilityLabel={label} accessibilityState={{disabled: !!disabled || !!busy, busy: !!busy}}
    disabled={disabled || busy} onPress={onPress}
    style={({pressed}) => [styles.pill, primary && styles.pillPrimary, pressed && styles.pillPressed, (disabled || busy) && styles.disabled, style]}>
    {busy ? <ActivityIndicator size="small" color={color}/> : <>
      {icon && <ChromeIcon name={icon} size={20} color={color}/>}
      <Text numberOfLines={1} style={[chatType.bodyLg(containsChinese(label)), {color}]}>{label}</Text>
    </>}
  </Pressable>;
}

/** 1-2 / 3-3: a 332 dp glass card with 40 dp corners over the Content/800 scrim. */
export function ChatDialog({title, children, onClose, testID}: {title: string; children: ReactNode; onClose: () => void; testID?: string}) {
  return <Modal transparent animationType="fade" statusBarTranslucent navigationBarTranslucent onRequestClose={onClose}>
    <KeyboardAvoidingView behavior="padding" style={styles.scrim}>
      <Pressable style={StyleSheet.absoluteFill} accessibilityLabel={title} onPress={onClose}/>
      <View testID={testID} accessibilityViewIsModal style={styles.dialog}>
        <View pointerEvents="none" style={styles.dialogGlass}/>
        <Text accessibilityRole="header" style={[chatType.bodyLgMedium(containsChinese(title)), styles.primary, styles.dialogTitle]}>{title}</Text>
        <View style={styles.dialogBody}>{children}</View>
      </View>
    </KeyboardAvoidingView>
  </Modal>;
}

export function DialogActions({children}: {children: ReactNode}) {
  return <View style={styles.dialogActions}>{children}</View>;
}

/** Figma 1-2 rename: the current name in a Content/500 field, Cancel and Confirm. */
export function RenameDialog({title, label, initial, language, busy, error, onCancel, onConfirm}: {
  title: string; label: string; initial: string; language: Language; busy: boolean; error: string; onCancel: () => void; onConfirm: (value: string) => void;
}) {
  const t = chatText(language);
  const [value, setValue] = useState(initial);
  const valid = !!value.trim() && value.trim().length <= 120;
  return <ChatDialog title={title} onClose={onCancel} testID="rename-dialog">
    <TextInput testID="rename-input" value={value} onChangeText={setValue} autoFocus selectTextOnFocus maxLength={120} accessibilityLabel={label}
      editable={!busy} returnKeyType="done" onSubmitEditing={() => { if (valid) onConfirm(value.trim()); }}
      style={[chatType.bodyLg(containsChinese(value)), styles.field]} placeholderTextColor={chatColors.placeholder}/>
    {!!error && <Text accessibilityLiveRegion="polite" style={[chatType.bodyMd(language === 'zh'), styles.error]}>{error}</Text>}
    <DialogActions>
      <PillButton label={t('cancel')} onPress={onCancel} style={styles.grow}/>
      <PillButton testID="rename-confirm" label={t('confirm')} variant="primary" busy={busy} disabled={!valid} onPress={() => onConfirm(value.trim())} style={styles.grow}/>
    </DialogActions>
  </ChatDialog>;
}

/** Figma 3-3: "<name> panel" in Surface/900, then the warning in Surface/700; Cancel and a red Delete. */
export function DeleteDialog({title, name, body, language, busy, error, onCancel, onConfirm}: {
  title: string; name: string; body: string; language: Language; busy: boolean; error: string; onCancel: () => void; onConfirm: () => void;
}) {
  const t = chatText(language);
  return <ChatDialog title={title} onClose={onCancel} testID="delete-dialog">
    <Text style={[chatType.bodyLg(language === 'zh' || containsChinese(name)), styles.secondary]}>
      <Text style={styles.primary}>{name} </Text>{body}
    </Text>
    {!!error && <Text accessibilityLiveRegion="polite" style={[chatType.bodyMd(language === 'zh'), styles.error]}>{error}</Text>}
    <DialogActions>
      <PillButton label={t('cancel')} onPress={onCancel} style={styles.grow}/>
      <PillButton testID="delete-confirm" label={t('panelDelete')} variant="destructive" busy={busy} onPress={onConfirm} style={styles.grow}/>
    </DialogActions>
  </ChatDialog>;
}

const styles = themedStyles(() => StyleSheet.create({
  round: {alignItems: 'center', justifyContent: 'center', backgroundColor: chatColors.glass},
  header: {position: 'absolute', left: 0, right: 0, top: 0, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 16},
  headerTitle: {position: 'absolute', left: 72, right: 72, textAlign: 'center'},
  headerSpacer: {width: 40, height: 40},
  menu: {width: 182, padding: 8, gap: 4, borderRadius: 24, backgroundColor: chatColors.glass, overflow: 'hidden'},
  menuRow: {flexDirection: 'row', alignItems: 'center', gap: 8, paddingHorizontal: 8, paddingVertical: 8, borderRadius: 9999},
  divider: {height: 1, backgroundColor: chatColors.fillControl},
  pill: {height: 40, flexDirection: 'row', gap: 4, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 16, borderRadius: 9999,
    backgroundColor: chatColors.fillControl},
  pillPrimary: {backgroundColor: chatColors.pill},
  pillPressed: {opacity: 0.75},
  scrim: {flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: chatColors.scrim, padding: 24},
  dialog: {width: 332, maxWidth: '100%', borderRadius: 40, paddingBottom: 16, overflow: 'hidden', backgroundColor: chatColors.fillControl},
  dialogGlass: {...StyleSheet.absoluteFill, backgroundColor: chatColors.glass},
  dialogTitle: {textAlign: 'center', paddingHorizontal: 16, paddingVertical: 16},
  dialogBody: {paddingHorizontal: 16, gap: 24},
  dialogActions: {flexDirection: 'row', gap: 8},
  field: {color: chatColors.textSecondary, backgroundColor: chatColors.fieldInset, borderRadius: 9999, paddingHorizontal: 12, paddingVertical: 12},
  pressed: {backgroundColor: chatColors.fillControl},
  disabled: {opacity: 0.45},
  primary: {color: chatColors.textPrimary},
  secondary: {color: chatColors.textTertiary},
  destructive: {color: chatColors.destructive},
  error: {color: chatColors.textError},
  grow: {flex: 1},
}));
