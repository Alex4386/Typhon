const TOKEN_KEY = 'typhon_auth_token';

export function getAuthToken(): string {
  return sessionStorage.getItem(TOKEN_KEY) || '';
}

export function setAuthToken(token: string): void {
  sessionStorage.setItem(TOKEN_KEY, token);
}

export function clearAuthToken(): void {
  sessionStorage.removeItem(TOKEN_KEY);
}

export function getAuthHeader(): string | null {
  const token = getAuthToken();
  if (!token) return null;
  if (token.startsWith('Bearer ') || token.startsWith('Basic ')) {
    return token;
  }
  return 'Bearer ' + token;
}
