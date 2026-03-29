FROM node:20-slim

WORKDIR /app
COPY package.json .
RUN npm install --production
COPY src/ src/

EXPOSE 8002
CMD ["node", "--import", "tsx", "src/index.ts"]
