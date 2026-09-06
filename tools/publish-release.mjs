#!/usr/bin/env node

/**
 * NexusTVGuide - Release Publication Tool
 * 
 * Automates the atomic build, verification, local publishing, and server deployment of production APKs.
 * 
 * Usage:
 *   LAN-kanaal (standaard, publiceert naar tvguide-api op .171):
 *     node tools/publish-release.mjs [--notes "..."] [--no-deploy] [--server root@...] [--dry-run]
 *
 *   Release-kanaal, zelf publiceren via de API (GitHub):
 *     RELEASE_TOKEN=<pat> node tools/publish-release.mjs --channel release \
 *       --repo <eigenaar>/<repo> --forge github [--dry-run]
 *
 *   Forgejo/Gitea kunnen dit kanaal niet bedienen: hun release-assets staan onder
 *   /attachments/<uuid>, dus er bestaat geen URL die vooraf in de APK past. De tool
 *   weigert die combinatie; gebruik GitHub of --download-base met een eigen webserver.
 *
 *   Release-kanaal, alleen artefacten schrijven (handmatig uploaden):
 *     node tools/publish-release.mjs --channel release \
 *       --download-base https://github.com/<user>/<repo>/releases/download/v<versie>/
 *
 *   Met --repo bepaalt de tool de download-URL zelf en uploadt het APK + version.json als
 *   assets van dezelfde release. Het token komt uit de omgeving (RELEASE_TOKEN of
 *   GITHUB_TOKEN), nooit uit een argument: argumenten belanden in shell-history en zijn
 *   voor andere processen zichtbaar. `gh` is niet nodig.
 */

