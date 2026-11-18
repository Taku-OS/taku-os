import React, {createContext, useContext, type ReactNode} from 'react';
import {StyleSheet, View} from 'react-native';
import {useStateValue, type ComponentRenderProps} from '@json-render/react-native';
import {FeedImage, FeedPlaceholder} from '../feed/feedVisual';
import {radii, spacing} from '../feed/feedTokens';
import {fieldWaiting, pendingAssemblyRegions, pendingItemRegions, showFieldSkeleton, type CardAssembly} from '../model/genuiAssembly';
import type {UiItem} from '../model/genui';

const RegionPaths = createContext<Record<string, string>>({});
export function useRegionWaiting(prop: string) {
  const paths = useContext(RegionPaths), assembly = useStateValue<CardAssembly | undefined>('/assembly');
  return fieldWaiting(assembly, paths[prop]);
}
export function useRegionSkeleton(prop: string, value: unknown) {
  const paths = useContext(RegionPaths), assembly = useStateValue<CardAssembly | undefined>('/assembly');
  return showFieldSkeleton(assembly, paths[prop], value);
}
export function useItemRegions(item?: UiItem) {
  const paths = useContext(RegionPaths), assembly = useStateValue<CardAssembly | undefined>('/assembly');
  return item ? pendingItemRegions(item, paths.items ?? paths.options, assembly) : [];
}
export function ItemRegions({item}: {item: UiItem}) {
  const fields = useItemRegions(item);
  return <>{fields.filter(field => field !== 'image').map(field => <FeedPlaceholder key={field} kind="text"/>)}</>;
}

export function AssemblyRegion({element, children}: ComponentRenderProps<{widget: string; paths: Record<string, string>; values: Record<string, unknown>}>) {
  const {widget, paths, values} = element.props, assembly = useStateValue<CardAssembly | undefined>('/assembly');
  const fields = pendingAssemblyRegions(widget, paths, values, assembly);
  return <RegionPaths.Provider value={paths}>{children}{fields.length > 0 && <View style={styles.regions}>
    {fields.map(prop => <FeedPlaceholder key={prop} kind={prop === 'image' || prop === 'src' || widget === 'MediaSurface' && prop === 'url' ? 'media' : prop === 'value' ? 'value' : 'text'}/>)}
  </View>}</RegionPaths.Provider>;
}
export function CardBodyRegions({children}: {children: ReactNode}) {
  const assembly = useStateValue<CardAssembly | undefined>('/assembly');
  const data = useStateValue<{actions?: unknown[]}>('/data');
  return <>{children}{showFieldSkeleton(assembly, 'actions', data?.actions) && <FeedPlaceholder kind="action"/>}</>;
}

export function ProgressiveImage({src, label, width = '100%', height, aspectRatio, radius = radii.xl, fit, onLoad, onError}: {
  src: string; label?: string; width?: number | `${number}%`; height?: number | `${number}%`; aspectRatio?: number; radius?: number;
  fit?: 'cover' | 'contain'; onLoad?: () => void; onError?: () => void;
}) {
  const currentUri = React.useRef(src);
  React.useLayoutEffect(() => { currentUri.current = src; }, [src]);
  const [settled, setSettled] = React.useState<{uri: string; failed: boolean} | null>(null);
  const complete = settled?.uri === src;
  return <View style={{width, height: height ?? (aspectRatio ? undefined : 120), aspectRatio, borderRadius: radius, overflow: 'hidden'}}>
    {!complete && <View style={StyleSheet.absoluteFill}><FeedPlaceholder kind="media"/></View>}
    {!(complete && settled.failed) && <FeedImage src={src} label={label} width="100%" height="100%" radius={radius} fit={fit}
      onLoad={() => {if (currentUri.current !== src) return; setSettled({uri: src, failed: false}); onLoad?.();}}
      onError={() => {if (currentUri.current !== src) return; setSettled({uri: src, failed: true}); onError?.();}}/>}
  </View>;
}
const styles = StyleSheet.create({regions: {gap: spacing[8], paddingVertical: spacing[4]}});
