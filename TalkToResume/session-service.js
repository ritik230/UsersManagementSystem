import { getCollections } from "./db.js";

const DEFAULT_SESSION_STATE = {
  selectedCandidate: null,
  lastCandidates: [],
  lastQuery: "",
  lastIntent: "",
  filters: {},
  history: []
};

export async function getSession(sessionId, userId) {
  const { sessionsCollection } = getCollections();
  const existing = await sessionsCollection.findOne({ sessionId, userId });
  if (existing) {
    return normalizeSession(existing);
  }

  const now = new Date();
  const fresh = {
    sessionId,
    userId,
    ...DEFAULT_SESSION_STATE,
    createdAt: now,
    updatedAt: now
  };

  await sessionsCollection.insertOne(fresh);
  return fresh;
}

export async function updateSession(sessionId, userId, updates) {
  const { sessionsCollection } = getCollections();
  const now = new Date();
  const patch = {
    ...updates,
    updatedAt: now
  };

  await sessionsCollection.updateOne(
    { sessionId, userId },
    {
      $set: patch,
      $setOnInsert: {
        sessionId,
        userId,
        createdAt: now
      }
    },
    { upsert: true }
  );

  return getSession(sessionId, userId);
}

export async function clearSession(sessionId, userId) {
  const { sessionsCollection } = getCollections();
  await sessionsCollection.deleteOne({ sessionId, userId });
}

function normalizeSession(sessionDoc) {
  if (!sessionDoc) {
    return null;
  }

  return {
    sessionId: sessionDoc.sessionId,
    userId: sessionDoc.userId,
    selectedCandidate: sessionDoc.selectedCandidate || null,
    lastCandidates: Array.isArray(sessionDoc.lastCandidates) ? sessionDoc.lastCandidates : [],
    lastQuery: sessionDoc.lastQuery || "",
    lastIntent: sessionDoc.lastIntent || "",
    filters: sessionDoc.filters || {},
    history: Array.isArray(sessionDoc.history) ? sessionDoc.history : [],
    createdAt: sessionDoc.createdAt,
    updatedAt: sessionDoc.updatedAt
  };
}