import { existsSync, readFileSync, writeFileSync, renameSync, copyFileSync, readdirSync, mkdirSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { createHash } from 'node:crypto';
import { execSync } from 'node:child_process';
import { ReleaseApi, ReleaseApiError, FORGE_GITHUB, FORGE_FORGEJO } from './release-api.mjs';

const ROOT_DIR = resolve(new URL('.', import.meta.url).pathname, '..');
const ANDROID_DIR = join(ROOT_DIR, 'android');
const KEYSTORE_PROPS_PATH = join(ANDROID_DIR, 'keystore.properties');
const DEFAULT_RELEASES_DIR = join(ROOT_DIR, 'tvguide-api', 'data', 'releases');
const DEFAULT_REMOTE_HOST = 'root@100.88.166.57';
const DEFAULT_REMOTE_DIR = '/opt/nexustvguide-api/current/tvguide-api/data/releases';

const javaHome = process.env.JAVA_HOME || '/home/djawiz/.jdks/jbr-21.0.11';
const envWithJava = { ...process.env, JAVA_HOME: javaHome, PATH: `${javaHome}/bin:${process.env.PATH || ''}` };

// Parse CLI args
const args = process.argv.slice(2);
let releaseNotes = 'Onderhoudsupdate en prestatieverbeteringen';
let releasesDir = DEFAULT_RELEASES_DIR;
let deploy = true;
let remoteHost = DEFAULT_REMOTE_HOST;
let remoteDir = DEFAULT_REMOTE_DIR;
let dryRun = false;
let skipBuild = false;
let testApkPath = null;
let channel = 'lan';
let downloadBase = null;
let repo = null;
let forge = null;
let apiBase = null;
let releaseTag = null;
let draft = false;
let prerelease = false;

for (let i = 0; i < args.length; i++) {
  if (args[i] === '--notes' && args[i + 1]) {
    releaseNotes = args[++i];
  } else if (args[i] === '--releases-dir' && args[i + 1]) {
    releasesDir = resolve(process.cwd(), args[++i]);
  } else if (args[i] === '--no-deploy') {
    deploy = false;
  } else if (args[i] === '--server' && args[i + 1]) {
    remoteHost = args[++i];
    deploy = true;
  } else if (args[i] === '--remote-dir' && args[i + 1]) {
    remoteDir = args[++i];
  } else if (args[i] === '--dry-run') {
    dryRun = true;
  } else if (args[i] === '--skip-build') {
    skipBuild = true;
  } else if (args[i] === '--apk' && args[i + 1]) {
    testApkPath = resolve(process.cwd(), args[++i]);
    skipBuild = true;
  } else if (args[i] === '--channel' && args[i + 1]) {
    channel = args[++i].trim();
  } else if (args[i] === '--download-base' && args[i + 1]) {
    downloadBase = args[++i].trim();
  } else if (args[i] === '--repo' && args[i + 1]) {
    repo = args[++i].trim();
  } else if (args[i] === '--forge' && args[i + 1]) {
    forge = args[++i].trim();
  } else if (args[i] === '--api-base' && args[i + 1]) {
    apiBase = args[++i].trim().replace(/\/+$/, '');
  } else if (args[i] === '--tag' && args[i + 1]) {
    releaseTag = args[++i].trim();
  } else if (args[i] === '--draft') {
    draft = true;
  } else if (args[i] === '--prerelease') {
    prerelease = true;
  } else if (args[i] === '--token' || args[i] === '--token=') {
    console.error('ERROR: geef het token niet als argument mee; gebruik RELEASE_TOKEN=<pat> in de omgeving.');
    console.error('  Argumenten zijn zichtbaar in shell-history en in de procestabel.');
    process.exit(1);
  }
}

if (channel !== 'lan' && channel !== 'release') {
  console.error(`ERROR: Onbekend --channel '${channel}'; kies 'lan' of 'release'.`);
  process.exit(1);
}

// Token uitsluitend uit de omgeving; zie de --token-guard hierboven.
const releaseToken = process.env.RELEASE_TOKEN || process.env.GITHUB_TOKEN || null;
let releaseApi = null;

if (channel === 'release') {
  // Het release-kanaal publiceert via de release-host, niet via SCP naar .171.
  deploy = false;
  if (releasesDir === DEFAULT_RELEASES_DIR) {
    releasesDir = join(ROOT_DIR, 'dist', 'release');
  }

  if (repo && downloadBase) {
    console.error('ERROR: gebruik --repo (zelf publiceren) of --download-base (handmatig), niet allebei.');
    process.exit(1);
  }

  if (repo) {
    if (!/^[^/\s]+\/[^/\s]+$/.test(repo)) {
      console.error(`ERROR: --repo moet de vorm 'eigenaar/repo' hebben, ontvangen: '${repo}'`);
      process.exit(1);
    }

    // Forge afleiden uit --api-base als die niet expliciet is opgegeven. Een eigen host
    // wordt als Forgejo/Gitea behandeld; die gebruiken /api/v1-paden. Voor GitHub
    // Enterprise is dat verkeerd, dus dat vraagt om een expliciete --forge github.
    let forgeInferred = false;
    if (!forge) {
      forge = (apiBase && !apiBase.includes('api.github.com')) ? FORGE_FORGEJO : FORGE_GITHUB;
      forgeInferred = Boolean(apiBase);
    }
    if (forgeInferred) {
      console.log(`Let op: --forge niet opgegeven; afgeleid als '${forge}' uit --api-base.`);
      console.log("  Klopt dat niet (bijv. GitHub Enterprise), geef dan expliciet --forge github mee.");
    }
    if (forge !== FORGE_GITHUB && forge !== FORGE_FORGEJO) {
      console.error(`ERROR: onbekende --forge '${forge}'; kies 'github' of 'forgejo'.`);
      process.exit(1);
    }
    if (!apiBase) {
      if (forge === FORGE_GITHUB) {
        apiBase = 'https://api.github.com';
      } else {
        console.error('ERROR: --forge forgejo vereist --api-base <url>, bijv. https://forgejo.example.com');
        process.exit(1);
      }
    }
    // Het token gaat over deze verbinding mee; http zou het in platte tekst versturen.
    // De ontsnapping is uitsluitend bedoeld voor de mockserver in de tests.
    const allowInsecureApi = process.env.RELEASE_API_ALLOW_INSECURE === '1';
    if (!apiBase.startsWith('https://') && !allowInsecureApi) {
      console.error(`ERROR: --api-base moet HTTPS zijn, ontvangen: '${apiBase}'`);
      process.exit(1);
    }
    if (allowInsecureApi && !apiBase.startsWith('https://')) {
      console.warn(`WAARSCHUWING: onversleutelde API-verbinding naar ${apiBase}; het token gaat in platte tekst mee.`);
    }
    if (!releaseToken) {
      console.error('ERROR: geen token gevonden. Zet RELEASE_TOKEN (of GITHUB_TOKEN) in de omgeving.');
      console.error('  Bijv: RELEASE_TOKEN=$(cat ~/.config/nexustvguide/release-token) node tools/publish-release.mjs ...');
      console.error("  Benodigde scope: GitHub 'contents: write' (classic: repo); Forgejo: write:repository.");
      process.exit(1);
    }

    // Een updatekanaal vereist een asset-URL die al vaststaat vóór de build, want die
    // wordt in de APK gecompileerd. Forgejo/Gitea kennen die niet: assets krijgen daar
    // per upload een UUID onder /attachments/. Zonder deze controle zou de tool een APK
    // bouwen die naar een 404 wijst, en dat pas na het uploaden ontdekken.
    if (forge !== FORGE_GITHUB) {
      console.error(`ERROR: --forge ${forge} kan geen release-updatekanaal bedienen.`);
      console.error('  Forgejo/Gitea serveren release-assets onder /attachments/<uuid>: er is geen');
      console.error('  stabiele URL die vooraf in de APK vastgelegd kan worden, dus elke release');
      console.error('  zou een herbouw van de app vereisen.');
      console.error('');
      console.error('  Gebruik GitHub voor de APK-distributie:');
      console.error('    node tools/publish-release.mjs --channel release --repo <eigenaar>/<repo> --forge github');
      console.error('  of publiceer handmatig met --download-base <url> naar een eigen webserver.');
      process.exit(1);
    }

    releaseApi = new ReleaseApi({ apiBase, repo, token: releaseToken, forge, dryRun });
  } else {
    // Handmatige modus: de asset-URL kan niet worden afgeleid, dus moet die expliciet mee.
    if (!downloadBase) {
      console.error('ERROR: --channel release vereist --repo <eigenaar>/<repo> (zelf publiceren)');
      console.error('  of --download-base <url> (artefacten schrijven, handmatig uploaden).');
      process.exit(1);
    }
    if (!downloadBase.startsWith('https://')) {
      console.error(`ERROR: --download-base moet HTTPS zijn, ontvangen: '${downloadBase}'`);
      process.exit(1);
    }
    if (!downloadBase.endsWith('/')) {
      downloadBase += '/';
    }
  }
}

console.log('=== NexusTVGuide Release Publisher ===');
console.log(`Working Directory:  ${ROOT_DIR}`);
console.log(`Releases Directory: ${releasesDir}`);
console.log(`Update Channel:     ${channel}`);
if (channel === 'release') {
  if (releaseApi) {
    console.log(`Release Target:     ${forge} ${repo} via ${apiBase}`);
    console.log(`Token:              uit omgeving (${process.env.RELEASE_TOKEN ? 'RELEASE_TOKEN' : 'GITHUB_TOKEN'})`);
  } else {
    console.log(`Download Base:      ${downloadBase} (handmatige upload)`);
  }
}
console.log(`Auto Deploy:        ${deploy ? `Enabled (${remoteHost}:${remoteDir})` : 'Disabled'}`);
console.log(`Dry Run:            ${dryRun}`);

// Step 1: Validate Keystore Configuration (unless explicit test APK provided)
if (!testApkPath) {
  console.log('\n[1/7] Validating release signing configuration...');
  if (!existsSync(KEYSTORE_PROPS_PATH)) {
    console.error(`ERROR: keystore.properties not found at ${KEYSTORE_PROPS_PATH}`);
    console.error('Release builds require a valid keystore.properties containing storeFile, storePassword, keyAlias, and keyPassword.');
    process.exit(1);
  }

  const propsContent = readFileSync(KEYSTORE_PROPS_PATH, 'utf8');
  const props = {};
  for (const line of propsContent.split('\n')) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const [k, ...v] = trimmed.split('=');
    if (k && v.length > 0) props[k.trim()] = v.join('=').trim();
  }

  const requiredProps = ['storeFile', 'storePassword', 'keyAlias', 'keyPassword'];
  for (const req of requiredProps) {
    if (!props[req]) {
      console.error(`ERROR: Missing required property '${req}' in ${KEYSTORE_PROPS_PATH}`);
      process.exit(1);
    }
  }

  const storeFilePath = resolve(ANDROID_DIR, props.storeFile);
  if (!existsSync(storeFilePath)) {
    console.error(`ERROR: Keystore file '${props.storeFile}' resolved to '${storeFilePath}' does not exist.`);
    process.exit(1);
  }
  console.log('✓ Keystore properties and store file verified.');
}

