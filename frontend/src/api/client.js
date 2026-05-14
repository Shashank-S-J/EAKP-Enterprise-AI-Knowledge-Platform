import {
    getAccessToken,
    setAccessToken,
    getRefreshToken,
    setRefreshToken,
    clearAuthTokens,
    migrateFromLocalStorage,
} from "../utils/cookies";

// ── Bootstrap: migrate any legacy localStorage tokens ───────────────────────
migrateFromLocalStorage();

const BASE = import.meta.env.VITE_API_BASE_URL || "";
// Multipart uploads must bypass the Vercel rewrite — Vercel's free tier caps
// rewrite bodies at 4.5 MB and times rewrites out at ~60s. We POST directly
// to the ingestion service instead. CORS_ALLOWED_ORIGINS on that service must
// include the Vercel origin.
const UPLOAD_BASE =
    import.meta.env.VITE_UPLOAD_BASE_URL ||
    "https://eakp-ingestion-service.onrender.com";
const MAX_RETRIES = 3;

// Rate limit notification (lazy-loaded to avoid circular deps)
let _toastStore = null;
function notifyRateLimit(seconds) {
    if (!_toastStore) {
        import("../store/toastStore")
            .then((m) => {
                _toastStore = m.useToastStore;
                _toastStore
                    .getState()
                    .warning(`Rate limited. Retrying in ${seconds}s...`);
            })
            .catch(() => {});
    } else {
        _toastStore.getState().warning(`Rate limited. Retrying in ${seconds}s...`);
    }
}

// ── Refresh-token mutex ─────────────────────────────────────────────────────
let refreshPromise = null;

function authHeaders() {
    const token = getAccessToken();
    return token ? { Authorization: `Bearer ${token}` } : {};
}

/** Exponential backoff delay */
function backoffDelay(attempt) {
    return Math.min(1000 * 2 ** attempt + Math.random() * 500, 15000);
}

async function request(url, options = {}, _retried = false, _attempt = 0) {
    try {
        const res = await fetch(BASE + url, {
            ...options,
            headers: {
                "Content-Type": "application/json",
                ...authHeaders(),
                ...options.headers,
            },
        });

        if (res.status === 401 && !_retried) {
            const refreshed = await tryRefresh();
            if (refreshed) return request(url, options, true, 0);
            clearAuthTokens();
            globalThis.location.href = "/login";
            throw new Error("Session expired");
        }

        // Retry on 5xx server errors with exponential backoff
        if (res.status >= 500 && _attempt < MAX_RETRIES) {
            await new Promise((r) => setTimeout(r, backoffDelay(_attempt)));
            return request(url, options, _retried, _attempt + 1);
        }

        // Retry on 429 rate limited
        if (res.status === 429 && _attempt < MAX_RETRIES) {
            const retryAfter = parseInt(
                res.headers.get("X-Rate-Limit-Retry-After-Seconds") || "5",
                10,
            );
            // Notify user about rate limiting
            notifyRateLimit(retryAfter);
            await new Promise((r) => setTimeout(r, retryAfter * 1000));
            return request(url, options, _retried, _attempt + 1);
        }

        if (!res.ok) {
            const body = await res.json().catch(() => ({}));
            const err = new Error(
                body.error ||
                body.detail ||
                body.message ||
                body.title ||
                `HTTP ${res.status}`,
            );
            err.status = res.status;
            throw err;
        }

        if (res.status === 204) return null;
        return res.json();
    } catch (err) {
        // Retry on network errors (fetch failures) with backoff
        if (err.name === "TypeError" && _attempt < MAX_RETRIES) {
            await new Promise((r) => setTimeout(r, backoffDelay(_attempt)));
            return request(url, options, _retried, _attempt + 1);
        }
        throw err;
    }
}

async function tryRefresh() {
    // If a refresh is already in-flight, wait for it
    if (refreshPromise) return refreshPromise;

    const rt = getRefreshToken();
    if (!rt) return false;

    refreshPromise = (async () => {
        try {
            const res = await fetch(BASE + "/api/v1/auth/refresh", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ refreshToken: rt }),
            });
            if (!res.ok) return false;
            const data = await res.json();
            storeTokens(data);
            return true;
        } catch {
            return false;
        } finally {
            refreshPromise = null;
        }
    })();

    return refreshPromise;
}

// ── Centralised token storage ───────────────────────────────────────────────

export function storeTokens(data) {
    setAccessToken(data.accessToken, data.expiresAt);
    setRefreshToken(data.refreshToken);
}

// ── Auth ─────────────────────────────────────────────────────────────────────

