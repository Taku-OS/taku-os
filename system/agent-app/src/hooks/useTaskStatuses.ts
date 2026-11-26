import {useEffect, useState} from 'react';
import {callAgent, gatewayLoad} from '../native/agent';
import {retryWhileBusy} from '../native/gatewayLoad';
import type {Json, SessionView} from '../model/session';
import {emptyTaskBoard, sessionTaskState, type TaskBoard} from '../model/taskStatus';
import {createTaskFollower} from './taskFollower';

/**
 * The status board of the Tasks TAKU's main page shows.
 *
 * [ids] are the Tasks to follow, newest first; they are read only while [following], which is while
 * the main conversation is on screen and has finished loading. [session] is the open conversation:
 * when it is one of those Tasks, its live state goes onto the board directly, so returning to TAKU
 * never shows that Task as it was before the visit.
 */
export function useTaskStatuses(ids: readonly string[], following: boolean, session: SessionView | null): {
  board: TaskBoard;
  /** Sends a choice made in the main stream to that Task; resolves false when it was not accepted. */
  answer: (threadId: string, key: string, label: string, response: Json) => Promise<boolean>;
} {
  const [board, setBoard] = useState<TaskBoard>(emptyTaskBoard);
  const [follower] = useState(() => createTaskFollower({
    // Nobody waits on a status read: it goes when the open conversation leaves the gateway room.
    read: async threadId => {
      await gatewayLoad.quiet();
      return callAgent('readSession', {threadId, statusOnly: true});
    },
    // The user is waiting on this one. BUSY means the gateway refused it whole, so it is sent again.
    answer: (threadId, key, response) => retryWhileBusy(() => callAgent('answer', {threadId, key, response})),
    publish: setBoard,
  }));
  // The list is a new array on every thread refresh; only a change of its IDs restarts the round.
  const followed = ids.join('\n');
  useEffect(() => {
    if (!following) return;
    follower.follow(followed ? followed.split('\n') : []);
    return () => follower.stop();
  }, [follower, following, followed]);
  useEffect(() => {
    if (session?.id) follower.observe(session.id, sessionTaskState(session));
  }, [follower, session]);
  return {board, answer: follower.answer};
}