// Step 2: Build assembleRelease
//
// Het updatekanaal moet in de APK zelf terechtkomen: BuildConfig.UPDATE_BASE_URL wordt
// bij het compileren vastgelegd. Zonder deze parameters valt de build terug op het
// LAN-kanaal en zou een release-APK alsnog bij .171 om updates vragen.
//
// De app leest het manifest bewust via de 'latest'-URL en niet via de tag-URL van deze
// release: die is versie-onafhankelijk, dus al bekend vóórdat de versie uit de APK is
// gelezen, en laat toekomstige releases vinden zonder de app opnieuw te bouwen. De
// downloadUrl in het manifest blijft wel tag-specifiek, zodat een manifest altijd naar
// precies het bijbehorende APK verwijst.
let apkPath = testApkPath;
if (!skipBuild) {
  console.log('\n[2/7] Building assembleRelease via Gradle...');
  const gradleArgs = ['./gradlew', ':app:assembleRelease'];
  if (channel === 'release') {
    const manifestBase = releaseApi
      ? releaseApi.latestDownloadBase()
      : downloadBase;
    gradleArgs.push('-PupdateChannel=release');
    gradleArgs.push(`-PupdateBaseUrl=${manifestBase}`);
    console.log(`  Updatekanaal in APK: release -> ${manifestBase}version.json`);
  }
  try {
    execSync(gradleArgs.join(' '), {
      cwd: ANDROID_DIR,
      env: envWithJava,
      stdio: 'inherit'
    });
  } catch (err) {
    console.error('ERROR: Gradle assembleRelease failed.');
    process.exit(1);
  }
}

