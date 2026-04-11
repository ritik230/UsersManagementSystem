import { ObjectId } from "mongodb";
import { getCollections } from "./db.js";
import { getSession, updateSession } from "./session-service.js";
import { parseRecruiterQuery, mergeFilters, buildSearchText } from "./query-parser.js";
import { convertCandidateDocumentForRetrieval, exposeMatchedCandidate } from "./candidate-service.js";
import { embedText, cosineSimilarity, keywordFallbackRanking, rankResumesByQuery } from "./embeddings.js";

const EMBEDDING_CACHE = new Map();
const MAX_HISTORY = 6;

export async function answerRecruiterQuestion({ recruiter, question, sessionId }) {
  const { candidatesCollection } = getCollections();
  const activeSessionId = normalizeSessionId(sessionId);
  const session = await getSession(activeSessionId, recruiter.id);
  const parsed = parseRecruiterQuery(question, session);
  const mergedFilters = mergeFilters(session.filters || {}, parsed.filters);
  const searchText = buildSearchText(question, mergedFilters, session, parsed);

  const allCandidates = await candidatesCollection.find({}).toArray();
  if (!allCandidates.length) {
    throw new Error("No candidate profiles are available yet.");
  }

  const pool = allCandidates.map(convertCandidateDocumentForRetrieval);
  const scopedPool = scopeCandidatesToSession(pool, session, parsed);
  const semanticPool = scopedPool.length ? scopedPool : pool;

  const ranked = await hybridRankCandidates({
    candidates: semanticPool,
    question: searchText,
    parsed,
    filters: mergedFilters,
    candidatesCollection
  });

  const shortlisted = selectShortlist(ranked, parsed);
  const selectedCandidate = chooseSelectedCandidate(shortlisted, session, parsed);
  const persistedCandidates = getPersistedCandidates(shortlisted, parsed);
  const answerPayload = buildAnswer({
    question,
    parsed,
    shortlisted,
    selectedCandidate,
    session,
    filters: mergedFilters
  });

  const updatedHistory = trimHistory([
    ...(session.history || []),
    {
      query: question,
      filters: mergedFilters
    }
  ]);

  const nextSession = await updateSession(activeSessionId, recruiter.id, {
    selectedCandidate,
    lastCandidates: persistedCandidates.map((candidate) => ({
      id: candidate.id,
      name: candidate.name,
      metadata: candidate.metadata || {}
    })),
    lastQuery: question,
    lastIntent: parsed.intent,
    filters: mergedFilters,
    history: updatedHistory
  });

  const confidence = computeConfidence({ answer: answerPayload.answer, shortlisted, parsed, selectedCandidate, filters: mergedFilters });

  return {
    sessionId: activeSessionId,
    answer: answerPayload.answer,
    confidence,
    decisionMemory: {
      selectedCandidate: nextSession.selectedCandidate || null,
      lastCandidates: nextSession.lastCandidates || [],
      filters: nextSession.filters || {},
      lastQuery: nextSession.lastQuery || "",
      lastIntent: nextSession.lastIntent || ""
    },
    matchedCandidates: shortlisted.map(exposeRankedCandidate),
    totalCandidates: allCandidates.length
  };
}

async function hybridRankCandidates({ candidates, question, parsed, filters, candidatesCollection }) {
  const normalizedCandidates = candidates.map((candidate) => ({
    ...candidate,
    semanticProfile: candidate.semanticProfile || candidate.semanticProfile || null
  }));

  const vectorMode = determineEmbeddingMode(normalizedCandidates);
  const queryVector = await buildQueryEmbedding(question, vectorMode);
  const atlasCandidates = await tryAtlasVectorSearch({
    candidatesCollection,
    queryVector,
    filters,
    mode: vectorMode
  });

  const baseCandidates = atlasCandidates.length ? atlasCandidates : rankCandidatesLocally(normalizedCandidates, question, queryVector);
  const filtered = applyStructuredFilters(baseCandidates, filters);
  const relaxed = filtered.length ? filtered : applyRelaxedFilters(baseCandidates, filters);
  const ranked = relaxed.map((candidate) => scoreCandidate(candidate, filters, parsed, queryVector))
    .sort((left, right) => right.finalScore - left.finalScore);

  return ranked;
}

