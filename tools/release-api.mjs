/**
 * Publiceert releases via de GitHub-releases-API.
 *
 * Forgejo en Gitea implementeren dezelfde endpoints onder `/api/v1`, dus dit werkt tegen
 * alle drie zolang de juiste --api-base wordt meegegeven. Bewust geen `gh`-afhankelijkheid:
 * een token en fetch volstaan, en dat werkt ook tegen de zelfgehoste Forgejo.
 *
 * Alleen de endpoints die deze tool nodig heeft zijn geïmplementeerd:
 * release opzoeken, aanmaken, asset uploaden en asset verwijderen.
 */

import { createReadStream, statSync } from 'node:fs';
import { basename } from 'node:path';

/** Hosts waarvan we weten hoe de download-URL van een asset is opgebouwd. */
export const FORGE_GITHUB = 'github';
export const FORGE_FORGEJO = 'forgejo';

class ReleaseApiError extends Error {
  constructor(message, status, body) {
    super(message);
    this.name = 'ReleaseApiError';
    this.status = status;
    this.body = body;
  }
}

export class ReleaseApi {
  /**
   * @param {object} opts
   * @param {string} opts.apiBase   Basis-URL van de API, bijv. https://api.github.com
   * @param {string} opts.repo      "eigenaar/repo"
   * @param {string} opts.token     Persoonlijk toegangstoken
   * @param {string} opts.forge     FORGE_GITHUB of FORGE_FORGEJO
   * @param {boolean} opts.dryRun   Bij true worden geen schrijfacties uitgevoerd
   */
  constructor({ apiBase, repo, token, forge, dryRun = false }) {
    this.apiBase = apiBase.replace(/\/+$/, '');
    this.repo = repo;
    this.token = token;
    this.forge = forge;
    this.dryRun = dryRun;
  }