if (!apkPath) {
  const releaseOutputDir = join(ANDROID_DIR, 'app', 'build', 'outputs', 'apk', 'release');
  if (existsSync(releaseOutputDir)) {
    const apks = readdirSync(releaseOutputDir).filter(f => f.endsWith('.apk') && !f.includes('-unsigned'));
    if (apks.length > 0) {
      apkPath = join(releaseOutputDir, apks[0]);
    }
  }
  if (!apkPath) {
    apkPath = join(releaseOutputDir, 'app-release.apk');
  }
}

if (!apkPath || !existsSync(apkPath)) {
  console.error(`ERROR: Release APK not found at ${apkPath}`);
  process.exit(1);
}
console.log(`✓ Release APK located at ${apkPath}`);

// Step 3: Find Android SDK build-tools for inspection
console.log('\n[3/7] Inspecting and verifying APK signature & manifest...');
const androidHome = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || '/home/djawiz/Android/Sdk';
let aaptPath = null;
let apksignerPath = null;

if (existsSync(join(androidHome, 'build-tools'))) {
  const versions = execSync(`ls -1 ${join(androidHome, 'build-tools')}`, { encoding: 'utf8' }).trim().split('\n').filter(Boolean).sort().reverse();
  for (const v of versions) {
    const candidateAapt = join(androidHome, 'build-tools', v, 'aapt');
    const candidateApksigner = join(androidHome, 'build-tools', v, 'apksigner');
    if (existsSync(candidateAapt) && existsSync(candidateApksigner)) {
      aaptPath = candidateAapt;
      apksignerPath = candidateApksigner;
      break;
    }
  }
}

