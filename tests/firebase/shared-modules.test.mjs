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
        'profile-owner': { status: 'ACTIVE', role: 'OWNER_ADMIN' },
        'profile-guardian': { status: 'ACTIVE', role: 'GUARDIAN' },
        inactive: { status: 'INACTIVE', role: 'ADULT_MEMBER' }
      },
      familyB: { [outsider]: { status: 'ACTIVE', role: 'ADULT_MEMBER' } }
    });
  });
});
after(async () => { if (env) await env.cleanup(); });

test('live tracking disconnect cleanup is registered after the owned session exists', { timeout: 45000 }, async () => {
  const ref = env.authenticatedContext(reader).database().ref('liveTrackingSessions/familyA/disconnect-order');
  await assertFails(ref.onDisconnect().remove());
  await assertSucceeds(ref.set({ viewerUid: reader, active: true, requestedAt: 1000, ttlMs: 15000, mode: 'MAP_VISIBLE' }));
  await assertSucceeds(ref.onDisconnect().remove());
  await ref.onDisconnect().cancel();
  await assertSucceeds(ref.remove());
});

test('family profiles: retried payload remains realtime and preserves existing write roles', { timeout: 45000 }, async t => {
  const path = 'familyProfiles/familyA/retry-profile';
  const owner = env.authenticatedContext('profile-owner').database().ref(path);
  const guardian = env.authenticatedContext('profile-guardian').database().ref(path);
  const adult = env.authenticatedContext(reader).database().ref(path);
  const feed = stream(adult);
  const profile = {
    profileId: 'retry-profile', familyId: 'familyA', name: 'Member', relation: '',
    phone: '', email: '', dateOfBirth: '', note: '', gender: '', bloodGroup: '',
    address: '', emergencyContactName: '', emergencyContactPhone: '',
    familyRole: 'ADULT', isGuardian: false, createdAt: 900, updatedAt: 1000,
    updatedBy: 'profile-owner', serverUpdatedAt: { '.sv': 'timestamp' }
  };
  try {
    await feed.until(snapshot => !snapshot.exists());
    await t.test('owner create and server revision reach another member', async () => {
      await assertSucceeds(owner.update(profile));
      const received = await feed.until(snapshot => snapshot.val()?.updatedAt === 1000);
      assert.equal(typeof received.val().serverUpdatedAt, 'number');
    });
    await t.test('guardian edit reaches the same existing profile', async () => {
      await assertSucceeds(guardian.update({ name: 'Updated member', updatedAt: 2000, updatedBy: 'profile-guardian', serverUpdatedAt: { '.sv': 'timestamp' } }));
      assert.equal((await feed.until(snapshot => snapshot.val()?.updatedAt === 2000)).val().name, 'Updated member');
    });
    await t.test('adult, outside family and forged identity cannot gain profile write access', async () => {
      await assertFails(adult.update({ name: 'Denied', updatedBy: reader }));
      await assertFails(env.authenticatedContext(outsider).database().ref(path).once('value'));
      await assertFails(owner.update({ updatedBy: reader }));
    });
    await t.test('owner delete reaches another device', async () => {
      await assertSucceeds(owner.remove());
      await feed.until(snapshot => !snapshot.exists());
    });
  } finally { feed.close(); }
});

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

test('family chat delivers in realtime and receipts stay owned by the receiver', { timeout: 45000 }, async () => {
  const path = 'familyChat/familyA/group';
  const sender = env.authenticatedContext(writer).database();
  const receiver = env.authenticatedContext(reader).database();
  const outside = env.authenticatedContext(outsider).database();
  const stamp = { '.sv': 'timestamp' };
  const received = new Promise(resolve => receiver.ref(path).on('child_added', resolve));
  await assertSucceeds(sender.ref(path + '/sample').set({ sender: writer, body: 'Hello family', at: stamp }));
  assert.equal((await received).child('body').val(), 'Hello family');
  receiver.ref(path).off('child_added');
  await assertFails(outside.ref(path).get());
  await assertFails(receiver.ref(path + '/sample/body').set('Edited'));
  await assertFails(sender.ref(path + '/sample/receipts/' + reader).set({ deliveredAt: stamp }));
  await assertSucceeds(receiver.ref(path + '/sample/receipts/' + reader).set({ deliveredAt: stamp }));
  await assertSucceeds(receiver.ref(path + '/sample/receipts/' + reader + '/readAt').set(stamp));
  assert.equal((await sender.ref(path + '/sample/receipts/' + reader + '/readAt').get()).exists(), true);
  await assertFails(receiver.ref(path + '/sample/receipts/' + reader + '/readAt').remove());
});