async function tryAtlasVectorSearch({ candidatesCollection, queryVector, filters, mode }) {
  if (!process.env.MONGODB_VECTOR_INDEX || !queryVector?.length || mode === "mixed") {
    return [];
  }

  try {
    const pipeline = [
      {
        $vectorSearch: {
          index: process.env.MONGODB_VECTOR_INDEX,
          path: "embedding",
          queryVector,
          numCandidates: 100,
          limit: 20
        }
      },
      {
        $project: {
          _id: 1,
          userId: 1,
          name: 1,
          structuredData: 1,
          embedding: 1,
          semanticProfile: 1,
          metadata: 1,
          resumeScore: 1,
          skillGapAnalysis: 1,
          suggestedImprovements: 1,
          vectorScore: { $meta: "vectorSearchScore" }
        }
      }
    ];

    const docs = await candidatesCollection.aggregate(pipeline).toArray();
    return docs.map((doc) => ({
      id: doc._id.toString(),
      name: doc.name,
      parsedData: doc.structuredData,
      metadata: doc.metadata || {},
      resumeScore: doc.resumeScore,
      skillGapAnalysis: doc.skillGapAnalysis || [],
      suggestedImprovements: doc.suggestedImprovements || [],
      semanticProfile: doc.semanticProfile,
      vectorScore: normalizeScore(doc.vectorScore)
    }));
  } catch {
    return [];
  }
}

function rankCandidatesLocally(candidates, question, queryVector) {
  const semanticRanking = rankResumesByQuery(candidates, question, Math.max(5, candidates.length), {
    queryEmbedding: queryVector
  });
  return semanticRanking.matches.map((candidate) => ({
    id: candidate.id,
    name: candidate.name,
    parsedData: candidate.structuredData,
    metadata: candidate.metadata || {},
    resumeScore: candidate.resumeScore,
    skillGapAnalysis: candidate.skillGapAnalysis || [],
    suggestedImprovements: candidate.suggestedImprovements || [],
    semanticProfile: candidate.semanticProfile,
    vectorScore: normalizeScore(candidate.retrieval?.score)
  }));
}

function applyStructuredFilters(candidates, filters) {
  const filtered = candidates.filter((candidate) => matchesFilters(candidate, filters));
  return filtered;
}

function applyRelaxedFilters(candidates, filters) {
  const hasStrictRole = Boolean(filters.role);
  const hasSkill = Array.isArray(filters.skills) && filters.skills.length > 0;
  const hasLocation = Boolean(filters.location);
  const minExperience = Number(filters.experience || 0);

  return candidates.filter((candidate) => {
    const profile = candidate.parsedData || {};
    const metadata = candidate.metadata || {};
    const candidateSkills = getCandidateSkills(profile);
    const candidateExperience = getCandidateExperience(profile);

    const roleOk = !hasStrictRole || matchesRole(candidate, filters.role);
    const skillOk = !hasSkill || candidateSkills.some((skill) => filters.skills.some((requested) => skill.toLowerCase().includes(requested.toLowerCase())));
    const locationOk = !hasLocation || matchesLocation(candidate, filters.location);
    const experienceOk = !minExperience || candidateExperience >= Math.max(0, minExperience - 1);

    return roleOk || skillOk || locationOk || experienceOk;
  });
}

