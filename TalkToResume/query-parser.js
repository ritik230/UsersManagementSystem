const ROLE_ALIASES = [
  { role: "backend", terms: ["backend", "java developer", "spring boot", "microservices", "api developer"] },
  { role: "integration", terms: ["integration", "boomi", "middleware", "etl", "soap", "edi"] },
  { role: "guidewire", terms: ["guidewire", "claimcenter", "gosu", "policycenter"] },
  { role: "frontend", terms: ["frontend", "react", "ui developer", "javascript", "typescript"] },
  { role: "sales", terms: ["sales", "sales developer", "sales officer", "crm", "lead generation", "salesforce"] },
  { role: "accounting", terms: ["accounting", "finance", "reconciliation", "ledger", "audit"] },
  { role: "operations", terms: ["operations", "ops", "workflow", "dispatch", "vendor"] },
  { role: "data", terms: ["data engineer", "etl", "sql", "analytics", "reporting"] }
];

const SKILL_ALIASES = [
  ["dell boomi", "Dell Boomi"],
  ["boomi", "Dell Boomi"],
  ["java", "Java"],
  ["spring boot", "Spring Boot"],
  ["microservices", "Microservices"],
  ["sql", "SQL"],
  ["python", "Python"],
  ["guidewire", "Guidewire"],
  ["gosu", "Gosu"],
  ["salesforce", "Salesforce"],
  ["crm", "CRM"],
  ["lead generation", "Lead Generation"],
  ["react", "React"],
  ["node.js", "Node.js"],
  ["etl", "ETL"],
  ["soap", "SOAP"],
  ["edi", "EDI"],
  ["xml", "XML"],
  ["sap", "SAP"]
];

const LOCATIONS = [
  "Jaipur", "Delhi", "Gurugram", "Noida", "Bengaluru", "Bangalore", "Mumbai", "Pune",
  "Hyderabad", "Chennai", "Kolkata", "Ahmedabad", "Indore", "Bhopal", "Chandigarh", "Lucknow",
  "Surat", "Nagpur", "Kochi", "Trivandrum", "Coimbatore", "Mysore", "Gandhinagar"
];

export function parseRecruiterQuery(question, session = {}) {
  const normalized = String(question || "").toLowerCase();
  const role = detectRole(normalized) || session.filters?.role || null;
  const skills = detectSkills(normalized);
  const experience = detectExperience(normalized);
  const location = detectLocation(question) || session.filters?.location || null;
  const salary = /\bsalary|ctc|compensation|pay|package\b/i.test(normalized);
  const exclusion = /\b(other|apart from|besides|excluding|except|any other|another|remaining|else)\b/i.test(normalized) && /\b(these|those|them|candidates|profiles|shortlist|two|one|selected)\b/i.test(normalized);
  const comparison = /\b(compare|comparison|better|best|vs|versus|who is best|who's best|which one|among them|in them|them)\b/i.test(normalized);
  const explicitSingle = /\b(one|only|just one|single)\b/i.test(normalized) && !comparison;
  const singularReference = /\b(he|she|him|her|that candidate|this candidate|selected candidate)\b/i.test(normalized);
  const pluralReference = /\b(they|them|their|those candidates|these candidates|both|both of them|these two|those two)\b/i.test(normalized);
  const followUp = singularReference || pluralReference;
  const contact = /\b(contact|email|phone|mobile|reach|linkedin|github)\b/i.test(normalized);
  const project = /\b(projects?|worked on|built|build)\b/i.test(normalized);
  const availability = /\b(do we have|any candidate|any profile|available|profiles for|do we have any|show profiles|give me profiles)\b/i.test(normalized) && !project;

  const intent = salary
    ? "salary"
    : contact
      ? "contact"
      : project
        ? "project"
        : comparison
          ? "comparison"
          : exclusion
            ? "exclusion"
          : availability
            ? "availability"
            : explicitSingle
              ? "single"
              : "general";

  return {
    role,
    skills,
    experience,
    location,
    salary,
    exclusion,
    comparison,
    explicitSingle,
    followUp,
    singularReference,
    pluralReference,
    contact,
    project,
    availability,
    intent,
    filters: {
      ...(role ? { role } : {}),
      ...(skills.length ? { skills } : {}),
      ...(experience ? { experience } : {}),
      ...(location ? { location } : {})
    }
  };
}

export function mergeFilters(previous = {}, current = {}) {
  const merged = { ...previous, ...current };
  if (Array.isArray(previous.skills) || Array.isArray(current.skills)) {
    const nextSkills = [...new Set([...(previous.skills || []), ...(current.skills || [])])];
    merged.skills = nextSkills;
  }
  return merged;
}

export function isCandidateReference(question) {
  return /\b(he|she|him|her|that candidate|this candidate|them|their)\b/i.test(String(question || "").toLowerCase());
}

export function buildSearchText(question, filters = {}, session = {}, parsed = {}) {
  const parts = [question];
  if (filters.role) parts.push(filters.role);
  if (Array.isArray(filters.skills)) parts.push(...filters.skills);
  if (filters.experience) parts.push(`${filters.experience} years`);
  if (filters.location) parts.push(filters.location);
  if ((parsed.followUp || parsed.comparison || parsed.explicitSingle || parsed.contact || parsed.project || parsed.salary || parsed.exclusion) && session.lastQuery) {
    parts.push(session.lastQuery);
  }
  return parts.filter(Boolean).join(" ");
}

export function shouldReturnSingleCandidate(parsed, session = {}) {
  if (parsed.explicitSingle) return true;
  return /\b(one candidate|one name|just give one|only one|single candidate)\b/i.test(String(session.lastQuery || "").toLowerCase());
}

export function inferMinimumExperience(question) {
  const match = String(question || "").match(/(\d+(?:\.\d+)?)\s*\+?\s*(?:year|years)/i);
  return match ? Number(match[1]) : 0;
}

function detectRole(normalized) {
  for (const entry of ROLE_ALIASES) {
    if (entry.terms.some((term) => normalized.includes(term))) {
      return entry.role;
    }
  }
  return null;
}

function detectSkills(normalized) {
  const matches = [];
  for (const [needle, label] of SKILL_ALIASES) {
    if (normalized.includes(needle)) {
      matches.push(label);
    }
  }
  return [...new Set(matches)];
}

function detectExperience(normalized) {
  const match = normalized.match(/(\d+(?:\.\d+)?)\s*\+?\s*(?:year|years)/i);
  return match ? Number(match[1]) : null;
}

function detectLocation(question) {
  const normalized = String(question || "").toLowerCase();
  const found = LOCATIONS.find((city) => normalized.includes(city.toLowerCase()));
  return found || null;
}