test('direct chat accepts only its two active family members', { timeout: 45000 }, async () => {
  const path = `familyChat/familyA/direct/${writer}/${reader}`;
  const sender = env.authenticatedContext(writer).database();
  const receiver = env.authenticatedContext(reader).database();
  const third = env.authenticatedContext('profile-owner').database();
  const stamp = { '.sv': 'timestamp' };
  await assertFails(receiver.ref(path + '/forged').set({ sender: writer, body: 'forged', at: stamp }));
  await assertSucceeds(sender.ref(path + '/direct').set({ sender: writer, body: 'Private', at: stamp }));
  assert.equal((await receiver.ref(path + '/direct').get()).child('body').val(), 'Private');
  await assertFails(third.ref(path).get());
  await assertFails(third.ref(path + '/other').set({ sender: 'profile-owner', body: 'No', at: stamp }));
  await assertFails(sender.ref('familyChat/familyA/direct/' + writer + '/inactive/x')
    .set({ sender: writer, body: 'No', at: stamp }));
});

test('private groups and their attachments are visible only to selected active members', { timeout: 45000 }, async () => {
  const path = 'familyChat/familyA';
  const invited = 'profile-owner';
  const excluded = 'profile-guardian';
  const owner = env.authenticatedContext(writer).database();
  const member = env.authenticatedContext(reader).database();
  const other = env.authenticatedContext(excluded).database();
  const stamp = { '.sv': 'timestamp' };
  const room = id => `rooms/${id}`;
  const index = (uid, id) => `roomIndex/${uid}/${id}`;
  const create = (id, members, avatar) => {
    const updates = {
      [room(id) + '/title']: 'Private shopping',
      [room(id) + '/creator']: writer,
    };
    if (avatar) updates[room(id) + '/photo'] = avatar;
    for (const uid of members) {
      updates[room(id) + '/members/' + uid] = true;
      updates[index(uid, id)] = true;
    }
    return owner.ref(path).update(updates);
  };

  await assertFails(create('invalid-room', [writer, reader, 'inactive']));
  await assertFails(owner.ref(path + '/' + index(excluded, 'private')).set(true));
  await assertSucceeds(create('private', [writer, reader, invited]));
  await assertSucceeds(create('two-members', [writer, reader], 'YWJj'));
  const photo = path + '/' + room('two-members') + '/photo';
  assert.equal((await assertSucceeds(member.ref(photo).get())).val(), 'YWJj');
  await assertFails(other.ref(photo).get());
  await assertFails(member.ref(photo).set('YWJjZA=='));
  await assertFails(owner.ref(photo).set('invalid avatar!'));
  await assertFails(owner.ref(photo).set('A'.repeat(64001)));
  await assertSucceeds(owner.ref(photo).set('YWJjZA=='));
  assert.equal((await assertSucceeds(member.ref(photo).get())).val(), 'YWJjZA==');
  assert.equal((await member.ref(path + '/' + index(reader, 'private')).get()).val(), true);
  await assertFails(other.ref(path + '/rooms').get());
  await assertFails(other.ref(path + '/rooms/private').get());
  await assertFails(other.ref(path + '/roomIndex/' + reader).get());
  await assertFails(owner.ref(path + '/rooms/private/members/' + excluded).set(true));

  const message = 'rooms/private/messages/photo';
  const file = 'files/rooms/private/photo';
  const attachment = { sender: writer, body: 'Photo', at: stamp, kind: 'photo', mime: 'image/jpeg' };
  await assertFails(owner.ref(path + '/' + message).set(attachment));
  await assertFails(owner.ref(path + '/' + file).set('YWJj'));
  await assertSucceeds(owner.ref(path).update({ [message]: attachment, [file]: 'YWJj' }));
  assert.equal((await member.ref(path + '/' + file).get()).val(), 'YWJj');
  await assertFails(other.ref(path + '/' + file).get());
  await assertFails(other.ref(path + '/' + message + '/receipts/' + excluded).set({ deliveredAt: stamp }));
  await assertSucceeds(member.ref(path + '/' + message + '/receipts/' + reader).set({ deliveredAt: stamp, readAt: stamp }));
  await assertFails(member.ref(path + '/' + file).set('YWJjZA=='));
  await env.withSecurityRulesDisabled(async context => {
    await context.database().ref(`memberships/familyA/${invited}/status`).set('INACTIVE');
  });
  try {
    await assertFails(env.authenticatedContext(invited).database().ref(path + '/' + file).get());
  } finally {
    await env.withSecurityRulesDisabled(async context => {
      await context.database().ref(`memberships/familyA/${invited}/status`).set('ACTIVE');
    });
  }
});

