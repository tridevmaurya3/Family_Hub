import { before, after, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';

// Never use production credentials, project IDs or database endpoints here.
const projectId = 'demo-familyhub-sync';
const endpoint = process.env.FIREBASE_DATABASE_EMULATOR_HOST;
if (!endpoint || !/^(127\.0\.0\.1|localhost):\d+$/.test(endpoint)) {
  throw new Error('A local Firebase database emulator is required; live testing is forbidden.');
}
const [host, port] = endpoint.split(':');
let env;
const writer = 'member-alice';
const reader = 'member-bob';
const outsider = 'other-family-member';

before(async () => {
  env = await initializeTestEnvironment({
    projectId,
    database: { host, port: Number(port), rules: await readFile(new URL('../../database.rules.json', import.meta.url), 'utf8') }
  });
  await env.clearDatabase();
  await env.withSecurityRulesDisabled(async context => {
    await context.database().ref('memberships').set({
      familyA: {
        [writer]: { status: 'ACTIVE', role: 'ADULT_MEMBER' },
        [reader]: { status: 'ACTIVE', role: 'ADULT_MEMBER' },
        inactive: { status: 'INACTIVE', role: 'ADULT_MEMBER' }
      },
      familyB: { [outsider]: { status: 'ACTIVE', role: 'ADULT_MEMBER' } }
    });
  });
});
after(async () => { if (env) await env.cleanup(); });

function item(module) {
  const common = { cloudId: 'record', familyId: 'familyA', updatedByUid: writer, updatedAt: 1000 };
  switch (module) {
    case 'health': return { ...common, shared: true, createdAt: 900, memberName: 'Member', recordType: 'MEDICINE', title: 'Medicine' };
    case 'vehicles': return { ...common, shared: true, createdAt: 900, ownerName: 'Member', vehicleType: 'CAR', displayName: 'Car' };
    case 'properties': return { ...common, shared: true, createdAt: 900, ownerName: 'Member', propertyType: 'HOUSE', title: 'House' };
    case 'finance': return { ...common, shared: true, entryType: 'EXPENSE', amount: 100, category: 'Food', transactionDate: '2026-10-08', accountName: 'Cash', paymentMethod: 'CASH', recurring: false, updatedByName: 'Alice' };
    default: return { ...common, collaborationStatus: 'PENDING', assignedMemberId: '', assignedMemberName: '', title: 'Record' };
  }
}

// Keep one real subscription open across create/update/delete. This checks delivery,
// not just a writer's local cached value or one-time read.
function stream(reference) {
  let current;
  let failure;
  let pending;
  const listener = reference.on('value', snapshot => {
    current = snapshot;
    pending?.();
  }, error => { failure = error; pending?.(); });
  return {
    until(predicate) {
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => { pending = undefined; reject(new Error('Realtime delivery timed out')); }, 10000);
        pending = () => {
          if (failure) { clearTimeout(timer); pending = undefined; reject(failure); }
          else if (current && predicate(current)) { clearTimeout(timer); pending = undefined; resolve(current); }
        };
        pending();
      });
    },
    close() { reference.off('value', listener); }
  };
}

for (const module of ['planner', 'reminders', 'notes', 'finance', 'tasks', 'health', 'vehicles', 'properties']) {
  test(`${module}: family-member realtime create/edit/delete and isolation`, { timeout: 45000 }, async t => {
    const path = `sharedModules/familyA/${module}/record`;
    const a = env.authenticatedContext(writer).database().ref(path);
    const b = env.authenticatedContext(reader).database().ref(path);
    const feed = stream(b);
    try {
      await feed.until(snapshot => !snapshot.exists());
      const payload = item(module);
      await t.test('create reaches another active member', async () => {
        await assertSucceeds(a.update(payload));
        const snapshot = await feed.until(snapshot => snapshot.exists() && snapshot.val().updatedAt === 1000);
        assert.deepEqual(snapshot.val(), payload);
      });
      await t.test('edit by second member reaches first member', async () => {
        const firstFeed = stream(a);
        try {
          await firstFeed.until(snapshot => snapshot.val()?.updatedAt === 1000);
          await assertSucceeds(b.update({ updatedByUid: reader, updatedAt: 2000 }));
          const snapshot = await firstFeed.until(snapshot => snapshot.val()?.updatedAt === 2000);
          assert.equal(snapshot.val().updatedByUid, reader);
        } finally { firstFeed.close(); }
      });
      for (const uid of [outsider, 'inactive', null]) {
        await t.test(`${uid ?? 'unauthenticated'} cannot read/write`, async () => {
          const context = uid === null ? env.unauthenticatedContext() : env.authenticatedContext(uid);
          const denied = context.database().ref(path);
          await assertFails(denied.once('value'));
          await assertFails(denied.update({ ...payload, updatedByUid: uid ?? 'anonymous' }));
        });
      }
      await t.test('cross-family destination and forged identity rejected', async () => {
        await assertFails(env.authenticatedContext(writer).database().ref(`sharedModules/familyB/${module}/record`).update({ ...payload, familyId: 'familyB' }));
        for (const changes of [{ familyId: 'familyB' }, { cloudId: 'wrong' }, { updatedByUid: outsider }, { updatedAt: 'not-a-number' }]) {
          await assertFails(a.update({ ...payload, ...changes }));
        }
      });
      if (['health', 'vehicles', 'properties'].includes(module)) {
        await t.test('private and malformed records rejected', async () => {
          await assertFails(a.update({ ...payload, shared: false }));
          await assertFails(a.update({ ...payload, createdAt: 'not-a-number' }));
          const field = module === 'health' ? 'memberName' : 'ownerName';
          await assertFails(a.update({ ...payload, [field]: null }));
        });
      }
      await t.test('deletion reaches other member', async () => {
        await feed.until(snapshot => snapshot.exists());
        await assertSucceeds(a.remove());
        await feed.until(snapshot => !snapshot.exists());
      });
    } finally { feed.close(); }
  });
}
test('unknown module rejected', async () => {
  await assertFails(env.authenticatedContext(writer).database().ref('sharedModules/familyA/unknown/record').update(item('notes')));
});
