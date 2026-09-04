#!/usr/bin/env node

// Minimal App Store Connect release helper. It deliberately has no npm dependencies so
// the macOS release runner can use it before installing project packages.
import { readFile } from "node:fs/promises";
import { createPrivateKey, sign } from "node:crypto";

const API = "https://api.appstoreconnect.apple.com/v1";
const APP_ID = process.env.ASC_APP_ID || "6803282435";
const keyId = required("ASC_KEY_ID");
const issuerId = required("ASC_ISSUER_ID");
const privateKey = await loadPrivateKey();

function required(name) {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is required`);
  return value;
}

async function loadPrivateKey() {
  if (process.env.ASC_PRIVATE_KEY) return process.env.ASC_PRIVATE_KEY;
  if (process.env.ASC_PRIVATE_KEY_PATH) {
    return readFile(process.env.ASC_PRIVATE_KEY_PATH, "utf8");
  }
  throw new Error("ASC_PRIVATE_KEY or ASC_PRIVATE_KEY_PATH is required");
}

function base64url(value) {
  return Buffer.from(value).toString("base64url");
}

function token() {
  const now = Math.floor(Date.now() / 1000);
  const header = base64url(JSON.stringify({ alg: "ES256", kid: keyId, typ: "JWT" }));
  const payload = base64url(JSON.stringify({ iss: issuerId, iat: now, exp: now + 15 * 60, aud: "appstoreconnect-v1" }));
  const signingInput = `${header}.${payload}`;
  const signature = sign("sha256", Buffer.from(signingInput), {
    key: createPrivateKey(privateKey),
    dsaEncoding: "ieee-p1363",
  }).toString("base64url");
  return `${signingInput}.${signature}`;
}

async function request(path, { method = "GET", body } = {}) {
  const response = await fetch(`${API}${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${token()}`,
      ...(body ? { "Content-Type": "application/json" } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`${method} ${path} failed (${response.status}): ${text.slice(0, 2000)}`);
  }
  return text ? JSON.parse(text) : null;
}

async function groups() {
  const query = new URLSearchParams({ "filter[app]": APP_ID, limit: "200" });
  return (await request(`/betaGroups?${query}`)).data;
}

async function ensureGroup(name, isInternalGroup) {
  const existing = (await groups()).find(
    (group) => group.attributes.name === name && group.attributes.isInternalGroup === isInternalGroup,
  );
  if (existing) return existing;
  const created = await request("/betaGroups", {
    method: "POST",
    body: {
      data: {
        type: "betaGroups",
        attributes: {
          name,
          isInternalGroup,
          hasAccessToAllBuilds: false,
          feedbackEnabled: true,
          ...(isInternalGroup ? {} : { publicLinkEnabled: false }),
        },
        relationships: { app: { data: { type: "apps", id: APP_ID } } },
      },
    },
  });
  return created.data;
}

async function ensureGroups() {
  const internal = await ensureGroup("Dayfold Internal", true);
  const external = await ensureGroup("Dayfold Beta", false);
  console.log(`Dayfold Internal: ${internal.id}`);
  console.log(`Dayfold Beta: ${external.id}`);
}

async function waitForBuild(marketingVersion, buildNumber) {
  const deadline = Date.now() + 30 * 60 * 1000;
  const query = new URLSearchParams({
    "filter[app]": APP_ID,
    "filter[version]": buildNumber,
    "filter[preReleaseVersion.version]": marketingVersion,
    "filter[preReleaseVersion.platform]": "IOS",
    limit: "10",
  });
  while (Date.now() < deadline) {
    const builds = (await request(`/builds?${query}`)).data;
    const build = builds[0];
    if (build?.attributes.processingState === "VALID") return build;
    if (["FAILED", "INVALID"].includes(build?.attributes.processingState)) {
      throw new Error(`App Store Connect processing ended as ${build.attributes.processingState}`);
    }
    console.log(build ? `Build ${buildNumber} is ${build.attributes.processingState}` : `Waiting for build ${buildNumber}`);
    await new Promise((resolve) => setTimeout(resolve, 30_000));
  }
  throw new Error(`Timed out waiting for build ${buildNumber}`);
}

async function assignBuild(channel, marketingVersion, buildNumber) {
  const groupName = channel === "alpha" ? "Dayfold Internal" : channel === "beta" ? "Dayfold Beta" : null;
  if (!groupName) throw new Error("assign-build channel must be alpha or beta");
  const expectedInternal = channel === "alpha";
  const group = (await groups()).find(
    (item) => item.attributes.name === groupName && item.attributes.isInternalGroup === expectedInternal,
  );
  if (!group) throw new Error(`${groupName} does not exist; run ensure-groups once`);
  const build = await waitForBuild(marketingVersion, buildNumber);
  await request(`/betaGroups/${group.id}/relationships/builds`, {
    method: "POST",
    body: { data: [{ type: "builds", id: build.id }] },
  });
  console.log(`Assigned build ${buildNumber} to ${groupName}`);
}

async function auditApp(marketingVersion) {
  const app = (await request(`/apps/${APP_ID}`)).data;
  const versionQuery = new URLSearchParams({
    "filter[platform]": "IOS",
    "filter[versionString]": marketingVersion,
    limit: "10",
  });
  const version = (await request(`/apps/${APP_ID}/appStoreVersions?${versionQuery}`)).data[0];
  if (!version) throw new Error(`App Store version ${marketingVersion} does not exist`);

  const localizations = (await request(`/appStoreVersions/${version.id}/appStoreVersionLocalizations?limit=200`)).data;
  const localizedVersions = [];
  for (const localization of localizations) {
    const sets = (await request(`/appStoreVersionLocalizations/${localization.id}/appScreenshotSets?limit=200`)).data;
    const screenshotSets = [];
    for (const set of sets) {
      const screenshots = (await request(`/appScreenshotSets/${set.id}/appScreenshots?limit=200`)).data;
      screenshotSets.push({
        displayType: set.attributes.screenshotDisplayType,
        count: screenshots.length,
        screenshots: screenshots.map((screenshot) => ({
          id: screenshot.id,
          fileName: screenshot.attributes.fileName,
          state: screenshot.attributes.assetDeliveryState?.state,
        })),
      });
    }
    localizedVersions.push({
      locale: localization.attributes.locale,
      descriptionPresent: Boolean(localization.attributes.description),
      keywordsPresent: Boolean(localization.attributes.keywords),
      supportUrlPresent: Boolean(localization.attributes.supportUrl),
      screenshotSets,
    });
  }

  const infoLocalizations = [];
  const appInfos = (await request(`/apps/${APP_ID}/appInfos?limit=10`)).data;
  for (const appInfo of appInfos) {
    const infos = (await request(`/appInfos/${appInfo.id}/appInfoLocalizations?limit=200`)).data;
    for (const info of infos) {
      infoLocalizations.push({
        locale: info.attributes.locale,
        name: info.attributes.name,
        subtitle: info.attributes.subtitle,
        privacyPolicyUrlPresent: Boolean(info.attributes.privacyPolicyUrl),
      });
    }
  }

  const betaGroups = (await groups()).map((group) => ({
    name: group.attributes.name,
    internal: group.attributes.isInternalGroup,
  }));

  console.log(JSON.stringify({
    app: {
      id: app.id,
      name: app.attributes.name,
      bundleId: app.attributes.bundleId,
      sku: app.attributes.sku,
    },
    version: {
      id: version.id,
      versionString: version.attributes.versionString,
      state: version.attributes.appStoreState,
      platform: version.attributes.platform,
    },
    localizedVersions,
    infoLocalizations,
    betaGroups,
  }, null, 2));
}

async function auditBuild(marketingVersion, buildNumber) {
  const query = new URLSearchParams({
    "filter[app]": APP_ID,
    "filter[version]": buildNumber,
    "filter[preReleaseVersion.version]": marketingVersion,
    "filter[preReleaseVersion.platform]": "IOS",
    limit: "10",
  });
  const build = (await request(`/builds?${query}`)).data[0];
  if (!build) throw new Error(`Build ${marketingVersion} (${buildNumber}) does not exist`);

  const assignedGroups = [];
  for (const group of await groups()) {
    const groupBuilds = (await request(`/betaGroups/${group.id}/builds?limit=200`)).data;
    if (groupBuilds.some((groupBuild) => groupBuild.id === build.id)) {
      assignedGroups.push({
        id: group.id,
        name: group.attributes.name,
        internal: group.attributes.isInternalGroup,
      });
    }
  }

  console.log(JSON.stringify({
    id: build.id,
    marketingVersion,
    buildNumber: build.attributes.version,
    processingState: build.attributes.processingState,
    uploadedDate: build.attributes.uploadedDate,
    expirationDate: build.attributes.expirationDate,
    expired: build.attributes.expired,
    minOsVersion: build.attributes.minOsVersion,
    usesNonExemptEncryption: build.attributes.usesNonExemptEncryption,
    assignedGroups,
  }, null, 2));
}

async function dedupeScreenshots(marketingVersion) {
  const versionQuery = new URLSearchParams({
    "filter[platform]": "IOS",
    "filter[versionString]": marketingVersion,
    limit: "10",
  });
  const version = (await request(`/apps/${APP_ID}/appStoreVersions?${versionQuery}`)).data[0];
  if (!version) throw new Error(`App Store version ${marketingVersion} does not exist`);
  const localizations = (await request(`/appStoreVersions/${version.id}/appStoreVersionLocalizations?limit=200`)).data;
  let removed = 0;
  for (const localization of localizations) {
    const sets = (await request(`/appStoreVersionLocalizations/${localization.id}/appScreenshotSets?limit=200`)).data;
    for (const set of sets) {
      const screenshots = (await request(`/appScreenshotSets/${set.id}/appScreenshots?limit=200`)).data;
      const seen = new Set();
      for (const screenshot of screenshots) {
        const fileName = screenshot.attributes.fileName;
        if (!seen.has(fileName)) {
          seen.add(fileName);
          continue;
        }
        await request(`/appScreenshots/${screenshot.id}`, { method: "DELETE" });
        removed += 1;
        console.log(`Removed duplicate ${set.attributes.screenshotDisplayType}/${fileName}`);
      }
    }
  }
  console.log(`Removed ${removed} duplicate screenshot(s)`);
}

const [command, ...args] = process.argv.slice(2);
if (command === "ensure-groups") {
  await ensureGroups();
} else if (command === "assign-build") {
  const [channel, marketingVersion, buildNumber] = args;
  if (!channel || !marketingVersion || !buildNumber) {
    throw new Error("usage: asc-testflight.mjs assign-build <alpha|beta> <marketing-version> <build-number>");
  }
  await assignBuild(channel, marketingVersion, buildNumber);
} else if (command === "audit-app") {
  const [marketingVersion = "1.0.0"] = args;
  await auditApp(marketingVersion);
} else if (command === "audit-build") {
  const [marketingVersion, buildNumber] = args;
  if (!marketingVersion || !buildNumber) {
    throw new Error("usage: asc-testflight.mjs audit-build <marketing-version> <build-number>");
  }
  await auditBuild(marketingVersion, buildNumber);
} else if (command === "dedupe-screenshots") {
  const [marketingVersion = "1.0.0"] = args;
  await dedupeScreenshots(marketingVersion);
} else {
  throw new Error("usage: asc-testflight.mjs ensure-groups | assign-build <alpha|beta> <marketing-version> <build-number> | audit-app [marketing-version] | audit-build <marketing-version> <build-number> | dedupe-screenshots [marketing-version]");
}