test('polls accept only a conversation member’s own vote for an existing option', { timeout: 45000 }, async () => {
  const path = `familyChat/familyA/direct/${writer}/${reader}/poll`;
  const sender = env.authenticatedContext(writer).database().ref(path);
  const receiver = env.authenticatedContext(reader).database().ref(path);
  const other = env.authenticatedContext('profile-owner').database().ref(path);
  await assertSucceeds(sender.set({
    sender: writer, body: 'Dinner?', kind: 'poll', at: { '.sv': 'timestamp' },
    options: { '0': 'Home', '1': 'Out' }
  }));
  await assertFails(receiver.child('votes').child(writer).set('1'));
  await assertFails(receiver.child('votes').child(reader).set('3'));
  await assertFails(other.child('votes').child('profile-owner').set('0'));
  await assertSucceeds(receiver.child('votes').child(reader).set('1'));
  assert.equal((await sender.child('votes').child(reader).get()).val(), '1');
});

test('chat tools validate replies, mentions, reaction ownership and pins', async () => {
  const path = `familyChat/familyA/direct/${writer}/${reader}`;
  const a = env.authenticatedContext(writer).database().ref(path);
  const b = env.authenticatedContext(reader).database().ref(path);
  const outsiderRef = env.authenticatedContext(outsider).database().ref(path);
  const stamp = { '.sv': 'timestamp' };
  await assertSucceeds(a.child('chat-tools-original').set({ sender: writer, body: 'Original', at: stamp }));
  const message = { sender: reader, body: 'Reply', at: stamp, replyTo: 'chat-tools-original', mentions: { [writer]: true } };
  await assertFails(b.child('chat-tools-bad-reply').set({ ...message, replyTo: 'missing' }));
  await assertFails(b.child('chat-tools-bad-mention').set({ ...message, mentions: { 'profile-owner': true } }));
  await assertFails(b.child('chat-tools-forged').set({ ...message, pinned: true }));
  await assertSucceeds(b.child('chat-tools-reply').set(message));
  const reaction = `chat-tools-original/reactions/${reader}`;
  await assertFails(a.child(reaction).set('👍'));
  await assertFails(b.child(reaction).set('invalid'));
  await assertSucceeds(b.child(reaction).set('👍'));
  await assertSucceeds(b.child(reaction).remove());
  await assertFails(outsiderRef.child('chat-tools-original/pinned').set(true));
  await assertSucceeds(b.child('chat-tools-original/pinned').set(true));
  assert.equal((await a.orderByChild('pinned').equalTo(true).get()).numChildren(), 1);
  await assertSucceeds(a.child('chat-tools-original/pinned').remove());
  const family = a.root.child('familyChat/familyA');
  const voicePath = `direct/${writer}/${reader}/voice-note`;
  const audioPath = `files/direct/${writer}/${reader}/voice-note`;
  const voice = { sender: writer, body: 'Voice note', at: stamp, kind: 'voice', mime: 'audio/wav' };
  await assertFails(family.update({ [voicePath]: { ...voice, mime: 'audio/mpeg' }, [audioPath]: 'UklGRg==' }));
  await assertSucceeds(family.update({ [voicePath]: voice, [audioPath]: 'UklGRg==' }));
  assert.equal((await b.root.child('familyChat/familyA/' + audioPath).get()).val(), 'UklGRg==');
  await assertFails(outsiderRef.root.child('familyChat/familyA/' + audioPath).get());
  const compressed = { ...voice, mime: 'audio/mp4', voiceDuration: 24000, voiceWave: '0123456789abcdef'.repeat(2) };
  const compressedPath = voicePath + '-compressed';
  const compressedAudio = audioPath + '-compressed';
  const sendCompressed = message => family.update({
    [compressedPath]: message, [compressedAudio]: 'AAAAHGZ0eXBpc29t',
  });
  await assertFails(sendCompressed({ ...compressed, voiceWave: 'invalid' }));
  await assertFails(sendCompressed({ ...compressed, voiceDuration: 0 }));
  await assertFails(sendCompressed({ ...compressed, kind: 'file' }));
  await assertSucceeds(sendCompressed(compressed));
  assert.equal((await b.child('voice-note-compressed/voiceWave').get()).val(), compressed.voiceWave);
  await assertFails(outsiderRef.root.child('familyChat/familyA/' + compressedAudio).get());
});

