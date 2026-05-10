/**
 * Conversation export utilities.
 * Allows users to download their conversation history in various formats.
 */

export function exportAsMarkdown(messages, title = 'Conversation') {
  const lines = [`# ${title}\n`, `_Exported: ${new Date().toLocaleString()}_\n\n---\n\n`];

  for (const msg of messages) {
    const role = msg.role === 'USER' ? '**You**' : '**EAKP Assistant**';
    const time = msg.createdAt ? new Date(msg.createdAt).toLocaleString() : '';
    lines.push(`### ${role} ${time ? `_(${time})_` : ''}\n\n`);
    lines.push(`${msg.content}\n\n`);

    if (msg.sources && msg.sources.length > 0) {
      lines.push(`<details><summary>Sources (${msg.sources.length})</summary>\n\n`);
      msg.sources.forEach((s, i) => {
        lines.push(`${i + 1}. **${s.source || 'Unknown'}**: ${s.snippet || ''}\n`);
      });
      lines.push(`\n</details>\n\n`);
    }
    lines.push(`---\n\n`);
  }

  return lines.join('');
}

export function exportAsJson(messages, title = 'Conversation') {
  return JSON.stringify({
    title,
    exportedAt: new Date().toISOString(),
    messageCount: messages.length,
    messages: messages.map(m => ({
      role: m.role,
      content: m.content,
      sources: m.sources || [],
      faithfulness: m.faithfulness || null,
      createdAt: m.createdAt,
    })),
  }, null, 2);
}

export function exportAsText(messages, title = 'Conversation') {
  const lines = [`${title}\nExported: ${new Date().toLocaleString()}\n${'='.repeat(60)}\n\n`];

  for (const msg of messages) {
    const role = msg.role === 'USER' ? 'You' : 'EAKP Assistant';
    const time = msg.createdAt ? new Date(msg.createdAt).toLocaleString() : '';
    lines.push(`[${role}] ${time}\n`);
    lines.push(`${msg.content}\n\n`);
  }

  return lines.join('');
}

/**
 * Trigger a file download in the browser.
 */
export function downloadFile(content, filename, mimeType = 'text/plain') {
  const blob = new Blob([content], { type: mimeType });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

/**
 * Export a conversation in the specified format and trigger download.
 */
export function exportConversation(messages, title, format = 'markdown') {
  const sanitizedTitle = (title || 'conversation').replace(/[^a-zA-Z0-9-_ ]/g, '').trim() || 'conversation';
  const timestamp = new Date().toISOString().slice(0, 10);

  switch (format) {
    case 'markdown': {
      const content = exportAsMarkdown(messages, title);
      downloadFile(content, `${sanitizedTitle}-${timestamp}.md`, 'text/markdown');
      break;
    }
    case 'json': {
      const content = exportAsJson(messages, title);
      downloadFile(content, `${sanitizedTitle}-${timestamp}.json`, 'application/json');
      break;
    }
    case 'text':
    default: {
      const content = exportAsText(messages, title);
      downloadFile(content, `${sanitizedTitle}-${timestamp}.txt`, 'text/plain');
      break;
    }
  }
}

