"""Mind Service API - Main entry point"""
from fastapi import FastAPI
from fastapi.responses import JSONResponse

app = FastAPI(
    title="Mind Service",
    description="MIND - extraction, embeddings, and scoring for Resonance Flow",
    version="0.1.0"
)


@app.get("/health")
async def health():
    """Health check endpoint"""
    return JSONResponse(
        status_code=200,
        content={
            "status": "UP",
            "service": "mind",
            "version": "0.1.0"
        }
    )


@app.get("/")
async def root():
    """Root endpoint"""
    return {
        "service": "Mind",
        "status": "running",
        "endpoints": {
            "health": "/health",
            "extract": "/api/extract (not yet implemented)",
            "embed": "/api/embed (not yet implemented)",
            "score": "/api/score (not yet implemented)"
        }
    }