test('voice notes can use the existing attachment rules in a direct chat', async () => {
  const root = 'familyChat/familyA';
  const family = env.authenticatedContext(writer).database().ref(root);
  const recipient = env.authenticatedContext(reader).database().ref(root);
  const nonMember = env.authenticatedContext(outsider).database().ref(root);
  const message = `direct/${writer}/${reader}/voice-attachment`;
  const audio = `files/${message}`;
  const mime = 'audio/mp4;voice-duration=24000;voice-wave=' + '0123456789abcdef'.repeat(2);
  const note = { sender: writer, body: 'Voice note', at: { '.sv': 'timestamp' }, kind: 'file', mime };
  const bytes = 'AAAAHGZ0eXBpc29t';
  await assertSucceeds(family.update({ [message]: note, [audio]: bytes }));
  assert.equal((await recipient.child(message).get()).child('mime').val(), mime);
  assert.equal((await recipient.child(audio).get()).val(), bytes);
  await assertFails(nonMember.child(audio).get());
  const stamp = { '.sv': 'timestamp' };
  const deletion = { body: 'Message deleted', deletedAt: stamp };
  await assertFails(recipient.child(message).update(deletion));
  await assertFails(family.child(message).update({ body: 'Edited attachment', editedAt: stamp }));
  await assertSucceeds(family.child(message).update(deletion));
  const deleted = await recipient.child(message).get();
  assert.equal(deleted.child('body').val(), 'Message deleted');
  assert.ok(deleted.child('deletedAt').exists());
});