export const auth = {
    login: (email, password) =>
        request("/api/v1/auth/login", {
            method: "POST",
            body: JSON.stringify({ email, password }),
        }),

    register: (email, password, fullName, workspaceName) => {
        const workspaceSlug = workspaceName
            .toLowerCase()
            .replaceAll(/[^a-z0-9]+/g, "-")
            .replaceAll(/^-|-$/g, "");
        return request("/api/v1/auth/register", {
            method: "POST",
            body: JSON.stringify({
                email,
                password,
                fullName,
                workspaceName,
                workspaceSlug,
            }),
        });
    },

    me: () => request("/api/v1/auth/me"),

    forgotPassword: (email) =>
        request("/api/v1/auth/forgot-password", {
            method: "POST",
            body: JSON.stringify({ email }),
        }),

    resetPassword: (token, newPassword) =>
        request("/api/v1/auth/reset-password", {
            method: "POST",
            body: JSON.stringify({ token, newPassword }),
        }),

    /** Exchange GitHub authorization code on the server (server handles client_secret) */
    githubCallback: (code, workspaceName) =>
        request("/api/v1/auth/oauth/github/callback", {
            method: "POST",
            body: JSON.stringify({ code, workspaceName: workspaceName || null }),
        }),

    /** Send Google ID token to server for server-side verification */
    googleCallback: (idToken, workspaceName) =>
        request("/api/v1/auth/oauth/google/callback", {
            method: "POST",
            body: JSON.stringify({ idToken, workspaceName: workspaceName || null }),
        }),

    /**
     * Logout sends both access + refresh tokens to the backend for blacklisting,
     * then clears them locally regardless of the server response.
     * Uses a raw fetch (not `request()`) so a 401 doesn't trigger a token refresh loop.
     */
    logout: async () => {
        const at = getAccessToken();
        const rt = getRefreshToken();
        try {
            await fetch(BASE + "/api/v1/auth/logout", {
                method: "POST",
                headers: {
                    "Content-Type": "application/json",
                    ...(at ? { Authorization: `Bearer ${at}` } : {}),
                },
                body: JSON.stringify({ refreshToken: rt }),
            });
        } catch {
            // Network error — tokens will expire naturally; clear locally anyway
        } finally {
            clearAuthTokens();
        }
    },
};

// ── Conversations ───────────────────────────────────────────────────────────

export const conversations = {
    list: () => request("/api/v1/chat/conversations"),
    create: (title) =>
        request("/api/v1/chat/conversations", {
            method: "POST",
            body: JSON.stringify({ title }),
        }),
    messages: (id) => request(`/api/v1/chat/conversations/${id}/messages`),
    delete: (id) =>
        request(`/api/v1/chat/conversations/${id}`, { method: "DELETE" }),
    rename: (id, title) =>
        request(`/api/v1/chat/conversations/${id}`, {
            method: "PATCH",
            body: JSON.stringify({ title }),
        }),
    export: (id, format = "json") =>
        request(`/api/v1/chat/conversations/${id}/export?format=${format}`),
    feedback: (conversationId, messageId, rating) =>
        request(
            `/api/v1/chat/conversations/${conversationId}/messages/${messageId}/feedback`,
            {
                method: "POST",
                body: JSON.stringify({ rating }), // 'positive' | 'negative'
            },
        ),
};

// ── SSE Chat Stream ─────────────────────────────────────────────────────────

export function streamChat(conversationId, message, onToken, onDone, onError) {
    const url = `${BASE}/api/v1/chat/stream`;
    let controller = new AbortController();
    let reconnectAttempts = 0;
    const MAX_RECONNECT = 3;

    const doStream = (hdrs, retried = false) => {
        fetch(url, {
            method: "POST",
            headers: { "Content-Type": "application/json", ...hdrs },
            body: JSON.stringify({ conversationId, message }),
            signal: controller.signal,
        })
            .then(async (res) => {
                if (res.status === 401 && !retried) {
                    const refreshed = await tryRefresh();
                    if (refreshed) {
                        doStream(authHeaders(), true);
                        return;
                    }
                    clearAuthTokens();
                    globalThis.location.href = "/login";
                    onError("Session expired");
                    return;
                }
                if (!res.ok) {
                    onError("Stream failed: " + res.status);
                    return;
                }
                reconnectAttempts = 0; // Reset on successful connection
                const reader = res.body.getReader();
                const decoder = new TextDecoder();
                let buffer = "";
                let currentEvent = "";
                while (true) {
                    const { done, value } = await reader.read();
                    if (done) break;
                    buffer += decoder.decode(value, { stream: true });
                    const lines = buffer.split("\n");
                    buffer = lines.pop() || "";
                    for (const line of lines) {
                        if (line.startsWith("event:")) {
                            currentEvent = line.slice(6).trim();
                            if (currentEvent === "done") {
                                onDone();
                                return;
                            }
                        } else if (line.startsWith("data:")) {
                            const data = line.slice(5);
                            if (data === "[DONE]") {
                                onDone();
                                return;
                            }
                            if (currentEvent === "error") {
                                onError(data);
                                return;
                            }
                            onToken(data);
                        }
                    }
                }
                onDone();
            })
            .catch((e) => {
                if (e.name === "AbortError") return;
                // Auto-reconnect on network failure
                if (reconnectAttempts < MAX_RECONNECT) {
                    reconnectAttempts++;
                    const delay = Math.min(1000 * 2 ** reconnectAttempts, 8000);
                    setTimeout(() => doStream(authHeaders()), delay);
                } else {
                    onError("Connection lost. Please check your network and try again.");
                }
            });
    };

    doStream(authHeaders());
    return () => {
        controller.abort();
    };
}

