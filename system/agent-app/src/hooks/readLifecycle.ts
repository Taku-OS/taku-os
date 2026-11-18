/** Restore local conversation context independently of the optional history index. */
export async function restoreConversation<T extends {id: string}>(options: {
  preferred: string;
  list: () => Promise<T[]>;
  select: (id: string) => Promise<void>;
  isCurrent: () => boolean;
  onListError: (error: unknown) => void;
}): Promise<void> {
  const {preferred, list, select, isCurrent, onListError} = options;
  if (!isCurrent()) return;
  if (preferred) {
    // A slow index must not delay an explicit route or its saved draft.
    void list().catch(onListError);
    await select(preferred);
    return;
  }
  let items: T[] = [];
  try { items = await list(); } catch (error) { onListError(error); }
  // Even an unavailable index must release restoration of the local new-chat draft.
  if (isCurrent()) await select(items[0]?.id || '');
}

/** Each independent read owns its errors; late results cannot mutate another route. */
export async function readCurrent<T>(options: {
  read: () => Promise<T>;
  isCurrent: () => boolean;
  accept: (value: T) => void;
  reject: (error: unknown) => void;
  settled?: () => void;
}): Promise<void> {
  try {
    const value = await options.read();
    if (options.isCurrent()) options.accept(value);
  } catch (error) {
    if (options.isCurrent()) options.reject(error);
  } finally {
    if (options.isCurrent()) options.settled?.();
  }
}