function matchesFilters(candidate, filters) {
  const profile = candidate.parsedData || {};
  const metadata = candidate.metadata || {};
  const candidateSkills = getCandidateSkills(profile);
  const candidateExperience = getCandidateExperience(profile);

  if (filters.role && !matchesRole(candidate, filters.role)) {
    return false;
  }

  if (filters.location && !matchesLocation(candidate, filters.location)) {
    return false;
  }

  if (Number(filters.experience || 0) > 0 && candidateExperience < Number(filters.experience || 0)) {
    return false;
  }

  if (Array.isArray(filters.skills) && filters.skills.length) {
    const skillHit = candidateSkills.some((skill) => filters.skills.some((requested) => skill.toLowerCase().includes(requested.toLowerCase())));
    if (!skillHit) {
      return false;
    }
  }

  return true;
}

function matchesRole(candidate, role) {
  const metadataRole = String(candidate.metadata?.role || "").toLowerCase();
  const summary = String(candidate.parsedData?.summary || "").toLowerCase();
  const strengths = (candidate.parsedData?.strengths || []).map((item) => String(item).toLowerCase());
  const roleText = String(role || "").toLowerCase();
  return metadataRole === roleText || summary.includes(roleText) || strengths.some((value) => value.includes(roleText));
}

function matchesLocation(candidate, location) {
  const candidateLocation = String(candidate.metadata?.location || candidate.parsedData?.location || "").toLowerCase();
  return candidateLocation.includes(String(location || "").toLowerCase());
}

function scoreCandidate(candidate, filters, parsed, queryVector) {
  const candidateSkills = getCandidateSkills(candidate.parsedData || {});
  const candidateExperience = getCandidateExperience(candidate.parsedData || {});
  const requestedSkills = Array.isArray(filters.skills) ? filters.skills : [];
  const vectorSimilarity = normalizeScore(candidate.vectorScore ?? candidate.retrieval?.score ?? 0);
  const experienceMatch = requestedSkills.length || Number(filters.experience || 0)
    ? clamp(candidateExperience / Math.max(Number(filters.experience || 0), 1), 0, 1)
    : clamp(candidateExperience / 8, 0.3, 1);
  const skillMatches = requestedSkills.length
    ? requestedSkills.filter((skill) => candidateSkills.some((candidateSkill) => candidateSkill.toLowerCase().includes(skill.toLowerCase()))).length
    : Math.min(candidateSkills.length / 6, 1);
  const skillMatch = requestedSkills.length ? skillMatches / requestedSkills.length : skillMatches;

  const finalScore = (vectorSimilarity * 0.7) + (experienceMatch * 0.2) + (skillMatch * 0.1);
  const reason = buildReason(candidate, filters, candidateSkills, candidateExperience, finalScore);
  return {
    ...candidate,
    vectorScore: vectorSimilarity,
    experienceMatch,
    skillMatch,
    finalScore: round(finalScore),
    reason,
    retrieval: {
      score: round(vectorSimilarity),
      topChunks: candidate.semanticProfile?.chunks?.slice(0, 3).map((chunk) => ({
        type: chunk.type,
        label: chunk.label,
        text: chunk.text,
        similarity: round(normalizeScore(chunk.embedding ? cosineSimilarity(queryVector, chunk.embedding) : 0))
      })) || []
    }
  };
}

function buildReason(candidate, filters, candidateSkills, candidateExperience, finalScore) {
  const parts = [];
  if (filters.role) {
    parts.push(`matched role ${filters.role}`);
  }
  if (Array.isArray(filters.skills) && filters.skills.length) {
    const matchedSkills = filters.skills.filter((requested) => candidateSkills.some((candidateSkill) => candidateSkill.toLowerCase().includes(requested.toLowerCase())));
    if (matchedSkills.length) {
      parts.push(`matched ${matchedSkills.join(", ")}`);
    }
  }
  if (filters.location && matchesLocation(candidate, filters.location)) {
    parts.push(`location ${filters.location}`);
  }
  if (Number(filters.experience || 0) > 0) {
    parts.push(`${round(candidateExperience)} years experience`);
  } else {
    parts.push(`${round(candidateExperience)} years experience`);
  }
  return parts.join(" + ") || `score ${round(finalScore)}`;
}