  get #authHeader() {
    // GitHub accepteert "Bearer <token>"; Forgejo/Gitea verwachten "token <token>".
    return this.forge === FORGE_GITHUB ? `Bearer ${this.token}` : `token ${this.token}`;
  }

  #repoPath() {
    return this.forge === FORGE_GITHUB
      ? `${this.apiBase}/repos/${this.repo}`
      : `${this.apiBase}/api/v1/repos/${this.repo}`;
  }

  async #request(method, url, { body, headers = {}, contentType } = {}) {
    const finalHeaders = {
      Authorization: this.#authHeader,
      Accept: 'application/json',
      'User-Agent': 'nexustvguide-publish-release',
      ...headers
    };
    if (contentType) finalHeaders['Content-Type'] = contentType;

    const response = await fetch(url, { method, headers: finalHeaders, body, duplex: body ? 'half' : undefined });

    const text = await response.text();
    let parsed = null;
    if (text) {
      try {
        parsed = JSON.parse(text);
      } catch {
        parsed = text;
      }
    }

    if (!response.ok) {
      const detail = typeof parsed === 'object' && parsed?.message ? parsed.message : (text || '(lege body)');
      throw new ReleaseApiError(
        `${method} ${url} faalde: HTTP ${response.status} ${response.statusText} - ${detail}`,
        response.status,
        parsed
      );
    }

    return parsed;
  }

  /** Controleert of het token geldig is en welke gebruiker het vertegenwoordigt. */
  async whoami() {
    const url = this.forge === FORGE_GITHUB
      ? `${this.apiBase}/user`
      : `${this.apiBase}/api/v1/user`;
    const user = await this.#request('GET', url);
    return user?.login ?? '(onbekend)';
  }

  /** Zoekt een bestaande release op tag; geeft null als die er niet is. */
  async findReleaseByTag(tag) {
    try {
      return await this.#request('GET', `${this.#repoPath()}/releases/tags/${encodeURIComponent(tag)}`);
    } catch (err) {
      if (err instanceof ReleaseApiError && err.status === 404) return null;
      throw err;
    }
  }

  async createRelease({ tag, name, body, draft = false, prerelease = false, targetCommitish }) {
    if (this.dryRun) {
      return { id: 0, tag_name: tag, html_url: '(dry-run)', __dryRun: true };
    }
    const payload = { tag_name: tag, name, body, draft, prerelease };
    if (targetCommitish) payload.target_commitish = targetCommitish;

    return this.#request('POST', `${this.#repoPath()}/releases`, {
      body: JSON.stringify(payload),
      contentType: 'application/json'
    });
  }

  async deleteAsset(assetId) {
    if (this.dryRun) return;
    const url = this.forge === FORGE_GITHUB
      ? `${this.#repoPath()}/releases/assets/${assetId}`
      : `${this.#repoPath()}/releases/assets/${assetId}`;
    await this.#request('DELETE', url);
  }

  /**
   * Uploadt één bestand als release-asset.
   *
   * GitHub gebruikt een aparte uploads-host met de bestandsnaam als queryparameter en de
   * ruwe bytes als body; Forgejo gebruikt multipart op de API-host zelf.
   */
  async uploadAsset(release, filePath, contentType = 'application/octet-stream') {
    const name = basename(filePath);
    const size = statSync(filePath).size;

    if (this.dryRun) {
      return { name, size, browser_download_url: '(dry-run)', __dryRun: true };
    }

    if (this.forge === FORGE_GITHUB) {
      // upload_url komt als RFC6570-template: ".../assets{?name,label}"
      const uploadBase = release.upload_url.replace(/\{.*\}$/, '');
      const url = `${uploadBase}?name=${encodeURIComponent(name)}`;
      return this.#request('POST', url, {
        body: createReadStream(filePath),
        contentType,
        headers: { 'Content-Length': String(size) }
      });
    }

    const form = new FormData();
    const bytes = await import('node:fs/promises').then(fs => fs.readFile(filePath));
    form.append('attachment', new Blob([bytes], { type: contentType }), name);
    return this.#request(
      'POST',
      `${this.#repoPath()}/releases/${release.id}/assets?name=${encodeURIComponent(name)}`,
      { body: form }
    );
  }

  /**
   * De publieke download-URL van een asset, zoals die in version.json terechtkomt.
   *
   * Deze wordt vooraf berekend omdat het manifest de URL van de APK moet bevatten terwijl
   * dat bestand nog geüpload moet worden. Na de upload wordt de voorspelling gecontroleerd
   * tegen wat de server teruggeeft; zie verifyAssetUrl.
   */
  assetDownloadUrl(tag, fileName) {
    const encodedTag = encodeURIComponent(tag);
    const encodedName = encodeURIComponent(fileName);
    if (this.forge === FORGE_GITHUB) {
      return `https://github.com/${this.repo}/releases/download/${encodedTag}/${encodedName}`;
    }
    const host = this.apiBase.replace(/\/+$/, '');
    return `${host}/${this.repo}/releases/download/${encodedTag}/${encodedName}`;
  }

  /**
   * De basis-URL waaronder de assets van de nieuwste release te vinden zijn, zonder
   * tag erin. Dit is wat in de APK als UPDATE_BASE_URL wordt vastgelegd: de app moet
   * het manifest kunnen vinden zonder te weten welke versie de laatste is, anders zou
   * elke nieuwe release een herbouw van de app vereisen.
   *
   * GitHub en Forgejo/Gitea gebruiken hier hetzelfde 'releases/latest/download/'-pad.
   */
  latestDownloadBase() {
    const host = this.forge === FORGE_GITHUB
      ? 'https://github.com'
      : this.apiBase.replace(/\/+$/, '');
    return `${host}/${this.repo}/releases/latest/download/`;
  }

  /** De host die het asset serveert; nodig voor de allowlist in de app. */
  assetHosts() {
    if (this.forge === FORGE_GITHUB) {
      return ['github.com', 'objects.githubusercontent.com', 'release-assets.githubusercontent.com'];
    }
    return [new URL(this.apiBase).host];
  }
}

export { ReleaseApiError };
