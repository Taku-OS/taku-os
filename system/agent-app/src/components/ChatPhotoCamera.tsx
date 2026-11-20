import React, {useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore} from 'react';
import {ActivityIndicator, AppState, Image, Linking, Modal, Platform, Pressable, StyleSheet, Text, View} from 'react-native';
import {CameraView, useCameraPermissions, type CameraCapturedPicture} from 'expo-camera';
import {File} from 'expo-file-system';
import {ImageManipulator, SaveFormat} from 'expo-image-manipulator';
import {StatusBar} from 'expo-status-bar';
import {useSharedValue} from 'react-native-reanimated';
import {chatText, type ChatTextKey} from '../chat/chatText';
import {chatColors, chatMetrics, chatType} from '../chat/chatTheme';
import {CHAT_PHOTO_PREFIX, isChatPhoto} from '../hooks/submission';
import {useMenuReducedMotion} from '../hooks/useMenuReducedMotion';
import {matchPublishedMenu} from '../model/menuEvidence';
import {formatMenuPrice, parseMenuDishes} from '../model/menuScan';
import {IslandAnchorView, type IslandAnchor} from '../native/IslandAnchorView';
import {applyMenuScanAction, beginMenuScan, finishMenuScan, readMenuScan, recognizeMenuText, subscribeMenuScan,
  type MenuScanAction, type MenuScanSnapshot} from '../native/agent';
import type {Language} from '../i18n';
import {Icon} from './Icon';
import {MenuScanResult, type ScannedMenu} from './MenuScanResult';
import {MenuScanIsland} from './MenuScanIsland';
import {MenuScanPerimeter} from './MenuScanPerimeter';
import type {MenuDishLayer} from './MenuScanSheet';

function discardCacheFile(uri: string) {
  try { new File(uri).delete(); }
  catch { /* The capture cache may already have been cleared. */ }
}

async function encodePhoto(photo: CameraCapturedPicture): Promise<string> {
  for (const {size, compress} of [{size: 1280, compress: 0.68}, {size: 960, compress: 0.58}, {size: 640, compress: 0.48}]) {
    const context = ImageManipulator.manipulate(photo.uri);
    try {
      context.resize(photo.width >= photo.height ? {width: Math.min(size, photo.width)} : {height: Math.min(size, photo.height)});
      const image = await context.renderAsync();
      try {
        const encoded = await image.saveAsync({format: SaveFormat.JPEG, compress, base64: true});
        discardCacheFile(encoded.uri);
        const data = `${CHAT_PHOTO_PREFIX}${encoded.base64 ?? ''}`;
        if (isChatPhoto(data)) return data;
      } finally { image.release(); }
    } finally { context.release(); }
  }
  throw new Error('Photo exceeds attachment limit');
}

function CameraAction({label, chinese, onPress, primary, testID}: {
  label: string; chinese: boolean; onPress: () => void; primary?: boolean; testID?: string;
}) {
  return <Pressable testID={testID} accessibilityRole="button" onPress={onPress}
    style={({pressed}) => [styles.button, primary && styles.primary, pressed && styles.pressed]}>
    <Text style={[chatType.bodyLgMedium(chinese), !primary && styles.inverseText]}>{label}</Text>
  </Pressable>;
}