function selectShortlist(rankedCandidates, parsed) {
  if (!rankedCandidates.length) {
    return [];
  }

  if (parsed.explicitSingle) {
    return rankedCandidates.slice(0, 1);
  }

  if (parsed.salary || parsed.comparison) {
    return rankedCandidates.slice(0, 3);
  }

  return rankedCandidates.slice(0, 5);
}

function getPersistedCandidates(shortlisted, parsed) {
  if (!Array.isArray(shortlisted) || !shortlisted.length) {
    return [];
  }

  if (parsed.comparison) {
    return shortlisted.slice(0, Math.min(2, shortlisted.length));
  }

  if (parsed.availability || parsed.exclusion) {
    return shortlisted.slice(0, Math.min(5, shortlisted.length));
  }

  if (parsed.salary || parsed.contact || parsed.project || parsed.explicitSingle || parsed.intent === "single") {
    return shortlisted.slice(0, 1);
  }

  return shortlisted.slice(0, 1);
}

function chooseSelectedCandidate(shortlisted, session, parsed) {
  if (!shortlisted.length) {
    return null;
  }

  if (parsed.exclusion || parsed.availability || parsed.followUp) {
    return session.selectedCandidate || null;
  }

  if (parsed.explicitSingle) {
    return session.selectedCandidate || toSelectedCandidate(shortlisted[0]);
  }

  if (parsed.intent === "comparison") {
    return toSelectedCandidate(shortlisted[0]);
  }

  const top = shortlisted[0];
  return top ? toSelectedCandidate(top) : session.selectedCandidate ? session.selectedCandidate : null;
}

function buildAnswer({ question, parsed, shortlisted, selectedCandidate, session, filters }) {
  if (!shortlisted.length) {
    return {
      answer: "This information is not clearly available in the provided resumes.",
      confidence: 28
    };
  }

  if (parsed.salary) {
    return buildSalaryAnswer(shortlisted, selectedCandidate, session, parsed, question);
  }

  if (parsed.explicitSingle) {
    const top = shortlisted[0];
    return {
      answer: formatContactLine(top),
      confidence: 92
    };
  }

  switch (parsed.intent) {
    case "contact":
      return buildContactAnswer(shortlisted, selectedCandidate, parsed, question);
    case "project":
      return buildProjectAnswer(shortlisted, selectedCandidate, parsed, question);
    case "comparison":
      return buildComparisonAnswer(shortlisted, session, parsed);
    case "exclusion":
      return buildExclusionAnswer(shortlisted);
    case "availability":
      return buildListAnswer(shortlisted, filters);
    case "single":
      return {
        answer: formatContactLine(shortlisted[0]),
        confidence: 92
      };
    default:
      return buildBestAnswer(shortlisted);
  }
}

function buildListAnswer(shortlisted, filters) {
  const intro = filters.role || (Array.isArray(filters.skills) && filters.skills.length)
    ? `Yes, here are the closest matches for ${filters.role || filters.skills.join(", ")}:`
    : "Here are the strongest matches:";

  const lines = shortlisted.map((candidate, index) => {
    return `${index + 1}. ${candidate.name} - ${candidate.reason}`;
  });
  return [intro, ...lines].join("\n");
}

function buildComparisonAnswer(shortlisted, session, parsed) {
  const pluralMode = Boolean(parsed?.pluralReference) || (Array.isArray(session?.lastCandidates) && session.lastCandidates.length > 1);
  if (pluralMode && shortlisted.length) {
    const lines = shortlisted.slice(0, 5).map((candidate, index) => `${index + 1}. ${candidate.name} - ${candidate.reason}`);
    const top = shortlisted[0];
    return {
      answer: `Here is the comparison for the current shortlist:\n${lines.join("\n")}${top ? `\n\n${top.name} is the strongest match because ${top.reason}.` : ""}`,
      confidence: 90
    };
  }

  const top = shortlisted[0];
  if (!top) {
    return {
      answer: "This information is not clearly available in the provided resumes.",
      confidence: 28
    };
  }

  if (shortlisted.length === 1) {
    return {
      answer: `${top.name} is the strongest match because ${top.reason}.`,
      confidence: 90
    };
  }

  const second = shortlisted[1];
  return {
    answer: `${top.name} is the strongest match because ${top.reason}. ${second.name} is the next best option because ${second.reason}.`,
    confidence: 90
  };
}