// ── Documents ───────────────────────────────────────────────────────────────

export const documents = {
    list: () => request("/api/v1/documents"),
    get: (id) => request(`/api/v1/documents/${id}`),
    upload: async (file, onProgress) => {
        const makeForm = () => {
            const f = new FormData();
            f.append("file", file);
            return f;
        };
        const doUpload = async (retried = false) => {
            const uploadUrl = UPLOAD_BASE + "/api/v1/documents/upload";
            // Use XHR for progress tracking if callback provided
            if (onProgress) {
                return new Promise((resolve, reject) => {
                    const xhr = new XMLHttpRequest();
                    xhr.open("POST", uploadUrl);
                    // Synchronous ingestion can take 30–60s on Render free tier
                    xhr.timeout = 120000;
                    const token = getAccessToken();
                    if (token) xhr.setRequestHeader("Authorization", `Bearer ${token}`);
                    xhr.upload.onprogress = (e) => {
                        if (e.lengthComputable)
                            onProgress(Math.round((e.loaded / e.total) * 100));
                    };
                    xhr.onload = () => {
                        if (xhr.status === 401 && !retried) {
                            tryRefresh().then((refreshed) => {
                                if (refreshed) {
                                    documents
                                        .upload(file, onProgress)
                                        .then(resolve)
                                        .catch(reject);
                                } else {
                                    clearAuthTokens();
                                    globalThis.location.href = "/login";
                                    reject(new Error("Session expired"));
                                }
                            });
                            return;
                        }
                        if (xhr.status >= 200 && xhr.status < 300) {
                            try {
                                resolve(JSON.parse(xhr.responseText));
                            } catch {
                                resolve({});
                            }
                        } else {
                            try {
                                const body = JSON.parse(xhr.responseText);
                                reject(
                                    new Error(body.error || body.message || "Upload failed"),
                                );
                            } catch {
                                reject(new Error("Upload failed: " + xhr.status));
                            }
                        }
                    };
                    xhr.onerror = () => reject(new Error("Network error during upload"));
                    xhr.ontimeout = () =>
                        reject(new Error("Upload timed out — the server is taking too long"));
                    xhr.send(makeForm());
                });
            }
            // Fallback: simple fetch without progress
            const res = await fetch(uploadUrl, {
                method: "POST",
                headers: authHeaders(),
                body: makeForm(),
            });
            if (res.status === 401 && !retried) {
                const refreshed = await tryRefresh();
                if (refreshed) return doUpload(true);
                clearAuthTokens();
                globalThis.location.href = "/login";
                throw new Error("Session expired");
            }
            if (!res.ok) {
                const body = await res.json().catch(() => ({}));
                throw new Error(body.error || body.message || "Upload failed");
            }
            return res.json();
        };
        return doUpload();
    },
    delete: (id) => request(`/api/v1/documents/${id}`, { method: "DELETE" }),
    downloadUrl: (id) => request(`/api/v1/documents/${id}/download-url`),
    reingest: (id) =>
        request(`/api/v1/documents/${id}/reingest`, { method: "POST" }),
};

// ── Analytics ───────────────────────────────────────────────────────────────

export const analytics = {
    overview: () => request("/api/v1/analytics/overview"),
    ragQuality: () => request("/api/v1/analytics/rag-quality"),
    usage: (days = 30) => request(`/api/v1/analytics/usage?days=${days}`),
    documents: () => request("/api/v1/analytics/documents"),
};

// ── Profile ─────────────────────────────────────────────────────────────────

export const profile = {
    update: (data) =>
        request("/api/v1/auth/profile", {
            method: "PATCH",
            body: JSON.stringify(data),
        }),
    changePassword: (data) =>
        request("/api/v1/auth/change-password", {
            method: "POST",
            body: JSON.stringify(data),
        }),
};

// ── Workspace Configuration ─────────────────────────────────────────────────

export const workspaceConfig = {
    get: () => request("/api/v1/workspaces/current/config"),
    update: (data) =>
        request("/api/v1/workspaces/current/config", {
            method: "PUT",
            body: JSON.stringify(data),
        }),
};

// ── Admin (ROLE_ADMIN) ──────────────────────────────────────────────────────

export const admin = {
    health: () => request("/api/v1/admin/health"),
    workspaces: () => request("/api/v1/admin/workspaces"),
    workspaceUsers: (id) => request(`/api/v1/admin/workspaces/${id}/users`),
    evictCache: (id) =>
        request(`/api/v1/admin/workspaces/${id}/cache/evict`, { method: "POST" }),
    deactivateUser: (wsId, userId) =>
        request(`/api/v1/admin/workspaces/${wsId}/users/${userId}/deactivate`, {
            method: "POST",
        }),
    activateUser: (wsId, userId) =>
        request(`/api/v1/admin/workspaces/${wsId}/users/${userId}/activate`, {
            method: "POST",
        }),
    workspaceRagQuality: (id) =>
        request(`/api/v1/admin/workspaces/${id}/rag-quality`),
};