export function ChatPhotoCamera({language, insets, onAttach, onClose}: {
  language: Language;
  insets: {top: number; bottom: number};
  onAttach: (photo: string) => boolean;
  onClose: () => void;
}) {
  const ct = chatText(language);
  const chinese = language === 'zh';
  const [permission, requestPermission, readPermission] = useCameraPermissions();
  const asked = useRef(false), live = useRef(true), captureTicket = useRef(0), takingPhoto = useRef(false);
  const camera = useRef<CameraView>(null);
  const [active, setActive] = useState(AppState.currentState === 'active');
  const [ready, setReady] = useState(false);
  const [busy, setBusy] = useState(false);
  const [photo, setPhoto] = useState<string>();
  const [menuMode, setMenuMode] = useState(false);
  const [menu, setMenu] = useState<ScannedMenu>();
  const retainedMenu = useRef<{uri: string; scanId?: string}>(undefined);
  const [error, setError] = useState<ChatTextKey>();
  const serializedScan = useSyncExternalStore(subscribeMenuScan, readMenuScan);
  const latest = useMemo<MenuScanSnapshot | null>(() => JSON.parse(serializedScan), [serializedScan]);
  const scan = latest && latest.scanId === menu?.snapshot?.scanId ? latest : menu?.snapshot;
  const reduceMotion = useMenuReducedMotion();
  const [loadedPhoto, setLoadedPhoto] = useState<string>();
  const [marksReady, setMarksReady] = useState(false);
  const [retaking, setRetaking] = useState(false);
  const [dishLayer, setDishLayer] = useState<MenuDishLayer>();
  const dishSwipeVelocity = useSharedValue(0);
  const [islandOverride, setIslandOverride] = useState<'expanded' | 'compact'>();
  const [islandBottom, setIslandBottom] = useState(0);
  const [islandAnchor, setIslandAnchor] = useState<IslandAnchor>();
  const photoLoaded = menu !== undefined && menu.uri === loadedPhoto;
  const markersVisible = photoLoaded && scan !== undefined && !retaking;
  const menuRun = menuMode && !retaking && (menu !== undefined || (busy && !error));
  useEffect(() => {
    if (!markersVisible || reduceMotion) return;
    const timer = setTimeout(() => setMarksReady(true), 370);
    return () => clearTimeout(timer);
  }, [markersVisible, reduceMotion]);
  const releaseMenu = useCallback(() => {
    const scan = retainedMenu.current;
    if (!scan) return;
    retainedMenu.current = undefined;
    try { if (scan.scanId) finishMenuScan(scan.scanId); }
    finally { discardCacheFile(scan.uri); }
  }, []);
  const cancelCapture = useCallback(() => {
    ++captureTicket.current;
    const scan = retainedMenu.current;
    if (scan && !scan.scanId) {
      releaseMenu();
      setMenu(undefined);
    }
  }, [releaseMenu]);
  const ask = useCallback(() => {
    setError(undefined);
    void requestPermission().catch(() => { if (live.current) setError('cameraUnavailable'); });
  }, [requestPermission]);
  useEffect(() => {
    if (!permission || permission.granted || !permission.canAskAgain || asked.current) return;
    asked.current = true;
    ask();
  }, [permission, ask]);
  useEffect(() => {
    live.current = true;
    const listener = AppState.addEventListener('change', state => {
      const foreground = state === 'active';
      setActive(foreground);
      if (foreground) void readPermission().catch(() => {});
      else {
        setReady(false);
        cancelCapture();
      }
    });
    return () => { live.current = false; listener.remove(); };
  }, [readPermission, cancelCapture]);
  useEffect(() => releaseMenu, [releaseMenu]);
  useEffect(() => {
    if (!retaking || !ready) return;
    const timer = setTimeout(() => {
      releaseMenu();
      setMenu(undefined);
      setLoadedPhoto(undefined);
      setMarksReady(false);
      setRetaking(false);
      setIslandOverride(undefined);
    }, reduceMotion ? 0 : 180);
    return () => clearTimeout(timer);
  }, [ready, reduceMotion, releaseMenu, retaking]);

  async function capture() {
    if (!camera.current || !ready || takingPhoto.current || AppState.currentState !== 'active') return;
    takingPhoto.current = true;
    setMarksReady(false);
    setLoadedPhoto(undefined);
    const ticket = ++captureTicket.current;
    const shutterStarted = performance.now();
    const isCurrent = () => live.current && captureTicket.current === ticket && AppState.currentState === 'active';
    setBusy(true);
    setError(undefined);
    let original: CameraCapturedPicture | undefined;
    try {
      original = await camera.current.takePictureAsync({quality: 0.85});
      if (!original) throw new Error('Camera returned no photo');
      if (!isCurrent()) return;
      if (menuMode) {
        const {uri, width, height} = original;
        retainedMenu.current = {uri};
        original = undefined;
        setMenu({uri, width, height});
        const recognition = await recognizeMenuText(uri);
        if (!isCurrent()) return;
        const matched = matchPublishedMenu(parseMenuDishes(recognition));
        const snapshot = beginMenuScan(uri, {width: recognition.width, height: recognition.height,
          scanMs: Math.round(performance.now() - shutterStarted), ...matched});
        retainedMenu.current = {uri, scanId: snapshot.scanId};
        setMenu({uri, width: snapshot.width, height: snapshot.height, snapshot,
          textBoxes: recognition.lines.map(({box}) => box)});
      } else {
        const encoded = await encodePhoto(original);
        if (isCurrent()) { setPhoto(encoded); setReady(false); }
      }
    } catch {
      if (isCurrent()) {
        if (menuMode) {
          releaseMenu();
          setMenu(undefined);
        }
        setError(menuMode ? 'menuScanFailed' : 'captureFailed');
      }
    } finally {
      if (original) discardCacheFile(original.uri);
      takingPhoto.current = false;
      if (live.current) setBusy(false);
    }
  }

  const retakeMenu = () => {
    if (retaking) return;
    setReady(false);
    setError(undefined);
    setRetaking(true);
  };
  const photoFailed = () => {
    cancelCapture();
    releaseMenu();
    setMenu(undefined);
    setError('menuScanFailed');
  };
  const close = () => {
    cancelCapture();
    onClose();
  };
  const back = () => {
    if (dishLayer?.phase === 'closing') return;
    if (menu?.snapshot) {
      const scan: MenuScanSnapshot | null = JSON.parse(readMenuScan());
      if (scan?.scanId === menu.snapshot.scanId) {
        if (scan.selectedDishId || scan.view !== 'menu') {
          applyMenuScanAction('back', scan.scanId);
          return;
        }
        if (dishLayer) return;
        if (scan.suggestionView === 'comparison' || scan.suggestionView === 'preferences') {
          applyMenuScanAction('showPicks', scan.scanId);
          return;
        }
      }
    }
    close();
  };
  const openSettings = () => { void Linking.openSettings().catch(() => setError('cameraUnavailable')); };
  const picks = scan?.dishes.filter(dish => dish.suggestions?.picks) ?? [];
  const hasLighter = scan?.dishes.some(dish => dish.suggestions?.lighter) ?? false;
  const showingDish = Boolean(scan?.selectedDishId || dishLayer);
  const browsing = scan?.view === 'menu' && !showingDish;
  const comparison = scan?.suggestionView === 'comparison';
  const preferences = scan?.suggestionView === 'preferences';
  const lighter = scan?.suggestionView === 'lighter';
  const recommendationsVisible = markersVisible && (marksReady || reduceMotion);
  const orderCount = scan?.orderItems.reduce((sum, item) => sum + item.quantity, 0) ?? 0;
  const phase = photo ? 'hidden' : !menuRun ? 'idle' : showingDish ? 'compact' : !recommendationsVisible ? 'scanning' : !browsing ? 'hidden'
    : picks.length > 0 && !lighter ? (islandOverride ?? (orderCount && !comparison && !preferences ? 'compact' : 'expanded')) : 'compact';
  const showSuggestions = (action: MenuScanAction) => {
    if (!scan) return;
    try {
      applyMenuScanAction(action, scan.scanId);
      setIslandOverride(action === 'showPicks' ? 'expanded' : undefined);
    }
    catch { setError('menuOrderFailed'); }
  };
  const allPicks = {id: 'picks', label: ct('menuAllPicks'), onPress: () => showSuggestions('showPicks')};
  const lighterChoice = {id: 'lighter', label: ct('menuLighterOptions'), onPress: () => showSuggestions('showLighter')};
  const actions = comparison || preferences ? [allPicks, ...(hasLighter ? [lighterChoice] : [])] : [
    {id: 'compare', label: ct('menuComparePicks'), onPress: () => showSuggestions('comparePicks')},
    ...(hasLighter ? [lighterChoice] : []),
    {id: 'preferences', label: ct('menuSetPreferences'), onPress: () => showSuggestions('openPreferences')},
  ];
  const headline = comparison ? ct('menuComparePicks') : preferences ? ct('menuChoosePicks')
    : picks.length === 1 ? ct('menuIslandOnePick') : ct('menuIslandPicks').replace('{count}', String(picks.length));
  const detailLines = comparison ? picks.map(dish => [dish.name, formatMenuPrice(dish), dish.suggestions?.picks?.[language]].join(' · ')) : undefined;
  const menuToolsTop = Math.max(insets.top + 8, (islandAnchor?.y ?? 33.5) + 24);
  const showCamera = active && permission?.granted && !photo && (!photoLoaded || retaking);
  const permissionPrompt = active && permission && !permission.granted && <View style={styles.permission}>
    <Text style={[chatType.bodyLg(chinese), styles.inverseText]}>{ct('cameraPermission')}</Text>
    <CameraAction label={ct(permission.canAskAgain ? 'allowCamera' : 'cameraSettings')} chinese={chinese}
      onPress={permission.canAskAgain ? ask : openSettings} primary/>
  </View>;
  const modes = <View style={styles.modes}>
    {([false, true] as const).map(isMenu => <Pressable key={String(isMenu)} accessibilityRole="button"
      accessibilityState={{selected: menuMode === isMenu}} testID={isMenu ? 'menu-scan-mode' : 'chat-camera-photo-mode'}
      disabled={busy} onPress={() => {
        if (menuMode === isMenu) return;
        setMenuMode(isMenu);
        if (error !== 'cameraUnavailable') setError(undefined);
      }} style={[styles.mode, menuMode === isMenu && styles.selectedMode]}>
      <Text style={[chatType.bodyMdMedium(chinese), styles.inverseText]}>{ct(isMenu ? 'menuScan' : 'cameraPhotoMode')}</Text>
    </Pressable>)}
  </View>;
  const shutter = permission?.granted && <Pressable testID="chat-camera-shutter" accessibilityRole="button"
    accessibilityLabel={ct(menuMode ? 'menuScanShutter' : 'takePhoto')}
    disabled={!active || !ready || busy} onPress={() => void capture()}
    style={({pressed}) => [styles.shutter, (!ready || pressed) && styles.pressed]}>
    {busy ? <ActivityIndicator color={chatColors.iconOnInverse}/> : <View style={styles.shutterFill}/>}
  </Pressable>;
  const closeButton = (scanning: boolean) => <Pressable testID="chat-camera-close" accessibilityRole="button"
    accessibilityLabel={ct('closeCamera')} onPress={close}
    style={({pressed}) => [styles.close, scanning && styles.menuClose, pressed && styles.pressed]}>
    <Icon name="close" color={chatColors.iconOnInverse}/>
  </Pressable>;
  return <Modal animationType="fade" statusBarTranslucent navigationBarTranslucent onRequestClose={back}>
    <View accessibilityViewIsModal style={styles.root}>
      {menuMode && Platform.OS === 'android' && <StatusBar hidden/>}
      {IslandAnchorView && <View pointerEvents="none" importantForAccessibility="no-hide-descendants" style={StyleSheet.absoluteFill}>
        <IslandAnchorView style={StyleSheet.absoluteFill} onAnchorChanged={event => setIslandAnchor(event.nativeEvent)}/>
      </View>}
      <View style={[styles.cameraLayout, !menuMode && {paddingTop: insets.top, paddingBottom: insets.bottom}]}>
        {!menuMode && <View style={styles.header} pointerEvents="box-none">
          <Text accessibilityRole="header" style={[chatType.bodyXlMedium(chinese), styles.inverseText]}>
            {ct(photo ? 'photoPreview' : 'takePhoto')}
          </Text>
          {closeButton(false)}
        </View>}
        {!menuMode && !photo && modes}
        <View style={styles.preview}>
          {/* A mode change resizes this view while the Android camera keeps its preview surface. */}
          {showCamera && <CameraView ref={camera} style={StyleSheet.absoluteFill} facing="back" mute animateShutter={!menuMode}
            onCameraReady={() => setReady(true)} onMountError={() => {
              setReady(false);
              if (retaking) setRetaking(false);
              setError('cameraUnavailable');
            }}/>}
          {photo ? <Image accessibilityLabel={ct('photoPreview')} source={{uri: photo}} resizeMode="contain" style={StyleSheet.absoluteFill}/>
            : permissionPrompt}
        </View>
        {!menuMode && <>
          {!!error && <Text accessibilityLiveRegion="polite" style={[chatType.bodyMd(chinese), styles.error]}>{ct(error)}</Text>}
          <View style={styles.controls}>
            {photo ? <>
              <CameraAction testID="chat-camera-retake" label={ct('retakePhoto')} chinese={chinese}
                onPress={() => { setPhoto(undefined); setError(undefined); }}/>
              <CameraAction testID="chat-camera-confirm" label={ct('usePhoto')} chinese={chinese} primary
                onPress={() => { if (AppState.currentState === 'active' && onAttach(photo)) onClose(); }}/>
            </> : shutter}
          </View>
        </>}
      </View>
      {menuMode && <>
        {!menu && <>
          <View style={[styles.menuTools, {top: menuToolsTop}]}>{modes}</View>
          {active && permission?.granted && !busy && <View pointerEvents="none" style={[styles.menuGuide,
            {top: menuToolsTop + 48, bottom: insets.bottom + 117}]}/>}
          <View style={[styles.menuControls, {bottom: insets.bottom}]}>
            <Text accessibilityLiveRegion="polite" style={[chatType.bodyMd(chinese), styles.menuHint]}>
              {ct(error ?? (busy ? 'menuScanning' : 'menuFrame'))}
            </Text>
            <View style={styles.controls}>{shutter}{closeButton(true)}</View>
          </View>
        </>}
        {menu && <MenuScanResult menu={menu} snapshot={scan} markersVisible={markersVisible}
          islandBottom={phase === 'expanded' ? islandBottom : 0}
          recommendationsVisible={recommendationsVisible} leaving={retaking} insets={insets} language={language}
          dishSwipeVelocity={dishSwipeVelocity}
          onPhotoLoad={() => {
            const ticket = captureTicket.current;
            requestAnimationFrame(() => requestAnimationFrame(() => {
              if (live.current && captureTicket.current === ticket) setLoadedPhoto(menu.uri);
            }));
          }} onPhotoError={photoFailed} onDishLayerChange={setDishLayer} onRetake={retakeMenu}/>}
        {menu && !showingDish && (!scan || browsing) && !retaking && <View
          style={[styles.menuResultClose, {bottom: insets.bottom + 12}]} pointerEvents="box-none">
          {closeButton(true)}
        </View>}
        <MenuScanPerimeter phase={!menuRun ? 'hidden' : phase === 'scanning' ? 'scanning'
          : !browsing ? 'detail' : phase === 'expanded' ? 'recommendations' : 'active'}/>
      </>}
      {(!IslandAnchorView || islandAnchor) && <MenuScanIsland phase={phase} insetTop={insets.top} insetBottom={insets.bottom} cutoutAnchor={islandAnchor}
        dishPhase={dishLayer?.phase} dishSwipeVelocity={dishSwipeVelocity}
        scanningLabel={ct('menuIslandScanning')} headline={headline} actions={actions} detailLines={detailLines}
        orderCount={orderCount} orderLabel={ct('menuOrder')} onOpenOrder={browsing && orderCount ? () => showSuggestions('openOrder') : undefined}
        onExpand={browsing && phase === 'compact' && picks.length ? () => showSuggestions('showPicks') : undefined}
        onCollapse={() => setIslandOverride('compact')} collapseLabel={ct('collapse')} onPanelBottom={setIslandBottom}/>}
    </View>
  </Modal>;
}