function buildExclusionAnswer(shortlisted) {
  if (!shortlisted.length) {
    return {
      answer: "No additional matching candidates are available beyond the current shortlist.",
      confidence: 42
    };
  }

  const lines = shortlisted.slice(0, 5).map((candidate, index) => `${index + 1}. ${candidate.name} - ${candidate.reason}`);
  return {
    answer: `Here are other candidates apart from the current shortlist:\n${lines.join("\n")}`,
    confidence: 84
  };
}

function buildBestAnswer(shortlisted) {
  const top = shortlisted[0];
  if (!top) {
    return {
      answer: "This information is not clearly available in the provided resumes.",
      confidence: 28
    };
  }

  return {
    answer: `${top.name} is the strongest match because ${top.reason}.`,
    confidence: 88
  };
}

function buildProjectAnswer(shortlisted, selectedCandidate, parsed, question) {
  const pluralMode = Boolean(parsed?.pluralReference) || (parsed?.followUp && !parsed?.singularReference && shortlisted.length > 1);
  if (pluralMode) {
    const lines = shortlisted.slice(0, 3).map((candidate, index) => {
      const projects = Array.isArray(candidate.parsedData?.projects) ? candidate.parsedData.projects.slice(0, 2) : [];
      const projectSummary = projects.length
        ? projects.map((project) => `${project.name} (${Array.isArray(project.techStack) && project.techStack.length ? project.techStack.join(", ") : "relevant technologies"})`).join("; ")
        : "No clear project details are available";
      return `${index + 1}. ${candidate.name} - ${projectSummary}`;
    });

    return {
      answer: `Here are the relevant projects for the current shortlist:\n${lines.join("\n")}`,
      confidence: 86
    };
  }

  const target = resolveTargetCandidate(shortlisted, selectedCandidate) || shortlisted[0];
  if (!target) {
    return {
      answer: "This information is not clearly available in the provided resumes.",
      confidence: 28
    };
  }

  const projects = target.parsedData?.projects || [];
  if (!projects.length) {
    return {
      answer: `No clear project details are available for ${target.name} in the provided resumes.`,
      confidence: 48
    };
  }

  const lines = projects.slice(0, 3).map((project, index) => {
    const stack = Array.isArray(project.techStack) && project.techStack.length ? project.techStack.join(", ") : "relevant technologies";
    return `${index + 1}. ${project.name} - ${project.description} Technologies: ${stack}.`;
  });

  return {
    answer: `${target.name}'s key projects:\n${lines.join("\n")}`,
    confidence: 88
  };
}

function buildContactAnswer(shortlisted, selectedCandidate, parsed, question) {
  const pluralMode = Boolean(parsed?.pluralReference) || (parsed?.followUp && !parsed?.singularReference && shortlisted.length > 1);
  if (pluralMode) {
    const lines = shortlisted.slice(0, 3).map((candidate, index) => {
      const contact = candidate.parsedData?.contactDetails || {};
      const parts = [contact.email, contact.phone].filter(Boolean);
      return parts.length ? `${index + 1}. ${candidate.name} — ${parts.join(" | ")}` : `${index + 1}. ${candidate.name}`;
    });

    return {
      answer: `Here are the contact details for the current shortlist:\n${lines.join("\n")}`,
      confidence: 88
    };
  }

  const target = resolveTargetCandidate(shortlisted, selectedCandidate) || shortlisted[0];
  if (!target) {
    return {
      answer: "No matching candidate is available right now.",
      confidence: 28
    };
  }

  const contact = target.parsedData?.contactDetails || {};
  const parts = [];
  if (contact.email) parts.push(contact.email);
  if (contact.phone) parts.push(contact.phone);
  if (!parts.length) {
    return {
      answer: `${target.name}: contact details are not clearly available in the provided resumes.`,
      confidence: 48
    };
  }

  return {
    answer: `${target.name} — ${parts.join(" | ")}`,
    confidence: 92
  };
}

