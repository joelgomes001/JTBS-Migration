# Use lightweight official Node.js Alpine image
FROM node:18-alpine

WORKDIR /app

# Copy server and web application files
COPY server.js ./
COPY jtbs-live/public ./jtbs-live/public
COPY jtbs-live/.firebaserc ./jtbs-live/.firebaserc

# Expose HTTP port
EXPOSE 8080
ENV PORT=8080

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:8080/ || exit 1

# Start server
CMD ["node", "server.js"]
