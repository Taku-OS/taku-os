/** Resolves `asset:feed/<path>` references for React Native (APK assets) or the web gallery. */
import {useEffect, useState} from 'react';
import {readAssetText} from './native';
import {parse, type JsxAST} from 'react-native-svg';
import {IMAGE_REFERENCE} from './imageReference';
import {fetchRemoteSvg} from './remoteSvg';

let webBase: string | null = null;
/** Web/gallery only: serve `asset:feed/<path>` from `<base>/<path>`. */
export function setFeedAssetBase(base: string | null): void { webBase = base ? base.replace(/\/$/, '') : null; }

const PREFIX = 'asset:feed/';
export const assetPath = (ref: string): string | null => ref.startsWith(PREFIX) && !ref.includes('..') ? ref.slice(PREFIX.length) : null;

export function imageUri(ref: string): string | null {
  if (!IMAGE_REFERENCE.test(ref)) return null;
  const path = assetPath(ref);
  if (path) return webBase ? `${webBase}/${path}` : `asset:/feed/${path}`;
  return ref;
}

export const isSvg = (ref: string): boolean => /^[^?#]*\.svg(?:[?#].*)?$/i.test(ref);

export type SvgAsset = {status: 'loading'} | {status: 'ready'; xml: string; ast: JsxAST} | {status: 'failed'};
const loading: SvgAsset = {status: 'loading'};
const failed: SvgAsset = {status: 'failed'};
const cache = new Map<string, SvgAsset>();
const pending = new Map<string, Promise<SvgAsset>>();
// The Figma exports carry CSS-only root attributes that SvgXml does not need.
const clean = (xml: string) => xml.replace(/\sstyle="[^"]*"/, '').replace(/\soverflow="visible"/, '');

function decodeSvg(text: string | null): SvgAsset {
  if (!text) return failed;
  try {
    const xml = clean(text), ast = parse(xml);
    return ast?.tag === 'svg' ? {status: 'ready', xml, ast} : failed;
  } catch { return failed; }
}

function loadSvg(ref: string): Promise<SvgAsset> {
  const known = pending.get(ref);
  if (known) return known;
  const uri = imageUri(ref);
  // Only bundled assets may use the developer gallery's local HTTP server.
  const task = (uri ? assetPath(ref) ? fetch(uri).then(r => r.ok ? r.text() : null) : fetchRemoteSvg(uri) : Promise.resolve(null))
    .then(decodeSvg)
    .catch(() => failed)
    .then(result => {cache.set(ref, result); return result;});
  pending.set(ref, task);
  return task;
}

export function useSvgAsset(ref: string | undefined): SvgAsset | null {
  const [, setVersion] = useState(0);
  const cached = ref ? cache.get(ref) : null;
  if (ref && cached === undefined && !webBase) {
    const path = assetPath(ref);
    if (path) cache.set(ref, decodeSvg(readAssetText(`feed/${path}`)));
  }
  const value = ref ? cache.get(ref) : null;
  useEffect(() => {
    if (!ref || cache.get(ref) !== undefined) return;
    let live = true;
    void loadSvg(ref).then(() => { if (live) setVersion(v => v + 1); });
    return () => { live = false; };
  }, [ref]);
  return ref ? value ?? loading : null;
}