test('message-yourself text and attachments are private to the account owner', async () => {
  const path = 'familyPrivateChat/' + writer;
  const own = env.authenticatedContext(writer).database().ref(path);
  const member = env.authenticatedContext(reader).database().ref(path);
  const anonymous = env.unauthenticatedContext().database().ref(path);
  const stamp = { '.sv': 'timestamp' };
  const text = { sender: writer, body: 'Private text', at: stamp };
  await assertSucceeds(own.child('messages/note').set(text));
  const audio = 'AAAAHGZ0eXBpc29t';
  const voice = { ...text, body: 'Voice note', kind: 'file', mime: 'audio/mp4' };
  await assertSucceeds(own.update({
    'messages/voice-note': voice,
    'files/voice-note': audio
  }));
  assert.equal((await own.child('messages').orderByChild('at').limitToLast(60).get()).numChildren(), 2);
  assert.equal((await own.child('files/voice-note').get()).val(), audio);
  await assertFails(member.child('messages').get());
  await assertFails(member.child('files/voice-note').get());
  await assertFails(anonymous.child('messages/note').get());
  await assertFails(member.child('messages/injected').set({ ...text, sender: reader }));
  await assertFails(member.child('files/injected').set(audio));
  await assertFails(own.child('files/unlinked').set(audio));
  await assertFails(own.child('messages/forged').set({ ...text, sender: reader }));
  await assertSucceeds(own.child('messages/note').update({ body: 'Edited', editedAt: stamp }));
  await assertSucceeds(own.child('messages/voice-note').update({ body: 'Message deleted', deletedAt: stamp }));
});

test('multi-message deletion is atomic and restricted to the sender', async () => {
  const path = `familyChat/familyA/direct/${writer}/${reader}`;
  const owner = env.authenticatedContext(writer).database().ref(path);
  const recipient = env.authenticatedContext(reader).database().ref(path);
  const stamp = { '.sv': 'timestamp' };
  for (const key of ['bulk-first', 'bulk-second']) {
    await assertSucceeds(owner.child(key).set({ sender: writer, body: key, at: stamp }));
  }
  await assertSucceeds(recipient.child('bulk-other').set({ sender: reader, body: 'Other sender', at: stamp }));
  const deleteBatch = (...keys) => Object.fromEntries(keys.flatMap(key => [
    [key + '/body', 'Message deleted'],
    [key + '/deletedAt', stamp]
  ]));
  await assertFails(owner.update(deleteBatch('bulk-first', 'bulk-other')));
  assert.equal((await recipient.child('bulk-first/body').get()).val(), 'bulk-first');
  await assertSucceeds(owner.update(deleteBatch('bulk-first', 'bulk-second')));
  for (const key of ['bulk-first', 'bulk-second']) {
    assert.ok((await recipient.child(key + '/deletedAt').get()).exists());
  }
  assert.equal((await recipient.child('bulk-other/body').get()).val(), 'Other sender');
});

test('chat edits, deletion, deadlines and group names enforce ownership', async () => {
  const path = `familyChat/familyA/direct/${writer}/${reader}`;
  const a = env.authenticatedContext(writer).database().ref(path);
  const b = env.authenticatedContext(reader).database().ref(path);
  const stamp = { '.sv': 'timestamp' };
  await assertSucceeds(a.child('editable').set({ sender: writer, body: 'Before', at: stamp }));
  await assertFails(b.child('editable').update({ body: 'Forged', editedAt: stamp }));
  await assertFails(a.child('editable/body').set('No audit marker'));
  await assertSucceeds(a.child('editable').update({ body: 'After', editedAt: stamp }));
  assert.equal((await b.child('editable/body').get()).val(), 'After');
  await assertSucceeds(a.child('editable').update({ body: 'Message deleted', deletedAt: stamp }));
  await assertFails(a.child('editable').update({ body: 'Restore', editedAt: stamp }));
  const poll = { sender: writer, body: 'Dinner?', at: stamp, kind: 'poll', options: { 0: 'Yes', 1: 'No' } };
  await assertFails(a.child('expired-on-send').set({ ...poll, closesAt: 1 }));
  await assertSucceeds(a.child('open-until').set({ ...poll, closesAt: Date.now() + 60000 }));
  await assertSucceeds(b.child(`open-until/votes/${reader}`).set('0'));
  await env.withSecurityRulesDisabled(context => context.database().ref(path + '/open-until/closesAt').set(1));
  await assertFails(b.child(`open-until/votes/${reader}`).set('1'));
  const family = a.root.child('familyChat/familyA');
  await assertSucceeds(family.child('rooms/rename-test').set({ title: 'Old', creator: writer, members: { [writer]: true, [reader]: true } }));
  await assertFails(b.root.child('familyChat/familyA/rooms/rename-test/title').set('Forged'));
  await assertSucceeds(family.child('rooms/rename-test/title').set('New'));
  assert.equal((await b.root.child('familyChat/familyA/rooms/rename-test/title').get()).val(), 'New');
});

