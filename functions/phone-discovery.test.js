const test = require("node:test");
const assert = require("node:assert/strict");
const { phoneDiscovery } = require("./phone-discovery");

function fixture() {
    const data = new Map();
    function path(key = "") {
        return {
            child(part) { return path(key ? key + "/" + part : part); },
            async get() { return { val: () => data.get(key) }; },
            async set(value) { data.set(key, value); },
            async remove() { data.delete(key); },
            async update(values) { for (const [part, value] of Object.entries(values)) data.set(part, value); },
            async transaction(edit) {
                const next = edit(data.get(key));
                if (next !== undefined) data.set(key, next);
                return { committed: next !== undefined, snapshot: { val: () => data.get(key) } };
            }
        };
    }
    const users = [
        { uid: "alice", displayName: "Alice", emailVerified: true, phoneNumber: "+12025550101" },
        { uid: "bob", displayName: "Bob", emailVerified: true, phoneNumber: "+12025550102" }
    ];
    const auth = {
        async getUser(uid) { return users.find(user => user.uid === uid); },
        async getUserByPhoneNumber(number) {
            const user = users.find(item => item.phoneNumber === number);
            if (!user) throw Object.assign(new Error("missing"), { code: "auth/user-not-found" });
            return user;
        }
    };
    return {
        data,
        users,
        run: (uid, data, now = 1_000) =>
            phoneDiscovery(auth, path(), { uid, token: { email_verified: true } }, data, now)
    };
}

test("only opted-in verified users match, and changing a number invalidates its consent", async () => {
    const { run, users } = fixture();
    const number = users[1].phoneNumber;
    assert.deepEqual(await run("alice", { operation: "find", number }), { outcome: "unavailable" });
    await run("bob", { operation: "consent", enabled: true });
    const match = await run("alice", { operation: "find", number });
    assert.equal(match.name, "Bob");
    assert.equal(match.number, undefined);
    users[1].phoneNumber = "+12025550103";
    assert.deepEqual(await run("alice", { operation: "find", number: users[1].phoneNumber }), {
        outcome: "unavailable"
    });
    assert.deepEqual(await run("bob", { operation: "status" }), { verified: true, enabled: false });
    await run("bob", { operation: "consent", enabled: true });
    await run("bob", { operation: "consent", enabled: false });
    assert.deepEqual(await run("alice", { operation: "find", number: users[1].phoneNumber }), {
        outcome: "unavailable"
    });
});

test("sending a request rechecks consent and the exact match, and duplicate sends reuse it", async () => {
    const { run, users, data } = fixture();
    const number = users[1].phoneNumber;
    await run("bob", { operation: "consent", enabled: true });
    const { match } = await run("alice", { operation: "find", number });
    assert.equal((await run("alice", { operation: "request", number, match: "wrong" })).outcome, "unavailable");
    assert.equal((await run("alice", { operation: "request", number, match })).outcome, "pending");
    assert.equal((await run("alice", { operation: "request", number, match })).outcome, "pending");
    const invitations = [...data.entries()].filter(([key]) => key.startsWith("chatConnectionInvites/"));
    assert.equal(invitations.length, 1);
    assert.equal(invitations[0][1].recipient, "bob");
    assert.equal(invitations[0][1].status, "pending");
    assert.equal([...data.keys()].filter(key => key.startsWith("chatConnectionIndex/")).length, 2);
    await run("bob", { operation: "consent", enabled: false });
    assert.equal((await run("alice", { operation: "request", number, match })).outcome, "unavailable");
});

test("lookup requires a verified sender phone and enforces a server-side quota", async () => {
    const { run, users } = fixture();
    const lookup = { operation: "find", number: users[1].phoneNumber };
    for (let i = 0; i < 10; i++) await run("alice", lookup);
    await assert.rejects(run("alice", lookup), { code: "resource-exhausted" });
    users[0].phoneNumber = undefined;
    await assert.rejects(run("alice", lookup, 86_402_000), { code: "failed-precondition" });
});