const styles = StyleSheet.create({
  root: {flex: 1, backgroundColor: chatColors.inverse},
  header: {minHeight: chatMetrics.header, paddingHorizontal: chatMetrics.side, justifyContent: 'center', alignItems: 'center', zIndex: 2},
  close: {position: 'absolute', right: chatMetrics.side, width: 48, height: 48, alignItems: 'center', justifyContent: 'center'},
  inverseText: {color: chatColors.textOnInverse},
  preview: {flex: 1, overflow: 'hidden', justifyContent: 'center', alignItems: 'center'},
  modes: {flexDirection: 'row', alignSelf: 'center', gap: 4, marginBottom: 12, borderRadius: 24, backgroundColor: '#303030', padding: 3},
  mode: {minHeight: 36, justifyContent: 'center', paddingHorizontal: 18, borderRadius: 20},
  selectedMode: {backgroundColor: '#555555'},
  cameraLayout: {flex: 1},
  menuTools: {position: 'absolute', left: 0, right: 0, alignItems: 'center'},
  menuGuide: {position: 'absolute', left: '7%', right: '7%', borderWidth: 1.5, borderColor: '#FFFFFFB3', borderRadius: 18},
  menuControls: {position: 'absolute', left: 0, right: 0},
  menuHint: {alignSelf: 'center', color: chatColors.textOnInverse, textAlign: 'center', backgroundColor: '#171514B8',
    paddingHorizontal: 12, paddingVertical: 5, borderRadius: 18},
  menuClose: {borderRadius: 24, backgroundColor: '#171514AA'},
  menuResultClose: {position: 'absolute', right: 0, width: 72, height: 48, justifyContent: 'center'},
  permission: {alignItems: 'center', gap: 16, padding: 16},
  controls: {minHeight: 96, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 12, paddingHorizontal: chatMetrics.side},
  button: {minHeight: 48, justifyContent: 'center', borderRadius: 24, paddingHorizontal: 24},
  primary: {backgroundColor: chatColors.send},
  pressed: {opacity: 0.55},
  shutter: {width: 64, height: 64, borderRadius: 32, borderWidth: 2, borderColor: chatColors.iconOnInverse, alignItems: 'center', justifyContent: 'center'},
  shutterFill: {width: 52, height: 52, borderRadius: 26, backgroundColor: chatColors.iconOnInverse},
  error: {color: chatColors.textOnInverse, textAlign: 'center', margin: 12},
});
