FROM node:22-alpine

WORKDIR /app

# Install only production deps
COPY package*.json ./
RUN npm ci --only=production

# Copy all JS files
COPY . .

EXPOSE 9999

CMD ["node", "index.js"]