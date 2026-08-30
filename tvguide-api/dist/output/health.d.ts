import type { FastifyInstance } from 'fastify';
import type { RefreshEngine } from '../store/refresh.js';
export declare function registerHealthRoutes(app: FastifyInstance, engine: RefreshEngine): void;
