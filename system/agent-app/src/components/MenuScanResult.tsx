import React, {useCallback, useMemo, useState} from 'react';
import {Linking, Pressable, ScrollView, StyleSheet, Text, View} from 'react-native';
import type {SharedValue} from 'react-native-reanimated';
import {chatText} from '../chat/chatText';
import {chatColors, chatMetrics, chatType} from '../chat/chatTheme';
import type {Language} from '../i18n';
import {menuDishKey} from '../model/menuEvidence';
import {formatMenuPrice, type MenuBox, type MenuDish} from '../model/menuScan';
import {applyMenuScanAction, type MenuScanAction, type MenuScanSnapshot} from '../native/agent';
import {Icon} from './Icon';
import {MenuDishCarousel} from './MenuDishCarousel';
import {MenuDishDetails} from './MenuDishDetails';
import {MenuScanPhoto, type MenuPhotoGeometry} from './MenuScanPhoto';
import {MenuScanSheet, type MenuDishLayer} from './MenuScanSheet';

export type ScannedMenu = {uri: string; width: number; height: number; snapshot?: MenuScanSnapshot; textBoxes?: readonly MenuBox[]};
type OrderItem = MenuDish & {quantity: number};
function OrderAction({label, onPress, testID, chinese, secondary, disabled, dishAction}: {
  label: string; onPress: () => void; testID?: string; chinese: boolean;
  secondary?: boolean; disabled?: boolean; dishAction?: boolean;
}) {
  return <Pressable testID={testID} accessibilityRole="button" accessibilityLabel={label}
    accessibilityState={{disabled: Boolean(disabled)}} disabled={disabled}
    onPress={onPress} style={[styles.action, secondary ? styles.actionSecondary : styles.actionPrimary, dishAction && styles.dishAction, disabled && styles.disabled]}>
    {dishAction && !secondary && !disabled && <Text importantForAccessibility="no" style={[chatType.bodyLg(chinese), styles.light]}>+</Text>}
    <Text style={[chatType.bodyLg(chinese), styles.center, !secondary && styles.light]}>{label}</Text>
  </Pressable>;
}

function QuantityStep({item, delta, onChange, language}: {
  item: OrderItem; delta: -1 | 1; onChange: (quantity: number) => void; language: Language;
}) {
  const next = item.quantity + delta;
  const disabled = next < 1 || next > 99;
  const label = chatText(language)(delta > 0 ? 'menuIncrease' : 'menuDecrease');
  return <Pressable accessibilityRole="button" accessibilityLabel={label + ' ' + item.name} accessibilityState={{disabled}}
    disabled={disabled} onPress={() => onChange(next)} style={[styles.step, disabled && styles.disabled]}>
    <Text style={chatType.headingLg(language === 'zh')}>{delta > 0 ? '+' : '−'}</Text>
  </Pressable>;
}

const emptyDishes: readonly MenuDish[] = [];

