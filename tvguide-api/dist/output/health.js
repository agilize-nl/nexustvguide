export function registerHealthRoutes(app, engine) {
    // GET /health
    app.get('/health', async (request, reply) => {
        const stats = engine.getStats();
        return stats;
    });
    // GET /ready
    app.get('/ready', async (request, reply) => {
        const ready = engine.isReady();
        if (ready) {
            return { status: 'ready', message: 'Gidsdata beschikbaar' };
        }
        else {
            reply.status(503);
            return { status: 'not_ready', message: 'Gidsdata wordt nog geladen' };
        }
    });
}
