#!/usr/bin/env python3
"""
Simple HTTP server for Mind service with /health endpoint.
"""

from http.server import HTTPServer, BaseHTTPRequestHandler
import json
from datetime import datetime
import logging

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class HealthHandler(BaseHTTPRequestHandler):
    """HTTP request handler with /health and /health/ready endpoints."""
    
    def do_GET(self):
        """Handle GET requests."""
        if self.path == "/health":
            self.send_response(200)
            self.send_header("Content-type", "application/json")
            self.end_headers()
            
            response = {
                "status": "UP",
                "service": "mind",
                "version": "0.1.0",
                "timestamp": datetime.utcnow().isoformat() + "Z"
            }
            self.wfile.write(json.dumps(response).encode())
            logger.info("Mind health: UP")
            
        elif self.path == "/health/ready":
            self.send_response(200)
            self.send_header("Content-type", "application/json")
            self.end_headers()
            
            response = {
                "status": "UP",
                "service": "mind",
                "ready": True,
                "timestamp": datetime.utcnow().isoformat() + "Z"
            }
            self.wfile.write(json.dumps(response).encode())
            logger.info("Mind readiness: UP")
            
        else:
            self.send_response(404)
            self.send_header("Content-type", "application/json")
            self.end_headers()
            
            response = {
                "error": "Not Found",
                "path": self.path
            }
            self.wfile.write(json.dumps(response).encode())
    
    def log_message(self, format, *args):
        """Override logging to use our logger."""
        logger.info("%s - %s", self.client_address[0], format % args)


def run_server(port=8080):
    """Run the HTTP server."""
    server_address = ("0.0.0.0", port)
    httpd = HTTPServer(server_address, HealthHandler)
    logger.info(f"Mind service listening on port {port}")
    httpd.serve_forever()


if __name__ == "__main__":
    run_server()
