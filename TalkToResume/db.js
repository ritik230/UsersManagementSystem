import { MongoClient } from "mongodb";

let client;
let db;
let usersCollection;
let candidatesCollection;
let sessionsCollection;

export async function connectDatabase() {
  const mongoUri = process.env.MONGODB_URI;
  const databaseName = process.env.MONGODB_DB || "talk_to_resume";
  if (!mongoUri) {
    throw new Error("MONGODB_URI is not configured.");
  }

  client = new MongoClient(mongoUri);
  await client.connect();
  db = client.db(databaseName);
  usersCollection = db.collection("users");
  candidatesCollection = db.collection("candidates");
  sessionsCollection = db.collection("sessions");

  await usersCollection.createIndex({ email: 1 }, { unique: true });
  await candidatesCollection.createIndex({ userId: 1 }, { unique: true });
  await candidatesCollection.createIndex({ name: 1 });
  await candidatesCollection.createIndex({ "metadata.role": 1 });
  await candidatesCollection.createIndex({ "metadata.location": 1 });
  await sessionsCollection.createIndex({ sessionId: 1, userId: 1 }, { unique: true });
  await sessionsCollection.createIndex({ updatedAt: -1 });
}

export function getCollections() {
  if (!usersCollection || !candidatesCollection) {
    throw new Error("Database is not connected.");
  }

  return {
    db,
    usersCollection,
    candidatesCollection,
    sessionsCollection
  };
}

export async function closeDatabase() {
  if (client) {
    await client.close();
  }

  client = null;
  db = null;
  usersCollection = null;
  candidatesCollection = null;
  sessionsCollection = null;
}
