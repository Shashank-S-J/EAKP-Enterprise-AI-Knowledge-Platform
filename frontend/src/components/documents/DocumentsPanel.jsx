import { useState, useEffect, useRef, useCallback } from 'react';
import { documents as docsApi } from '../../api/client';
import { useToastStore } from '../../store/toastStore';
import { validateBatch } from '../../utils/fileValidation';


function formatSize(bytes) {
    if (!bytes) return '';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / 1048576).toFixed(1) + ' MB';
}

function getFileIcon(filename) {
    const ext = (filename || '').split('.').pop()?.toLowerCase();
    if (ext === 'pdf') return { icon: 'picture_as_pdf', cls: 'pdf' };
    if (['docx', 'doc'].includes(ext)) return { icon: 'description', cls: 'docx' };
    if (['json'].includes(ext)) return { icon: 'data_object', cls: 'json' };
    if (['csv', 'xlsx'].includes(ext)) return { icon: 'table_chart', cls: 'csv' };
    return { icon: 'insert_drive_file', cls: 'default' };
}


export default function DocumentsPanel({ open, onClose }) {
    const [docs, setDocs] = useState([]);
    const [loading, setLoading] = useState(false);
    const [dragOver, setDragOver] = useState(false);
    const [search, setSearch] = useState('');
    const [uploadingFiles, setUploadingFiles] = useState([]);
    const [deletingId, setDeletingId] = useState(null);
    const [error, setError] = useState('');
    const fileRef = useRef(null);
    const toast = useToastStore();

    const loadDocs = useCallback(async () => {
        setLoading(true);
        try {
            const list = await docsApi.list();
            setDocs(list);
            setError('');
        } catch {
            setError('Failed to load documents');
        }
        setLoading(false);
    }, []);

    useEffect(() => {
        // eslint-disable-next-line react-hooks/set-state-in-effect -- triggers fetch+setState when panel opens
        if (open) loadDocs();
    }, [open, loadDocs]);

    useEffect(() => {
        if (!open) return;
        const handler = (e) => { if (e.key === 'Escape') onClose(); };
        globalThis.addEventListener('keydown', handler);
        return () => globalThis.removeEventListener('keydown', handler);
    }, [open, onClose]);

    useEffect(() => {
        if (!open) return;
        const pending = docs.some(d => d.status === 'PENDING' || d.status === 'PROCESSING');
        if (!pending) return;
        const timer = setInterval(loadDocs, 3000);
        return () => clearInterval(timer);
    }, [docs, open, loadDocs]);

    const handleUpload = async (fileList) => {
        const files = Array.from(fileList);
        // Validate files before uploading
        const { valid, errors } = validateBatch(files);
        if (!valid) {
            errors.forEach(err => toast.error(err));
            return;
        }
        const uploading = files.map((f) => ({ name: f.name, status: 'uploading', progress: 0 }));
        setUploadingFiles(uploading);
        for (let i = 0; i < files.length; i++) {
            try {
                await docsApi.upload(files[i], (pct) => {
                    setUploadingFiles((prev) => prev.map((u, j) => j === i ? { ...u, progress: pct } : u));
                });
                setUploadingFiles((prev) => prev.map((u, j) => j === i ? { ...u, status: 'done', progress: 100 } : u));
            } catch {
                setUploadingFiles((prev) => prev.map((u, j) => j === i ? { ...u, status: 'error' } : u));
                toast.error(`Upload failed: ${files[i].name}`);
            }
        }
        setTimeout(() => { setUploadingFiles([]); loadDocs(); toast.info('Documents uploaded.'); }, 1000);
    };

    const handleDrop = (e) => {
        e.preventDefault();
        setDragOver(false);
        if (e.dataTransfer.files.length) handleUpload(e.dataTransfer.files);
    };

    const handleDelete = async (id) => {
        try {
            await docsApi.delete(id);
            setDocs(docs.filter(d => d.id !== id));
            toast.success('Document deleted');
        } catch {
            toast.error('Failed to delete document');
        }
        setDeletingId(null);
    };

    const handleDownload = async (id) => {
        try {
            const { url } = await docsApi.downloadUrl(id);
            const a = document.createElement('a');
            a.href = url; a.target = '_blank'; a.rel = 'noopener'; a.click();
        } catch {
            toast.error('Failed to get download link');
        }
    };

    const handleReingest = async (id) => {
        try {
            await docsApi.reingest(id);
            toast.info('Re-ingestion started.');
            setDocs(docs.map(d => d.id === id ? { ...d, status: 'PROCESSING' } : d));
        } catch {
            toast.error('Failed to start re-ingestion');
        }
    };

    const readyDocs = docs.filter(d => d.status === 'READY');
    const filtered = search.trim()
        ? docs.filter((d) => d.filename.toLowerCase().includes(search.toLowerCase()))
        : docs;

    return (
        <aside className={`context-panel ${open ? 'open' : ''}`}>
            {/* Header */}
            <div className="context-panel-header">
                <div className="context-panel-header-left">
                    <span className="material-symbols-outlined" style={{ color: 'var(--secondary)' }}>database</span>
                    <h2>Active Context</h2>
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <span className="context-doc-count">{readyDocs.length} Doc{readyDocs.length !== 1 ? 's' : ''}</span>
                    <button className="context-file-remove" onClick={onClose} title="Close panel" aria-label="Close documents panel">
                        <span className="material-symbols-outlined" style={{ fontSize: 18 }}>close</span>
                    </button>
                </div>
            </div>

            <div className="context-panel-body">
                {/* Upload Dropzone */}
                <div
                    className={`context-dropzone ${dragOver ? 'drag-over' : ''}`}
                    onDragOver={(e) => { e.preventDefault(); setDragOver(true); }}
                    onDragLeave={() => setDragOver(false)}
                    onDrop={handleDrop}
                    onClick={() => fileRef.current?.click()}
                    onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); fileRef.current?.click(); } }}
                    role="button"
                    tabIndex={0}
                    aria-label="Upload files. Click or press Enter to browse."
                >
                    <div className="context-dropzone-icon">
                        <span className="material-symbols-outlined">upload_file</span>
                    </div>
                    <div className="context-dropzone-text">Click to upload or drag &amp; drop</div>
                    <div className="context-dropzone-formats">PDF, TXT, CSV, JSON (max 50MB)</div>
                    <input
                        type="file" ref={fileRef} style={{ display: 'none' }} multiple
                        accept=".pdf,.docx,.doc,.txt,.pptx,.xlsx,.csv,.html,.md,.json"
                        onChange={(e) => { if (e.target.files.length) handleUpload(e.target.files); e.target.value = ''; }}
                    />
                </div>

                {/* Upload progress */}
                {uploadingFiles.length > 0 && (
                    <div className="uploading-list">
                        {uploadingFiles.map((f, i) => (
                            <div key={i} className={`uploading-item ${f.status}`}>
                                <span className="material-symbols-outlined" style={{ fontSize: 14 }}>description</span>
                                <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis' }}>{f.name}</span>
                                <span className="uploading-status">
                  {f.status === 'uploading' ? `${f.progress || 0}%` : f.status === 'done' ? '✅' : '❌'}
                </span>
                                {f.status === 'uploading' && (
                                    <div style={{ position: 'absolute', bottom: 0, left: 0, height: 2, background: 'var(--accent)', borderRadius: 1, width: `${f.progress || 0}%`, transition: 'width 0.3s ease' }} />
                                )}
                            </div>
                        ))}
                    </div>
                )}

                {/* Indexed Files */}
                <div className="context-files-section">
                    <div className="context-section-label">Indexed Files</div>

                    {/* Search filter */}
                    <div className="docs-search-wrap" style={{ marginBottom: 8 }}>
                        <span className="material-symbols-outlined" style={{ fontSize: 16, color: 'var(--text-muted)' }}>search</span>
                        <input
                            type="text"
                            className="docs-search-input"
                            placeholder="Filter documents..."
                            aria-label="Filter documents"
                            value={search}
                            onChange={(e) => setSearch(e.target.value)}
                            style={{ flex: 1, background: 'transparent', border: 'none', outline: 'none', color: 'var(--text-primary)', fontSize: 13, padding: '4px 8px' }}
                        />
                        {search && (
                            <button onClick={() => setSearch('')} aria-label="Clear search" style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-muted)', padding: 2 }}>
                                <span className="material-symbols-outlined" style={{ fontSize: 14 }}>close</span>
                            </button>
                        )}
                    </div>

                    {error && <div className="docs-error">{error}</div>}

                    {loading && docs.length === 0 && (
                        <div className="skeleton-list">
                            {[1, 2, 3].map((i) => <div key={i} className="skeleton skeleton-doc" />)}
                        </div>
                    )}

                    {filtered.map((d) => {
                        const fi = getFileIcon(d.filename);
                        const isProcessing = d.status === 'PROCESSING' || d.status === 'PENDING';
                        return (
                            <div key={d.id} className={`context-file-item ${isProcessing ? 'processing' : ''}`}>
                                {isProcessing && <div className="context-file-processing-bar" />}
                                <div className={`context-file-icon ${fi.cls}`}>
                                    <span className="material-symbols-outlined" style={{ fontSize: 16 }}>{fi.icon}</span>
                                </div>
                                <div className="context-file-info">
                                    <div className="context-file-name">{d.filename}</div>
                                    {isProcessing ? (
                                        <div className="context-file-pipeline">
                                            <div className="pipeline-status">
                                                <span className="material-symbols-outlined pipeline-spin">sync</span>
                                                <span className="pipeline-label">VECTORIZING...</span>
                                            </div>
                                            <div className="pipeline-progress-track">
                                                <div className="pipeline-progress-fill" style={{ width: '65%' }}>
                                                    <div className="pipeline-shimmer" />
                                                </div>
                                            </div>
                                        </div>
                                    ) : (
                                        <div className="context-file-meta">
                                            {d.fileSize ? formatSize(d.fileSize) : ''}
                                            {d.fileSize && d.status === 'READY' ? ' · ' : ''}
                                            {d.status === 'READY' && (
                                                <span className="context-file-ready">
                          <span className="material-symbols-outlined" style={{ fontSize: 12 }}>check_circle</span>
                          READY
                        </span>
                                            )}
                                            {d.status === 'FAILED' && (
                                                <span className="context-file-failed">
                          <span className="material-symbols-outlined" style={{ fontSize: 12 }}>error</span>
                          FAILED
                        </span>
                                            )}
                                        </div>
                                    )}
                                </div>
                                <button className="context-file-remove" onClick={(e) => { e.stopPropagation(); setDeletingId(d.id); }} title="Remove" aria-label={`Remove ${d.filename}`}>
                                    <span className="material-symbols-outlined" style={{ fontSize: 16 }}>close</span>
                                </button>
                                {d.status === 'READY' && (
                                    <>
                                        <button className="context-file-action" onClick={(e) => { e.stopPropagation(); handleDownload(d.id); }} title="Download" aria-label={`Download ${d.filename}`}>
                                            <span className="material-symbols-outlined" style={{ fontSize: 14 }}>download</span>
                                        </button>
                                        <button className="context-file-action" onClick={(e) => { e.stopPropagation(); handleReingest(d.id); }} title="Re-ingest" aria-label={`Re-ingest ${d.filename}`}>
                                            <span className="material-symbols-outlined" style={{ fontSize: 14 }}>refresh</span>
                                        </button>
                                    </>
                                )}
                                {deletingId === d.id && (
                                    <div className="context-file-delete-confirm" onClick={(e) => e.stopPropagation()}>
                                        <button className="confirm-yes" onClick={() => handleDelete(d.id)} title="Confirm" aria-label="Confirm delete">
                                            <span className="material-symbols-outlined" style={{ fontSize: 14 }}>check</span>
                                        </button>
                                        <button className="confirm-no" onClick={() => setDeletingId(null)} title="Cancel" aria-label="Cancel delete">
                                            <span className="material-symbols-outlined" style={{ fontSize: 14 }}>close</span>
                                        </button>
                                    </div>
                                )}
                            </div>
                        );
                    })}

                    {!loading && filtered.length === 0 && (
                        <p className="docs-empty">
                            {search ? 'No documents match your search.' : 'No documents yet. Upload files to get started.'}
                        </p>
                    )}
                </div>
            </div>
        </aside>
    );
}