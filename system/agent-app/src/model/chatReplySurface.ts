import type {Spec} from '@json-render/core';

/** Presentation only: prose uses a chat bubble; interactive/rich cards keep their own surface. */
export function isTextReplySpec(spec: Spec): boolean {
  if (spec.elements[spec.root]?.type !== 'Card') return false;
  const seen = new Set<string>();
  let text = false;
  const visit = (id: string): boolean => {
    if (seen.has(id)) return false;
    seen.add(id);
    const node = spec.elements[id];
    if (!node || !['Card', 'Column', 'Text'].includes(node.type) || node.on && Object.keys(node.on).length) return false;
    if (node.type === 'Card' && id !== spec.root) return false;
    if (node.type === 'Text') text = true;
    return [...(node.children ?? []), ...Object.values(node.slots ?? {}).flat()].every(visit);
  };
  return visit(spec.root) && text;
}