test('group management updates membership and inbox together and limits admin authority', async () => {
  const id = 'managed-room';
  const createBy = env.authenticatedContext(writer).database().ref('familyChat/familyA');
  const admin = env.authenticatedContext(reader).database().ref('familyChat/familyA');
  const outsider = 'profile-owner';
  const invited = env.authenticatedContext(outsider).database().ref('familyChat/familyA');
  const room = `rooms/${id}`;
  const entry = `roomIndex/${outsider}/${id}`;
  await assertSucceeds(createBy.child(room).set({ creator: writer, title: 'Group', members: { [writer]: true, [reader]: true } }));
  await assertFails(admin.child(`${room}/admins/${reader}`).set(true));
  await assertSucceeds(createBy.child(`${room}/admins/${reader}`).set(true));
  await assertFails(admin.child(`${room}/members/${outsider}`).set(true));
  await assertFails(invited.child(room).get());
  await assertSucceeds(admin.update({ [`${room}/members/${outsider}`]: true, [entry]: true }));
  assert.equal((await invited.child(entry).get()).val(), true);
  await assertSucceeds(invited.child(room).get());
  await assertFails(admin.update({ [`${room}/members/${writer}`]: null, [`roomIndex/${writer}/${id}`]: null }));
  await assertSucceeds(admin.update({ [`${room}/members/${outsider}`]: null, [entry]: null }));
  assert.equal((await invited.child(entry).get()).exists(), false);
  await assertFails(invited.child(room).get());
});

test('announcements and checklists stay within a chat and bookmarks stay private', async () => {
  const path = `familyChat/familyA/direct/${writer}/${reader}`;
  const a = env.authenticatedContext(writer).database();
  const b = env.authenticatedContext(reader).database();
  const other = env.authenticatedContext(outsider).database();
  const stamp = { '.sv': 'timestamp' };
  const announcement = { sender: writer, body: 'Pickup at 5', kind: 'announcement', at: stamp };
  await assertFails(a.ref(path + '/forged-announcement').set({ ...announcement, acks: { [reader]: true } }));
  await assertSucceeds(a.ref(path + '/announcement').set(announcement));
  await assertFails(a.ref(path + '/announcement/acks/' + reader).set(true));
  await assertFails(other.ref(path + '/announcement/acks/' + outsider).set(true));
  await assertSucceeds(b.ref(path + '/announcement/acks/' + reader).set(true));
  assert.equal((await a.ref(path + '/announcement/acks/' + reader).get()).val(), true);
  const checklist = { sender: writer, body: 'Groceries', kind: 'checklist', at: stamp, entries: { 0: 'Milk', 1: 'Rice' } };
  await assertFails(a.ref(path + '/invalid-checklist').set({ ...checklist, entries: { 9: 'Invalid' } }));
  await assertSucceeds(a.ref(path + '/checklist').set(checklist));
  await assertFails(b.ref(path + '/checklist/checks/0').set(writer));
  await assertFails(b.ref(path + '/checklist/checks/3').set(reader));
  await assertSucceeds(b.ref(path + '/checklist/checks/0').set(reader));
  await assertSucceeds(a.ref(path + '/checklist/checks/0').remove());
  const key = `chatSaved/${reader}/de305d54-75b4-431b-adb2-eb6b9e546014/announcement`;
  await assertFails(a.ref(key).set(true));
  await assertSucceeds(b.ref(key).set(true));
  await assertFails(a.ref(key).get());
  await assertSucceeds(b.ref(key).set(false));
});

