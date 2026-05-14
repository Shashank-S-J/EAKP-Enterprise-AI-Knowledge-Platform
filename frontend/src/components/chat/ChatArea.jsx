import { useState, useRef, useEffect, useCallback } from "react";
import { useChatStore } from "../../store";
import { useToastStore } from "../../store/toastStore";
import { streamChat, documents as docsApi } from "../../api/client";
import { useNetworkStatus } from "../../hooks/useNetworkStatus";
import { validateBatch } from "../../utils/fileValidation";
import { sanitizeInput } from "../../utils/sanitize";
import MessageBubble from "./MessageBubble";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import rehypeHighlight from "rehype-highlight";

const SUGGESTIONS = [
    {
        icon: "lightbulb",
        color: "var(--tertiary)",
        title: "Analyze Trends",
        desc: "Compare operational costs across periods.",
    },
    {
        icon: "description",
        color: "var(--accent)",
        title: "Summarize Document",
        desc: "Extract key points from uploaded docs.",
    },
    {
        icon: "bar_chart",
        color: "var(--secondary)",
        title: "Generate Insights",
        desc: "Visualize data across regions.",
    },
    {
        icon: "code",
        color: "var(--danger)",
        title: "Audit Query",
        desc: "Review logic for data anomalies.",
    },
];

