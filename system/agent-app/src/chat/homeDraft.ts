/**
 * Ask-Taku drafts handed over by Home (or any DRAFT intent). Each request is consumed once:
 * it is appended to whatever the user has in the composer and then forgotten, so a later
 * request never re-adds earlier text that was already applied, sent or deleted.
 */
export function appendHomeDrafts(draft: string, incoming: readonly string[]): string {
  const added = incoming.map(text => text.trim()).filter(Boolean);
  if (!added.length) return draft;
  const text = added.join('\n\n');
  return draft.trim() ? `${draft}\n\n${text}` : text;
}
