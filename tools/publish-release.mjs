#!/usr/bin/env node

/**
 * NexusTVGuide - Release Publication Tool
 * 
 * Automates the atomic build, verification, and publishing of production APKs.
 * 
 * Usage:
 *   node tools/publish-release.mjs [--notes "Release notes text"] [--releases-dir ./tvguide-api/data/releases] [--dry-run]
 */

import { existsSync, readFileSync, writeFileSync, renameSync, copyFileSync, statSync } from 'node:fs';
import { resolve, join, basename } from 'node:path';
import { createHash } from 'node:crypto';
import { execSync } from 'node:child_process';

const ROOT_DIR = resolve(new URL('.', import.meta.url).pathname, '..');
const ANDROID_DIR = join(ROOT_DIR, 'android');
const KEYSTORE_PROPS_PATH = join(ANDROID_DIR, 'keystore.properties');
const DEFAULT_RELEASES_DIR = join(ROOT_DIR, 'tvguide-api', 'data', 'releases');

const javaHome = process.env.JAVA_HOME || '/home/djawiz/.jdks/jbr-21.0.11';
const envWithJava = { ...process.env, JAVA_HOME: javaHome, PATH: `${javaHome}/bin:${process.env.PATH || ''}` };

// Parse CLI args
const args = process.argv.slice(2);
let releaseNotes = 'Onderhoudsupdate en prestatieverbeteringen';
let releasesDir = DEFAULT_RELEASES_DIR;
let dryRun = false;
let skipBuild = false;
let testApkPath = null;

for (let i = 0; i < args.length; i++) {
  if (args[i] === '--notes' && args[i + 1]) {
    releaseNotes = args[++i];
  } else if (args[i] === '--releases-dir' && args[i + 1]) {
    releasesDir = resolve(process.cwd(), args[++i]);
  } else if (args[i] === '--dry-run') {
    dryRun = true;
  } else if (args[i] === '--skip-build') {
    skipBuild = true;
  } else if (args[i] === '--apk' && args[i + 1]) {
    testApkPath = resolve(process.cwd(), args[++i]);
    skipBuild = true;
  }
}

console.log('=== NexusTVGuide Release Publisher ===');
console.log(`Working Directory: ${ROOT_DIR}`);
console.log(`Releases Directory: ${releasesDir}`);
console.log(`Dry Run: ${dryRun}`);

// Step 1: Validate Keystore Configuration (unless explicit test APK provided)
if (!testApkPath) {
  console.log('\n[1/6] Validating release signing configuration...');
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
let apkPath = testApkPath;
if (!skipBuild) {
  console.log('\n[2/6] Building assembleRelease via Gradle...');
  try {
    execSync('./gradlew :app:assembleRelease', {
      cwd: ANDROID_DIR,
      env: envWithJava,
      stdio: 'inherit'
    });
  } catch (err) {
    console.error('ERROR: Gradle assembleRelease failed.');
    process.exit(1);
  }
  apkPath = join(ANDROID_DIR, 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk');
}

if (!apkPath || !existsSync(apkPath)) {
  console.error(`ERROR: Release APK not found at ${apkPath}`);
  process.exit(1);
}
console.log(`✓ Release APK located at ${apkPath}`);

// Step 3: Find Android SDK build-tools for inspection
console.log('\n[3/6] Inspecting and verifying APK signature & manifest...');
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
console.log('\n[4/6] Validating monotonic version code against active release...');
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
console.log('\n[5/6] Calculating checksum and file metrics...');
const apkBuffer = readFileSync(apkPath);
const fileSizeBytes = apkBuffer.length;
const sha256 = createHash('sha256').update(apkBuffer).digest('hex').toLowerCase();

console.log(`  Size:   ${fileSizeBytes} bytes (${(fileSizeBytes / (1024 * 1024)).toFixed(2)} MB)`);
console.log(`  SHA256: ${sha256}`);

if (fileSizeBytes > 100 * 1024 * 1024) {
  console.error('ERROR: APK exceeds maximum allowed size of 100 MiB.');
  process.exit(1);
}

// Step 6: Atomic publication
console.log('\n[6/6] Publishing release artifacts atomically...');
const targetApkName = `nexus-tv-guide-${packageInfo.versionName}.apk`;
const targetApkPath = join(releasesDir, targetApkName);
const targetDownloadPath = `/api/v1/app/download/${targetApkName}`;

const manifest = {
  schemaVersion: 1,
  applicationId: packageInfo.applicationId,
  versionCode: packageInfo.versionCode,
  versionName: packageInfo.versionName,
  releaseNotes: releaseNotes,
  downloadPath: targetDownloadPath,
  sha256: sha256,
  fileSizeBytes: fileSizeBytes,
  publishedAt: new Date().toISOString()
};

if (dryRun) {
  console.log('\n[DRY RUN] Manifest to publish:');
  console.log(JSON.stringify(manifest, null, 2));
  console.log(`[DRY RUN] APK would be copied to ${targetApkPath}`);
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

console.log('✓ Release published successfully!');
console.log(`  Manifest: ${currentManifestPath}`);
console.log(`  Artifact: ${targetApkPath}`);
