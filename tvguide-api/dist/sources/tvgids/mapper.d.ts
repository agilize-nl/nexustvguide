import type { RawProgramme } from './types.js';
import type { Programme } from '../../domain/programme.js';
/**
 * Zet een gevalideerd RawProgramme om naar het domeinmodel Programme.
 * Dit is de ENIGE plek in het project die de upstream vorm kent.
 */
export declare function mapProgramme(raw: RawProgramme, channelId: string): Programme;
