import { useState, useEffect } from 'react';

/**
 * Fetches current auth user from /api/user-info.
 * Returns { user, isAuthenticated, isLoading }
 */
export function useAuth() {
  const [user, setUser] = useState(null);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    fetch('/api/user-info', { credentials: 'include' })
      .then(res => res.ok ? res.json() : null)
      .then(data => { setUser(data); setIsLoading(false); })
      .catch(() => setIsLoading(false));
  }, []);

  return { user, isAuthenticated: !!user, isLoading };
}