function buildSalaryAnswer(shortlisted, selectedCandidate, session, parsed, question) {
  const pluralMode = Boolean(parsed?.pluralReference) || /\b(both|both of them|these two|those two)\b/i.test(String(question || "").toLowerCase());
  const activeShortlist = pluralMode ? shortlisted.slice(0, 5) : shortlisted;

  if (pluralMode && activeShortlist.length > 1) {
    const lines = activeShortlist.map((candidate, index) => {
      const years = getCandidateExperience(candidate?.structuredData || candidate?.parsedData || {});
      let min = 4;
      let max = 7;
      if (years >= 2) {
        min = 6;
        max = 10;
      }
      if (years >= 4) {
        min = 10;
        max = 16;
      }
      if (years >= 7) {
        min = 15;
        max = 24;
      }
      return `${index + 1}. ${candidate.name}: estimated market range ${min}-${max} LPA based on roughly ${round(years)} years of experience and visible skill depth.`;
    });

    return {
      answer: lines.join("\n"),
      confidence: 86
    };
  }

  const target = resolveTargetCandidate(activeShortlist, selectedCandidate) || activeShortlist[0] || shortlisted[0];
  const years = getCandidateExperience(target?.parsedData || {});
  let min = 4;
  let max = 7;
  if (years >= 2) {
    min = 6;
    max = 10;
  }
  if (years >= 4) {
    min = 10;
    max = 16;
  }
  if (years >= 7) {
    min = 15;
    max = 24;
  }
    return {
    answer: `${target?.name || shortlisted[0].name}: estimated market range ${min}-${max} LPA based on roughly ${round(years)} years of experience and visible skill depth.`,
    confidence: 88
  };
}

function formatContactLine(candidate) {
  if (!candidate) {
    return "No matching candidate is available right now.";
  }

  const contact = candidate.parsedData?.contactDetails || {};
  const parts = [contact.email, contact.phone].filter(Boolean);
  if (!parts.length) {
    return candidate.name;
  }
  return `${candidate.name} — ${parts.join(" | ")}`;
}

function toSelectedCandidate(candidate) {
  if (!candidate) return null;
  return {
    id: candidate.id,
    name: candidate.name,
    metadata: candidate.metadata || {}
  };
}

function resolveTargetCandidate(shortlisted, selectedCandidate) {
  if (!selectedCandidate) {
    return shortlisted[0] || null;
  }

  const resolved = shortlisted.find((candidate) => candidate.id === selectedCandidate.id || candidate.name === selectedCandidate.name);
  return resolved || shortlisted[0] || null;
}

function exposeRankedCandidate(candidate) {
  return {
    id: candidate.id,
    name: candidate.name,
    structuredData: candidate.parsedData,
    metadata: candidate.metadata,
    resumeScore: candidate.resumeScore,
    skillGapAnalysis: candidate.skillGapAnalysis,
    suggestedImprovements: candidate.suggestedImprovements,
    retrieval: {
      score: candidate.finalScore,
      reason: candidate.reason,
      vectorScore: candidate.vectorScore,
      experienceMatch: candidate.experienceMatch,
      skillMatch: candidate.skillMatch,
      topChunks: candidate.retrieval?.topChunks || []
    }
  };
}

