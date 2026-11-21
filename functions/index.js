const { initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getDatabase } = require("firebase-admin/database");
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { phoneDiscovery } = require("./phone-discovery");

initializeApp();

exports.connectionPhone = onCall({ region: "us-central1" }, async ({ auth, data }) => {
    try {
        return await phoneDiscovery(getAuth(), getDatabase().ref(), auth, data);
    } catch (error) {
        if (error.code && error.publicMessage) throw new HttpsError(error.code, error.publicMessage);
        throw new HttpsError("internal", "Mobile discovery is temporarily unavailable.");
    }
});
