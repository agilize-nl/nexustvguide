import type { FastifyInstance } from 'fastify';
import type { SnapshotStore } from '../store/cache.js';
import type { RefreshEngine } from '../store/refresh.js';
export declare function registerRestRoutes(app: FastifyInstance, store: SnapshotStore, engine: RefreshEngine): void;
