// Presentation typing only: each character is a separate keyboard insertion.
// Does not submit the form, navigate, record video or call robot APIs.
export const FOLLOW_PHRASES = ['我想要', '这个狗', '跟随', '前面', '黑裤子', '黑鞋子', '的人'];

export function typingPlan(phrases = FOLLOW_PHRASES) {
  const cadence = [190, 250, 165, 215, 280, 175, 230];
  let index = 0;
  return phrases.flatMap((phrase, group) => Array.from(phrase, (text, position) => ({
    text,
    delayMs: cadence[index++ % cadence.length]
      + (position === phrase.length - 1 && group < phrases.length - 1 ? 430 : 0),
  })));
}

export async function typeForTake(send, wait = ms => new Promise(resolve => setTimeout(resolve, ms))) {
  await wait(650);
  for (const step of typingPlan()) {
    await send('Input.insertText', {text: step.text});
    await wait(step.delayMs);
  }
  await wait(900);
}