let packageInfo = null;
if (aaptPath) {
  const badging = execSync(`"${aaptPath}" dump badging "${apkPath}"`, { encoding: 'utf8' });
  const pkgMatch = badging.match(/package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'/);
  if (pkgMatch) {
    packageInfo = {
      applicationId: pkgMatch[1],
      versionCode: parseInt(pkgMatch[2], 10),
      versionName: pkgMatch[3]
    };
  }
}

if (!packageInfo) {
  console.error('ERROR: Unable to extract package info from APK using aapt.');
  process.exit(1);
}

console.log(`  Application ID: ${packageInfo.applicationId}`);
console.log(`  Version Code:   ${packageInfo.versionCode}`);
console.log(`  Version Name:   ${packageInfo.versionName}`);

if (packageInfo.applicationId !== 'com.nexustvguide.app') {
  console.error(`ERROR: Application ID '${packageInfo.applicationId}' does not match expected 'com.nexustvguide.app'`);
  process.exit(1);
}

if (apksignerPath) {
  try {
    const verifyOutput = execSync(`"${apksignerPath}" verify --verbose --print-certs "${apkPath}"`, { encoding: 'utf8', env: envWithJava });
    if (!verifyOutput.includes('Verifies: true') && !verifyOutput.includes('Signer #1 certificate DN')) {
      console.error('ERROR: apksigner verification did not confirm a valid release signature.');
      console.error(verifyOutput);
      process.exit(1);
    }
    console.log('✓ APK signature verified via apksigner.');
  } catch (err) {
    console.error('ERROR: apksigner verify failed on APK:', err.message);
    process.exit(1);
  }
}

// Step 4: Validate monotonic versionCode
console.log('\n[4/7] Validating monotonic version code against active release...');
const currentManifestPath = join(releasesDir, 'version.json');
if (existsSync(currentManifestPath)) {
  try {
    const currentManifest = JSON.parse(readFileSync(currentManifestPath, 'utf8'));
    console.log(`  Current published versionCode: ${currentManifest.versionCode} (${currentManifest.versionName})`);
    if (packageInfo.versionCode <= currentManifest.versionCode) {
      console.error(`ERROR: New versionCode (${packageInfo.versionCode}) is not strictly higher than currently published versionCode (${currentManifest.versionCode}).`);
      process.exit(1);
    }
  } catch (err) {
    console.warn(`WARNING: Failed to parse existing version.json (${err.message}). Proceeding with publish.`);
  }
} else {
  console.log('  No existing version.json found. This will be the initial release.');
}

// Step 5: Compute SHA-256 and file size
console.log('\n[5/7] Calculating checksum and file metrics...');
const apkBuffer = readFileSync(apkPath);
const fileSizeBytes = apkBuffer.length;
const sha256 = createHash('sha256').update(apkBuffer).digest('hex').toLowerCase();

console.log(`  Size:   ${fileSizeBytes} bytes (${(fileSizeBytes / (1024 * 1024)).toFixed(2)} MB)`);
console.log(`  SHA256: ${sha256}`);

if (fileSizeBytes > 100 * 1024 * 1024) {
  console.error('ERROR: APK exceeds maximum allowed size of 100 MiB.');
  process.exit(1);
}

// Step 6: Atomic local publication
console.log('\n[6/7] Publishing release artifacts locally...');
const targetApkName = `nexus-tv-guide-${packageInfo.versionName}.apk`;
if (!existsSync(releasesDir)) {
  mkdirSync(releasesDir, { recursive: true });
}
const targetApkPath = join(releasesDir, targetApkName);

// Bij zelf publiceren is de tag de bron van de download-URL, dus die staat hier vast.
if (channel === 'release') {
  if (!releaseTag) {
    releaseTag = `v${packageInfo.versionName}`;
  }
  if (releaseApi) {
    // De URL wordt vooraf berekend omdat het manifest ernaar verwijst terwijl het APK nog
    // geüpload moet worden. Na de upload wordt dit gecontroleerd tegen de serverrespons.
    downloadBase = releaseApi.assetDownloadUrl(releaseTag, '').replace(/[^/]*$/, '');
  }
}

const manifest = {
  schemaVersion: 1,
  applicationId: packageInfo.applicationId,
  versionCode: packageInfo.versionCode,
  versionName: packageInfo.versionName,
  releaseNotes: releaseNotes,
  sha256: sha256,
  fileSizeBytes: fileSizeBytes,
  publishedAt: new Date().toISOString()
};

// De app accepteert precies één downloadverwijzing. De LAN-backend serveert het asset op
// zijn eigen origin (relatief pad); een release-host serveert het vanaf een eigen URL.
if (channel === 'release') {
  manifest.downloadUrl = `${downloadBase}${targetApkName}`;
} else {
  manifest.downloadPath = `/api/v1/app/download/${targetApkName}`;
}

if (dryRun) {
  console.log('\n[DRY RUN] Manifest to publish:');
  console.log(JSON.stringify(manifest, null, 2));
  console.log(`[DRY RUN] APK would be copied to ${targetApkPath}`);
  if (deploy) {
    console.log(`[DRY RUN] Remote deployment to ${remoteHost}:${remoteDir}`);
  }
  if (releaseApi) {
    console.log(`[DRY RUN] Release '${releaseTag}' zou worden aangemaakt op ${forge} ${repo}`);
    console.log(`[DRY RUN] Assets: ${targetApkName} + version.json`);
    console.log(`[DRY RUN] Verwachte download-URL: ${manifest.downloadUrl}`);
    console.log(`[DRY RUN] Benodigde app-allowlist: ${releaseApi.assetHosts().join(',')}`);
  }
  console.log('\n✓ Dry run completed successfully.');
  process.exit(0);
}

// Copy APK first
console.log(`Copying APK to ${targetApkPath}...`);
copyFileSync(apkPath, targetApkPath);

// Write version.json.tmp and atomically rename
const tempManifestPath = join(releasesDir, 'version.json.tmp');
writeFileSync(tempManifestPath, JSON.stringify(manifest, null, 2), 'utf8');
renameSync(tempManifestPath, currentManifestPath);

console.log('✓ Local release published successfully!');
console.log(`  Manifest: ${currentManifestPath}`);
console.log(`  Artifact: ${targetApkPath}`);

// Step 7: Automatic remote server deployment
if (deploy) {
  console.log(`\n[7/7] Deploying release to server (${remoteHost}:${remoteDir})...`);
  try {
    const scpCmd = `scp -o ConnectTimeout=5 "${currentManifestPath}" "${targetApkPath}" "${remoteHost}:${remoteDir}/"`;
    console.log(`  > ${scpCmd}`);
    execSync(scpCmd, { stdio: 'inherit' });

    const sshPermCmd = `ssh -o ConnectTimeout=5 "${remoteHost}" "chown -R tvguide:tvguide ${remoteDir} && ls -lh ${remoteDir}"`;
    console.log(`  > ${sshPermCmd}`);
    execSync(sshPermCmd, { stdio: 'inherit' });

    console.log('✓ Remote deployment to .171 server successful!');
  } catch (err) {
    console.error(`WARNING: Remote deployment failed: ${err.message}`);
    console.error('The local release is ready. You can manually copy it using:');
    console.error(`  scp "${currentManifestPath}" "${targetApkPath}" "${remoteHost}:${remoteDir}/"`);
  }
} else if (channel === 'release' && releaseApi) {
  console.log(`\n[7/7] Publishing release '${releaseTag}' to ${forge} ${repo}...`);
  try {
    const who = await releaseApi.whoami();
    console.log(`  Geauthenticeerd als: ${who}`);

    // Een bestaande tag opnieuw publiceren zou de assets van een al uitgerolde versie
    // vervangen; dat is precies het geval waarin apparaten een verkeerde APK zouden halen.
    const existing = await releaseApi.findReleaseByTag(releaseTag);
    if (existing) {
      console.error(`ERROR: release '${releaseTag}' bestaat al op ${repo}.`);
      console.error(`  ${existing.html_url ?? ''}`);
      console.error('  Verhoog VERSION_NAME in android/app/version.properties, of geef een andere --tag mee.');
      console.error(`  De lokale artefacten staan klaar in ${releasesDir}.`);
      process.exit(1);
    }

    const release = await releaseApi.createRelease({
      tag: releaseTag,
      name: `v${packageInfo.versionName}`,
      body: releaseNotes,
      draft,
      prerelease
    });
    console.log(`  ✓ Release aangemaakt: ${release.html_url ?? releaseTag}`);

    // APK eerst: version.json zonder bijbehorend APK zou apparaten naar een 404 sturen.
    console.log(`  Uploaden: ${targetApkName} (${(fileSizeBytes / (1024 * 1024)).toFixed(2)} MB)...`);
    const apkAsset = await releaseApi.uploadAsset(release, targetApkPath, 'application/vnd.android.package-archive');
    console.log(`  ✓ APK geüpload`);

    console.log('  Uploaden: version.json...');
    await releaseApi.uploadAsset(release, currentManifestPath, 'application/json');
    console.log('  ✓ Manifest geüpload');

    // De URL in het manifest is vooraf berekend; hier controleren we die tegen wat de
    // server werkelijk teruggeeft. Een stille afwijking zou pas op het apparaat opvallen.
    const actualUrl = apkAsset.browser_download_url;
    if (actualUrl && actualUrl !== manifest.downloadUrl) {
      console.error('\nWAARSCHUWING: de download-URL wijkt af van wat in version.json staat!');
      console.error(`  In manifest: ${manifest.downloadUrl}`);
      console.error(`  Server zegt: ${actualUrl}`);
      console.error('  De updater zal een 404 krijgen. Corrigeer version.json in de release.');
      process.exit(1);
    }

    console.log(`\n✓ Release gepubliceerd: ${release.html_url ?? releaseTag}`);
    console.log(`  Download-URL: ${manifest.downloadUrl}`);
    console.log('\n  Bouw de app met dit updatekanaal:');
    console.log(`    ./gradlew :app:assembleRelease -PupdateChannel=release \\`);
    console.log(`      -PupdateBaseUrl=${downloadBase} \\`);
    console.log(`      -PupdateHostAllowlist=${releaseApi.assetHosts().join(',')}`);
  } catch (err) {
    if (err instanceof ReleaseApiError) {
      console.error(`\nERROR: publiceren mislukt: ${err.message}`);
      if (err.status === 401 || err.status === 403) {
        console.error("  Controleer het token en de scope ('contents: write' bij GitHub, write:repository bij Forgejo).");
      } else if (err.status === 404) {
        console.error(`  Bestaat de repo '${repo}' en heeft het token er toegang toe?`);
      }
    } else {
      console.error(`\nERROR: publiceren mislukt: ${err.message}`);
    }
    console.error(`\n  De lokale artefacten staan klaar in ${releasesDir}; opnieuw draaien kan met --skip-build.`);
    process.exit(1);
  }
} else if (channel === 'release') {
  console.log('\n[7/7] Release-kanaal: upload de artefacten handmatig naar de release-host.');
  console.log(`  Artefacten: ${targetApkPath}`);
  console.log(`              ${currentManifestPath}`);
  console.log('');
  console.log('  Publiceer beide als assets van dezelfde release. Geef --repo <eigenaar>/<repo> mee');
  console.log('  om dit door de tool zelf te laten doen (geen gh nodig).');
  console.log('');
  console.log(`  Let op: het manifest verwijst naar ${manifest.downloadUrl}`);
  console.log('  Een mismatch met de werkelijke asset-URL laat de updater met een 404 falen.');
} else {
  console.log('\n[7/7] Remote deployment skipped (--no-deploy).');
}

console.log('\n=== Publication Complete ===\n');
