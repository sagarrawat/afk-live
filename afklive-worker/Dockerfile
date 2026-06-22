FROM node:20-alpine

# Install FFmpeg for RTMP streaming and media processing
RUN apk update && \
    apk add --no-cache ffmpeg

WORKDIR /app

# Copy package files and install production dependencies
COPY package*.json ./
RUN npm ci --only=production

# Copy application source
COPY worker.js ./

# The worker script relies on the dev.env or environment variables.
# For production, we will inject environment variables via docker-compose.
CMD ["node", "worker.js"]
