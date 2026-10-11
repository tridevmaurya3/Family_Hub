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
    case 'health': return { ...common, assignedMemberId: 'account_member-alice', assignedMemberName: 'Member', collaborationStatus: 'ACTIVE', shared: true, createdAt: 900, memberName: 'Member', recordType: 'MEDICINE', title: 'Medicine' };
    case 'vehicles': return { ...common, assignedMemberId: 'account_member-alice', assignedMemberName: 'Member', collaborationStatus: 'ACTIVE', shared: true, createdAt: 900, ownerName: 'Member', vehicleType: 'CAR', displayName: 'Car' };
    case 'properties': return { ...common, assignedMemberId: 'account_member-alice', assignedMemberName: 'Member', collaborationStatus: 'ACTIVE', shared: true, createdAt: 900, ownerName: 'Member', propertyType: 'HOUSE', title: 'House' };
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

test('account profile: same-account realtime photo/name and isolated access', { timeout: 45000 }, async () => {
  const uid='profile-owner';
  await env.withSecurityRulesDisabled(async context => {
    await context.database().ref(`users/${uid}`).set({uid,displayName:'Before',familyId:'profileFamily',role:'OWNER_ADMIN'});
  });
  const first=env.authenticatedContext(uid).database().ref(`users/${uid}`);
  const second=env.authenticatedContext(uid).database().ref(`users/${uid}`);
  const feed=stream(second);
  try {
    await feed.until(s=>s.val()?.displayName==='Before');
    await assertSucceeds(first.update({displayName:'Updated name',accountPhoto:'small-compressed-photo',profileSyncVersion:1}));
    const snapshot=await feed.until(s=>s.val()?.displayName==='Updated name' && s.val()?.accountPhoto==='small-compressed-photo');
    assert.equal(snapshot.val().familyId,'profileFamily');
    await assertFails(env.authenticatedContext(reader).database().ref(`users/${uid}`).once('value'));
    await assertFails(env.authenticatedContext(reader).database().ref(`users/${uid}/accountPhoto`).set('forged'));
    await assertFails(first.child('accountPhoto').set('x'.repeat(64001)));
    await assertSucceeds(first.child('accountPhoto').set(''));
    await feed.until(s=>s.child('accountPhoto').val()==='');
    const migration=await first.child('accountPhoto').transaction(value=>value===null?'old-device-photo':undefined);
    assert.equal(migration.committed,false,'removed photo must not be restored by an older local cache');
  } finally {feed.close();}
});

test('own membership name can change without granting role or other-member edits', async () => {
  const family='profileFamily', owner='profile-owner', member='profile-member';
  await env.withSecurityRulesDisabled(async context => {
    await context.database().ref(`families/${family}`).set({familyId:family,name:'Profile family',ownerUid:owner});
    await context.database().ref(`memberships/${family}`).set({
      [owner]:{uid:owner,displayName:'Owner',role:'OWNER_ADMIN',status:'ACTIVE'},
      [member]:{uid:member,displayName:'Member',role:'ADULT_MEMBER',status:'ACTIVE'}
    });
  });
  const db=env.authenticatedContext(member).database();
  await assertSucceeds(db.ref(`memberships/${family}/${member}/displayName`).set('New member name'));
  await assertFails(db.ref(`memberships/${family}/${member}/role`).set('OWNER_ADMIN'));
  await assertFails(db.ref(`memberships/${family}/${owner}/displayName`).set('Other name'));
  await assertFails(db.ref(`memberships/${family}/${member}/displayName`).set(''));
  await assertFails(db.ref(`memberships/${family}/${member}/email`).set('changed@example.com'));
});

test('family avatars: photo-only realtime reads, isolation and revocation', { timeout: 45000 }, async () => {
  const family='avatarFamily', owner='avatarOwner', member='avatarMember', external='avatarExternal';
  await env.withSecurityRulesDisabled(async context => {
    const db=context.database();
    await db.ref(`families/${family}`).set({familyId:family,name:'Avatar family',ownerUid:owner});
    await db.ref(`memberships/${family}`).set({
      [owner]:{uid:owner,displayName:'Same name',role:'OWNER_ADMIN',status:'ACTIVE'},
      [member]:{uid:member,displayName:'Same name',role:'ADULT_MEMBER',status:'ACTIVE'}
    });
    await db.ref(`users/${owner}`).set({uid:owner,familyId:family,accountPhoto:'first-photo',privateDetail:'private'});
    await db.ref(`users/${member}`).set({uid:member,familyId:family,accountPhoto:'member-photo'});
    await db.ref(`users/${external}`).set({uid:external,familyId:'otherAvatarFamily',accountPhoto:'other-photo'});
  });
  const author=env.authenticatedContext(owner).database().ref(`users/${owner}/accountPhoto`);
  const viewer=env.authenticatedContext(member).database();
  const feed=stream(viewer.ref(`users/${owner}/accountPhoto`));
  try {
    await feed.until(s=>s.val()==='first-photo');
    await assertSucceeds(author.set('updated-photo'));
    await feed.until(s=>s.val()==='updated-photo');
    await assertFails(viewer.ref(`users/${owner}`).once('value'));
    await assertFails(viewer.ref(`users/${owner}/privateDetail`).once('value'));
    await assertFails(viewer.ref(`users/${owner}/accountPhoto`).set('forged-photo'));
    await assertFails(env.authenticatedContext(external).database().ref(`users/${owner}/accountPhoto`).once('value'));
    await assertFails(env.unauthenticatedContext().database().ref(`users/${owner}/accountPhoto`).once('value'));
    await assertFails(viewer.ref(`users/${external}/accountPhoto`).once('value'));
    await assertSucceeds(author.set(''));
    await feed.until(s=>s.val()==='');
  } finally {feed.close();}
  await env.withSecurityRulesDisabled(async context => {
    await context.database().ref(`memberships/${family}/${member}/status`).set('INACTIVE');
  });
  await assertFails(viewer.ref(`users/${owner}/accountPhoto`).once('value'));
});
