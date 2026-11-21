const { createHash } = require("node:crypto");

const DAY_MS = 86_400_000;
const LIMITS = {
    status: [30, 60_000],
    consent: [10, 60_000],
    find: [10, 86_400_000],
    request: [5, 86_400_000]
};

function reject(code, publicMessage) {
    throw Object.assign(new Error(publicMessage), { code, publicMessage });
}

function hash(value) {
    return createHash("sha256").update(JSON.stringify(value)).digest("hex");
}

function displayName(user) {
    return user.displayName?.trim().slice(0, 80) || "Family Hub user";
}

async function throttle(root, uid, operation, now) {
    const [limit, duration] = LIMITS[operation];
    const slot = root.child("chatPhoneRateLimits").child(uid).child(operation);
    const result = await slot.transaction(current => {
        if (!current || now - current.start >= duration) return { start: now, count: 1 };
        if (current.count >= limit) return undefined;
        return { start: current.start, count: current.count + 1 };
    });
    if (!result.committed) reject("resource-exhausted", "Too many attempts. Try again later.");
}

async function findTarget(auth, root, number, senderUid) {
    let target;
    try {
        target = await auth.getUserByPhoneNumber(number);
    } catch (error) {
        if (error.code === "auth/user-not-found") return null;
        throw error;
    }
    if (target.uid === senderUid || target.disabled || !target.emailVerified) return null;
    const consent = await root.child("chatPhoneDiscovery").child(target.uid).get();
    return consent.val() === hash([target.uid, number]) ? target : null;
}

async function requestConnection(root, sender, target, now) {
    const code = hash([sender.uid, target.uid].sort()).slice(0, 32);
    const ref = root.child("chatConnectionInvites").child(code);
    const write = await ref.transaction(entry => {
        if (entry && (entry.status === "accepted" || now - entry.createdAt < DAY_MS)) return undefined;
        return {
            owner: sender.uid,
            ownerName: displayName(sender),
            recipient: target.uid,
            recipientName: displayName(target),
            status: "pending",
            createdAt: now
        };
    });
    const entry = write.snapshot.val();
    if (!entry || (entry.status !== "pending" && entry.status !== "accepted")) {
        return { outcome: "unavailable" };
    }
    // Repeating an interrupted request repairs both indexes without creating another invitation.
    await root.update({
        ["chatConnectionIndex/" + sender.uid + "/" + code]: true,
        ["chatConnectionIndex/" + target.uid + "/" + code]: true
    });
    return { outcome: entry.status === "accepted" ? "connected"
        : entry.recipient === sender.uid ? "incoming" : "pending" };
}

async function phoneDiscovery(auth, root, session, data, now = Date.now()) {
    const uid = session?.uid;
    if (!uid || session.token?.email_verified !== true) {
        reject("unauthenticated", "Sign in with a verified Family Hub account.");
    }
    const operation = data?.operation;
    if (typeof operation !== "string" || !Object.prototype.hasOwnProperty.call(LIMITS, operation)) {
        reject("invalid-argument", "Unknown mobile discovery action.");
    }
    if (operation !== "consent" || data.enabled !== false) await throttle(root, uid, operation, now);
    const sender = await auth.getUser(uid);
    if (sender.disabled || !sender.emailVerified) reject("permission-denied", "Account unavailable.");
    const consent = root.child("chatPhoneDiscovery").child(uid);
    const phone = sender.phoneNumber;

    if (operation === "consent") {
        if (typeof data.enabled !== "boolean") reject("invalid-argument", "Choose a discovery preference.");
        if (!data.enabled) await consent.remove();
        else {
            if (!phone) reject("failed-precondition", "Verify your mobile number first.");
            await consent.set(hash([uid, phone]));
        }
        return { enabled: data.enabled };
    }
    if (operation === "status") {
        const current = await consent.get();
        return { verified: !!phone, enabled: !!phone && current.val() === hash([uid, phone]) };
    }
    if (!phone) reject("failed-precondition", "Verify your mobile number first.");
    if (typeof data.number !== "string" || !/^\+[1-9]\d{7,14}$/.test(data.number)) {
        reject("invalid-argument", "Enter a full mobile number with its country code.");
    }
    const target = await findTarget(auth, root, data.number, uid);
    if (!target) return { outcome: "unavailable" };
    const match = hash([uid, target.uid, data.number]);
    if (operation === "find") return { outcome: "found", name: displayName(target), match };
    if (data.match !== match) return { outcome: "unavailable" };
    return requestConnection(root, sender, target, now);
}

module.exports = { phoneDiscovery };
