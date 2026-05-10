/**
 * File upload validation utilities.
 * Enforces enterprise-grade file restrictions before upload.
 */

const ALLOWED_EXTENSIONS = new Set([
  'pdf', 'docx', 'doc', 'txt', 'md', 'pptx', 'xlsx', 'csv', 'html', 'htm', 'rtf', 'json', 'xml'
]);

const ALLOWED_MIME_TYPES = new Set([
  'application/pdf',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  'application/msword',
  'text/plain',
  'text/markdown',
  'text/html',
  'text/csv',
  'application/vnd.openxmlformats-officedocument.presentationml.presentation',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  'application/json',
  'application/xml',
  'text/xml',
  'application/rtf',
]);

// 50MB max per file
const MAX_FILE_SIZE = 50 * 1024 * 1024;
// 200MB total per batch
const MAX_BATCH_SIZE = 200 * 1024 * 1024;
// Max 10 files per batch
const MAX_FILES_PER_BATCH = 10;

export function validateFile(file) {
  const errors = [];

  if (!file || !file.name) {
    return { valid: false, errors: ['Invalid file'] };
  }

  // Extension check
  const ext = file.name.split('.').pop()?.toLowerCase();
  if (!ext || !ALLOWED_EXTENSIONS.has(ext)) {
    errors.push(`Unsupported file type: .${ext}. Allowed: ${[...ALLOWED_EXTENSIONS].join(', ')}`);
  }

  // MIME type check (browsers can spoof this but it's a first line of defense)
  if (file.type && !ALLOWED_MIME_TYPES.has(file.type) && !file.type.startsWith('text/')) {
    // Soft warning — some browsers report wrong MIME types
    console.warn(`Unexpected MIME type: ${file.type} for ${file.name}`);
  }

  // Size check
  if (file.size > MAX_FILE_SIZE) {
    errors.push(`File "${file.name}" exceeds maximum size of ${MAX_FILE_SIZE / 1024 / 1024}MB`);
  }

  if (file.size === 0) {
    errors.push(`File "${file.name}" is empty`);
  }

  return { valid: errors.length === 0, errors };
}

export function validateBatch(files) {
  const errors = [];

  if (files.length > MAX_FILES_PER_BATCH) {
    errors.push(`Maximum ${MAX_FILES_PER_BATCH} files per upload. You selected ${files.length}.`);
  }

  const totalSize = files.reduce((sum, f) => sum + f.size, 0);
  if (totalSize > MAX_BATCH_SIZE) {
    errors.push(`Total batch size exceeds ${MAX_BATCH_SIZE / 1024 / 1024}MB limit`);
  }

  // Validate each file
  for (const file of files) {
    const result = validateFile(file);
    if (!result.valid) {
      errors.push(...result.errors);
    }
  }

  // Check for duplicates
  const names = files.map(f => f.name.toLowerCase());
  const duplicates = names.filter((n, i) => names.indexOf(n) !== i);
  if (duplicates.length > 0) {
    errors.push(`Duplicate files: ${[...new Set(duplicates)].join(', ')}`);
  }

  return { valid: errors.length === 0, errors };
}

export function formatFileSize(bytes) {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

export const FILE_LIMITS = {
  MAX_FILE_SIZE,
  MAX_BATCH_SIZE,
  MAX_FILES_PER_BATCH,
  ALLOWED_EXTENSIONS: [...ALLOWED_EXTENSIONS],
};

