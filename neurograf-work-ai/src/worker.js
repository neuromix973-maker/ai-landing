export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/api/health") {
      return Response.json({
        ok: true,
        app: "NEUROGRAF WORK AI",
        assistant: "Мира",
        version: "0.5-bootstrap"
      });
    }

    return env.ASSETS.fetch(request);
  }
};