export default function ChatArea({ onNewConv }) {
    const {
        messages,
        activeConversationId,
        streaming,
        streamingContent,
        setStreaming,
        appendStreamToken,
        clearStream,
        addMessage,
        createConversation,
        loadConversations,
        setMessages,
    } = useChatStore();
    const toast = useToastStore();
    const online = useNetworkStatus();
    const [input, setInput] = useState("");
    // Pending uploads triggered from the composer. Each entry:
    //   { localId, file, name, ext, sizeLabel, progress (0–100|-1), docId? }
    // When the upload completes, docId is filled and the entry moves to
    // `attachedDocs` (which is the authoritative list from the server).
    const [pendingUploads, setPendingUploads] = useState([]);
    // Documents already saved on the server for this conversation.
    const [attachedDocs, setAttachedDocs] = useState([]);
    const [thinking, setThinking] = useState(false);
    const [editingId, setEditingId] = useState(null);
    const [editText, setEditText] = useState("");
    const [sending, setSending] = useState(false);
    const [atBottom, setAtBottom] = useState(true);
    const messagesEndRef = useRef(null);
    const messagesAreaRef = useRef(null);
    const textareaRef = useRef(null);
    const editRef = useRef(null);
    const abortRef = useRef(null);
    const fileInputRef = useRef(null);

    // Track whether the user is near the bottom of the scroll area.
    // Claude-style: only auto-stick to bottom if the user hasn't scrolled up.
    const handleScroll = useCallback(() => {
        const el = messagesAreaRef.current;
        if (!el) return;
        const distance = el.scrollHeight - el.scrollTop - el.clientHeight;
        setAtBottom(distance < 80);
    }, []);

    const scrollToBottom = useCallback((behavior = "smooth") => {
        const el = messagesAreaRef.current;
        if (!el) return;
        el.scrollTo({ top: el.scrollHeight, behavior });
        setAtBottom(true);
    }, []);

    // Stick to bottom only when user is already at bottom; new user message always scrolls.
    useEffect(() => {
        if (atBottom) scrollToBottom(streaming ? "auto" : "smooth");
    }, [
        messages,
        streamingContent,
        thinking,
        atBottom,
        scrollToBottom,
        streaming,
    ]);

    useEffect(() => {
        if (textareaRef.current) {
            textareaRef.current.style.height = "auto";
            textareaRef.current.style.height =
                Math.min(textareaRef.current.scrollHeight, 200) + "px";
        }
    }, [input]);
    useEffect(() => {
        setTimeout(() => textareaRef.current?.focus(), 100);
    }, [activeConversationId]);
    useEffect(() => {
        if (editingId && editRef.current) {
            editRef.current.focus();
            editRef.current.selectionStart = editRef.current.value.length;
        }
    }, [editingId]);

    // Load the documents already attached to this conversation so the user can
    // see (and remove) them at any time. Fires whenever the active chat changes.
    const loadAttachedDocs = useCallback(async (convId) => {
        if (!convId) {
            setAttachedDocs([]);
            return;
        }
        try {
            const list = await docsApi.byConversation(convId);
            setAttachedDocs(Array.isArray(list) ? list : []);
        } catch {
            // soft-fail — not critical for chat flow
        }
    }, []);
    useEffect(() => {
        loadAttachedDocs(activeConversationId);
    }, [activeConversationId, loadAttachedDocs]);

    // Poll any pending docs (PENDING/PROCESSING) every 3s until READY/FAILED,
    // so the chip flips from "Processing" → "Ready" without a manual refresh.
    useEffect(() => {
        if (!activeConversationId) return;
        const stillProcessing = attachedDocs.some(
            (d) => d.status === "PENDING" || d.status === "PROCESSING",
        );
        if (!stillProcessing) return;
        const t = setInterval(
            () => loadAttachedDocs(activeConversationId),
            3000,
        );
        return () => clearInterval(t);
    }, [activeConversationId, attachedDocs, loadAttachedDocs]);

    const sendMessage = useCallback(
        async (text) => {
            const msg = sanitizeInput(text || input.trim(), 10000);
            if (!msg || streaming || sending) return;
            if (!online) {
                toast.error("You are offline. Please check your connection.");
                return;
            }
            setSending(true);

            // Wait for any in-flight uploads from this composer (they were started
            // the moment the user picked the file, so most of the time this is a
            // no-op by the time the message is sent).
            if (pendingUploads.some((p) => p.progress >= 0 && p.progress < 100)) {
                toast.info("Finishing your upload…");
                // Re-check shortly. The actual completion handlers run in the
                // background and will clear pendingUploads when done.
                const waitStart = Date.now();
                while (
                    Date.now() - waitStart < 60000 &&
                    pendingUploads.some((p) => p.progress >= 0 && p.progress < 100)
                    ) {
                    // eslint-disable-next-line no-await-in-loop
                    await new Promise((r) => setTimeout(r, 250));
                }
            }

            let convId = activeConversationId;
            if (!convId) {
                try {
                    const autoTitle = msg.length > 60 ? msg.slice(0, 57) + "..." : msg;
                    const conv = await createConversation(autoTitle);
                    convId = conv.id;
                    onNewConv(convId);
                } catch {
                    toast.error("Failed to create conversation");
                    setSending(false);
                    return;
                }
            }

            addMessage({
                id: Date.now().toString(),
                role: "USER",
                content: msg,
                sources: [],
                createdAt: new Date().toISOString(),
            });
            setInput("");
            setSending(false);
            setThinking(true);
            setStreaming(true);
            clearStream();

            let receivedFirstToken = false;
            abortRef.current = streamChat(
                convId,
                msg,
                (token) => {
                    if (!receivedFirstToken) {
                        receivedFirstToken = true;
                        setThinking(false);
                    }
                    appendStreamToken(token);
                },
                () => {
                    setThinking(false);
                    const finalContent = useChatStore.getState().streamingContent;
                    if (finalContent)
                        addMessage({
                            id: (Date.now() + 1).toString(),
                            role: "ASSISTANT",
                            content: finalContent,
                            sources: [],
                            createdAt: new Date().toISOString(),
                        });
                    clearStream();
                    setStreaming(false);
                    loadConversations();
                },
                (err) => {
                    setThinking(false);
                    addMessage({
                        id: (Date.now() + 1).toString(),
                        role: "ASSISTANT",
                        content: "",
                        _error: err,
                        sources: [],
                        createdAt: new Date().toISOString(),
                    });
                    clearStream();
                    setStreaming(false);
                },
            );
        },
        [
            input,
            streaming,
            activeConversationId,
            pendingUploads,
            sending,
            online,
            toast,
            createConversation,
            addMessage,
            setStreaming,
            appendStreamToken,
            clearStream,
            loadConversations,
            onNewConv,
        ],
    );

    const handleKeyDown = (e) => {
        if (e.key === "Enter" && !e.shiftKey) {
            e.preventDefault();
            sendMessage();
        }
    };

    const stopStreaming = () => {
        if (abortRef.current) abortRef.current();
        setThinking(false);
        const finalContent = useChatStore.getState().streamingContent;
        if (finalContent)
            addMessage({
                id: (Date.now() + 1).toString(),
                role: "ASSISTANT",
                content: finalContent + " [stopped]",
                sources: [],
                createdAt: new Date().toISOString(),
            });
        clearStream();
        setStreaming(false);
    };

    const handleEdit = (msgId, content) => {
        if (streaming) return;
        setEditingId(msgId);
        setEditText(content);
    };
    const submitEdit = () => {
        if (!editText.trim() || streaming) return;
        const idx = messages.findIndex((m) => m.id === editingId);
        if (idx === -1) return;
        setMessages(messages.slice(0, idx));
        setEditingId(null);
        sendMessage(editText.trim());
        setEditText("");
    };
    const cancelEdit = () => {
        setEditingId(null);
        setEditText("");
    };

    const handleRetry = (assistantMsgId) => {
        if (streaming) return;
        const idx = messages.findIndex((m) => m.id === assistantMsgId);
        if (idx <= 0) return;
        const prevUserMsg = messages[idx - 1];
        if (prevUserMsg.role !== "USER") return;
        setMessages(messages.slice(0, idx));
        sendMessage(prevUserMsg.content);
    };

    const handleRetryError = (errMsgId) => {
        const idx = messages.findIndex((m) => m.id === errMsgId);
        if (idx <= 0) return;
        const prevUserMsg = messages[idx - 1];
        setMessages(messages.slice(0, idx));
        sendMessage(prevUserMsg?.role === "USER" ? prevUserMsg.content : "");
    };

    // Build a stable "local id" so a single pending entry can be tracked across
    // multiple setPendingUploads calls without indexes shifting under us.
    const nextLocalIdRef = useRef(1);

    const ensureConversation = useCallback(async () => {
        if (activeConversationId) return activeConversationId;
        const conv = await createConversation("New chat");
        onNewConv(conv.id);
        return conv.id;
    }, [activeConversationId, createConversation, onNewConv]);

    // Start uploads immediately when the user picks files — don't wait for Send.
    // Each upload runs in parallel; the chip strip shows live progress and the
    // attached-docs strip refreshes when each upload finishes.
    const addFiles = useCallback(
        async (newFiles) => {
            const incoming = Array.from(newFiles);
            // Validate against everything already pending so the per-batch / total
            // size guards still hold.
            const allForValidation = [
                ...pendingUploads.map((p) => p.file),
                ...incoming,
            ];
            const { valid, errors } = validateBatch(allForValidation);
            if (!valid) {
                errors.forEach((err) => toast.error(err));
                return;
            }

            // Need a conversation before we can tag uploads. Create one lazily so the
            // first attachment locks the chat in.
            let convId;
            try {
                convId = await ensureConversation();
            } catch {
                toast.error("Failed to create conversation");
                return;
            }

            const newEntries = incoming.map((f) => {
                const ext = (f.name.split(".").pop() || "").toLowerCase();
                const sizeKb = f.size / 1024;
                const sizeLabel =
                    sizeKb >= 1024
                        ? `${(sizeKb / 1024).toFixed(1)} MB`
                        : `${Math.max(1, Math.round(sizeKb))} KB`;
                return {
                    localId: nextLocalIdRef.current++,
                    file: f,
                    name: f.name,
                    ext,
                    sizeLabel,
                    progress: 0,
                };
            });
            setPendingUploads((prev) => [...prev, ...newEntries]);

            // Kick off uploads in parallel.
            newEntries.forEach((entry) => {
                docsApi
                    .upload(
                        entry.file,
                        (pct) => {
                            setPendingUploads((prev) =>
                                prev.map((p) =>
                                    p.localId === entry.localId ? { ...p, progress: pct } : p,
                                ),
                            );
                        },
                        convId,
                    )
                    .then((dto) => {
                        // Remove from pending; the server is now the source of truth.
                        setPendingUploads((prev) =>
                            prev.filter((p) => p.localId !== entry.localId),
                        );
                        if (dto && dto.id) {
                            setAttachedDocs((prev) => [dto, ...prev]);
                        } else {
                            loadAttachedDocs(convId);
                        }
                    })
                    .catch((err) => {
                        setPendingUploads((prev) =>
                            prev.map((p) =>
                                p.localId === entry.localId ? { ...p, progress: -1 } : p,
                            ),
                        );
                        toast.error(`Upload failed: ${entry.name} — ${err.message || ""}`);
                    });
            });
        },
        [pendingUploads, ensureConversation, toast, loadAttachedDocs],
    );

    const removePendingUpload = useCallback((localId) => {
        // XHR can't be aborted cleanly here, but dropping the chip is enough —
        // even if the upload completes, the user can still delete it via the
        // attached strip below.
        setPendingUploads((prev) => prev.filter((p) => p.localId !== localId));
    }, []);

    const removeAttachedDoc = useCallback(
        async (docId, name) => {
            const ok = globalThis.confirm(`Remove “${name}” from this chat?`);
            if (!ok) return;
            const prev = attachedDocs;
            setAttachedDocs((d) => d.filter((x) => x.id !== docId));
            try {
                await docsApi.delete(docId);
            } catch (e) {
                setAttachedDocs(prev);
                toast.error(`Could not remove file: ${e.message || ""}`);
            }
        },
        [attachedDocs, toast],
    );

    const isEmpty = messages.length === 0 && !activeConversationId;

    return (
        <div className="chat-container">
            <div
                className="messages-area"
                ref={messagesAreaRef}
                onScroll={handleScroll}
            >
                <div className="chat-ambient" />
                {isEmpty ? (
                    <div className="empty-state">
                        <h2>What would you like to know?</h2>
                        <div className="suggestions">
                            {SUGGESTIONS.map((s) => (
                                <div
                                    key={s.title}
                                    className="suggestion-card"
                                    role="button"
                                    tabIndex={0}
                                    onClick={() => {
                                        setInput(s.title + ": " + s.desc);
                                        setTimeout(() => textareaRef.current?.focus(), 50);
                                    }}
                                    onKeyDown={(e) => {
                                        if (e.key === "Enter" || e.key === " ") {
                                            e.preventDefault();
                                            setInput(s.title + ": " + s.desc);
                                            setTimeout(() => textareaRef.current?.focus(), 50);
                                        }
                                    }}
                                >
                  <span
                      className="material-symbols-outlined suggestion-icon"
                      style={{ color: s.color }}
                  >
                    {s.icon}
                  </span>
                                    <div>
                                        <div className="suggestion-title">{s.title}</div>
                                        <div className="suggestion-desc">{s.desc}</div>
                                    </div>
                                </div>
                            ))}
                        </div>
                    </div>
                ) : (
                    <>
                        {messages.map((m) => {
                            if (editingId === m.id) {
                                return (
                                    <div key={m.id} className="message user message-editing">
                                        <div className="edit-message-card">
                      <textarea
                          ref={editRef}
                          className="edit-textarea"
                          value={editText}
                          onChange={(e) => {
                              setEditText(e.target.value);
                              e.target.style.height = "auto";
                              e.target.style.height =
                                  Math.min(e.target.scrollHeight, 280) + "px";
                          }}
                          onKeyDown={(e) => {
                              if (e.key === "Enter" && !e.shiftKey) {
                                  e.preventDefault();
                                  submitEdit();
                              }
                              if (e.key === "Escape") cancelEdit();
                          }}
                          rows={1}
                          aria-label="Edit your message"
                      />
                                            <div className="edit-actions">
                        <span className="edit-hint">
                          Editing creates a new branch from this point.
                        </span>
                                                <div className="edit-actions-buttons">
                                                    <button
                                                        className="btn-edit-cancel"
                                                        type="button"
                                                        onClick={cancelEdit}
                                                    >
                                                        Cancel
                                                    </button>
                                                    <button
                                                        className="btn-edit-save"
                                                        type="button"
                                                        onClick={submitEdit}
                                                        disabled={!editText.trim()}
                                                    >
                            <span
                                className="material-symbols-outlined"
                                style={{ fontSize: 14 }}
                            >
                              send
                            </span>
                                                        Send
                                                    </button>
                                                </div>
                                            </div>
                                        </div>
                                    </div>
                                );
                            }
                            if (m._error) {
                                return (
                                    <div key={m.id} className="message assistant">
                                        <div className="message-meta">
                                            <div
                                                className="message-avatar"
                                                style={{ background: "var(--danger)" }}
                                            >
                        <span
                            className="material-symbols-outlined"
                            style={{ fontSize: 14, color: "#690005" }}
                        >
                          warning
                        </span>
                                            </div>
                                            <span className="role-label">EAKP</span>
                                        </div>
                                        <div className="message-bubble">
                                            <div className="error-retry">
                                                <span>Failed to get response: {m._error}</span>
                                                <button
                                                    onClick={() => handleRetryError(m.id)}
                                                    className="action-btn"
                                                >
                          <span
                              className="material-symbols-outlined"
                              style={{ fontSize: 14 }}
                          >
                            refresh
                          </span>{" "}
                                                    Retry
                                                </button>
                                            </div>
                                        </div>
                                    </div>
                                );
                            }
                            return (
                                <MessageBubble
                                    key={m.id}
                                    message={m}
                                    onEdit={handleEdit}
                                    onRetry={handleRetry}
                                    conversationId={activeConversationId}
                                />
                            );
                        })}

                        {/* Thinking Orb */}
                        {thinking && !streamingContent && (
                            <div className="thinking-orb-container">
                                <div className="thinking-orb">
                                    <div className="orb-glow" />
                                    <div className="orb-inner" />
                                    <span className="material-symbols-outlined">graphic_eq</span>
                                </div>
                            </div>
                        )}

                        {/* Streaming content */}
                        {streaming && streamingContent && (
                            <div
                                className="message assistant"
                                aria-live="polite"
                                aria-atomic="false"
                            >
                                <div className="message-meta">
                                    <div className="message-avatar">
                    <span
                        className="material-symbols-outlined filled"
                        style={{ fontSize: 14 }}
                    >
                      smart_toy
                    </span>
                                    </div>
                                    <span className="role-label">EAKP Core</span>
                                </div>
                                <div className="message-bubble">
                                    <div className="message-content streaming-cursor">
                                        <ReactMarkdown
                                            remarkPlugins={[remarkGfm]}
                                            rehypePlugins={[rehypeHighlight]}
                                        >
                                            {streamingContent}
                                        </ReactMarkdown>
                                    </div>
                                </div>
                            </div>
                        )}
                    </>
                )}
                <div ref={messagesEndRef} />
            </div>

            {/* Floating scroll-to-bottom button (Claude-style) */}
            {!atBottom && messages.length > 0 && (
                <button
                    type="button"
                    className="scroll-to-bottom-btn"
                    onClick={() => scrollToBottom("smooth")}
                    title="Scroll to latest"
                    aria-label="Scroll to latest message"
                >
                    <span className="material-symbols-outlined">arrow_downward</span>
                </button>
            )}

            {/* Input Area */}
            <div className="input-area">
                {streaming && (
                    <div className="input-status-banner">
                        <span className="material-symbols-outlined">sync</span>
                        EAKP is generating a response…
                        <button
                            className="action-btn"
                            onClick={stopStreaming}
                            style={{ marginLeft: 8, padding: "2px 8px" }}
                        >
              <span
                  className="material-symbols-outlined"
                  style={{ fontSize: 14 }}
              >
                stop
              </span>{" "}
                            Stop
                        </button>
                    </div>
                )}
                {(pendingUploads.length > 0 || attachedDocs.length > 0) && (
                    <div
                        className="composer-attachments"
                        role="list"
                        aria-label="Attached files"
                    >
                        {pendingUploads.map((p) => {
                            const iconMap = {
                                pdf: "picture_as_pdf",
                                docx: "description",
                                doc: "description",
                                txt: "article",
                                md: "article",
                                csv: "table_view",
                                xlsx: "table_view",
                                xls: "table_view",
                                json: "data_object",
                                html: "code",
                                pptx: "slideshow",
                                ppt: "slideshow",
                                png: "image",
                                jpg: "image",
                                jpeg: "image",
                                gif: "image",
                                webp: "image",
                                svg: "image",
                            };
                            const icon = iconMap[p.ext] || "attach_file";
                            const isUploading = p.progress >= 0 && p.progress < 100;
                            const isFailed = p.progress === -1;
                            return (
                                <div
                                    key={`p-${p.localId}`}
                                    className={`composer-attachment-card ext-${p.ext}${isUploading ? " is-uploading" : ""}${isFailed ? " is-failed" : ""}`}
                                    role="listitem"
                                >
                                    <div className="composer-attachment-icon">
                    <span className="material-symbols-outlined filled">
                      {isFailed ? "error" : icon}
                    </span>
                                    </div>
                                    <div className="composer-attachment-meta">
                                        <div className="composer-attachment-name" title={p.name}>
                                            {p.name}
                                        </div>
                                        <div className="composer-attachment-sub">
                      <span className="composer-attachment-ext">
                        {p.ext.toUpperCase() || "FILE"}
                      </span>
                                            <span
                                                className="composer-attachment-dot"
                                                aria-hidden="true"
                                            >
                        •
                      </span>
                                            <span className="composer-attachment-size">
                        {isUploading
                            ? `Uploading… ${p.progress}%`
                            : isFailed
                                ? "Failed"
                                : p.sizeLabel}
                      </span>
                                        </div>
                                        {isUploading && (
                                            <div
                                                className="composer-attachment-progress"
                                                role="progressbar"
                                                aria-valuemin={0}
                                                aria-valuemax={100}
                                                aria-valuenow={p.progress}
                                            >
                                                <div
                                                    className="composer-attachment-progress-bar"
                                                    style={{ width: `${p.progress}%` }}
                                                />
                                            </div>
                                        )}
                                    </div>
                                    <button
                                        type="button"
                                        className="composer-attachment-remove"
                                        onClick={() => removePendingUpload(p.localId)}
                                        aria-label={`Cancel ${p.name}`}
                                        title="Cancel"
                                    >
                                        <span className="material-symbols-outlined">close</span>
                                    </button>
                                </div>
                            );
                        })}
                        {attachedDocs.map((d) => {
                            const ext = (d.fileType || "").toLowerCase();
                            const iconMap = {
                                pdf: "picture_as_pdf",
                                docx: "description",
                                doc: "description",
                                txt: "article",
                                md: "article",
                                csv: "table_view",
                                xlsx: "table_view",
                                xls: "table_view",
                                json: "data_object",
                                html: "code",
                                pptx: "slideshow",
                                ppt: "slideshow",
                            };
                            const icon = iconMap[ext] || "attach_file";
                            const isProcessing =
                                d.status === "PENDING" || d.status === "PROCESSING";
                            const isFailed = d.status === "FAILED";
                            const isReady = d.status === "READY";
                            const sub = isProcessing
                                ? "Processing…"
                                : isFailed
                                    ? "Failed"
                                    : isReady
                                        ? "Ready"
                                        : d.status;
                            return (
                                <div
                                    key={d.id}
                                    className={`composer-attachment-card ext-${ext}${isProcessing ? " is-uploading" : ""}${isReady ? " is-done" : ""}${isFailed ? " is-failed" : ""}`}
                                    role="listitem"
                                >
                                    <div className="composer-attachment-icon">
                    <span className="material-symbols-outlined filled">
                      {isReady
                          ? "check_circle"
                          : isFailed
                              ? "error"
                              : icon}
                    </span>
                                    </div>
                                    <div className="composer-attachment-meta">
                                        <div
                                            className="composer-attachment-name"
                                            title={d.filename}
                                        >
                                            {d.filename}
                                        </div>
                                        <div className="composer-attachment-sub">
                      <span className="composer-attachment-ext">
                        {(ext || "FILE").toUpperCase()}
                      </span>
                                            <span
                                                className="composer-attachment-dot"
                                                aria-hidden="true"
                                            >
                        •
                      </span>
                                            <span className="composer-attachment-size">{sub}</span>
                                        </div>
                                    </div>
                                    <button
                                        type="button"
                                        className="composer-attachment-remove"
                                        onClick={() => removeAttachedDoc(d.id, d.filename)}
                                        aria-label={`Remove ${d.filename}`}
                                        title="Remove from this chat"
                                    >
                                        <span className="material-symbols-outlined">close</span>
                                    </button>
                                </div>
                            );
                        })}
                    </div>
                )}
                <div
                    className="input-wrapper"
                    onDragOver={(e) => {
                        e.preventDefault();
                        e.currentTarget.classList.add("drag-active");
                    }}
                    onDragLeave={(e) => {
                        e.currentTarget.classList.remove("drag-active");
                    }}
                    onDrop={(e) => {
                        e.preventDefault();
                        e.currentTarget.classList.remove("drag-active");
                        if (e.dataTransfer.files.length) addFiles(e.dataTransfer.files);
                    }}
                >
                    {/* Drag overlay */}
                    <div className="input-drag-overlay">
                        <div className="input-drag-content">
                            <span className="material-symbols-outlined">cloud_upload</span>
                            <span>Drop files to attach</span>
                        </div>
                    </div>
                    <input
                        type="file"
                        ref={fileInputRef}
                        style={{ display: "none" }}
                        multiple
                        accept=".pdf,.docx,.doc,.txt,.pptx,.xlsx,.csv,.html,.md"
                        onChange={(e) => {
                            if (e.target.files.length) addFiles(e.target.files);
                            e.target.value = "";
                        }}
                    />
                    <button
                        className="input-btn"
                        onClick={() => fileInputRef.current?.click()}
                        title="Attach documents"
                        disabled={streaming || uploading}
                    >
                        <span className="material-symbols-outlined">attach_file</span>
                    </button>
                    <textarea
                        ref={textareaRef}
                        value={input}
                        onChange={(e) => setInput(e.target.value)}
                        onKeyDown={handleKeyDown}
                        placeholder={
                            online ? "Ask anything about your data..." : "You're offline..."
                        }
                        aria-label="Chat message input"
                        rows={1}
                        maxLength={10000}
                        disabled={uploading || streaming || !online}
                    />
                    <div className="input-actions">
                        {streaming ? (
                            <button
                                className="send-btn"
                                onClick={stopStreaming}
                                title="Stop generating"
                            >
                                <span className="material-symbols-outlined filled">stop</span>
                            </button>
                        ) : (
                            <button
                                className="send-btn"
                                onClick={() => sendMessage()}
                                disabled={
                                    (!input.trim() && !files.length) || sending || uploading
                                }
                                title={uploading ? "Uploading attachments…" : "Send message"}
                            >
                                {uploading ? (
                                    <span className="send-btn-spinner" aria-hidden="true" />
                                ) : (
                                    <span className="material-symbols-outlined filled">send</span>
                                )}
                            </button>
                        )}
                    </div>
                </div>
                <div className="input-disclaimer">
                    {input.length > 8000 && (
                        <span
                            className="input-char-count"
                            style={{
                                color: input.length > 9500 ? "var(--danger)" : "var(--warning)",
                                marginRight: 12,
                            }}
                        >
              {input.length.toLocaleString()} / 10,000
            </span>
                    )}
                    EAKP AI may produce inaccurate information about people, places, or
                    facts.
                </div>
            </div>
        </div>
    );
}