export type ImeGeometry = {
  windowHeight: number;
  rootTop: number;
  rootHeight: number;
  imeBottom: number;
  visible: boolean;
};

// All native coordinates belong to one Insets/layout observation. A root already resized
// by Android has zero overlap; an edge-to-edge root receives only the part still covered.
export function sheetSpace(geometry: ImeGeometry | null, fallbackHeight: number, topInset: number) {
  const valid = geometry && [geometry.windowHeight, geometry.rootTop, geometry.rootHeight, geometry.imeBottom].every(Number.isFinite)
    && geometry.windowHeight > 0 && geometry.rootHeight > 0;
  const height = valid ? geometry.rootHeight : Math.max(1, fallbackHeight);
  const imeBottom = valid && geometry.visible ? Math.max(0, geometry.imeBottom) : 0;
  const overlap = valid && imeBottom > 0
    ? Math.max(0, Math.min(height, geometry.rootTop + height - (geometry.windowHeight - imeBottom))) : 0;
  return {bottom: overlap, available: Math.max(1, height - overlap - Math.max(0, topInset))};
}