export function MenuScanResult({menu, snapshot, markersVisible, recommendationsVisible, islandBottom, leaving, insets, language,
  dishSwipeVelocity, onPhotoLoad, onPhotoError, onDishLayerChange, onRetake}: {
  menu: ScannedMenu;
  snapshot?: MenuScanSnapshot;
  markersVisible: boolean;
  recommendationsVisible: boolean;
  islandBottom: number;
  leaving: boolean;
  insets: {top: number; bottom: number};
  language: Language;
  dishSwipeVelocity: SharedValue<number>;
  onPhotoLoad: () => void;
  onPhotoError: () => void;
  onDishLayerChange: (layer: MenuDishLayer | undefined) => void;
  onRetake: () => void;
}) {
  const ct = chatText(language);
  const chinese = language === 'zh';
  const [geometry, setGeometry] = useState<MenuPhotoGeometry>();
  const [dishLayer, setDishLayer] = useState<MenuDishLayer>();
  const updateDishLayer = useCallback((layer: MenuDishLayer | undefined) => {
    setDishLayer(layer);
    onDishLayerChange(layer);
  }, [onDishLayerChange]);
  const [error, setError] = useState<'menuOrderFailed' | 'menuLinkFailed'>();
  const dishes = snapshot?.dishes ?? emptyDishes;
  const selectedDishId = snapshot?.selectedDishId;
  const view = snapshot?.view ?? 'menu';
  const venue = snapshot?.venue;
  const dishIndex = dishes.findIndex(item => item.id === selectedDishId);
  const dish = dishes[dishIndex];
  const badgeKind = snapshot?.suggestionView === 'lighter' ? 'lighter' : 'picks';
  const order = useMemo(() => snapshot?.orderItems.flatMap(({dishId, quantity}) => {
    const item = dishes.find(entry => entry.id === dishId);
    return item ? [{...item, quantity}] : [];
  }) ?? [], [dishes, snapshot?.orderItems]);
  const addedDishIds = useMemo(() => new Set(order.map(item => item.id)), [order]);
  const badges = useMemo(() => {
    const labels = new Map<string, {label: string; added?: boolean}>();
    const addedLabel = chatText(language)('menuAddedBadge');
    if (recommendationsVisible) for (const item of dishes) {
      const added = addedDishIds.has(item.id);
      const label = added ? addedLabel : item.suggestions?.[badgeKind]?.[language];
      if (label) labels.set(item.id, {label, added});
    }
    return labels;
  }, [addedDishIds, badgeKind, dishes, language, recommendationsVisible]);
  const pickup = venue?.pickupUrl && order.length > 0 && order.every(item => venue.pickupDishNames?.includes(menuDishKey(item.name)))
    ? {url: venue.pickupUrl, name: venue.name, address: venue.address} : undefined;
  const showStaff = view === 'staff';
  const hideMenu = Boolean(dish || view !== 'menu');
  const photoHidden = hideMenu || Boolean(dishLayer) || leaving;
  const box = dish?.box;
  const anchor = useMemo(() => box && geometry ? {
    left: geometry.left + box.left * geometry.scale,
    top: geometry.top + box.top * geometry.scale,
    width: (box.right - box.left) * geometry.scale,
    height: (box.bottom - box.top) * geometry.scale,
  } : undefined, [box, geometry]);
  const added = dish !== undefined && addedDishIds.has(dish.id);
  const dispatch = (action: MenuScanAction, dishId?: string, count?: number) => {
    if (!snapshot) return false;
    try {
      applyMenuScanAction(action, snapshot.scanId, dishId, count);
      setError(undefined);
      return true;
    } catch {
      setError('menuOrderFailed');
      return false;
    }
  };
  const closeSheet = () => dispatch('back');
  const open = (url: string) => {
    setError(undefined);
    void Linking.openURL(url).catch(() => setError('menuLinkFailed'));
  };

  return <View style={styles.root}>
    <MenuScanPhoto uri={menu.uri} width={menu.width} height={menu.height} textBoxes={menu.textBoxes}
      dishes={markersVisible ? dishes : emptyDishes} badges={badges} islandBottom={islandBottom}
      selectedDishId={selectedDishId ?? dishLayer?.dishId} dishPhase={dishLayer?.phase}
      hidden={photoHidden} label={ct('menuPhoto')}
      onLoad={onPhotoLoad} onError={onPhotoError}
      onSelect={dishId => dispatch('select', dishId)} onGeometry={setGeometry}/>
    {!photoHidden && markersVisible && <View style={[styles.footer, {bottom: insets.bottom + 12}]} pointerEvents="box-none">
      {(error || !dishes.length) && <Text accessibilityLiveRegion="polite" style={[chatType.bodyMd(chinese), styles.footerPill]}>
        {ct(error ?? 'menuNoDishes')}
      </Text>}
      <Pressable testID="menu-retake" accessibilityRole="button" onPress={onRetake} style={styles.retake}>
        <Text style={[chatType.bodyMdMedium(chinese), styles.light]}>{ct('retakePhoto')}</Text>
      </Pressable>
    </View>}
    <MenuScanSheet visible={hideMenu} surfaceKey={dish ? 'dish' : showStaff ? 'staff' : 'order'} anchor={anchor} insets={insets}
      onDismiss={closeSheet} dishId={dish?.id} onDishLayerChange={updateDishLayer}
      hero={dish && (layout => <MenuDishCarousel layout={layout} artwork={snapshot?.artwork} dishes={dishes}
        selectedIndex={dishIndex} language={language} onSelect={dishId => dispatch('select', dishId)}
        swipeVelocity={dishSwipeVelocity}
        onRetryArtwork={dishId => dispatch('retryArtwork', dishId)} onDismiss={closeSheet}/>)}
      dishActions={dish && <View style={[styles.actionRow, styles.dishActions]}>
        <OrderAction label={ct('menuKeepBrowsing')} onPress={closeSheet} chinese={chinese} secondary dishAction/>
        <OrderAction label={ct(added ? 'menuAdded' : 'menuAdd')} onPress={() => dispatch('add', dish.id)}
          chinese={chinese} testID="menu-add-dish" disabled={added} dishAction/>
      </View>}>
        {dish ? <>
          {error && <Text accessibilityLiveRegion="polite" style={[chatType.bodyMd(chinese), styles.error, styles.detailError]}>{ct(error)}</Text>}
          <MenuDishDetails dish={dish} dishes={dishes} badge={dish.suggestions?.[badgeKind]?.[language] ?? dish.suggestions?.picks?.[language]}
            hasVenue={Boolean(venue)} videoSearch={snapshot?.videoSearch?.[dish.id]}
            language={language} open={open}/>
        </> : <>
          <View style={styles.sheetHeader}>
            <Text accessibilityRole="header" style={[chatType.headingXl(chinese), styles.fill]}>
              {ct(showStaff ? 'menuStaffTitle' : 'menuReview')}
            </Text>
            <Pressable testID="menu-close-sheet" accessibilityRole="button" accessibilityLabel={ct(showStaff ? 'menuBackToOrder' : 'menuBack')}
              onPress={closeSheet} style={styles.close}><Icon name="close"/></Pressable>
          </View>
          {error && <Text accessibilityLiveRegion="polite" style={[chatType.bodyMd(chinese), styles.error]}>{ct(error)}</Text>}
          <ScrollView style={showStaff ? styles.fill : styles.scroll}>
            {order.map(item => showStaff ? <View key={item.id} style={styles.staffRow}>
              <Text style={[chatType.headingXl(chinese), styles.staffQuantity]}>{item.quantity}×</Text>
              <Text style={[chatType.headingXl(chinese), styles.fill]}>{item.name}</Text>
            </View> : <View key={item.id} style={styles.orderRow}>
              <View style={styles.fill}>
                <Text style={chatType.bodyLgMedium(chinese)}>{item.name}</Text>
                <Text style={[chatType.bodyMd(chinese), styles.secondary]}>{formatMenuPrice(item)}</Text>
              </View>
              <View style={styles.stepper}>
                <QuantityStep item={item} delta={-1} language={language} onChange={count => dispatch('setQuantity', item.id, count)}/>
                <Text accessibilityLiveRegion="polite" style={chatType.bodyMdMedium(chinese)}>{item.quantity}</Text>
                <QuantityStep item={item} delta={1} language={language} onChange={count => dispatch('setQuantity', item.id, count)}/>
              </View>
              <Pressable accessibilityRole="button" accessibilityLabel={ct('menuRemove') + ' ' + item.name} style={styles.close}
                onPress={() => dispatch('remove', item.id)}><Icon name="close" size={16}/></Pressable>
            </View>)}
          </ScrollView>
          {showStaff ? <OrderAction label={ct('menuBackToOrder')} onPress={closeSheet} chinese={chinese} testID="menu-back-to-order" secondary/> : <>
            {pickup && <View style={styles.pickup}>
              <Text style={chatType.bodyMdMedium(chinese)}>{pickup.name}</Text>
              <Text style={[chatType.bodySm(chinese), styles.secondary]}>{pickup.address}</Text>
              <Text style={[chatType.bodySm(chinese), styles.secondary]}>{ct('menuPickupHelp')}</Text>
              <OrderAction label={ct('menuPickup')} onPress={() => open(pickup.url)} chinese={chinese} testID="menu-open-pickup" secondary/>
            </View>}
            <OrderAction label={ct('menuShowStaff')} onPress={() => dispatch('showToStaff')} chinese={chinese} testID="menu-show-staff"/>
          </>}
        </>}
    </MenuScanSheet>
  </View>;
}

