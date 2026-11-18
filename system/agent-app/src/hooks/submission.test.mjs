import test from 'node:test';
import assert from 'node:assert/strict';
import {isAcceptedReceipt, isNotSentReceipt, preserveAndReleaseUnconfirmed, requestReceiptWithRetry, retireAcceptedSubmission} from './submission.ts';

const pending = {threadId: 'session-A', requestId: 'send-A', action: 'sendMessage', text: 'Check the screen'};

test('lost receipt is checked once more using the identical request', async () => {
  const seen = [];
  const receipt = await requestReceiptWithRetry(async () => {
    seen.push(pending.requestId);
    if (seen.length === 1) throw new Error('transport lost');
    return {...pending, state: 'queued'};
  });
  assert.deepEqual(seen, ['send-A', 'send-A']);
  assert.equal(isAcceptedReceipt(pending, receipt), true);
});

test('continuing preserves an ambiguous request before releasing it, without touching the draft', async () => {
  let current = JSON.stringify(pending), archive = null, draft = 'a newer draft';
  const stored = current;
  await preserveAndReleaseUnconfirmed(stored, {
    readPending: async () => current, readArchive: async () => archive,
    writeArchive: async value => {archive = value;}, removePending: async () => {current = null;},
  });
  assert.equal(current, null);
  assert.equal(archive, stored);
  assert.equal(draft, 'a newer draft');
});

test('a failed archive or changed pending request never releases the duplicate fence', async () => {
  const stored = JSON.stringify(pending);
  for (const mode of ['write-fails', 'archive-conflict', 'pending-changes']) {
    let current = stored, archive = mode === 'archive-conflict' ? 'different' : null, removed = false;
    await assert.rejects(preserveAndReleaseUnconfirmed(stored, {
      readPending: async () => current, readArchive: async () => archive,
      writeArchive: async value => {
        if (mode === 'write-fails') throw new Error('storage unavailable');
        archive = value;
        if (mode === 'pending-changes') current = 'changed';
      },
      removePending: async () => {removed = true;},
    }));
    assert.equal(removed, false, mode);
  }
});

test('a retry after an interrupted archive reuses the exact saved record', async () => {
  const stored = JSON.stringify(pending); let current = stored, writes = 0;
  await preserveAndReleaseUnconfirmed(stored, {
    readPending: async () => current, readArchive: async () => stored,
    writeArchive: async () => {writes++;}, removePending: async () => {current = null;},
  });
  assert.equal(writes, 0);
  assert.equal(current, null);
});

test('a receipt for another session or an earlier request cannot clear this draft', () => {
  assert.equal(isAcceptedReceipt(pending, {threadId: 'session-B', requestId: 'send-A', state: 'queued'}), false);
  assert.equal(isAcceptedReceipt(pending, {threadId: 'session-A', requestId: 'send-previous', state: 'queued'}), false);
  assert.equal(isAcceptedReceipt(pending, {...pending, state: 'queued'}), true);
});

test('lost, malformed and unknown delivery remain unresolved after UI recovery', () => {
  for (const state of [undefined, '', 'publishing', 'deliveryUnknown', 'completed', 'failed']) {
    assert.equal(isAcceptedReceipt(pending, {...pending, state}), false);
  }
  assert.equal(isAcceptedReceipt(pending, {}), false);
});

test('steering and queued task receipts are not interchangeable', () => {
  const steer = {...pending, action: 'steer', expectedTurnId: 'turn-A'};
  assert.equal(isAcceptedReceipt(steer, {...pending, state: 'queued'}), false);
  assert.equal(isAcceptedReceipt(steer, {...pending, state: 'accepted'}), true);
  assert.equal(isAcceptedReceipt(pending, {...pending, state: 'accepted'}), false);
});

test('a next-turn fallback receipt must carry a real task ID', () => {
  const steer = {...pending, action: 'steer', expectedTurnId: 'turn-A'};
  const fallback = {...steer, state: 'accepted', result: {queuedAfterTurn: true}};
  assert.equal(isAcceptedReceipt(steer, fallback), false);
  assert.equal(isAcceptedReceipt(steer, {...fallback, taskId: 'not-a-task'}), false);
  assert.equal(isAcceptedReceipt(steer, {...fallback, taskId: '12345'}), true);
});

test('only an exact durable preflight receipt allows a new explicit submission', () => {
  const receipt = {...pending, state: 'notSent', code: 'THREAD_BUSY'};
  assert.equal(isNotSentReceipt(pending, receipt), true);
  assert.equal(isAcceptedReceipt(pending, receipt), false);
  assert.equal(isNotSentReceipt(pending, {...receipt, threadId: 'another-thread'}), false);
  assert.equal(isNotSentReceipt(pending, {...receipt, requestId: 'earlier-request'}), false);
  assert.equal(isNotSentReceipt(pending, {...receipt, state: 'deliveryUnknown'}), false);
  assert.equal(isNotSentReceipt(pending, {...receipt, state: undefined}), false);
  assert.equal(isNotSentReceipt(pending, {...receipt, code: undefined}), false);
});

test('storage failure cannot leave a resendable accepted draft without its request ID', async () => {
  let draft = pending.text, hasPending = true;
  await assert.rejects(retireAcceptedSubmission(pending, {
    readDraft: async () => draft,
    writeDraft: async () => {throw new Error('Disk full');},
    removePending: async () => {hasPending = false;},
  }));
  assert.equal(draft, pending.text);
  assert.equal(hasPending, true);
  await assert.rejects(retireAcceptedSubmission(pending, {
    readDraft: async () => draft,
    writeDraft: async text => {draft = text;},
    removePending: async () => {throw new Error('Process ended after draft write');},
  }));
  assert.equal(draft, '');
  assert.equal(hasPending, true);
});

test('reconciling an older receipt preserves the newer unsent draft', async () => {
  let draft = 'A different follow-up', hasPending = true;
  const cleared = await retireAcceptedSubmission(pending, {
    readDraft: async () => draft,
    writeDraft: async text => {draft = text;},
    removePending: async () => {hasPending = false;},
  });
  assert.equal(cleared, false);
  assert.equal(draft, 'A different follow-up');
  assert.equal(hasPending, false);
});