test('status audience, receipts and 24-hour expiry', async () => {
  const status = 'familyTextStatus/familyA/' + writer;
  const ref = (uid, path) => env.authenticatedContext(uid).database().ref(path);
  await assertSucceeds(ref(writer, status).set({
    body: 'Only Bob', postedAt: { '.sv': 'timestamp' }, audience: { [reader]: true }
  }));
  const current = await assertSucceeds(ref(reader, status).once('value'));
  const receipts = 'familyStatusReceipts/familyA/' + writer + '/' + current.val().postedAt;
  await assertFails(ref('profile-owner', status).once('value'));
  await assertFails(ref(outsider, status).once('value'));
  await assertSucceeds(ref(reader, receipts + '/' + reader).set('liked'));
  await assertSucceeds(ref(writer, receipts).once('value'));
  await assertFails(ref(reader, receipts).once('value'));
  await assertFails(ref('profile-owner', receipts + '/profile-owner').set('seen'));
  await env.withSecurityRulesDisabled(ctx =>
    ctx.database().ref(status + '/postedAt').set(Date.now() - 86400001));
  await assertFails(ref(reader, status).once('value'));
  await assertFails(ref(reader, receipts + '/' + reader).set('seen'));
});

test('photo status accepts an optional caption and enforces the selected audience', async () => {
  const path = 'familyTextStatus/familyA/' + writer;
  const ref = uid => env.authenticatedContext(uid).database().ref(path);
  const status = {
    body: '', postedAt: { '.sv': 'timestamp' }, audience: { [reader]: true }
  };
  const jpegSignature = '/9j/QUJDREVGR0hJ';
  await assertFails(ref(writer).set(status));
  await assertFails(ref(writer).set({ ...status, photo: 'not-a-jpeg' }));
  await assertFails(ref(writer).set({ ...status, photo: '/9j/' + 'A'.repeat(524288) }));
  await assertSucceeds(ref(writer).set({ ...status, photo: jpegSignature }));
  const selected = await assertSucceeds(ref(reader).child('photo').get());
  assert.equal(selected.val(), jpegSignature);
  await assertFails(ref('profile-owner').child('photo').get());
  await assertFails(ref(outsider).child('photo').get());
});

test('video status retains audience restrictions and rejects oversized or mixed media', async () => {
  const path = 'familyTextStatus/familyA/' + writer;
  const ref = uid => env.authenticatedContext(uid).database().ref(path);
  const status = { body: '', postedAt: { '.sv': 'timestamp' }, audience: { [reader]: true } };
  const video = Buffer.alloc(5 * 1024 * 1024).toString('base64');
  await assertFails(ref(writer).set({ ...status, video: 'A'.repeat(6990509) }));
  await assertFails(ref(writer).set({ ...status, video, photo: '/9j/QUJDREVGR0hJ' }));
  await assertSucceeds(ref(writer).set({ ...status, video }));
  const selected = await assertSucceeds(ref(reader).child('video').get());
  assert.equal(selected.val(), video);
  await assertFails(ref('profile-owner').child('video').get());
  await assertFails(ref(outsider).child('video').get());
});

test('secure direct text allows only the two active participants and immutable encrypted records', async () => {
  const channel = 'familySecureText/familyA/' + writer + '/' + reader;
  const ref = (uid, path) => env.authenticatedContext(uid).database().ref(path);
  const payload = { sender: writer, at: { '.sv': 'timestamp' }, ciphertext: 'A'.repeat(40) };
  await assertFails(ref(writer, channel + '/plain').set({ ...payload, body: 'clear text' }));
  await assertFails(ref(reader, channel + '/forged').set(payload));
  await assertSucceeds(ref(writer, channel + '/sealed').set(payload));
  await assertSucceeds(ref(reader, channel).limitToLast(50).get());
  await assertFails(ref('profile-owner', channel).get());
  await assertFails(ref(outsider, channel).get());
  await assertFails(ref(writer, channel + '/sealed').remove());
});
