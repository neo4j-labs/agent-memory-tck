/**
 * Error hierarchy for the neo4j-agent-memory TypeScript client.
 */

export class MemoryError extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "MemoryError";
  }
}

export class ConnectionError extends MemoryError {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "ConnectionError";
  }
}

export class AuthenticationError extends MemoryError {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "AuthenticationError";
  }
}

export class NotFoundError extends MemoryError {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "NotFoundError";
  }
}

export class ValidationError extends MemoryError {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "ValidationError";
  }
}

export class TransportError extends MemoryError {
  public readonly statusCode?: number;
  public readonly responseBody?: unknown;

  constructor(
    message: string,
    statusCode?: number,
    responseBody?: unknown,
    options?: ErrorOptions,
  ) {
    super(message, options);
    this.name = "TransportError";
    this.statusCode = statusCode;
    this.responseBody = responseBody;
  }
}

/** Raised when a transport cannot fulfil a method (e.g. REST has no equivalent). */
export class NotSupportedError extends MemoryError {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "NotSupportedError";
  }
}