function scopeCandidatesToSession(pool, session, parsed) {
  if (parsed.exclusion && Array.isArray(session.lastCandidates) && session.lastCandidates.length) {
    const excludedIds = new Set(session.lastCandidates.map((candidate) => candidate.id));
    return pool.filter((candidate) => !excludedIds.has(candidate.id));
  }

  if (parsed.pluralReference && Array.isArray(session.lastCandidates) && session.lastCandidates.length && !parsed.comparison) {
    const lastIds = session.lastCandidates.map((candidate) => candidate.id);
    return pool.filter((candidate) => lastIds.includes(candidate.id));
  }

  if (parsed.singularReference && session.selectedCandidate && !parsed.comparison) {
    return pool.filter((candidate) => candidate.id === session.selectedCandidate.id || candidate.name === session.selectedCandidate.name);
  }

  if (parsed.followUp && session.selectedCandidate && !parsed.comparison) {
    return pool.filter((candidate) => candidate.id === session.selectedCandidate.id || candidate.name === session.selectedCandidate.name);
  }

  if (parsed.comparison && Array.isArray(session.lastCandidates) && session.lastCandidates.length) {
    const lastIds = session.lastCandidates.map((candidate) => candidate.id);
    return pool.filter((candidate) => lastIds.includes(candidate.id));
  }

  return pool;
}

function determineEmbeddingMode(candidates) {
  const providers = new Set(candidates.map((candidate) => candidate.semanticProfile?.provider || (Array.isArray(candidate.embedding) ? "local" : "unknown")));
  if (providers.size !== 1) {
    return "mixed";
  }
  return [...providers][0].startsWith("gemini") ? "gemini" : "local";
}

async function buildQueryEmbedding(question, mode) {
  if (mode !== "gemini") {
    return embedText(question);
  }

  const geminiApiKey = process.env.GEMINI_API_KEY;
  const geminiEmbeddingModel = process.env.GEMINI_EMBEDDING_MODEL || "text-embedding-004";
  if (!geminiApiKey) {
    return embedText(question);
  }

  const cacheKey = `${geminiEmbeddingModel}:query:${question.trim()}`;
  if (EMBEDDING_CACHE.has(cacheKey)) {
    return EMBEDDING_CACHE.get(cacheKey);
  }

  try {
    const response = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${geminiEmbeddingModel}:embedContent`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-goog-api-key": geminiApiKey
      },
      body: JSON.stringify({
        model: `models/${geminiEmbeddingModel}`,
        taskType: "RETRIEVAL_QUERY",
        content: {
          parts: [{ text: String(question || "").trim() }]
        }
      })
    });

    if (!response.ok) {
      return embedText(question);
    }

    const payload = await response.json();
    const vector = Array.isArray(payload.embedding?.values) ? payload.embedding.values : [];
    if (!vector.length) {
      return embedText(question);
    }
    EMBEDDING_CACHE.set(cacheKey, vector);
    return vector;
  } catch {
    return embedText(question);
  }
}

function getCandidateSkills(parsedData) {
  return (parsedData.skills || []).map((skill) => skill.name);
}

function getCandidateExperience(parsedData) {
  return (parsedData.experience || []).reduce((sum, entry) => sum + (Number(entry.duration_years) || 0), 0);
}

function computeConfidence({ answer, shortlisted, parsed, selectedCandidate, filters }) {
  if (/not clearly available/i.test(answer)) {
    return 28;
  }

  const topScore = shortlisted[0]?.finalScore || 0.5;
  const filterStrength = Object.keys(filters || {}).length;
  const base = 60 + (topScore * 30) + (Math.min(filterStrength, 4) * 2);
  return clamp(Math.round(base), 35, 98);
}

function trimHistory(history) {
  return history.slice(-MAX_HISTORY);
}

function normalizeSessionId(sessionId) {
  return typeof sessionId === "string" && sessionId.trim() ? sessionId.trim() : new ObjectId().toString();
}

function normalizeScore(value) {
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) {
    return 0;
  }
  if (numeric <= 1) return clamp(numeric, 0, 1);
  if (numeric <= 10) return clamp(numeric / 10, 0, 1);
  return clamp(numeric / 100, 0, 1);
}

function round(value) {
  return Math.round(Number(value || 0) * 100) / 100;
}

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}
