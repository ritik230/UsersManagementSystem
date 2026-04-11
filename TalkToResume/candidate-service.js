import { ObjectId } from "mongodb";
import { getCollections } from "./db.js";
import { buildLocalSemanticProfile, createCandidateSemanticDocument, withEmbeddings } from "./embeddings.js";

const embeddingCache = new Map();

export async function createOrUpdateCandidateProfile(user, parsedData) {
  const { candidatesCollection } = getCollections();
  const payload = await buildCandidateStoragePayload({
    userId: user.id,
    parsedData,
    name: parsedData.name || user.name || "Candidate"
  });

  await candidatesCollection.updateOne(
    { userId: user.id },
    {
      $set: payload,
      $setOnInsert: {
        createdAt: new Date()
      }
    },
    { upsert: true }
  );

  const candidate = await candidatesCollection.findOne({ userId: user.id });
  return projectCandidate(candidate);
}

export async function buildCandidateStoragePayload({ userId, parsedData, name }) {
  const semanticProfile = await createSemanticProfile(parsedData);
  return {
    userId,
    name: name || parsedData.name || "Candidate",
    structuredData: parsedData,
    embedding: semanticProfile.overviewEmbedding,
    semanticProfile,
    metadata: semanticProfile.metadata,
    resumeScore: computeResumeScore(parsedData),
    skillGapAnalysis: buildSkillGapAnalysis(parsedData),
    suggestedImprovements: buildSuggestedImprovements(parsedData),
    updatedAt: new Date()
  };
}

export async function getCandidateProfileForUser(userId) {
  const { candidatesCollection } = getCollections();
  const candidate = await candidatesCollection.findOne({ userId });
  return projectCandidate(candidate);
}

export async function getCandidatePoolCount() {
  const { candidatesCollection } = getCollections();
  return candidatesCollection.countDocuments();
}

export async function getAllCandidateDocuments() {
  const { candidatesCollection } = getCollections();
  return candidatesCollection.find({}).toArray();
}

export function convertCandidateDocumentForRetrieval(candidate) {
  return {
    id: candidate._id.toString(),
    name: candidate.name,
    structuredData: candidate.structuredData,
    metadata: candidate.metadata,
    resumeScore: candidate.resumeScore,
    skillGapAnalysis: candidate.skillGapAnalysis || [],
    suggestedImprovements: candidate.suggestedImprovements || [],
    semanticProfile: candidate.semanticProfile || {
      provider: "mongo-stored",
      overviewText: candidate.structuredData?.summary || candidate.name,
      overviewEmbedding: candidate.embedding,
      metadata: candidate.metadata,
      chunks: []
    }
  };
}

export function exposeMatchedCandidate(candidate) {
  return {
    id: candidate.id,
    name: candidate.name,
    structuredData: candidate.parsedData,
    metadata: candidate.metadata,
    retrieval: candidate.retrieval,
    resumeScore: candidate.resumeScore,
    skillGapAnalysis: candidate.skillGapAnalysis,
    suggestedImprovements: candidate.suggestedImprovements
  };
}

async function createSemanticProfile(parsedData) {
  const geminiApiKey = process.env.GEMINI_API_KEY;
  const geminiEmbeddingModel = process.env.GEMINI_EMBEDDING_MODEL || "text-embedding-004";
  const document = createCandidateSemanticDocument(parsedData);
  if (!geminiApiKey) {
    return buildLocalSemanticProfile(parsedData);
  }

  try {
    const vectors = await embedTextsWithGemini(
      [document.overviewText, ...document.chunks.map((chunk) => chunk.text)],
      "RETRIEVAL_DOCUMENT"
    );
    return withEmbeddings(document, vectors, `gemini-${geminiEmbeddingModel}`);
  } catch {
    return buildLocalSemanticProfile(parsedData);
  }
}

async function embedTextsWithGemini(texts, taskType) {
  const geminiApiKey = process.env.GEMINI_API_KEY;
  const geminiEmbeddingModel = process.env.GEMINI_EMBEDDING_MODEL || "text-embedding-004";
  const embeddings = [];
  for (const text of texts) {
    const normalizedText = String(text || "").trim();
    const cacheKey = `${geminiEmbeddingModel}:${taskType}:${normalizedText}`;
    if (embeddingCache.has(cacheKey)) {
      embeddings.push(embeddingCache.get(cacheKey));
      continue;
    }

    const response = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${geminiEmbeddingModel}:embedContent`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-goog-api-key": geminiApiKey
      },
      body: JSON.stringify({
        model: `models/${geminiEmbeddingModel}`,
        taskType,
        content: {
          parts: [{ text: normalizedText }]
        }
      })
    });

    if (!response.ok) {
      throw new Error(`Gemini embedding request failed: ${await response.text()}`);
    }

    const payload = await response.json();
    const vector = payload.embedding?.values;
    if (!Array.isArray(vector) || !vector.length) {
      throw new Error("Gemini embedding response did not include a vector.");
    }

    embeddingCache.set(cacheKey, vector);
    embeddings.push(vector);
  }

  return embeddings;
}

function projectCandidate(candidate) {
  if (!candidate) return null;
  return {
    id: candidate._id.toString(),
    userId: candidate.userId,
    name: candidate.name,
    structuredData: candidate.structuredData,
    metadata: candidate.metadata,
    resumeScore: candidate.resumeScore,
    skillGapAnalysis: candidate.skillGapAnalysis || [],
    suggestedImprovements: candidate.suggestedImprovements || [],
    createdAt: candidate.createdAt,
    updatedAt: candidate.updatedAt
  };
}

function computeResumeScore(structuredData) {
  const skills = (structuredData.skills?.length || 0) * 6;
  const projects = (structuredData.projects?.length || 0) * 8;
  const experience = (structuredData.experience?.length || 0) * 10;
  const summary = structuredData.summary ? 12 : 0;
  return Math.max(35, Math.min(100, Math.round(skills + projects + experience + summary)));
}

function buildSkillGapAnalysis(structuredData) {
  const skills = structuredData.skills || [];
  const gaps = [];
  if (!skills.some((skill) => skill.category === "backend")) {
    gaps.push("Add clearer backend evidence with API, service, or platform work.");
  }
  if (!skills.some((skill) => skill.category === "tools")) {
    gaps.push("Show more tooling and deployment depth such as CI/CD, cloud, or automation tools.");
  }
  if ((structuredData.projects || []).length < 2) {
    gaps.push("Add more project examples with measurable outcomes and delivery impact.");
  }
  return gaps.slice(0, 3);
}

function buildSuggestedImprovements(structuredData) {
  const suggestions = [];
  if (!(structuredData.summary || "").includes("years")) {
    suggestions.push("Add a sharper summary with years of experience and primary role focus.");
  }
  if ((structuredData.projects || []).some((project) => !(project.features || []).length)) {
    suggestions.push("Make projects stronger by naming concrete features, responsibilities, and business outcomes.");
  }
  if ((structuredData.skills || []).length < 5) {
    suggestions.push("List more technologies explicitly so search and recruiter matching become stronger.");
  }
  return suggestions.slice(0, 3);
}
