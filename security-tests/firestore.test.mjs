import { before, after, beforeEach, test } from 'node:test';
import { readFile } from 'node:fs/promises';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, collection, query, where, getDoc, getDocs, setDoc, updateDoc, deleteDoc, writeBatch, serverTimestamp, Timestamp } from 'firebase/firestore';

let env;
const user = uid => env.authenticatedContext(uid, { email: uid + '@wvsu.edu.ph', email_verified: true }).firestore();
const profile = uid => ({ uid, name: uid, email: '', profileIconName: 'Person', department: 'CIT', course: 'BSIT', year: '3', rating: 0, swaps: 0, about: '', skillsToTeach: [], skillsLearning: [] });
const exchange = (id, status = 'ACTIVE') => ({ id, requesterUid: 'alice', responderUid: 'bob', skillOffered: 'Kotlin', skillWanted: 'Python', status,
  requesterMarkedDone: status === 'DONE', responderMarkedDone: status === 'DONE', requesterRated: false, responderRated: false,
  requesterTeachingRating: 0, requesterLearningRating: 0, responderTeachingRating: 0, responderLearningRating: 0, timestamp: Timestamp.now() });
const notification = id => ({ id, receiverUid: 'bob', type: 'SKILL_EXCHANGE', title: 'Skill exchange', content: 'Learn together', senderUid: 'alice', senderName: 'alice', senderIconName: 'Person', senderDept: 'CIT', skillOffered: 'Kotlin', skillWanted: 'Python', timestamp: serverTimestamp(), isRead: false, read: false });
const wall = id => ({ id, authorUid: 'bob', authorName: 'bob', authorIconName: 'Person', content: 'A post', timestamp: Timestamp.now(), likes: 0, likedBy: [], colorHex: '#FFFFFF', topComment: null, commentCount: 0, anonymous: false });
async function seed(path, data) { await env.withSecurityRulesDisabled(async context => setDoc(doc(context.firestore(), path), data)); }
before(async () => { env = await initializeTestEnvironment({ projectId: 'demo-westcon', firestore: { rules: await readFile('firestore.rules', 'utf8') } }); });
after(async () => { await env?.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); for (const uid of ['alice', 'bob', 'eve']) await seed('users/' + uid, profile(uid)); });

