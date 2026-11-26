import {useEffect} from 'react';
import {setWindowBackdrop} from '../native/agent';
import {chatBlur, chatColors} from './chatTheme';

// Route changes can briefly mount two chat-styled screens in the shared React host.
// Their common backdrop must survive until the last screen releases it.
let backdropUsers = 0;

/**
 * The window blurs whatever lies under it (Feed, Home or another app) while a chat-styled screen
 * shows: the chat itself and its Panels pages.
 */
export function useChatBackdrop(): void {
  useEffect(() => {
    backdropUsers += 1;
    void setWindowBackdrop({sigmaDp: chatBlur.backdropSigma, tint: chatColors.backdropTintArgb, fallback: chatColors.backdropFallback}).catch(() => {});
    return () => {
      backdropUsers -= 1;
      if (backdropUsers === 0) void setWindowBackdrop(null).catch(() => {});
    };
  }, []);
}
