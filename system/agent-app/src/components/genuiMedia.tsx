import React, {useEffect, useId, useRef, useState} from 'react';
import {AppState, Image, Linking, Platform, StyleSheet, View} from 'react-native';
import {CameraView, useCameraPermissions} from 'expo-camera';
import {VideoView} from 'expo-video';
import {File, Paths} from 'expo-file-system';
import * as MediaLibrary from 'expo-media-library';
import Svg, {Defs, FeColorMatrix, Filter, Image as SvgImage, Line, Rect} from 'react-native-svg';
import type {Language} from '../i18n';
import type {UiConfig} from '../model/genui';
import {accent, main, spacing} from '../feed/feedTokens';
import {analyzeCameraPhoto} from '../model/genuiCameraAnalysis';
import {applyCameraConfig, leasePlayback, ownVideoSurface, photoColorMatrix, reloadPlayback, seekPlayback, togglePlayback, updateCamera, useMediaStore} from '../model/genuiMediaStore';
import {ProgressiveImage} from './genuiAssembly';
import {FeedButton, FeedImage, FeedInset, FeedMediaFrame, FeedPlaceholder, FeedSlider, FeedStatus, FeedText} from '../feed/feedVisual';

export type MediaContentProps = {mediaType: 'image' | 'audio' | 'video' | 'camera'; image?: string; url?: string; language: Language; title?: string; config?: UiConfig};
const sourceUrl = (value?: string) => value && /^(https?:\/\/|file:\/\/|content:\/\/|data:image\/)/.test(value) ? value : '';

