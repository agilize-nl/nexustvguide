import type { FastifyInstance, FastifyRequest, FastifyReply } from 'fastify';
import type { RefreshEngine } from '../store/refresh.js';

export function registerHealthRoutes(app: FastifyInstance, engine: RefreshEngine): void {
  // GET /health
  app.get('/health', async (request: FastifyRequest, reply: FastifyReply) => {
    const stats = engine.getStats();
    return stats;
  });

  // GET /ready
  app.get('/ready', async (request: FastifyRequest, reply: FastifyReply) => {
    const ready = engine.isReady();
    if (ready) {
      return { status: 'ready', message: 'Gidsdata beschikbaar' };
    } else {
      reply.status(503);
      return { status: 'not_ready', message: 'Gidsdata wordt nog geladen' };
    }
  });
}
