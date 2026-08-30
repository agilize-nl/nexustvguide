import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import type { DaySnapshot } from '../domain/guide.js';
import type { Channel } from '../domain/channel.js';

export class SnapshotStore {
  private inMemorySnapshots = new Map<string, DaySnapshot>();
  private dataDir: string;
  private channels: Channel[] = [];

  constructor(dataDir: string) {
    this.dataDir = dataDir;
    if (!fs.existsSync(this.dataDir)) {
      fs.mkdirSync(this.dataDir, { recursive: true });
    }
  }

  setChannels(channels: Channel[]): void {
    this.channels = channels;
  }

  getChannels(): Channel[] {
    return this.channels;
  }

  /**
   * Laadt alle bestaande snapshots van disk bij opstarten.
   */
  async loadFromDisk(): Promise<number> {
    try {
      const files = await fs.promises.readdir(this.dataDir);
      let count = 0;
      for (const file of files) {
        if (file.startsWith('guide-') && file.endsWith('.json')) {
          const filePath = path.join(this.dataDir, file);
          try {
            const raw = await fs.promises.readFile(filePath, 'utf-8');
            const snapshot = JSON.parse(raw) as DaySnapshot;
            if (snapshot.date && Array.isArray(snapshot.programmes)) {
              this.inMemorySnapshots.set(snapshot.date, snapshot);
              count++;
            }
          } catch (readErr) {
            console.error(`Failed to load snapshot ${file}:`, readErr);
          }
        }
      }
      return count;
    } catch (err) {
      console.error('Failed to read data directory:', err);
      return 0;
    }
  }

  getSnapshot(date: string): DaySnapshot | undefined {
    return this.inMemorySnapshots.get(date);
  }

  getAllSnapshots(): Map<string, DaySnapshot> {
    return this.inMemorySnapshots;
  }

  /**
   * Slaat een snapshot atomair op in het geheugen en op disk via een tijdelijk bestand en rename.
   */
  async saveSnapshot(snapshot: DaySnapshot): Promise<void> {
    this.inMemorySnapshots.set(snapshot.date, snapshot);

    const fileName = `guide-${snapshot.date}.json`;
    const finalPath = path.join(this.dataDir, fileName);
    const tmpPath = path.join(this.dataDir, `${fileName}.tmp.${process.pid}.${Date.now()}`);

    const json = JSON.stringify(snapshot, null, 2);
    await fs.promises.writeFile(tmpPath, json, 'utf-8');
    await fs.promises.rename(tmpPath, finalPath);
  }

  /**
   * Berekent een ETag op basis van de opgeslagen snapshots.
   */
  getETag(dates: string[]): string {
    const versions = dates
      .map((d) => {
        const snap = this.inMemorySnapshots.get(d);
        return snap ? `${d}:${snap.publishedAt}:${snap.programmes.length}` : `${d}:none`;
      })
      .join('|');

    const hash = crypto.createHash('md5').update(versions).digest('hex').substring(0, 16);
    return `W/"${hash}"`;
  }

  /**
   * Verwijdert oude snapshots die ouder zijn dan de opgegeven drempeldatum.
   */
  async cleanOldSnapshots(minAllowedDate: string): Promise<void> {
    for (const [date] of this.inMemorySnapshots.entries()) {
      if (date < minAllowedDate) {
        this.inMemorySnapshots.delete(date);
        const filePath = path.join(this.dataDir, `guide-${date}.json`);
        try {
          if (fs.existsSync(filePath)) {
            await fs.promises.unlink(filePath);
          }
        } catch (err) {
          console.error(`Failed to delete expired snapshot file for ${date}:`, err);
        }
      }
    }
  }
}