test('anonymous, unverified and non-school accounts cannot access member data', async () => {
  const contexts = [env.unauthenticatedContext(), env.authenticatedContext('alice', { email: 'alice@wvsu.edu.ph', email_verified: false }), env.authenticatedContext('outsider', { email: 'outsider@example.com', email_verified: true })];
  for (const context of contexts) await assertFails(getDoc(doc(context.firestore(), 'users/bob')));
  await assertSucceeds(getDoc(doc(user('alice'), 'users/bob')));
});
test('profiles are owner-only, bounded and cannot publish email or fake aggregate scores', async () => {
  await assertFails(updateDoc(doc(user('eve'), 'users/alice'), { name: 'Hijacked' }));
  await assertFails(updateDoc(doc(user('alice'), 'users/alice'), { email: 'private@example.invalid' }));
  await assertFails(updateDoc(doc(user('alice'), 'users/alice'), { rating: 5, swaps: 999 }));
  await assertFails(updateDoc(doc(user('alice'), 'users/alice'), { admin: true }));
  await assertSucceeds(updateDoc(doc(user('alice'), 'users/alice'), { about: 'A profile description' }));
});
test('legacy email-bearing profiles are hidden; public projection queries work', async () => {
  await seed('users/legacy', { ...profile('legacy'), email: 'private@example.invalid' });
  await assertFails(getDoc(doc(user('alice'), 'users/legacy')));
  await assertSucceeds(getDocs(query(collection(user('alice'), 'users'), where('email', '==', ''))));
});
test('skill posts cannot spoof their author or be deleted by somebody else', async () => {
  const db = user('alice');
  const data = { id: 'skill1', authorUid: 'alice', authorName: 'alice', authorIconName: 'Person', authorMastery: 1, department: 'CIT', category: 'IT', title: 'Kotlin', description: 'Learn Kotlin', postType: 'SHARE', anonymous: false, timestamp: serverTimestamp() };
  await assertFails(setDoc(doc(db, 'skills/spoof'), { ...data, id: 'spoof', authorUid: 'bob' }));
  await assertSucceeds(setDoc(doc(db, 'skills/skill1'), data));
  await assertFails(deleteDoc(doc(user('bob'), 'skills/skill1')));
  await assertSucceeds(deleteDoc(doc(db, 'skills/skill1')));
});
test('exchange requests are authentic, private and accepted only by the recipient', async () => {
  await assertSucceeds(setDoc(doc(user('alice'), 'notifications/request1'), notification('request1')));
  await assertFails(getDoc(doc(user('eve'), 'notifications/request1')));
  await assertFails(setDoc(doc(user('eve'), 'notifications/spoof'), { ...notification('spoof'), senderUid: 'alice' }));
  const fake = writeBatch(user('eve')); fake.set(doc(user('eve'), 'exchanges/request1'), { ...exchange('request1'), timestamp: serverTimestamp() }); fake.delete(doc(user('eve'), 'notifications/request1'));
  await assertFails(fake.commit());
  const db = user('bob'), batch = writeBatch(db);
  batch.set(doc(db, 'exchanges/request1'), { ...exchange('request1'), timestamp: serverTimestamp() });
  batch.delete(doc(db, 'notifications/request1'));
  await assertSucceeds(batch.commit());
  await assertFails(getDoc(doc(user('eve'), 'exchanges/request1')));
});
test('an exchange participant may mark only their own side complete', async () => {
  await seed('exchanges/ex1', exchange('ex1'));
  await assertFails(updateDoc(doc(user('eve'), 'exchanges/ex1'), { requesterMarkedDone: true }));
  await assertFails(updateDoc(doc(user('alice'), 'exchanges/ex1'), { responderMarkedDone: true }));
  await assertSucceeds(updateDoc(doc(user('alice'), 'exchanges/ex1'), { requesterMarkedDone: true, status: 'ACTIVE' }));
  await assertSucceeds(updateDoc(doc(user('bob'), 'exchanges/ex1'), { responderMarkedDone: true, status: 'DONE' }));
});
test('peer ratings require completion, the correct target, atomic flags and valid bounds', async () => {
  await seed('exchanges/ex1', exchange('ex1', 'DONE'));
  const db = user('alice');
  const rating = { exchangeId: 'ex1', raterUid: 'alice', targetUid: 'bob', teachingRating: 4, learningRating: 5, taughtSkillName: 'Python', learnedSkillName: 'Kotlin', timestamp: serverTimestamp() };
  await assertFails(setDoc(doc(db, 'exchange_ratings/ex1_alice'), rating));
  const write = (value) => { const batch = writeBatch(db); batch.set(doc(db, 'exchange_ratings/ex1_alice'), value); batch.update(doc(db, 'exchanges/ex1'), { requesterRated: true, responderTeachingRating: value.teachingRating, responderLearningRating: value.learningRating }); return batch.commit(); };
  await assertFails(write({ ...rating, targetUid: 'eve' }));
  await assertFails(write({ ...rating, teachingRating: 99 }));
  await assertSucceeds(write(rating));
  await assertFails(write(rating));
  await assertFails(updateDoc(doc(db, 'exchange_ratings/ex1_alice'), { teachingRating: 1 }));
});
test('messages require chat membership and authenticated sender; summaries commit together', async () => {
  const db = user('alice'), batch = writeBatch(db);
  batch.set(doc(db, 'messages/alice_bob'), { members: ['alice', 'bob'] });
  batch.set(doc(db, 'messages/alice_bob/history/m1'), { id: 'm1', senderUid: 'alice', receiverUid: 'bob', text: 'Hello', timestamp: serverTimestamp(), isRead: false, read: false });
  for (const [uid, other] of [['alice', 'bob'], ['bob', 'alice']]) batch.set(doc(db, 'chat_summaries/' + uid + '/chats/' + other), { otherUserUid: other, otherUserName: other, otherUserIconName: 'Person', otherUserDept: 'CIT', lastMessage: 'Hello', timestamp: serverTimestamp(), unreadCount: uid === 'alice' ? 0 : 1, lastMessageSenderUid: 'alice', lastMessageRead: uid === 'alice', isRead: uid === 'alice', otherUserOnline: false, typing: false, lastMessageId: 'm1', chatId: 'alice_bob' });
  await assertSucceeds(batch.commit());
  await assertSucceeds(getDocs(collection(user('bob'), 'messages/alice_bob/history')));
  await assertFails(getDocs(collection(user('eve'), 'messages/alice_bob/history')));
  await assertFails(setDoc(doc(user('eve'), 'messages/alice_bob/history/spoof'), { id: 'spoof', senderUid: 'alice', receiverUid: 'bob', text: 'Fake', timestamp: serverTimestamp(), isRead: false, read: false }));
  await assertSucceeds(updateDoc(doc(user('bob'), 'messages/alice_bob/history/m1'), { isRead: true, read: true }));
  await assertFails(updateDoc(doc(user('alice'), 'messages/alice_bob/history/m1'), { text: 'Changed' }));
  await assertFails(getDocs(collection(user('eve'), 'chat_summaries/bob/chats')));
});
test('likes can change only the caller membership and cannot inflate counts', async () => {
  await seed('freedom_wall/p1', wall('p1'));
  await assertFails(updateDoc(doc(user('alice'), 'freedom_wall/p1'), { likedBy: ['alice', 'eve'], likes: 2 }));
  await assertSucceeds(updateDoc(doc(user('alice'), 'freedom_wall/p1'), { likedBy: ['alice'], likes: 1 }));
  await assertFails(updateDoc(doc(user('eve'), 'freedom_wall/p1'), { likedBy: [], likes: 0 }));
  await assertFails(updateDoc(doc(user('alice'), 'freedom_wall/p1'), { content: 'Hijacked' }));
});
test('comments and counters must be an atomic, correctly authored write', async () => {
  await seed('freedom_wall/p1', wall('p1'));
  const db = user('alice'), batch = writeBatch(db);
  batch.set(doc(db, 'freedom_wall/p1/comments/c1'), { id: 'c1', postId: 'p1', authorUid: 'alice', authorName: 'alice', authorIconName: 'Person', content: 'A comment', timestamp: serverTimestamp(), anonymous: false });
  batch.update(doc(db, 'freedom_wall/p1'), { commentCount: 1, topComment: 'A comment', lastCommentId: 'c1' });
  await assertSucceeds(batch.commit());
  await assertFails(updateDoc(doc(db, 'freedom_wall/p1'), { commentCount: 999 }));
  await assertFails(updateDoc(doc(user('eve'), 'freedom_wall/p1/comments/c1'), { content: 'Replaced' }));
});
test('unknown collections are denied by default', async () => {
  await assertFails(setDoc(doc(user('alice'), 'admins/alice'), { role: 'admin' }));
  await assertFails(getDoc(doc(user('alice'), 'private/secrets')));
});