const styles = StyleSheet.create({
  root: {...StyleSheet.absoluteFill},
  footer: {position: 'absolute', left: 0, right: 0, alignItems: 'center', gap: 8},
  footerPill: {color: chatColors.textOnInverse, backgroundColor: '#171514AA', borderRadius: 20,
    paddingHorizontal: 12, paddingVertical: 6, overflow: 'hidden'},
  light: {color: chatColors.textOnInverse, textAlign: 'center'},
  center: {textAlign: 'center'},
  action: {minHeight: 48, justifyContent: 'center', paddingHorizontal: 12, borderRadius: 999},
  actionRow: {flexDirection: 'row', gap: 8},
  dishAction: {flex: 1, minHeight: 40, flexDirection: 'row', alignItems: 'center', gap: 6},
  actionPrimary: {backgroundColor: chatColors.inverse},
  actionSecondary: {backgroundColor: chatColors.fillControl},
  disabled: {opacity: 0.5},
  retake: {minHeight: 48, borderRadius: 24, justifyContent: 'center', paddingHorizontal: 16, backgroundColor: '#171514AA'},
  sheetHeader: {flexDirection: 'row', alignItems: 'center', gap: 12},
  close: {width: 44, height: 44, alignItems: 'center', justifyContent: 'center'},
  fill: {flex: 1},
  scroll: {flexGrow: 0, flexShrink: 1},
  error: {color: chatColors.textError},
  detailError: {paddingTop: 12, paddingHorizontal: chatMetrics.side},
  dishActions: {paddingHorizontal: 20, paddingBottom: 12, paddingTop: 8},
  secondary: {color: chatColors.textSecondary},
  orderRow: {flexDirection: 'row', alignItems: 'center', gap: 8, paddingVertical: 8, borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: chatColors.hairline},
  stepper: {flexDirection: 'row', alignItems: 'center', borderRadius: 22, backgroundColor: chatColors.fillControl},
  step: {width: 44, height: 44, alignItems: 'center', justifyContent: 'center'},
  staffRow: {flexDirection: 'row', alignItems: 'flex-start', gap: 16, paddingVertical: 16, borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: chatColors.hairline},
  staffQuantity: {minWidth: 40},
  pickup: {gap: 4, padding: 12, borderRadius: 16, borderWidth: 1, borderColor: chatColors.hairline},
});
