import {fromMarkdown} from 'mdast-util-from-markdown';

const tag = 'taku-menu-app:';
const receiptPrefix = `${tag}org.agentos.generated.menu.m`;

export function menuAppReply(text: string, active: boolean, interrupted: boolean): {text: string; menuAppPackage?: string} {
  const trimmed = text.trimEnd();
  const lastLineStart = trimmed.lastIndexOf('\n') + 1;
  const lastLine = trimmed.slice(lastLineStart);
  const suffix = lastLine.startsWith(receiptPrefix) ? lastLine.slice(receiptPrefix.length) : null;
  const complete = suffix?.length === 32 && /^[a-f0-9]+$/.test(suffix);
  const hidePartial = active || (interrupted && lastLine.startsWith(tag));
  const partial = hidePartial && !!lastLine && (receiptPrefix.startsWith(lastLine)
    || (suffix !== null && /^[a-f0-9]{0,31}$/.test(suffix)));
  if ((!complete && !partial) || fromMarkdown(trimmed).children.at(-1)?.type !== 'paragraph') return {text};
  const body = text.slice(0, lastLineStart);
  if (active) return {text: body};
  return complete && !interrupted ? {text: body.trimEnd(), menuAppPackage: lastLine.slice(tag.length)} : {text: body.trimEnd()};
}