function PlaybackContent({mediaType, url, image, language, title}: MediaContentProps) {
  const source = sourceUrl(url), poster = sourceUrl(image), id = useId(), zh = language === 'zh';
  const entry = useMediaStore(s => s.playback[source]);
  useEffect(() => source ? leasePlayback(source, id, mediaType === 'video') : undefined, [source, id, mediaType]);
  if (!source) return <FeedText variant="body-sm" tone="secondary" text={zh ? '尚未提供可播放的媒体来源。' : 'No playable media source was supplied.'}/>;
  const seconds = Math.max(0, Math.floor(entry?.currentTime ?? 0)), duration = Math.max(0, Math.floor(entry?.duration ?? 0));
  const time = `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
  const total = duration ? `${Math.floor(duration / 60)}:${String(duration % 60).padStart(2, '0')}` : '—';
  return <View style={styles.content}>
    {mediaType === 'video' && entry?.player && (entry.surfaceOwner === id ?
      <FeedMediaFrame kind="video"><VideoView player={entry.player} style={StyleSheet.absoluteFill} contentFit="contain" surfaceType="textureView" nativeControls={false} accessibilityLabel={title}/></FeedMediaFrame> :
      <View style={styles.content}>{!!poster && <ProgressiveImage src={poster} height={190} fit="contain" label={title}/>}
        <FeedText variant="body-sm" tone="secondary" text={zh ? '此来源在另一个视图中显示；播放位置与控制已共享。' : 'This source is displayed in another view; playback position and controls are shared.'}/>
        <FeedButton label={zh ? '在这里显示视频' : 'Show video here'} onPress={() => ownVideoSurface(source, id)}/></View>)}
    {mediaType === 'audio' && !!poster && <ProgressiveImage src={poster} height={190} fit="contain" label={title}/>}
    <View style={styles.row}><FeedButton variant="primary" label={entry?.playing ? zh ? '暂停' : 'Pause' : zh ? '播放' : 'Play'} disabled={!entry?.player || entry.status === 'error'} onPress={() => togglePlayback(source)}/>
      <FeedText variant="body-sm" tone="secondary" text={[time, " / ", total].join('')}/></View>
    <FeedSlider label={zh ? '播放位置' : 'Playback position'} min={0} max={duration || 1} value={Math.min(seconds, duration)} disabled={!duration || entry?.status === 'error'} onComplete={value => seekPlayback(source, value)}/>
    {entry?.status === 'loading' && <FeedPlaceholder kind="media"/>}
    {!!entry?.error && <FeedStatus state="error" text={entry.error}>
      <FeedButton label={zh ? '重新加载' : 'Reload source'} onPress={() => reloadPlayback(source)}/></FeedStatus>}
  </View>;
}

function CameraContent({language, image, title, config}: MediaContentProps) {
  const zh = language === 'zh', id = useId(), filterId = `photo${id.replace(/[^a-zA-Z0-9]/g, '')}`;
  const session = useMediaStore(s => s.camera), [permission, requestPermission] = useCameraPermissions();
  const camera = useRef<CameraView>(null), processed = useRef<Svg>(null), [readyUri, setReadyUri] = useState<string | null>(null);
  const {photo} = session, reference = sourceUrl(image), locked = session.busy || session.analyzing;
  const imageFilter = config?.imageFilter, filterStrength = config?.filterStrength, analysisPrompt = config?.analysisPrompt;
  useEffect(() => {
    applyCameraConfig({imageFilter, filterStrength, analysisPrompt});
  }, [imageFilter, filterStrength, analysisPrompt]);
  useEffect(() => {
    if (session.photo && !session.busy && !session.analyzing && session.analysisRequest > session.analyzedRequest)
      void analyzeCameraPhoto(language);
  }, [session.photo, session.busy, session.analyzing, session.analysisRequest, session.analyzedRequest, language]);
  const imageReady = !!photo && readyUri === photo.uri;
  useEffect(() => {
    updateCamera({active: AppState.currentState === 'active'});
    const subscription = AppState.addEventListener('change', value => updateCamera({active: value === 'active', ready: false}));
    return () => {
      subscription.remove();
      if (useMediaStore.getState().camera.owner === id) updateCamera({owner: null, ready: false});
    };
  }, [id]);

  async function openCamera() {
    try {
      const response = permission?.granted ? permission : await requestPermission();
      if (!response.granted) { updateCamera({error: zh ? '相机权限未获准。可在系统设置中授权。' : 'Camera permission was denied. You can grant it in system settings.'}); return; }
      updateCamera({owner: id, ready: false, photo: null, error: '', saved: '', analysis: null, analysisError: ''});
    } catch (error) { updateCamera({error: error instanceof Error ? error.message : String(error)}); }
  }
  async function capture() {
    if (!camera.current || !session.ready || useMediaStore.getState().camera.busy) return;
    updateCamera({busy: true, error: '', saved: ''});
    try {
      const result = await camera.current.takePictureAsync({quality: 0.9});
      if (!result) throw new Error(zh ? '相机未返回照片。' : 'The camera did not return a photograph.');
      updateCamera({photo: {uri: result.uri, width: result.width, height: result.height, capturedAt: Date.now()}, ready: false, analysis: null, analysisError: '', compare: false});
    } catch (error) { updateCamera({error: error instanceof Error ? error.message : String(error)}); }
    finally { updateCamera({busy: false}); }
  }
  async function loadReference() {
    if (!reference || useMediaStore.getState().camera.busy) return;
    updateCamera({busy: true, owner: null, ready: false, error: '', saved: ''});
    try {
      const size = await Image.getSize(reference);
      updateCamera({photo: {uri: reference, width: size.width, height: size.height, provided: true}, analysis: null, analysisError: '', compare: false});
    } catch (error) {updateCamera({error: error instanceof Error ? error.message : String(error)});}
    finally {updateCamera({busy: false});}
  }
  async function save() {
    if (!photo || !processed.current || !imageReady || useMediaStore.getState().camera.busy) return;
    updateCamera({busy: true, saved: '', error: ''});
    let output: File | undefined;
    try {
      if (Platform.OS === 'web') throw new Error(zh ? '请在设备应用中将处理结果保存到相册。' : 'Save the processed result to Photos in the device app.');
      const response = await MediaLibrary.requestPermissionsAsync(true, ['photo']);
      if (!response.granted) throw new Error(zh ? '未获得保存照片的权限。' : 'Permission to save photos was denied.');
      // Export the same filtered SVG used for preview, without the separate guidance overlay.
      let timeout: ReturnType<typeof setTimeout> | undefined;
      let base64: string;
      try {
        base64 = await new Promise<string>((resolve, reject) => {
          timeout = setTimeout(() => reject(new Error(zh ? '照片导出未完成，请重试。' : 'Photo export did not complete. Please retry.')), 15000);
          processed.current!.toDataURL(value => {if (value) resolve(value); else reject(new Error('Empty image export'));}, {width: photo.width, height: photo.height});
        });
      } finally { clearTimeout(timeout); }
      output = new File(Paths.cache, `Taku-${Date.now()}.png`);
      output.create(); await output.write(base64, {encoding: 'base64'});
      // SDK 58: the root createAssetAsync export is a throwing legacy stub; Asset.create copies into Photos.
      const asset = await MediaLibrary.Asset.create(output.uri);
      updateCamera({saved: await asset.getUri()});
    } catch (error) { updateCamera({error: error instanceof Error ? error.message : String(error)}); }
    finally { if (output?.exists) output.delete(); updateCamera({busy: false}); }
  }

  const frame = session.analysis?.frame;
  return <View style={styles.content}>
    {!photo && <>
      {permission?.granted && session.owner === id && session.active ? <FeedMediaFrame kind="camera">
        <CameraView ref={camera} style={StyleSheet.absoluteFill} facing={session.facing} mute
          onCameraReady={() => updateCamera({ready: true})}
          onMountError={event => updateCamera({ready: false, error: event.message})}/>
        {session.grid && <ThirdsGuide/>}
      </FeedMediaFrame> : <FeedText variant="body-sm" tone="secondary" text={zh ? '点击打开相机，授权后显示真实预览。' : 'Open the camera to authorize and show a live preview.'}/>}
      <View style={styles.row}>
        <FeedButton variant={session.owner !== id ? 'primary' : 'secondary'} label={zh ? '打开相机' : 'Open camera'} disabled={locked} onPress={() => void openCamera()}/>
        {session.owner === id && permission?.granted && <>
          <FeedButton variant="primary" label={zh ? '拍照' : 'Capture'} disabled={!session.ready || locked} onPress={() => void capture()}/>
          <FeedButton label={zh ? '翻转' : 'Flip camera'} disabled={locked} onPress={() => updateCamera({facing: session.facing === 'back' ? 'front' : 'back', ready: false})}/>
          <FeedButton label={zh ? '关闭相机' : 'Close camera'} disabled={locked} onPress={() => updateCamera({owner: null, ready: false})}/>
        </>}
        {permission && !permission.granted && !permission.canAskAgain && <FeedButton label={zh ? '系统设置' : 'System settings'} onPress={() => void Linking.openSettings()}/>}
      </View>
    </>}
    {photo && <>
      <View style={[styles.content, session.compare && styles.comparison]}>
        {session.compare && <View style={styles.photoColumn}><FeedText variant="body-sm" tone="secondary" text={zh ? '原图' : 'Original'}/>
          <FeedImage src={photo.uri} aspectRatio={photo.width / photo.height} fit="contain" radius={12} label={zh ? '原始照片' : 'Original photograph'}/></View>}
        <View style={styles.photoColumn}><FeedText variant="body-sm" tone="secondary" text={zh ? '处理后' : 'Processed'}/>
          <FeedMediaFrame kind="image" aspectRatio={photo.width / photo.height}>
            <Svg ref={processed} width="100%" height="100%" viewBox={`0 0 ${photo.width} ${photo.height}`} accessibilityLabel={title ?? (zh ? '处理后的照片' : 'Processed photograph')}>
              <Defs><Filter id={filterId} x="0%" y="0%" width="100%" height="100%"><FeColorMatrix type="matrix" values={photoColorMatrix(session.filter, session.intensity)}/></Filter></Defs>
              <SvgImage href={{uri: photo.uri}} width={photo.width} height={photo.height} filter={`url(#${filterId})`} onLoad={() => setReadyUri(photo.uri)}/>
            </Svg>
            {(frame || session.grid) && <View pointerEvents="none" style={StyleSheet.absoluteFill}><Svg width="100%" height="100%" viewBox="0 0 1 1" preserveAspectRatio="none">
              {frame && <Rect x={frame.x} y={frame.y} width={frame.width} height={frame.height} stroke={accent.green} strokeWidth={0.005} fill="none"/>}
              {session.grid && <><Line x1={1 / 3} y1={0} x2={1 / 3} y2={1} stroke={main.surface} strokeOpacity={0.6} strokeWidth={0.002}/><Line x1={2 / 3} y1={0} x2={2 / 3} y2={1} stroke={main.surface} strokeOpacity={0.6} strokeWidth={0.002}/><Line x1={0} y1={1 / 3} x2={1} y2={1 / 3} stroke={main.surface} strokeOpacity={0.6} strokeWidth={0.002}/><Line x1={0} y1={2 / 3} x2={1} y2={2 / 3} stroke={main.surface} strokeOpacity={0.6} strokeWidth={0.002}/></>}
            </Svg></View>}
          </FeedMediaFrame>
        </View>
      </View>
      <FeedInset><View style={styles.options}>
        <FeedButton label={zh ? '暖色' : 'Warm'} selected={session.filter === 'warm'} disabled={locked} onPress={() => updateCamera({filter: 'warm', saved: ''})}/>
        <FeedButton label={zh ? '黑白' : 'Black & white'} selected={session.filter === 'mono'} disabled={locked} onPress={() => updateCamera({filter: 'mono', saved: ''})}/>
        <FeedButton label={zh ? '对比原图' : 'Compare original'} selected={session.compare} onPress={() => updateCamera({compare: !session.compare})}/>
      </View></FeedInset>
      <FeedText variant="body-sm" tone="secondary" text={[zh ? '强度' : 'Intensity', " · ", Math.round(session.intensity * 100), "%"].join('')}/>
      <FeedSlider label={zh ? '滤镜强度' : 'Filter intensity'} min={0} max={1} step={0.01} value={session.intensity} disabled={locked} onChange={intensity => updateCamera({intensity, saved: ''})}/>
      <View style={styles.row}>
        <FeedButton label={zh ? '重新拍摄' : 'Retake'} disabled={locked} onPress={() => void openCamera()}/>
        <FeedButton variant="primary" label={zh ? '保存处理结果' : 'Save processed photo'} disabled={locked || !imageReady} onPress={() => void save()}/>
        <FeedButton label={session.analyzing ? zh ? '正在分析…' : 'Analyzing…' : zh ? '分析这张照片的构图' : 'Analyze this photo'} disabled={locked} onPress={() => void analyzeCameraPhoto(language)}/>
      </View>
      {!!session.saved && <FeedStatus state="info" text={zh ? '处理后的照片已保存到相册。' : 'The processed photograph was saved to Photos.'}/>}
      {session.analysis && <FeedStatus state="info" text={session.analysis.text}>
        <FeedText variant="body-sm" tone="secondary" text={[photo.provided ? zh ? '来源：提供的图片 · ' : 'Source: supplied image · ' : zh ? '来源：本次拍摄的静态照片 · ' : 'Source: this captured still · ', session.analysis.model].join('')}/>
        {frame && <FeedText variant="body-sm" tone="secondary" text={[frame.label, ' · ', zh ? '模型识别置信度' : 'Model confidence', ' ', Math.round(frame.confidence * 100), '% · ', zh ? '不是实时对齐检测' : 'Still-image observation; no live alignment'].join('')}/>}</FeedStatus>}
      {!!session.analysisError && <FeedStatus state="error" text={session.analysisError}/>}
    </>}
      {!!session.analysisPrompt && <FeedText variant="body-sm" tone="secondary" text={[zh ? '当前构图目标：' : 'Current composition goal: ', session.analysisPrompt, !photo ? zh ? ' · 拍摄或选择提供的图片后分析' : ' · Capture or select a supplied image to analyze' : ''].join('')}/>}
    <FeedButton label={zh ? '三分构图参考线' : 'Rule-of-thirds guide'} selected={session.grid} onPress={() => updateCamera({grid: !session.grid})}/>
    {session.grid && <FeedText variant="body-sm" tone="secondary" text={zh ? '参考线是静态构图辅助，不表示检测到人物或已对齐。' : 'These static composition guides do not indicate a detected subject or alignment.'}/>}
    {!!reference && <FeedInset><View style={styles.notice}><FeedText variant="body-sm" tone="secondary" text={zh ? '提供的参考图' : 'Supplied reference image'}/><FeedImage src={reference} height={190} fit="contain"/>
      <FeedButton label={zh ? '使用这张图片' : 'Use this image'} disabled={locked} onPress={() => void loadReference()}/></View></FeedInset>}
    {!!session.error && <FeedStatus state="error" text={session.error}/>}
  </View>;
}
function ThirdsGuide() {
  return <View pointerEvents="none" style={StyleSheet.absoluteFill}><Svg width="100%" height="100%" viewBox="0 0 3 3" preserveAspectRatio="none">
    <Line x1={1} y1={0} x2={1} y2={3} stroke={main.surface} strokeOpacity={0.6} strokeWidth={0.008}/><Line x1={2} y1={0} x2={2} y2={3} stroke={main.surface} strokeOpacity={0.6} strokeWidth={0.008}/>
    <Line x1={0} y1={1} x2={3} y2={1} stroke={main.surface} strokeOpacity={0.6} strokeWidth={0.008}/><Line x1={0} y1={2} x2={3} y2={2} stroke={main.surface} strokeOpacity={0.6} strokeWidth={0.008}/>
  </Svg></View>;
}
function ImageContent({image, url, title, language}: MediaContentProps) {
  const source = sourceUrl(image) || sourceUrl(url), [failedSource, setFailedSource] = useState<string | null>(null);
  const error = failedSource === source;
  return source ? <View style={styles.content}>{error ? <FeedStatus state="error" text={language === 'zh' ? '图片暂时无法加载。' : 'The image could not be loaded.'}/> :
    <ProgressiveImage src={source} height={190} fit="contain" label={title} onError={() => setFailedSource(source)}/>}</View>
    : <FeedText variant="body-sm" tone="secondary" text={language === 'zh' ? '尚未提供图片来源。' : 'No image source was supplied.'}/>;
}
export function MediaContent(props: MediaContentProps) {
  if (props.mediaType === 'camera') return <CameraContent {...props}/>;
  if (props.mediaType === 'audio' || props.mediaType === 'video') return <PlaybackContent {...props}/>;
  return <ImageContent {...props}/>;
}
const styles = StyleSheet.create({
  content: {gap: spacing[12], minWidth: 0}, row: {flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', gap: spacing[8]},
  options: {flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', gap: spacing[4], padding: spacing[4]},
  notice: {gap: spacing[8], padding: spacing[12], minWidth: 0},
  comparison: {flexDirection: 'row'}, photoColumn: {flex: 1, minWidth: 0, gap: spacing[6]},
});
